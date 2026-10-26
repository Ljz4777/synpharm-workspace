package com.synpharm.design.application.impl;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.design.api.dto.CandidateVersionRequest;
import com.synpharm.design.api.dto.CandidateVersionResponse;
import com.synpharm.design.application.CandidateVersionAppService;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.MoleculeEditLog;
import com.synpharm.design.infrastructure.client.ChemistryClient;
import com.synpharm.design.infrastructure.client.ChemistryDtos;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.MoleculeEditLogMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 候选版本写回：把画布上改完的结构变成版本树上的合法节点。
 *
 * <p><b>为什么不覆盖父版本</b>：历史只增不删。被改掉的那一版恰恰是回流沉淀的样本 ——
 * 「换成这个基团之后活性掉了」这种结论，只有在旧结构还在时才能回溯。
 *
 * <p><b>为什么一定要写 {@code molecule_edit_log}</b>：决策阶段的证据包会按
 * {@code parent_mol_id} 回溯血缘，并从这条日志里取「这一步是怎么改的」。
 * 不写日志的编辑，在决策页上会显示成一串光秃秃的分子 id。
 *
 * <p><b>为什么新版本一律 {@code needsReEval=1}</b>：结构变了，旧分数就作废。
 * 带着旧分数进决策等于拿上一个分子的结论去下单。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateVersionAppServiceImpl implements CandidateVersionAppService {

    /** 引擎一次调用的 ref，只在本方法内有意义 */
    private static final String EDIT_REF = "edited";

    private final CandidateMoleculeMapper candidateMapper;
    private final DesignProjectMapper projectMapper;
    private final MoleculeEditLogMapper editLogMapper;
    private final ChemistryClient chemistryClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CandidateVersionResponse createVersion(Long candidateId, CandidateVersionRequest request) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule parent = loadOwnedCandidate(candidateId, userId);

        if ("abandoned".equalsIgnoreCase(parent.getBranchStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "该分支已被放弃，不能再从它派生新版本；如需继续请从更早的版本另开分支",
                    Map.of("candidateId", candidateId));
        }
        if (request.getDescribeText() == null || request.getDescribeText().isBlank()) {
            throw new BizException(ErrorCode.EDIT_REASON_REQUIRED,
                    "必须填写改动说明：版本树靠它解释这一版改了什么", Map.of("candidateId", candidateId));
        }

        String smiles = request.getSmiles() == null ? "" : request.getSmiles().trim();
        if (smiles.isEmpty()) {
            throw new BizException(ErrorCode.MOLECULE_EDIT_INVALID, "SMILES 不能为空", Map.of());
        }

        long startedAt = System.currentTimeMillis();
        ChemistryDtos.MoleculeResult result = evaluate(smiles);
        if (result == null || !result.isOk()) {
            String reason = result == null || result.getError() == null ? "计算层没有返回结果" : result.getError();
            throw new BizException(ErrorCode.MOLECULE_EDIT_INVALID,
                    "编辑后的结构无法解析：" + reason, Map.of("candidateId", candidateId));
        }

        // 结构没变就不该产生新版本：版本树的每个节点都得代表一次真实的改动
        if (parent.getInchikey() != null && parent.getInchikey().equals(result.getInchikey())) {
            throw new BizException(ErrorCode.MOLECULE_EDIT_NO_CHANGE,
                    "结构与父版本 #" + candidateId + " 完全相同（InChIKey 一致），没有产生新版本",
                    Map.of("candidateId", candidateId, "inchikey", result.getInchikey()));
        }

        int parentVersion = parent.getVersionNo() == null ? 1 : parent.getVersionNo();
        int parentRound = parent.getEditRound() == null ? 0 : parent.getEditRound();

        CandidateMolecule child = new CandidateMolecule();
        child.setProjectId(parent.getProjectId());
        child.setRoundId(parent.getRoundId());
        child.setOriginRunId(null);
        child.setSmiles(smiles);
        child.setCanonicalSmiles(result.getCanonicalSmiles());
        child.setInchikey(result.getInchikey());
        // 名字沿用父版本：它还是同一系列的第 N 版，另起名字会让版本树看起来像两个分子
        child.setMoleculeName(parent.getMoleculeName());
        child.setOriginType("EDITED");
        child.setModalityCode(parent.getModalityCode());
        child.setMoleculeSubtype(parent.getMoleculeSubtype());
        child.setStrategyName("MANUAL_EDIT");
        child.setParentMolId(parent.getId());
        child.setVersionNo(parentVersion + 1);
        child.setEditRound(parentRound + 1);
        child.setBranchStatus("active");
        // 结构变了，旧分数作废；冻结时会把它清回 0
        child.setNeedsReEval(1);
        child.setHardPassed(0);
        child.setReviewStatus("NEW");
        child.setPipelineStatus("generated");
        child.setMw(metric(result, "MOL_WT"));
        child.setLogp(metric(result, "MOL_LOGP"));
        child.setQed(metric(result, "QED"));
        child.setNote("手工编辑自 #" + candidateId + "：" + request.getDescribeText().trim());
        candidateMapper.insert(child);

        MoleculeEditLog editLog = new MoleculeEditLog();
        editLog.setProjectId(parent.getProjectId());
        editLog.setRoundId(parent.getRoundId());
        editLog.setParentMolId(parent.getId());
        editLog.setChildMolId(child.getId());
        // 人直接给了完整结构，没有拆成「加取代基/换环」这类原子级动作
        editLog.setEditType("RAW_SMILES");
        // params_json 是 NOT NULL：人直接给了完整结构，没有原子级动作参数，记下「怎么来的」即可
        editLog.setParamsJson("{\"mode\":\"RAW_SMILES\",\"source\":\"CANVAS\"}");
        editLog.setTargetRegion(blankToNull(request.getTargetRegion()));
        editLog.setModalityCode(parent.getModalityCode());
        editLog.setParentSmiles(parent.getSmiles());
        editLog.setChildSmiles(smiles);
        editLog.setDescribeText(request.getDescribeText().trim());
        editLog.setValid(1);
        editLog.setOperatorId(userId);
        editLog.setOperatorType("USER");
        editLog.setLatencyMs((int) (System.currentTimeMillis() - startedAt));
        editLog.setCreatedAt(LocalDateTime.now());
        editLogMapper.insert(editLog);

        log.info("候选 {} 派生新版本 #{}-v{}：InChIKey {} → {} 说明={}",
                candidateId, child.getId(), child.getVersionNo(),
                parent.getInchikey(), child.getInchikey(), editLog.getDescribeText());

        CandidateVersionResponse resp = new CandidateVersionResponse();
        resp.setCandidateId(child.getId());
        resp.setParentCandidateId(parent.getId());
        resp.setVersionNo(child.getVersionNo());
        resp.setEditRound(child.getEditRound());
        resp.setSmiles(child.getCanonicalSmiles() != null ? child.getCanonicalSmiles() : child.getSmiles());
        resp.setInchikey(child.getInchikey());
        resp.setFormula(result.getFormula());
        resp.setMw(child.getMw());
        resp.setLogp(child.getLogp());
        resp.setQed(child.getQed());
        resp.setMetricCount(result.getMetrics() == null ? 0 : result.getMetrics().size());
        resp.setOriginType(child.getOriginType());
        resp.setNeedsReEval(true);
        resp.setEditLogId(editLog.getId());
        return resp;
    }

    private ChemistryDtos.MoleculeResult evaluate(String smiles) {
        try {
            ChemistryDtos.PropertiesResponse response = chemistryClient.properties(
                    List.of(ChemistryDtos.MoleculeIn.of(EDIT_REF, smiles)), null, false);
            if (response.getResults() == null || response.getResults().isEmpty()) {
                return null;
            }
            return response.getResults().get(0);
        } catch (ChemistryClient.ChemistryUnavailableException e) {
            throw new BizException(ErrorCode.FASTAPI_UNAVAILABLE,
                    "化学计算引擎不可用，暂时无法校验编辑后的结构：" + e.getMessage(), Map.of());
        }
    }

    private BigDecimal metric(ChemistryDtos.MoleculeResult result, String code) {
        if (result.getMetrics() == null) {
            return null;
        }
        ChemistryDtos.MetricEntry entry = result.getMetrics().get(code);
        return entry == null ? null : entry.getValue();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private CandidateMolecule loadOwnedCandidate(Long candidateId, Long userId) {
        CandidateMolecule candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new BizException(ErrorCode.CANDIDATE_NOT_FOUND,
                    "候选不存在：" + candidateId, Map.of("candidateId", candidateId));
        }
        DesignProject project = projectMapper.selectById(candidate.getProjectId());
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.CANDIDATE_NOT_FOUND, Map.of("candidateId", candidateId));
        return candidate;
    }
}
