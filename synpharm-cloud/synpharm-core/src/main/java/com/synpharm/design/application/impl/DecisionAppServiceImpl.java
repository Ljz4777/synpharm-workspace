package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.DecisionActionRequest;
import com.synpharm.design.api.dto.DecisionCandidateResponse;
import com.synpharm.design.api.dto.DecisionCreateRequest;
import com.synpharm.design.api.dto.DecisionOverrideRequest;
import com.synpharm.design.api.dto.DecisionReplayResponse;
import com.synpharm.design.api.dto.DecisionResponse;
import com.synpharm.design.api.dto.RiskAcceptanceRequest;
import com.synpharm.design.api.dto.RiskAcceptanceResponse;
import com.synpharm.design.api.dto.RiskFlagResponse;
import com.synpharm.design.api.dto.VetoHitResponse;
import com.synpharm.design.application.DecisionAppService;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DecisionCandidate;
import com.synpharm.design.domain.model.DecisionRecord;
import com.synpharm.design.domain.model.DecisionVetoHit;
import com.synpharm.design.domain.model.DecisionVetoRule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.EvaluationFreeze;
import com.synpharm.design.domain.model.ProjectRiskItem;
import com.synpharm.design.domain.model.RiskAcceptance;
import com.synpharm.design.domain.model.WetExperiment;
import com.synpharm.design.infrastructure.persistence.BaselineSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DecisionCandidateMapper;
import com.synpharm.design.infrastructure.persistence.DecisionRecordMapper;
import com.synpharm.design.infrastructure.persistence.DecisionVetoHitMapper;
import com.synpharm.design.infrastructure.persistence.DecisionVetoRuleMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationFreezeMapper;
import com.synpharm.design.infrastructure.persistence.EvidenceBundleMapper;
import com.synpharm.design.infrastructure.persistence.ProjectRiskItemMapper;
import com.synpharm.design.infrastructure.persistence.RiskAcceptanceMapper;
import com.synpharm.design.infrastructure.persistence.WetExperimentMapper;
import com.synpharm.design.domain.model.EvidenceBundle;
import com.synpharm.design.support.Hashes;
import com.synpharm.design.support.SerialNumbers;
import com.synpharm.design.support.decision.DecisionDtos;
import com.synpharm.design.support.decision.DecisionLifecycle;
import com.synpharm.design.support.decision.DecisionRanker;
import com.synpharm.design.support.decision.DecisionVetoEngine;
import com.synpharm.design.support.decision.EvidenceBundleAssembler;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 决策 D0~D5（07 §2）。
 *
 * <p><b>候选集由系统推导，不由调用方指定</b>：能进决策的是「该项目当前轮次里已有评估冻结
 * 快照」的候选。让前端传候选 id 等于让一次未留痕的选择发生在决策记录之外。
 *
 * <p><b>只读冻结快照</b>：证据包从 {@code evaluation_freeze} 装配，绝不读候选当前分数 ——
 * 否则同一份决策在不同时间重放会得到不同结论。
 *
 * <p><b>没有冻结快照的候选不是「零分」，是「没有证据」</b>：它们不会以 0 分参与排序，
 * 而是被排除并在响应里说明。若一个都没有，整个决策被拒绝（{@code DECISION_EVIDENCE_MISSING}）。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionAppServiceImpl implements DecisionAppService {

    /** 规则版本前缀：规则定义变化时 hash 会变，回放才能察觉「尺子换了」 */
    private static final String RULE_VERSION_PREFIX = "veto-v1:";

    private final DesignProjectMapper projectMapper;
    private final CandidateMoleculeMapper candidateMapper;
    private final BaselineSnapshotMapper baselineSnapshotMapper;
    private final EvaluationFreezeMapper freezeMapper;
    private final ProjectRiskItemMapper riskItemMapper;
    private final RiskAcceptanceMapper riskAcceptanceMapper;
    private final WetExperimentMapper wetExperimentMapper;
    private final DecisionVetoRuleMapper vetoRuleMapper;
    private final EvidenceBundleMapper bundleMapper;
    private final DecisionRecordMapper decisionMapper;
    private final DecisionCandidateMapper decisionCandidateMapper;
    private final DecisionVetoHitMapper vetoHitMapper;
    private final EvidenceBundleAssembler assembler;
    private final DecisionVetoEngine vetoEngine;
    private final DecisionRanker ranker;
    private final JsonCodec jsonCodec;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecisionResponse create(Long projectId, DecisionCreateRequest request) {
        Long userId = CurrentUser.requireUserId();
        DesignProject project = loadOwnedProject(projectId, userId);

        Long roundId = request != null && request.getRoundId() != null
                ? request.getRoundId() : project.getCurrentRoundId();
        BaselineSnapshot snapshot = resolveBaseline(project);
        List<ProjectRiskItem> risks = loadRisks(projectId);
        List<DecisionVetoRule> rules = loadRules(projectId);

        // ---- D0~D5：装配 → 复核 → 否决 → 分桶 → 名额（与回放、确认共用同一条流水线）----
        Pipeline pipeline = runPipeline(project, roundId, snapshot, risks, rules, true);
        List<String> warnings = new ArrayList<>(pipeline.warnings());

        // ---- 决策记录 ----
        DecisionRecord record = persistDecision(project, roundId, snapshot, rules, pipeline, request);
        persistCandidates(record, pipeline);

        log.info("决策已建立 decision={} project={} 候选={} 选中={} 备选={} 淘汰={} 名额={}",
                record.getDecisionNo(), projectId, pipeline.ranked().candidates().size(),
                pipeline.ranked().selectedCount(), pipeline.ranked().backupCount(),
                pipeline.ranked().rejectedCount(), pipeline.ranked().capacity());

        return toResponse(record, pipeline.ranked(), pipeline.byId(), warnings);
    }

    /**
     * D0~D5 流水线。抽出来是因为回放（D8）必须走**完全相同**的一条路径 ——
     * 回放另写一套逻辑等于自己给自己出证明。
     *
     * @param persistBundles 创建时为 true（证据包要落库）；回放/确认时为 false（只读，不改库）
     */
    private Pipeline runPipeline(DesignProject project, Long roundId, BaselineSnapshot snapshot,
                                 List<ProjectRiskItem> risks, List<DecisionVetoRule> rules,
                                 boolean persistBundles) {
        Long projectId = project.getId();
        List<CandidateMolecule> candidates = loadCandidates(projectId, roundId);
        List<CandidateContext> contexts = new ArrayList<>();
        List<Long> noEvidence = new ArrayList<>();

        for (CandidateMolecule candidate : candidates) {
            EvaluationFreeze freeze = latestFreeze(candidate.getId());
            if (freeze == null) {
                noEvidence.add(candidate.getId());
                continue;
            }
            int experiments = countExperiments(candidate.getId());
            DecisionDtos.AssembledBundle bundle =
                    assembler.assemble(candidate, freeze, snapshot, risks, experiments);
            EvidenceBundle saved = persistBundle(candidate, freeze, snapshot, bundle, persistBundles);
            contexts.add(new CandidateContext(candidate, freeze, bundle, saved));
        }

        if (contexts.isEmpty()) {
            if (!noEvidence.isEmpty()) {
                throw new BizException(ErrorCode.DECISION_EVIDENCE_MISSING,
                        "有 " + noEvidence.size() + " 个候选还没有评估冻结快照，不能进入决策",
                        Map.of("candidateIds", noEvidence));
            }
            throw new BizException(ErrorCode.DECISION_NO_CANDIDATE,
                    "项目里没有可进入决策的候选（需要先跑生成并评估冻结）",
                    Map.of("projectId", projectId));
        }

        List<String> warnings = new ArrayList<>();
        if (!noEvidence.isEmpty()) {
            warnings.add("有 " + noEvidence.size() + " 个候选没有评估冻结快照，已排除："
                    + noEvidence);
        }

        // ---- D2 + D3：复核与否决 ----
        List<DecisionRanker.CandidateInput> inputs = new ArrayList<>();
        for (CandidateContext ctx : contexts) {
            DecisionVetoEngine.VetoResult veto = vetoEngine.evaluate(ctx.bundle(), rules, project);
            warnings.addAll(veto.warnings());
            int mustVerify = countMustVerify(ctx.bundle());
            inputs.add(new DecisionRanker.CandidateInput(
                    ctx.candidate().getId(), ctx.bundle(), veto.hits(), mustVerify));
        }

        // ---- D4 + D5：分桶、排序、名额 ----
        DecisionDtos.RankOutcome ranked = ranker.rank(inputs, project);
        warnings.addAll(ranked.warnings());

        Map<Long, CandidateContext> byId = new LinkedHashMap<>();
        for (CandidateContext ctx : contexts) {
            byId.put(ctx.candidate().getId(), ctx);
        }

        return new Pipeline(contexts, byId, ranked, warnings,
                inputHashOf(contexts), ruleVersionOf(rules));
    }

    /** 候选明细 + 否决命中落库 */
    private void persistCandidates(DecisionRecord record, Pipeline pipeline) {
        for (DecisionDtos.RankedCandidate row : pipeline.ranked().candidates()) {
            CandidateContext ctx = pipeline.byId().get(row.candidateId());
            DecisionCandidate entity = new DecisionCandidate();
            entity.setDecisionId(record.getId());
            entity.setCandidateId(row.candidateId());
            entity.setEvidenceBundleId(ctx == null || ctx.saved() == null ? null : ctx.saved().getId());
            entity.setBucket(row.bucket());
            entity.setRankNo(row.rankNo());
            entity.setWeightedScore(row.weightedScore());
            entity.setCoversMetrics(row.coversMetrics());
            entity.setMissingMetricsJson(jsonCodec.write(row.missingMetrics()));
            entity.setOutcome(row.outcome());
            entity.setRejectReason(row.rejectReason());
            entity.setManualOverride(0);
            decisionCandidateMapper.insert(entity);

            for (DecisionDtos.VetoHit hit : row.vetoes()) {
                DecisionVetoHit hitEntity = new DecisionVetoHit();
                hitEntity.setDecisionId(record.getId());
                hitEntity.setCandidateId(row.candidateId());
                hitEntity.setRuleCode(hit.ruleCode());
                hitEntity.setSeverity(hit.severity());
                hitEntity.setMessage(hit.message());
                hitEntity.setEvidenceRef(hit.evidenceRef());
                vetoHitMapper.insert(hitEntity);
            }
        }
    }

    @Override
    public DecisionResponse get(Long decisionId) {
        Long userId = CurrentUser.requireUserId();
        DecisionRecord record = decisionMapper.selectById(decisionId);
        if (record == null) {
            throw new BizException(ErrorCode.DECISION_NOT_FOUND,
                    "决策记录不存在：" + decisionId, Map.of("decisionId", decisionId));
        }
        loadOwnedProject(record.getProjectId(), userId);

        List<DecisionCandidate> rows = decisionCandidateMapper.selectList(
                Wrappers.<DecisionCandidate>lambdaQuery()
                        .eq(DecisionCandidate::getDecisionId, decisionId)
                        .orderByAsc(DecisionCandidate::getRankNo)
                        .orderByAsc(DecisionCandidate::getCandidateId));

        DecisionResponse resp = baseResponse(record);
        List<DecisionCandidateResponse> list = new ArrayList<>();
        for (DecisionCandidate row : rows) {
            DecisionCandidateResponse cr = new DecisionCandidateResponse();
            cr.setCandidateId(row.getCandidateId());
            CandidateMolecule molecule = moleculeOf(row.getCandidateId());
            cr.setMoleculeName(molecule == null ? null : molecule.getMoleculeName());
            cr.setSmiles(molecule == null ? null : molecule.getSmiles());
            cr.setMw(molecule == null ? null : molecule.getMw());
            cr.setBucket(row.getBucket());
            cr.setRankNo(row.getRankNo());
            cr.setWeightedScore(row.getWeightedScore());
            cr.setCoversMetrics(row.getCoversMetrics());
            cr.setMissingMetrics(readStringList(row.getMissingMetricsJson()));
            cr.setOutcome(row.getOutcome());
            cr.setRejectReason(row.getRejectReason());
            cr.setManualOverride(row.getManualOverride() != null && row.getManualOverride() == 1);
            cr.setOverrideReason(row.getOverrideReason());

            List<DecisionVetoHit> hits = vetoHitMapper.selectList(
                    Wrappers.<DecisionVetoHit>lambdaQuery()
                            .eq(DecisionVetoHit::getDecisionId, decisionId)
                            .eq(DecisionVetoHit::getCandidateId, row.getCandidateId()));
            cr.setVetoes(toVetoResponsesFromEntities(hits));
            cr.setAcceptances(acceptancesOf(decisionId, row.getCandidateId()));
            cr.setRisks(risksOf(row.getEvidenceBundleId()));
            list.add(cr);
        }
        resp.setCandidates(list);
        // 读取时只能还原「由计数决定」的告警：创建期的其它告警（如排除了几个未冻结候选）
        // 没有落库，重读时无法重建 —— 那部分只在刚生成完的那一次响应里出现。
        List<String> warnings = new ArrayList<>();
        if (record.getSelectedCount() != null && record.getSelectedCount() == 0
                && record.getRejectedCount() != null && record.getRejectedCount() > 0) {
            warnings.add("NO_VIABLE_CANDIDATE：全部候选均被否决或复核不通过");
        }
        resp.setWarnings(warnings);
        return resp;
    }

    @Override
    public List<DecisionResponse> listByProject(Long projectId) {
        Long userId = CurrentUser.requireUserId();
        loadOwnedProject(projectId, userId);
        List<DecisionRecord> records = decisionMapper.selectList(
                Wrappers.<DecisionRecord>lambdaQuery()
                        .eq(DecisionRecord::getProjectId, projectId)
                        .orderByDesc(DecisionRecord::getId));
        List<DecisionResponse> out = new ArrayList<>();
        for (DecisionRecord r : records) {
            out.add(baseResponse(r));
        }
        return out;
    }

    // ------------------------------------------------------------------ D6 人工调整

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecisionResponse override(Long decisionId, Long candidateId, DecisionOverrideRequest request) {
        DecisionRecord record = loadOwnedRecord(decisionId);
        DecisionLifecycle.requireDraft(record.getStatus(), decisionId);
        DecisionLifecycle.requireReason(request == null ? null : request.getOverrideReason());

        DecisionCandidate row = decisionCandidateMapper.selectOne(
                Wrappers.<DecisionCandidate>lambdaQuery()
                        .eq(DecisionCandidate::getDecisionId, decisionId)
                        .eq(DecisionCandidate::getCandidateId, candidateId)
                        .last("LIMIT 1"));
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "候选 " + candidateId + " 不在本次决策里", Map.of("candidateId", candidateId));
        }

        List<DecisionDtos.VetoHit> vetoes = vetoHitsOf(decisionId, candidateId);
        DecisionLifecycle.assertOverridable(row.getBucket(), vetoes, request.getOutcome());

        row.setOutcome(request.getOutcome());
        // 调整后的淘汰原因由人来解释，不再沿用规则推断出来的那个
        row.setRejectReason(DecisionLifecycle.OUTCOME_REJECTED.equals(request.getOutcome())
                || DecisionLifecycle.OUTCOME_DEFERRED.equals(request.getOutcome())
                ? DecisionVetoEngine.filterReasonOf("MANUAL_VETO") : null);
        row.setManualOverride(1);
        row.setOverrideReason(request.getOverrideReason());
        decisionCandidateMapper.updateById(row);

        recalcCounts(record);
        log.info("决策人工调整 decision={} candidate={} -> {} 理由={}",
                record.getDecisionNo(), candidateId, request.getOutcome(), request.getOverrideReason());
        return get(decisionId);
    }

    // ------------------------------------------------------------------ D7 风险接受

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecisionResponse acceptRisk(Long decisionId, Long candidateId, RiskAcceptanceRequest request) {
        DecisionRecord record = loadOwnedRecord(decisionId);
        DecisionLifecycle.requireDraft(record.getStatus(), decisionId);

        DecisionCandidate row = decisionCandidateMapper.selectOne(
                Wrappers.<DecisionCandidate>lambdaQuery()
                        .eq(DecisionCandidate::getDecisionId, decisionId)
                        .eq(DecisionCandidate::getCandidateId, candidateId)
                        .last("LIMIT 1"));
        if (row == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "候选 " + candidateId + " 不在本次决策里", Map.of("candidateId", candidateId));
        }

        // 风险点必须真的在证据包里：凭空接受一个不存在的风险没有意义
        EvidenceBundle bundle = row.getEvidenceBundleId() == null
                ? null : bundleMapper.selectById(row.getEvidenceBundleId());
        DecisionDtos.RiskFlag risk = findRisk(bundle, request.getRiskCode());
        if (risk == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND,
                    "证据包里没有风险点 " + request.getRiskCode(), Map.of("riskCode", request.getRiskCode()));
        }
        // 「必须验证」不可接受：能点「接受」，它就从硬要求退化成建议
        if (risk.mustVerify()) {
            throw new BizException(ErrorCode.RISK_ACCEPTANCE_FORBIDDEN,
                    "风险 " + request.getRiskCode() + " 标记为必须验证且尚无实测证据，不能接受；"
                            + "请补实验数据，或把该候选改为暂缓",
                    Map.of("riskCode", request.getRiskCode(), "candidateId", candidateId));
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new BizException(ErrorCode.OVERRIDE_REASON_REQUIRED,
                    "接受风险必须填写理由", Map.of("riskCode", request.getRiskCode()));
        }

        RiskAcceptance existing = riskAcceptanceMapper.selectOne(
                Wrappers.<RiskAcceptance>lambdaQuery()
                        .eq(RiskAcceptance::getDecisionId, decisionId)
                        .eq(RiskAcceptance::getCandidateId, candidateId)
                        .eq(RiskAcceptance::getRiskCode, request.getRiskCode())
                        .last("LIMIT 1"));
        RiskAcceptance entity = existing == null ? new RiskAcceptance() : existing;
        entity.setDecisionId(decisionId);
        entity.setCandidateId(candidateId);
        entity.setRiskCode(request.getRiskCode());
        entity.setSeverityAtAccept(risk.severity());
        entity.setReason(request.getReason());
        entity.setAcceptedBy(CurrentUser.requireUserId());
        entity.setAcceptedAt(LocalDateTime.now());
        entity.setValidUntil(request.getValidUntil() == null
                ? null : request.getValidUntil().atStartOfDay());
        if (existing == null) {
            riskAcceptanceMapper.insert(entity);
        } else {
            riskAcceptanceMapper.updateById(entity);
        }

        log.info("决策风险接受 decision={} candidate={} risk={} severity={}",
                record.getDecisionNo(), candidateId, request.getRiskCode(), risk.severity());
        return get(decisionId);
    }

    // ------------------------------------------------------------------ D8 回放 / 确认 / 暂缓 / 取消

    @Override
    public DecisionReplayResponse replay(Long decisionId) {
        DecisionRecord record = loadOwnedRecord(decisionId);
        return replayOf(record);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecisionResponse confirm(Long decisionId, DecisionActionRequest request) {
        DecisionRecord record = loadOwnedRecord(decisionId);
        DecisionLifecycle.requireDraft(record.getStatus(), decisionId);

        int selected = record.getSelectedCount() == null ? 0 : record.getSelectedCount();
        if (selected <= 0) {
            throw new BizException(ErrorCode.NO_VIABLE_CANDIDATE,
                    "没有任何候选进入湿实验，无法确认；可以转暂缓去补数据，或调整后重新决策",
                    Map.of("decisionId", decisionId));
        }

        // D8 的回放口径：用当前输入与当前规则重算，指纹不一致就不许确认
        DecisionReplayResponse replay = replayOf(record);
        if (!replay.getStoredInputHash().equals(replay.getRecomputedInputHash())) {
            throw new BizException(ErrorCode.REPLAY_MISMATCH,
                    "回放输入指纹不一致：上次决策之后证据或规则变过了（"
                            + replay.getStoredInputHash().substring(0, 8) + " → "
                            + replay.getRecomputedInputHash().substring(0, 8)
                            + "）。请新建一版决策，不要直接确认旧结论",
                    Map.of("storedInputHash", replay.getStoredInputHash(),
                            "recomputedInputHash", replay.getRecomputedInputHash()));
        }

        record.setStatus(DecisionLifecycle.STATUS_CONFIRMED);
        record.setDecidedBy(CurrentUser.requireUserId());
        record.setDecidedAt(LocalDateTime.now());
        record.setReplayHash(replay.getRecomputedInputHash());
        if (request != null && request.getReason() != null && !request.getReason().isBlank()) {
            record.setDecisionNote(request.getReason());
        }
        decisionMapper.updateById(record);

        log.info("决策已确认 decision={} 选中={} 名额={} replayHash={}",
                record.getDecisionNo(), record.getSelectedCount(), record.getCapacity(),
                record.getReplayHash() == null ? null : record.getReplayHash().substring(0, 8));
        return get(decisionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecisionResponse defer(Long decisionId, DecisionActionRequest request) {
        DecisionRecord record = loadOwnedRecord(decisionId);
        DecisionLifecycle.requireDraft(record.getStatus(), decisionId);
        String reason = requireReasonFor(request, "转暂缓");
        record.setStatus(DecisionLifecycle.STATUS_DEFERRED);
        record.setDecidedBy(CurrentUser.requireUserId());
        record.setDecidedAt(LocalDateTime.now());
        record.setDecisionNote(appendNote(record.getDecisionNote(), "转暂缓：" + reason));
        decisionMapper.updateById(record);
        return get(decisionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecisionResponse cancel(Long decisionId, DecisionActionRequest request) {
        DecisionRecord record = loadOwnedRecord(decisionId);
        DecisionLifecycle.requireDraft(record.getStatus(), decisionId);
        String reason = requireReasonFor(request, "取消");
        record.setStatus(DecisionLifecycle.STATUS_CANCELLED);
        record.setDecidedBy(CurrentUser.requireUserId());
        record.setDecidedAt(LocalDateTime.now());
        record.setDecisionNote(appendNote(record.getDecisionNote(), "取消：" + reason));
        decisionMapper.updateById(record);
        return get(decisionId);
    }

    /** 回放：同一条流水线重算，逐候选比桶/名次/去向 */
    private DecisionReplayResponse replayOf(DecisionRecord record) {
        DesignProject project = loadOwnedProject(record.getProjectId(), CurrentUser.requireUserId());
        Long roundId = record.getRoundId() != null ? record.getRoundId() : project.getCurrentRoundId();
        BaselineSnapshot snapshot = resolveBaseline(project);
        List<ProjectRiskItem> risks = loadRisks(project.getId());
        List<DecisionVetoRule> rules = loadRules(project.getId());

        Pipeline pipeline = runPipeline(project, roundId, snapshot, risks, rules, false);

        List<DecisionCandidate> storedRows = decisionCandidateMapper.selectList(
                Wrappers.<DecisionCandidate>lambdaQuery()
                        .eq(DecisionCandidate::getDecisionId, record.getId()));
        List<DecisionLifecycle.Snapshot> stored = new ArrayList<>();
        for (DecisionCandidate r : storedRows) {
            stored.add(new DecisionLifecycle.Snapshot(
                    r.getCandidateId(), r.getBucket(), r.getRankNo(), r.getOutcome()));
        }
        List<DecisionLifecycle.Snapshot> fresh = new ArrayList<>();
        for (DecisionDtos.RankedCandidate r : pipeline.ranked().candidates()) {
            fresh.add(new DecisionLifecycle.Snapshot(
                    r.candidateId(), r.bucket(), r.rankNo(), r.outcome()));
        }

        DecisionReplayResponse resp = new DecisionReplayResponse();
        resp.setDecisionId(record.getId());
        resp.setDecisionNo(record.getDecisionNo());
        resp.setStoredInputHash(record.getInputSnapshotHash());
        resp.setRecomputedInputHash(pipeline.inputHash());
        resp.setStoredRuleVersion(record.getRuleVersion());
        resp.setCurrentRuleVersion(pipeline.ruleVersion());

        List<DecisionReplayResponse.ReplayDiff> diffs = new ArrayList<>();
        for (DecisionLifecycle.Change c : DecisionLifecycle.diff(stored, fresh)) {
            DecisionReplayResponse.ReplayDiff d = new DecisionReplayResponse.ReplayDiff();
            d.setCandidateId(c.candidateId());
            CandidateMolecule molecule = moleculeOf(c.candidateId());
            d.setMoleculeName(molecule == null ? null : molecule.getMoleculeName());
            d.setStoredBucket(c.storedBucket());
            d.setCurrentBucket(c.currentBucket());
            d.setStoredRankNo(c.storedRankNo());
            d.setCurrentRankNo(c.currentRankNo());
            d.setStoredOutcome(c.storedOutcome());
            d.setCurrentOutcome(c.currentOutcome());
            d.setChanges(c.changes());
            diffs.add(d);
        }
        resp.setDiffs(diffs);
        resp.setAddedCandidates(DecisionLifecycle.added(stored, fresh));
        resp.setRemovedCandidates(DecisionLifecycle.removed(stored, fresh));
        resp.setWarnings(pipeline.warnings());
        // 基线换了一版：判定阈值跟着变，这本身就是「输入变了」，必须说出来
        if (record.getBaselineSnapshotId() != null
                && !record.getBaselineSnapshotId().equals(snapshot.getId())) {
            List<String> warnings = new ArrayList<>(pipeline.warnings());
            warnings.add("基线已更新（快照 #" + record.getBaselineSnapshotId()
                    + " → #" + snapshot.getId() + "）：判定阈值变了，旧结论不可直接沿用");
            resp.setWarnings(warnings);
        }
        resp.setSame(record.getInputSnapshotHash().equals(pipeline.inputHash())
                && diffs.isEmpty()
                && resp.getAddedCandidates().isEmpty());
        return resp;
    }

    private DecisionRecord loadOwnedRecord(Long decisionId) {
        Long userId = CurrentUser.requireUserId();
        DecisionRecord record = decisionMapper.selectById(decisionId);
        if (record == null) {
            throw new BizException(ErrorCode.DECISION_NOT_FOUND,
                    "决策记录不存在：" + decisionId, Map.of("decisionId", decisionId));
        }
        loadOwnedProject(record.getProjectId(), userId);
        return record;
    }

    /** 人工调整后计数必须重算：沿用创建时那组数会让「选中几个」和明细对不上 */
    private void recalcCounts(DecisionRecord record) {
        List<DecisionCandidate> rows = decisionCandidateMapper.selectList(
                Wrappers.<DecisionCandidate>lambdaQuery()
                        .eq(DecisionCandidate::getDecisionId, record.getId()));
        List<String> outcomes = new ArrayList<>();
        for (DecisionCandidate r : rows) {
            outcomes.add(r.getOutcome());
        }
        DecisionLifecycle.Counts counts = DecisionLifecycle.countsOf(outcomes);
        record.setSelectedCount(counts.selected());
        record.setBackupCount(counts.backup());
        record.setRejectedCount(counts.rejected());
        record.setDeferredCount(counts.deferred());
        decisionMapper.updateById(record);
    }

    private List<DecisionDtos.VetoHit> vetoHitsOf(Long decisionId, Long candidateId) {
        List<DecisionVetoHit> hits = vetoHitMapper.selectList(Wrappers.<DecisionVetoHit>lambdaQuery()
                .eq(DecisionVetoHit::getDecisionId, decisionId)
                .eq(DecisionVetoHit::getCandidateId, candidateId));
        List<DecisionDtos.VetoHit> out = new ArrayList<>();
        for (DecisionVetoHit h : hits) {
            out.add(new DecisionDtos.VetoHit(h.getRuleCode(), h.getSeverity(), h.getMessage(), h.getEvidenceRef()));
        }
        return out;
    }

    private List<RiskFlagResponse> riskFlagsOf(CandidateContext ctx) {
        List<RiskFlagResponse> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        for (DecisionDtos.RiskFlag flag : ctx.bundle().riskFlags()) {
            RiskFlagResponse dto = new RiskFlagResponse();
            dto.setRiskCode(flag.riskCode());
            dto.setSeverity(flag.severity());
            dto.setMustVerify(flag.mustVerify());
            dto.setSource(flag.source());
            out.add(dto);
        }
        return out;
    }

    private List<RiskAcceptanceResponse> acceptancesOf(Long decisionId, Long candidateId) {
        List<RiskAcceptance> rows = riskAcceptanceMapper.selectList(
                Wrappers.<RiskAcceptance>lambdaQuery()
                        .eq(RiskAcceptance::getDecisionId, decisionId)
                        .eq(RiskAcceptance::getCandidateId, candidateId)
                        .orderByAsc(RiskAcceptance::getId));
        List<RiskAcceptanceResponse> out = new ArrayList<>();
        for (RiskAcceptance r : rows) {
            RiskAcceptanceResponse dto = new RiskAcceptanceResponse();
            dto.setId(r.getId());
            dto.setCandidateId(r.getCandidateId());
            dto.setRiskCode(r.getRiskCode());
            dto.setSeverityAtAccept(r.getSeverityAtAccept());
            dto.setReason(r.getReason());
            dto.setAcceptedBy(r.getAcceptedBy());
            dto.setAcceptedAt(r.getAcceptedAt());
            dto.setValidUntil(r.getValidUntil() == null ? null : r.getValidUntil().toLocalDate());
            out.add(dto);
        }
        return out;
    }

    private List<RiskFlagResponse> risksOf(Long bundleId) {
        List<RiskFlagResponse> out = new ArrayList<>();
        if (bundleId == null) {
            return out;
        }
        EvidenceBundle bundle = bundleMapper.selectById(bundleId);
        if (bundle == null || bundle.getRiskFlagsJson() == null || bundle.getRiskFlagsJson().isBlank()) {
            return out;
        }
        Map<?, ?>[] flags = jsonCodec.read(bundle.getRiskFlagsJson(), Map[].class);
        if (flags == null) {
            return out;
        }
        for (Map<?, ?> m : flags) {
            RiskFlagResponse dto = new RiskFlagResponse();
            dto.setRiskCode(m.get("riskCode") == null ? null : String.valueOf(m.get("riskCode")));
            dto.setSeverity(m.get("severity") == null ? null : String.valueOf(m.get("severity")));
            dto.setMustVerify(Boolean.TRUE.equals(m.get("mustVerify")));
            dto.setSource(m.get("source") == null ? null : String.valueOf(m.get("source")));
            out.add(dto);
        }
        return out;
    }

    private DecisionDtos.RiskFlag findRisk(EvidenceBundle bundle, String riskCode) {
        if (bundle == null || bundle.getRiskFlagsJson() == null || bundle.getRiskFlagsJson().isBlank()) {
            return null;
        }
        Map<?, ?>[] flags = jsonCodec.read(bundle.getRiskFlagsJson(), Map[].class);
        if (flags == null) {
            return null;
        }
        for (Map<?, ?> m : flags) {
            Object code = m.get("riskCode");
            if (code != null && riskCode.equals(String.valueOf(code))) {
                return new DecisionDtos.RiskFlag(
                        String.valueOf(code),
                        m.get("severity") == null ? null : String.valueOf(m.get("severity")),
                        Boolean.TRUE.equals(m.get("mustVerify")),
                        m.get("source") == null ? null : String.valueOf(m.get("source")));
            }
        }
        return null;
    }

    private String requireReasonFor(DecisionActionRequest request, String action) {
        String reason = request == null ? null : request.getReason();
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.OVERRIDE_REASON_REQUIRED,
                    action + "必须填写理由：这次决策不算数，不写清楚为什么下一个人只能靠猜",
                    Map.of("action", action));
        }
        return reason;
    }

    private String appendNote(String existing, String line) {
        String base = existing == null || existing.isBlank() ? "" : existing + " ｜ ";
        String merged = base + line;
        return merged.length() > 2000 ? merged.substring(0, 2000) : merged;
    }

    // ------------------------------------------------------------------ D0/D1 支撑

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

    private BaselineSnapshot resolveBaseline(DesignProject project) {
        Long snapshotId = project.getCurrentBaselineId();
        if (snapshotId == null) {
            throw new BizException(ErrorCode.BASELINE_NOT_FOUND,
                    "项目还没有冻结基线：决策的判定阈值来自基线快照，没有它无法复核",
                    Map.of("projectId", project.getId()));
        }
        BaselineSnapshot snapshot = baselineSnapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.BASELINE_NOT_FOUND,
                    "当前基线快照不存在：" + snapshotId, Map.of("snapshotId", snapshotId));
        }
        return snapshot;
    }

    private List<ProjectRiskItem> loadRisks(Long projectId) {
        return riskItemMapper.selectList(Wrappers.<ProjectRiskItem>lambdaQuery()
                .eq(ProjectRiskItem::getProjectId, projectId)
                .eq(ProjectRiskItem::getDeleted, 0));
    }

    private List<DecisionVetoRule> loadRules(Long projectId) {
        return vetoRuleMapper.selectList(Wrappers.<DecisionVetoRule>lambdaQuery()
                .in(DecisionVetoRule::getProjectId, 0L, projectId)
                .eq(DecisionVetoRule::getEnabled, 1)
                .orderByAsc(DecisionVetoRule::getSortNo)
                .orderByAsc(DecisionVetoRule::getId));
    }

    private List<CandidateMolecule> loadCandidates(Long projectId, Long roundId) {
        var query = Wrappers.<CandidateMolecule>lambdaQuery()
                .eq(CandidateMolecule::getProjectId, projectId)
                .eq(CandidateMolecule::getDeleted, 0);
        if (roundId != null) {
            query.eq(CandidateMolecule::getRoundId, roundId);
        }
        List<CandidateMolecule> all = candidateMapper.selectList(query.orderByAsc(CandidateMolecule::getId));
        List<CandidateMolecule> out = new ArrayList<>();
        for (CandidateMolecule c : all) {
            // 已放弃的分支不参与决策：它们连编辑价值都没了
            if ("abandoned".equalsIgnoreCase(c.getBranchStatus())) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private EvaluationFreeze latestFreeze(Long candidateId) {
        return freezeMapper.selectOne(Wrappers.<EvaluationFreeze>lambdaQuery()
                .eq(EvaluationFreeze::getCandidateId, candidateId)
                .orderByDesc(EvaluationFreeze::getId)
                .last("LIMIT 1"));
    }

    private int countExperiments(Long candidateId) {
        Long n = wetExperimentMapper.selectCount(Wrappers.<WetExperiment>lambdaQuery()
                .eq(WetExperiment::getCandidateId, candidateId)
                .eq(WetExperiment::getDeleted, 0));
        return n == null ? 0 : n.intValue();
    }

    /**
     * 落库证据包。同候选 + 同内容哈希复用已有行 ——
     * 证据包是不可变对象，重复决策不该刷出一堆内容相同的行。
     *
     * @param persist false 时不写库（回放/确认只读）；仍按同样规则查已有行以附带 id
     */
    private EvidenceBundle persistBundle(CandidateMolecule candidate, EvaluationFreeze freeze,
                                         BaselineSnapshot snapshot, DecisionDtos.AssembledBundle bundle,
                                         boolean persist) {
        EvidenceBundle existing = bundleMapper.selectOne(Wrappers.<EvidenceBundle>lambdaQuery()
                .eq(EvidenceBundle::getCandidateId, candidate.getId())
                .eq(EvidenceBundle::getContentHash, bundle.contentHash())
                .last("LIMIT 1"));
        if (existing != null || !persist) {
            return existing;
        }

        EvidenceBundle entity = new EvidenceBundle();
        entity.setBundleNo(SerialNumbers.evidenceBundleNo());
        entity.setProjectId(candidate.getProjectId());
        entity.setRoundId(candidate.getRoundId());
        entity.setCandidateId(candidate.getId());
        entity.setBaselineSnapshotId(snapshot.getId());
        entity.setEvaluationFreezeId(freeze.getId());
        entity.setLineageJson(jsonCodec.write(bundle.lineage().stream()
                .map(DecisionDtos.LineageStep::toMap).toList()));
        entity.setConstraintVerdictJson(jsonCodec.write(bundle.verdicts().stream()
                .map(DecisionDtos.ConstraintVerdict::toMap).toList()));
        entity.setRiskFlagsJson(jsonCodec.write(bundle.riskFlags().stream()
                .map(DecisionDtos.RiskFlag::toMap).toList()));
        entity.setPendingItemsJson(jsonCodec.write(bundle.pendingItems().stream()
                .map(DecisionDtos.PendingItem::toMap).toList()));
        entity.setCoversMetrics(bundle.coversMetrics());
        entity.setCompleteness(bundle.completeness());
        entity.setContentHash(bundle.contentHash());
        entity.setStatus("FROZEN");
        entity.setFrozenAt(LocalDateTime.now());
        bundleMapper.insert(entity);
        return entity;
    }

    private int countMustVerify(DecisionDtos.AssembledBundle bundle) {
        int hits = 0;
        for (DecisionDtos.PendingItem item : bundle.pendingItems()) {
            if (item != null) {
                hits++;
            }
        }
        return hits;
    }

    // ------------------------------------------------------------------ 决策记录

    private DecisionRecord persistDecision(DesignProject project, Long roundId, BaselineSnapshot snapshot,
                                           List<DecisionVetoRule> rules, Pipeline pipeline,
                                           DecisionCreateRequest request) {
        DecisionRecord record = new DecisionRecord();
        record.setDecisionNo(SerialNumbers.decisionNo());
        record.setProjectId(project.getId());
        record.setRoundId(roundId);
        record.setBaselineSnapshotId(snapshot.getId());
        record.setWeightsSnapshot(jsonCodec.write(weightsOf(snapshot)));
        record.setRuleVersion(pipeline.ruleVersion());
        record.setInputSnapshotHash(pipeline.inputHash());
        record.setStatus(DecisionLifecycle.STATUS_DRAFT);
        record.setCapacity(pipeline.ranked().capacity());
        record.setSelectedCount(pipeline.ranked().selectedCount());
        record.setBackupCount(pipeline.ranked().backupCount());
        record.setRejectedCount(pipeline.ranked().rejectedCount());
        record.setDeferredCount(0);
        record.setDecisionNote(request == null ? null : request.getDecisionNote());
        record.setVersionNo(1);
        decisionMapper.insert(record);
        return record;
    }

    /** 权重快照：{metricCode: weight}，回放时用它重算排序 */
    private Map<String, Object> weightsOf(BaselineSnapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> root = jsonCodec.readMap(snapshot.getMetricsJson());
        if (root == null || !(root.get("items") instanceof List<?> items)) {
            return out;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Object code = raw.get("metricCode");
            Object weight = raw.get("weight");
            if (code != null) {
                out.put(String.valueOf(code), weight);
            }
        }
        return out;
    }

    /**
     * 规则版本 = 参与判定的规则定义哈希。
     *
     * <p>不写死 "v1"：规则被改过而版本号不变，回放就会「同样的输入得出不同结论」
     * 却查不出原因。
     */
    private String ruleVersionOf(List<DecisionVetoRule> rules) {
        List<Map<String, Object>> canonical = new ArrayList<>();
        for (DecisionVetoRule r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.getRuleCode());
            m.put("severity", r.getSeverity());
            m.put("threshold", r.getThresholdNum());
            m.put("params", r.getParamsJson());
            m.put("projectId", r.getProjectId());
            canonical.add(m);
        }
        canonical.sort(Comparator.comparing(m -> String.valueOf(m.get("code")) + m.get("projectId")));
        return RULE_VERSION_PREFIX + Hashes.sha256(jsonCodec.write(canonical)).substring(0, 12);
    }

    /** 输入指纹 = 全部证据包 hash 排序聚合 */
    private String inputHashOf(List<CandidateContext> contexts) {
        List<String> hashes = new ArrayList<>();
        for (CandidateContext c : contexts) {
            hashes.add(c.bundle().contentHash());
        }
        hashes.sort(Comparator.naturalOrder());
        return Hashes.sha256(String.join(",", hashes));
    }

    // ------------------------------------------------------------------ 响应

    private DecisionResponse baseResponse(DecisionRecord record) {
        DecisionResponse resp = new DecisionResponse();
        resp.setId(record.getId());
        resp.setDecisionNo(record.getDecisionNo());
        resp.setProjectId(record.getProjectId());
        resp.setRoundId(record.getRoundId());
        resp.setBaselineSnapshotId(record.getBaselineSnapshotId());
        resp.setStatus(record.getStatus());
        resp.setWeightsSnapshot(jsonCodec.readMap(record.getWeightsSnapshot()));
        resp.setRuleVersion(record.getRuleVersion());
        resp.setInputSnapshotHash(record.getInputSnapshotHash());
        resp.setCapacity(record.getCapacity());
        resp.setSelectedCount(record.getSelectedCount());
        resp.setBackupCount(record.getBackupCount());
        resp.setRejectedCount(record.getRejectedCount());
        resp.setDeferredCount(record.getDeferredCount());
        resp.setDecisionNote(record.getDecisionNote());
        resp.setVersionNo(record.getVersionNo());
        resp.setRevisionOf(record.getRevisionOf());
        resp.setCreatedAt(record.getCreatedAt());
        resp.setDecidedAt(record.getDecidedAt());
        return resp;
    }

    private DecisionResponse toResponse(DecisionRecord record, DecisionDtos.RankOutcome ranked,
                                        Map<Long, CandidateContext> byId, List<String> warnings) {
        DecisionResponse resp = baseResponse(record);
        resp.setWarnings(warnings);

        List<DecisionCandidateResponse> list = new ArrayList<>();
        for (DecisionDtos.RankedCandidate row : ranked.candidates()) {
            DecisionCandidateResponse cr = new DecisionCandidateResponse();
            cr.setCandidateId(row.candidateId());
            CandidateContext ctx = byId.get(row.candidateId());
            cr.setMoleculeName(ctx == null ? null : ctx.candidate().getMoleculeName());
            cr.setSmiles(ctx == null ? null : ctx.candidate().getSmiles());
            cr.setMw(ctx == null ? null : ctx.candidate().getMw());
            cr.setBucket(row.bucket());
            cr.setRankNo(row.rankNo());
            cr.setWeightedScore(row.weightedScore());
            cr.setCoversMetrics(row.coversMetrics());
            cr.setMissingMetrics(row.missingMetrics());
            cr.setOutcome(row.outcome());
            cr.setRejectReason(row.rejectReason());
            cr.setManualOverride(false);
            cr.setVetoes(toVetoResponses(row.vetoes()));
            cr.setRisks(riskFlagsOf(ctx));
            cr.setAcceptances(List.of());
            list.add(cr);
        }
        resp.setCandidates(list);
        return resp;
    }

    private List<VetoHitResponse> toVetoResponses(List<DecisionDtos.VetoHit> hits) {
        List<VetoHitResponse> out = new ArrayList<>();
        if (hits == null) {
            return out;
        }
        for (DecisionDtos.VetoHit h : hits) {
            VetoHitResponse r = new VetoHitResponse();
            r.setRuleCode(h.ruleCode());
            r.setSeverity(h.severity());
            r.setMessage(h.message());
            r.setEvidenceRef(h.evidenceRef());
            out.add(r);
        }
        return out;
    }

    /** 从库里的命中行还原响应（详情查询用） */
    private List<VetoHitResponse> toVetoResponsesFromEntities(List<DecisionVetoHit> hits) {
        List<VetoHitResponse> out = new ArrayList<>();
        if (hits == null) {
            return out;
        }
        for (DecisionVetoHit h : hits) {
            VetoHitResponse r = new VetoHitResponse();
            r.setRuleCode(h.getRuleCode());
            r.setSeverity(h.getSeverity());
            r.setMessage(h.getMessage());
            r.setEvidenceRef(h.getEvidenceRef());
            out.add(r);
        }
        return out;
    }

    private CandidateMolecule moleculeOf(Long candidateId) {
        if (candidateId == null) {
            return null;
        }
        return candidateMapper.selectById(candidateId);
    }

    private List<String> readStringList(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        String[] parts = json.replace("[", "").replace("]", "").replace("\"", "").split(",");
        for (String p : parts) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    /** 一个候选 + 它的冻结快照 + 证据包 + 落库行 */
    private record CandidateContext(
            CandidateMolecule candidate,
            EvaluationFreeze freeze,
            DecisionDtos.AssembledBundle bundle,
            EvidenceBundle saved) {
    }

    /**
     * D0~D5 的产物。带上 {@code inputHash} 与 {@code ruleVersion} 是因为
     * 创建、回放、确认三处都要用同一份指纹 —— 各自重算会各自漂。
     */
    private record Pipeline(
            List<CandidateContext> contexts,
            Map<Long, CandidateContext> byId,
            DecisionDtos.RankOutcome ranked,
            List<String> warnings,
            String inputHash,
            String ruleVersion) {
    }
}
