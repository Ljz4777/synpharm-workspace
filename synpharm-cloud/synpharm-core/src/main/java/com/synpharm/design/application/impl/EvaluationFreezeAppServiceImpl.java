package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.EvaluationFreezeResponse;
import com.synpharm.design.application.EvaluationFreezeAppService;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.EvaluationFreeze;
import com.synpharm.design.infrastructure.client.ChemistryClient;
import com.synpharm.design.infrastructure.client.ChemistryDtos;
import com.synpharm.design.infrastructure.persistence.BaselineSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationFreezeMapper;
import com.synpharm.design.support.Hashes;
import com.synpharm.design.support.MetricFieldMapper;
import com.synpharm.design.support.SerialNumbers;
import com.synpharm.design.support.evaluation.EvaluationContextBuilder;
import com.synpharm.design.support.evaluation.EvaluationDtos;
import com.synpharm.design.support.evaluation.MinimalEvaluator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 最小评估 + 冻结（06 §5.1）。
 *
 * <p><b>两种「算不出来」必须区别对待</b>：
 * <ul>
 *   <li>分子没有 SMILES（抗体序列）—— 当前计算层确实没有序列指标，
 *       这是<b>能力缺口</b>，允许冻结并把全部指标记进 {@code missingMetrics}；</li>
 *   <li>有 SMILES 但计算层调用失败 —— 这是<b>环境故障</b>，抛错让用户重试。
 *       悄悄冻结一个「全空」快照，下游会拿它当有效结论。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationFreezeAppServiceImpl implements EvaluationFreezeAppService {

    /** 请求计算层的指标集合：与 {@link MetricFieldMapper#KNOWN_METRIC_CODES} 一致 */
    private static final List<String> REQUEST_METRICS = List.of(
            "MOL_WT", "MOL_LOGP", "TPSA", "NUM_H_DONORS", "NUM_H_ACCEPTORS",
            "NUM_ROTATABLE", "NUM_RINGS", "QED", "FRACTION_CSP3",
            "LIPINSKI_PASS", "COMPLEXITY_PENALTY", "PAINS_HITS", "BRENK_HITS");

    private final CandidateMoleculeMapper candidateMapper;
    private final BaselineSnapshotMapper baselineSnapshotMapper;
    private final DesignProjectMapper projectMapper;
    private final EvaluationFreezeMapper freezeMapper;
    private final ChemistryClient chemistryClient;
    private final MinimalEvaluator evaluator;
    private final EvaluationContextBuilder contextBuilder;
    private final JsonCodec jsonCodec;

    @Override
    public EvaluationFreezeResponse freeze(Long candidateId, Long baselineSnapshotId) {
        Long userId = CurrentUser.requireUserId();
        CandidateMolecule candidate = loadOwnedCandidate(candidateId, userId);
        BaselineSnapshot snapshot = resolveBaseline(candidate.getProjectId(), baselineSnapshotId);
        EvaluationDtos.EvalContext context = contextBuilder.build(snapshot);

        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, Object> engineVersions = new LinkedHashMap<>();

        if (StringUtils.hasText(candidate.getSmiles())) {
            Map<String, BigDecimal> computed = computeProperties(candidate);
            values.putAll(computed);
            engineVersions.put("properties", "fastapi-rdkit");
            engineVersions.put("properties_requested", REQUEST_METRICS.size());
            engineVersions.put("properties_returned", computed.size());
        } else {
            // 序列类：能力缺口而非故障，如实记录后照常冻结
            engineVersions.put("properties", "unavailable-for-sequence");
            engineVersions.put("note", "当前计算层只有小分子理化指标，序列类指标全部未判定");
        }

        EvaluationDtos.Outcome outcome = evaluator.evaluate(values, context, engineVersions);
        String scoresJson = jsonCodec.write(outcome.scoresAsMap());
        String contentHash = Hashes.sha256(scoresJson + "|" + snapshot.getContentHash());

        int versionNo = candidate.getVersionNo() == null ? 1 : candidate.getVersionNo();

        // 幂等：同候选 + 同版本 + 同内容 → 返回已有快照，不制造第二条「一样的结论」
        EvaluationFreeze existing = freezeMapper.selectOne(Wrappers.lambdaQuery(EvaluationFreeze.class)
                .eq(EvaluationFreeze::getCandidateId, candidateId)
                .eq(EvaluationFreeze::getVersionNo, versionNo)
                .eq(EvaluationFreeze::getContentHash, contentHash)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("评估冻结幂等命中 candidate={} version={} freeze={}",
                    candidateId, versionNo, existing.getFreezeNo());
            return toResponse(existing, outcome);
        }

        EvaluationFreeze freeze = new EvaluationFreeze();
        freeze.setFreezeNo(SerialNumbers.evaluationFreezeNo());
        freeze.setProjectId(candidate.getProjectId());
        freeze.setCandidateId(candidateId);
        freeze.setVersionNo(versionNo);
        freeze.setScoresJson(scoresJson);
        freeze.setHardPassed(outcome.hardPassed() ? 1 : 0);
        freeze.setTotalScore(outcome.totalScore());
        freeze.setWeightCoverage(outcome.weightCoverage());
        freeze.setMissingMetrics(jsonCodec.write(outcome.missingMetrics()));
        freeze.setSkippedRules(jsonCodec.write(outcome.skippedRules()));
        freeze.setEngineVersions(jsonCodec.write(outcome.engineVersions()));
        freeze.setContentHash(contentHash);
        freezeMapper.insert(freeze);

        // 候选行同步最新结论：列表页与排行榜直接读这两列，避免每次都去 join 快照表
        candidate.setTotalScore(outcome.totalScore());
        candidate.setHardPassed(outcome.hardPassed() ? 1 : 0);
        candidate.setNeedsReEval(0);
        candidateMapper.updateById(candidate);

        log.info("评估冻结完成 candidate={} freeze={} hardPassed={} score={} coverage={}",
                candidateId, freeze.getFreezeNo(), outcome.hardPassed(),
                outcome.totalScore(), outcome.weightCoverage());
        return toResponse(freeze, outcome);
    }

    @Override
    public EvaluationFreezeResponse latest(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        loadOwnedCandidate(candidateId, userId);
        EvaluationFreeze freeze = freezeMapper.selectOne(Wrappers.lambdaQuery(EvaluationFreeze.class)
                .eq(EvaluationFreeze::getCandidateId, candidateId)
                .orderByDesc(EvaluationFreeze::getId)
                .last("LIMIT 1"));
        if (freeze == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "该候选还没有评估冻结快照；编辑与决策都以冻结快照为输入，请先评估",
                    Map.of("candidateId", candidateId));
        }
        return toResponse(freeze, null);
    }

    @Override
    public List<EvaluationFreezeResponse> history(Long candidateId) {
        Long userId = CurrentUser.requireUserId();
        loadOwnedCandidate(candidateId, userId);
        List<EvaluationFreeze> rows = freezeMapper.selectList(Wrappers.lambdaQuery(EvaluationFreeze.class)
                .eq(EvaluationFreeze::getCandidateId, candidateId)
                .orderByDesc(EvaluationFreeze::getId)
                .last("LIMIT 50"));
        List<EvaluationFreezeResponse> out = new ArrayList<>(rows.size());
        for (EvaluationFreeze row : rows) {
            out.add(toResponse(row, null));
        }
        return out;
    }

    // ------------------------------------------------------------------ 计算

    /**
     * 调计算层拿指标值。
     *
     * <p>失败就抛：有 SMILES 却算不出指标，说明计算层不可用，此时冻结出来的
     * 快照会让下游误以为「评估过了、只是不及格」。
     */
    private Map<String, BigDecimal> computeProperties(CandidateMolecule candidate) {
        String smiles = StringUtils.hasText(candidate.getCanonicalSmiles())
                ? candidate.getCanonicalSmiles() : candidate.getSmiles();
        ChemistryDtos.MoleculeIn input = ChemistryDtos.MoleculeIn.of(
                String.valueOf(candidate.getId()), smiles);
        ChemistryDtos.PropertiesResponse response;
        try {
            response = chemistryClient.properties(List.of(input), REQUEST_METRICS, false);
        } catch (ChemistryClient.ChemistryUnavailableException e) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "计算层不可用，无法评估该候选：" + e.getMessage(),
                    Map.of("hint", "确认 fastapi 容器健康后重试；不要用空快照顶替"));
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (response.getResults() == null) {
            return values;
        }
        for (ChemistryDtos.MoleculeResult r : response.getResults()) {
            if (!Boolean.TRUE.equals(r.getSuccess()) || r.getMetrics() == null) {
                log.warn("候选 {} 理化计算失败：{}", candidate.getId(), r.getError());
                continue;
            }
            r.getMetrics().forEach((code, entry) -> {
                if (entry != null && entry.getValue() != null) {
                    values.put(code, entry.getValue());
                }
            });
        }
        return values;
    }

    // ------------------------------------------------------------------ 归属

    private CandidateMolecule loadOwnedCandidate(Long candidateId, Long userId) {
        CandidateMolecule candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "候选不存在：" + candidateId, Map.of("candidateId", candidateId));
        }
        DesignProject project = projectMapper.selectById(candidate.getProjectId());
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.RESOURCE_NOT_FOUND, Map.of("candidateId", candidateId));
        return candidate;
    }

    private BaselineSnapshot resolveBaseline(Long projectId, Long snapshotId) {
        if (snapshotId != null) {
            BaselineSnapshot snapshot = baselineSnapshotMapper.selectById(snapshotId);
            if (snapshot == null || !projectId.equals(snapshot.getProjectId())) {
                throw new BizException(ErrorCode.BASELINE_NOT_FOUND,
                        "指定的基线快照不属于该项目：" + snapshotId);
            }
            return snapshot;
        }
        BaselineSnapshot active = baselineSnapshotMapper.selectOne(Wrappers.lambdaQuery(BaselineSnapshot.class)
                .eq(BaselineSnapshot::getProjectId, projectId)
                .eq(BaselineSnapshot::getStatus, "ACTIVE")
                .orderByDesc(BaselineSnapshot::getProjectVersion)
                .last("LIMIT 1"));
        if (active == null) {
            throw new BizException(ErrorCode.BASELINE_NOT_FOUND,
                    "项目还没有冻结基线；评估要用基线的指标配置与硬约束，请先在需求分析中冻结");
        }
        return active;
    }

    // ------------------------------------------------------------------ 组装

    private EvaluationFreezeResponse toResponse(EvaluationFreeze freeze, EvaluationDtos.Outcome outcome) {
        EvaluationFreezeResponse dto = new EvaluationFreezeResponse();
        dto.setId(freeze.getId());
        dto.setFreezeNo(freeze.getFreezeNo());
        dto.setProjectId(freeze.getProjectId());
        dto.setCandidateId(freeze.getCandidateId());
        dto.setVersionNo(freeze.getVersionNo());
        dto.setEvalRunId(freeze.getEvalRunId());
        dto.setScores(jsonCodec.readMap(freeze.getScoresJson()));
        dto.setHardPassed(freeze.getHardPassed() != null && freeze.getHardPassed() == 1);
        dto.setTotalScore(freeze.getTotalScore());
        dto.setWeightCoverage(freeze.getWeightCoverage());
        dto.setMissingMetrics(readStringList(freeze.getMissingMetrics()));
        dto.setSkippedRules(readStringList(freeze.getSkippedRules()));
        dto.setEngineVersions(jsonCodec.readMap(freeze.getEngineVersions()));
        dto.setCalibrationId(freeze.getCalibrationId());
        dto.setContentHash(freeze.getContentHash());
        dto.setCreatedAt(freeze.getCreatedAt());
        // 违例明细不落库（它可以从 scores 里重算），本次评估有就直接带上
        dto.setViolations(outcome == null ? List.of() : outcome.violations());
        return dto;
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        List<?> parsed = jsonCodec.read(json, List.class);
        if (parsed != null) {
            for (Object item : parsed) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
