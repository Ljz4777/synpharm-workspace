package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.design.api.dto.ManualCandidateRequest;
import com.synpharm.design.api.dto.ManualCandidateResponse;
import com.synpharm.design.application.ManualCandidateAppService;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.infrastructure.client.ChemistryClient;
import com.synpharm.design.infrastructure.client.ChemistryDtos;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手工候选：把二维画布上的结构变成可以评估、可以决策的候选。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>不信任调用方给的化学信息</b>：归一化 SMILES、InChIKey、理化指标一律由计算层算。
 *       前端能填 InChIKey 就能填出一个「看起来对、其实是另一个分子」的候选，
 *       而后续每个判定都建立在「这是不是同一个分子」之上。</li>
 *   <li><b>算不出来就不写</b>：引擎没返回的指标留空，不补 0 ——
 *       LogP=0 与「LogP 算不出」在决策阶段含义完全不同。</li>
 *   <li><b>同分子不重复建</b>：同项目同轮次 InChIKey 相同即视为同一个分子，
 *       返回已有那条并标 {@code existed=true}，而不是报错 ——
 *       用户很可能只是想再打开它看看。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualCandidateAppServiceImpl implements ManualCandidateAppService {

    /** 引擎一个分子一次的 ref，只在本方法内有意义 */
    private static final String MANUAL_REF = "manual";

    private final DesignProjectMapper projectMapper;
    private final CandidateMoleculeMapper candidateMapper;
    private final ChemistryClient chemistryClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ManualCandidateResponse create(Long projectId, ManualCandidateRequest request) {
        Long userId = CurrentUser.requireUserId();
        DesignProject project = loadOwnedProject(projectId, userId);

        Long roundId = request.getRoundId() != null ? request.getRoundId() : project.getCurrentRoundId();
        if (roundId == null) {
            throw new BizException(ErrorCode.ROUND_NOT_FOUND,
                    "项目还没有开启轮次：手工建立的候选必须属于某一轮，否则它进不了生成/评估/决策",
                    Map.of("projectId", projectId));
        }

        String smiles = request.getSmiles() == null ? "" : request.getSmiles().trim();
        if (smiles.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_SMILES, "SMILES 不能为空", Map.of());
        }

        ChemistryDtos.MoleculeResult result = evaluate(smiles);
        if (result == null || !result.isOk()) {
            String reason = result == null || result.getError() == null ? "计算层没有返回结果" : result.getError();
            throw new BizException(ErrorCode.INVALID_SMILES,
                    "SMILES 无法解析：" + reason, Map.of("smiles", smiles));
        }

        String inchikey = result.getInchikey();
        CandidateMolecule existing = findByInchikey(projectId, roundId, inchikey);
        if (existing != null) {
            log.info("手工候选已存在，复用 candidate={} project={} inchikey={}",
                    existing.getId(), projectId, inchikey);
            return toResponse(existing, smiles, result, true);
        }

        CandidateMolecule entity = new CandidateMolecule();
        entity.setProjectId(projectId);
        entity.setRoundId(roundId);
        entity.setSmiles(smiles);
        entity.setCanonicalSmiles(result.getCanonicalSmiles());
        entity.setInchikey(inchikey);
        entity.setMoleculeName(defaultName(request.getMoleculeName(), inchikey));
        // 人手工给的分子，不是生成漏斗产出的 —— 这个区分在版本树与回流里都要用
        entity.setOriginType("IMPORTED");
        entity.setModalityCode(null);
        entity.setNote("在二维画布手工建立（未经过生成漏斗），结构与指标由计算层实算");
        entity.setMw(metric(result, "MOL_WT"));
        entity.setLogp(metric(result, "MOL_LOGP"));
        entity.setQed(metric(result, "QED"));
        candidateMapper.insert(entity);

        log.info("手工候选已建立 candidate={} project={} round={} inchikey={} 指标={}",
                entity.getId(), projectId, roundId, inchikey,
                result.getMetrics() == null ? 0 : result.getMetrics().size());
        return toResponse(entity, smiles, result, false);
    }

    private ChemistryDtos.MoleculeResult evaluate(String smiles) {
        try {
            ChemistryDtos.PropertiesResponse response = chemistryClient.properties(
                    List.of(ChemistryDtos.MoleculeIn.of(MANUAL_REF, smiles)), null, false);
            if (response.getResults() == null || response.getResults().isEmpty()) {
                return null;
            }
            return response.getResults().get(0);
        } catch (ChemistryClient.ChemistryUnavailableException e) {
            // 引擎不可用不是用户的错：503，并说明「无法校验结构」而不是「你的 SMILES 错了」
            throw new BizException(ErrorCode.FASTAPI_UNAVAILABLE,
                    "化学计算引擎不可用，暂时无法校验结构：" + e.getMessage(), Map.of());
        }
    }

    private CandidateMolecule findByInchikey(Long projectId, Long roundId, String inchikey) {
        if (inchikey == null || inchikey.isBlank()) {
            return null;
        }
        return candidateMapper.selectOne(Wrappers.<CandidateMolecule>lambdaQuery()
                .eq(CandidateMolecule::getProjectId, projectId)
                .eq(CandidateMolecule::getRoundId, roundId)
                .eq(CandidateMolecule::getInchikey, inchikey)
                .eq(CandidateMolecule::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private String defaultName(String requested, String inchikey) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String tail = inchikey == null || inchikey.length() < 8 ? "新分子" : inchikey.substring(0, 8);
        return "画布-" + tail;
    }

    private BigDecimal metric(ChemistryDtos.MoleculeResult result, String code) {
        if (result.getMetrics() == null) {
            return null;
        }
        ChemistryDtos.MetricEntry entry = result.getMetrics().get(code);
        return entry == null ? null : entry.getValue();
    }

    private ManualCandidateResponse toResponse(CandidateMolecule entity, String inputSmiles,
                                               ChemistryDtos.MoleculeResult result, boolean existed) {
        ManualCandidateResponse resp = new ManualCandidateResponse();
        resp.setCandidateId(entity.getId());
        resp.setProjectId(entity.getProjectId());
        resp.setRoundId(entity.getRoundId());
        resp.setInputSmiles(inputSmiles);
        resp.setSmiles(entity.getCanonicalSmiles() != null ? entity.getCanonicalSmiles() : entity.getSmiles());
        resp.setInchikey(entity.getInchikey());
        resp.setFormula(result == null ? null : result.getFormula());
        resp.setMw(entity.getMw());
        resp.setLogp(entity.getLogp());
        resp.setQed(entity.getQed());
        resp.setMetricCount(result == null || result.getMetrics() == null ? 0 : result.getMetrics().size());
        resp.setOriginType(entity.getOriginType());
        resp.setExisted(existed);
        return resp;
    }

    private DesignProject loadOwnedProject(Long projectId, Long userId) {
        DesignProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BizException(ErrorCode.DESIGN_PROJECT_NOT_FOUND,
                    "项目不存在：" + projectId, Map.of("projectId", projectId));
        }
        OwnershipGuard.assertOwned(userId, project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        return project;
    }
}
