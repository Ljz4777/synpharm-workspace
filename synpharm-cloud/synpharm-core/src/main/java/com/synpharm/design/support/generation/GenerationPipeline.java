package com.synpharm.design.support.generation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DedupMerge;
import com.synpharm.design.domain.model.GenerationCandidateLog;
import com.synpharm.design.domain.model.GenerationOutputSnapshot;
import com.synpharm.design.domain.model.GenerationRun;
import com.synpharm.design.domain.model.GenerationSlice;
import com.synpharm.design.infrastructure.client.ChemistryDtos;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DedupMergeMapper;
import com.synpharm.design.infrastructure.persistence.GenerationCandidateLogMapper;
import com.synpharm.design.infrastructure.persistence.GenerationOutputSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.GenerationRunMapper;
import com.synpharm.design.infrastructure.persistence.GenerationSliceMapper;
import com.synpharm.design.support.SerialNumbers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 生成流水线编排（S0~S9，06 §4.1）。
 *
 * <p><b>同步执行的取舍</b>：设计里 S4 是异步主耗时环节。当前候选来源是规则式
 * （单点突变 / 导入），耗时在毫秒级，做成异步只会让"提交后立刻查还是 PENDING"变成
 * 一个不必要的排障点。因此此处同步跑完 S0~S9 并写全状态机 —— 将来接真引擎时，
 * 只需把 {@link #execute} 挪进线程池，{@code PENDING→RUNNING} 的状态流转已经就位。
 *
 * <p><b>四条纪律的落点</b>：
 * <ul>
 *   <li>幂等 —— 由 {@code idempotency_key} 在 AppService 层命中已有 run；</li>
 *   <li>缓存优先 —— {@code generation_cache} 表就绪，规则式实现不产生缓存（无可复用的外部产物）；</li>
 *   <li>原因必记 —— 每次筛除都写 {@code generation_candidate_log}；</li>
 *   <li>Agent 只出现两次 —— S3 与 S9 目前都是规则实现，接口位置见各自类注释。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationPipeline {

    private final GenerationPlanner planner;
    private final DedupStage dedupStage;
    private final PreFilterStage preFilterStage;
    private final DiversityQuotaStage quotaStage;
    private final HardRuleParser hardRuleParser;

    private final GenerationRunMapper runMapper;
    private final GenerationSliceMapper sliceMapper;
    private final GenerationCandidateLogMapper logMapper;
    private final DedupMergeMapper dedupMergeMapper;
    private final GenerationOutputSnapshotMapper snapshotMapper;
    private final CandidateMoleculeMapper candidateMapper;

    private final JsonCodec jsonCodec;

    /** S0 校验结果 + 建好的 run */
    public record Submission(GenerationRun run, GenerationDtos.GenerationContext context) {
    }

    // ================================================================== S0

    /**
     * S0 基线校验与任务登记。
     *
     * <p>只读快照，不读项目当前状态 —— 用户中途改约束不影响本任务。
     *
     * @param existingCandidates 项目内已有候选（作为父本种子）
     * @param providedSeeds      请求体显式提供的种子（优先于项目候选）
     */
    public Submission prepare(
            GenerationRun run,
            BaselineSnapshot snapshot,
            List<GenerationDtos.SeedMolecule> providedSeeds,
            List<CandidateMolecule> existingCandidates) {

        GenerationDtos.GenerationContext context =
                buildContext(run, snapshot, providedSeeds, existingCandidates);
        return new Submission(run, context);
    }

    private GenerationDtos.GenerationContext buildContext(
            GenerationRun run,
            BaselineSnapshot snapshot,
            List<GenerationDtos.SeedMolecule> providedSeeds,
            List<CandidateMolecule> existingCandidates) {

        Map<String, Object> completeness = jsonCodec.readMap(snapshot.getCompletenessJson());
        if (completeness != null) {
            List<String> missing = completeness.entrySet().stream()
                    .filter(e -> "MISSING".equals(String.valueOf(e.getValue())))
                    .map(Map.Entry::getKey)
                    .toList();
            if (!missing.isEmpty()) {
                throw new BizException(ErrorCode.BASELINE_INCOMPLETE,
                        "基线快照缺少必需块 " + missing + "，不能作为生成输入");
            }
        }

        Map<String, Object> quota = jsonCodec.readMap(snapshot.getQuotaJson());
        Integer minCandidates = intOf(quota, "minCandidates");
        BigDecimal gpuHours = decimalOf(quota, "gpuHours");
        Integer wetLab = intOf(quota, "wetLab");
        if (minCandidates == null || minCandidates < 1) {
            throw new BizException(ErrorCode.BASELINE_INCOMPLETE,
                    "基线块7 缺少 min_candidates，生成后置闸门无从判定");
        }

        Map<String, Object> targets = jsonCodec.readMap(snapshot.getTargetsJson());
        String moaCode = targets == null ? null : stringOf(targets, "moaCode");

        List<GenerationDtos.HardRule> hardRules = hardRuleParser.parse(snapshot.getHardRulesJson());
        if (hardRules.isEmpty()) {
            throw new BizException(ErrorCode.BASELINE_INCOMPLETE,
                    "基线块4 没有启用的硬约束，生成阶段无法过滤越界候选");
        }

        boolean pocketAvailable = hasSelectedPocket(snapshot.getStructureJson());

        // 种子：请求显式提供优先；否则退回项目内已有候选（第二轮迭代的常态）
        List<GenerationDtos.SeedMolecule> seeds = new ArrayList<>();
        if (providedSeeds != null && !providedSeeds.isEmpty()) {
            seeds.addAll(providedSeeds);
        } else if (existingCandidates != null) {
            int limit = Math.min(existingCandidates.size(), 50);
            for (int i = 0; i < limit; i++) {
                CandidateMolecule c = existingCandidates.get(i);
                seeds.add(new GenerationDtos.SeedMolecule(
                        "CAND-" + c.getId(),
                        c.getSmiles(),
                        c.getHeavyChain(),
                        c.getLightChain(),
                        parseExtraChains(c.getExtraChains())));
            }
        }

        boolean seqRequired = isSequenceModality(snapshot.getModalityCode());

        return new GenerationDtos.GenerationContext(
                run.getProjectId(),
                snapshot.getId(),
                snapshot.getModalityCode(),
                seqRequired,
                moaCode,
                minCandidates,
                gpuHours,
                null,
                wetLab,
                minCandidates,
                hardRules,
                seeds,
                pocketAvailable);
    }


    private boolean hasSelectedPocket(String structureJson) {
        Map<String, Object> structure = jsonCodec.readMap(structureJson);
        if (structure == null) {
            return false;
        }
        Object pockets = structure.get("pockets");
        if (!(pockets instanceof List<?> list)) {
            return false;
        }
        for (Object p : list) {
            if (p instanceof Map<?, ?> raw && Boolean.TRUE.equals(raw.get("isSelected"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSequenceModality(String modalityCode) {
        if (modalityCode == null) {
            return false;
        }
        return switch (modalityCode.toUpperCase()) {
            case "ANTIBODY", "BISPECIFIC", "NANOBODY", "PEPTIDE", "FUSION_PROTEIN", "ADC" -> true;
            default -> false;
        };
    }

    // ================================================================== S2 + S3

    /** S2 空间定义 → S3 策略计划，并把计划写回 run */
    public GenerationDtos.GenerationPlan planAndPersist(
            GenerationRun run, GenerationDtos.GenerationContext context, long seed) {

        GenerationDtos.SpaceManifest space = planner.estimateSpace(context);
        if (space.spaceSize() <= 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "搜索空间为空，无法生成：" + space.description(), Map.of(
                    "sourceType", space.sourceType(),
                    "exclusions", space.exclusions(),
                    "hint", "请提供父本分子 / 种子结构，或部署生成引擎（crem）"));
        }

        GenerationDtos.GenerationPlan plan = planner.plan(context, space, seed);

        run.setBaselineSnapshotId(context.baselineSnapshotId());
        run.setPlanJson(jsonCodec.write(plan.toMap()));
        run.setPlanHash(sha256(jsonCodec.write(plan.toMap())));
        run.setFallbackStrategy(plan.fallbackStrategy());
        run.setNSamples(plan.nSamples());
        run.setBatchSize(plan.batchSize());
        run.setSeed(plan.seed());
        run.setEstimatedGpuHours(plan.estimatedGpuHours());
        run.setRationale(plan.rationale());
        run.setPlanConfidence(plan.confidence());
        run.setMinCandidates(context.minCandidates());
        run.setStrategies(plan.strategy());
        runMapper.updateById(run);

        return plan;
    }

    // ================================================================== S4~S9

    /**
     * 执行 S4~S9，全程落库。
     *
     * @return 诊断结果
     */
    public FunnelDiagnosis.Diagnosis execute(
            GenerationRun run,
            GenerationDtos.GenerationContext context,
            GenerationDtos.GenerationPlan plan,
            CandidateSource source) {

        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        runMapper.updateById(run);

        // ---------------- S4 分片执行 ----------------
        int sliceCount = Math.max(1, (int) Math.ceil((double) plan.nSamples() / plan.batchSize()));
        List<GenerationDtos.GeneratedCandidate> generated = new ArrayList<>();
        int failedSlices = 0;

        for (int sliceNo = 1; sliceNo <= sliceCount; sliceNo++) {
            int count = Math.min(plan.batchSize(), plan.nSamples() - generated.size()
                    - failedSlices * plan.batchSize());
            if (count <= 0) {
                count = plan.batchSize();
            }
            GenerationSlice slice = new GenerationSlice();
            slice.setRunId(run.getId());
            slice.setSliceNo(sliceNo);
            slice.setSeed(plan.seed() * 1_000_003L + sliceNo);
            slice.setParamHash(sha256(jsonCodec.write(plan.toMap())));
            slice.setInputHash(sha256(String.valueOf(context.baselineSnapshotId())));
            slice.setStatus("RUNNING");
            slice.setStartedAt(LocalDateTime.now());
            sliceMapper.insert(slice);

            try {
                List<GenerationDtos.GeneratedCandidate> produced =
                        source.produce(context, plan, sliceNo, count);
                generated.addAll(produced);

                slice.setStatus("SUCCEEDED");
                slice.setProducedCount(produced.size());
                slice.setGpuHoursUsed(BigDecimal.ZERO);
                slice.setCompletedAt(LocalDateTime.now());
                sliceMapper.updateById(slice);
            } catch (Exception e) {
                failedSlices++;
                log.warn("分片 {} 执行失败：{}", sliceNo, e.getMessage());
                slice.setStatus("FAILED");
                slice.setErrorMsg(truncate(e.getMessage(), 900));
                slice.setCompletedAt(LocalDateTime.now());
                sliceMapper.updateById(slice);
            }
        }

        if (generated.isEmpty()) {
            // 全部分片失败或产出为空：不允许空集往下游流
            run.setStatus("FAILED");
            run.setErrorMsg(failedSlices > 0
                    ? "全部 " + sliceCount + " 个分片失败或未产出候选"
                    : "分片执行未产出任何候选（种子空间不足）");
            run.setCompletedAt(LocalDateTime.now());
            run.setProgress(BigDecimal.ZERO);
            runMapper.updateById(run);
            throw new BizException(ErrorCode.BUSINESS_CONFLICT, run.getErrorMsg());
        }

        run.setGeneratedCount(generated.size());
        run.setProgress(new BigDecimal("40.00"));
        runMapper.updateById(run);

        // ---------------- S5 去重 ----------------
        DedupStage.Result dedup = dedupStage.dedup(generated);
        for (DedupStage.Entry e : dedup.kept()) {
            writeLog(run.getId(), null, "S5_DEDUP", e.candidate(), "KEPT", null,
                    null, e.clusterId(), null);
        }
        for (DedupStage.Entry e : dedup.merged()) {
            Long logId = writeLog(run.getId(), null, "S5_DEDUP", e.candidate(), "DEDUPED",
                    List.of(new PreFilterStage.FilterReason("DEDUP_MERGED", "INFO",
                            "与 " + e.mergedInto() + " 相似度 "
                                    + (e.similarity() == null ? "1.0000" : e.similarity().toPlainString()))),
                    null, e.clusterId(), null);

            DedupMerge merge = new DedupMerge();
            merge.setRunId(run.getId());
            merge.setMergedLogId(logId);
            merge.setSimilarity(e.similarity());
            merge.setMethod(e.candidate().smiles() != null ? "ECFP4" : "EXACT");
            dedupMergeMapper.insert(merge);
        }
        run.setDedupedCount(dedup.kept().size());
        run.setProgress(new BigDecimal("55.00"));
        runMapper.updateById(run);

        // ---------------- S6 粗筛 ----------------
        PreFilterStage.Result preFilter = preFilterStage.filter(dedup.kept(), context);
        Map<String, Long> reasonCounts = new LinkedHashMap<>();
        for (PreFilterStage.Entry e : preFilter.passed()) {
            writeLog(run.getId(), null, "S6_PRE_FILTER", e.dedupEntry().candidate(), "KEPT",
                    e.reasons(), e.preScore(), e.dedupEntry().clusterId(), null);
            collectWarnings(reasonCounts, e.reasons());
        }
        for (PreFilterStage.Entry e : preFilter.filtered()) {
            writeLog(run.getId(), null, "S6_PRE_FILTER", e.dedupEntry().candidate(), "FILTERED",
                    e.reasons(), e.preScore(), e.dedupEntry().clusterId(), null);
            collectWarnings(reasonCounts, e.reasons());
        }
        run.setRejectedCount(preFilter.filtered().size());
        run.setProgress(new BigDecimal("70.00"));
        runMapper.updateById(run);

        if (preFilter.passed().isEmpty()) {
            // 粗筛后剩 0：明确失败，提示回 S2/S3
            run.setStatus("FAILED");
            run.setErrorMsg("粗筛后无剩余候选：硬约束可能过严，或种子本身不满足约束。"
                    + "主要淘汰原因：" + reasonCounts);
            run.setCompletedAt(LocalDateTime.now());
            runMapper.updateById(run);
            throw new BizException(ErrorCode.BUSINESS_CONFLICT, run.getErrorMsg());
        }

        // ---------------- S7 多样性配额 ----------------
        int quota = context.minCandidates();
        DiversityQuotaStage.Result quotaResult = quotaStage.allocate(preFilter.passed(), quota);
        for (DiversityQuotaStage.Entry e : quotaResult.selected()) {
            writeLog(run.getId(), null, "S7_QUOTA", e.filterEntry().dedupEntry().candidate(), "KEPT",
                    e.filterEntry().reasons(), e.filterEntry().preScore(), e.clusterId(), null);
        }
        for (DiversityQuotaStage.Entry e : quotaResult.cut()) {
            writeLog(run.getId(), null, "S7_QUOTA", e.filterEntry().dedupEntry().candidate(), "QUOTA_CUT",
                    List.of(new PreFilterStage.FilterReason("QUOTA_CUT", "INFO",
                            e.cutReason() == null ? "超出配额" : e.cutReason())),
                    e.filterEntry().preScore(), e.clusterId(), null);
            reasonCounts.merge("QUOTA_CUT", 1L, Long::sum);
        }
        run.setValidClusterCount(quotaResult.clusterCount());
        run.setProgress(new BigDecimal("85.00"));
        runMapper.updateById(run);

        // ---------------- S8 登记与快照 ----------------
        List<Long> candidateIds = persistCandidates(run, context, quotaResult.selected());
        run.setFinalCount(candidateIds.size());

        FunnelDiagnosis.Funnel funnel = new FunnelDiagnosis.Funnel(
                generated.size(), dedup.kept().size(), preFilter.passed().size(),
                quotaResult.cut().size(), candidateIds.size(),
                FunnelDiagnosis.topReasons(reasonCounts, 5));

        GenerationOutputSnapshot snapshot = writeSnapshot(run, plan, candidateIds, funnel, quotaResult);
        run.setOutputSnapshotId(snapshot.getId());

        // ---------------- S9 漏斗解读 ----------------
        FunnelDiagnosis.Diagnosis diagnosis = FunnelDiagnosis.diagnose(
                funnel, context.minCandidates(), quotaResult.clusterCount(), quotaResult.warnings());

        run.setStatus(diagnosis.verdict() == FunnelDiagnosis.FAIL ? "FAILED" : "SUCCEEDED");
        run.setProgress(new BigDecimal("100.00"));
        run.setCompletedAt(LocalDateTime.now());
        run.setLatencyMs((int) java.time.Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis());
        // 判不了的规则必须对外说清楚：没检查 ≠ 检查通过。
        // 静默跳过会让用户以为「硬约束都过了」，而实际上一条都没判定
        List<String> notes = new ArrayList<>(quotaResult.warnings());
        if (!preFilter.skippedRules().isEmpty()) {
            notes.add("以下规则未能判定（不是通过）：" + String.join("、", preFilter.skippedRules()));
        }
        if (diagnosis.verdict() == FunnelDiagnosis.PASS_WITH_WARNING || !preFilter.skippedRules().isEmpty()) {
            run.setErrorMsg(String.join("；", notes));
        }
        runMapper.updateById(run);

        log.info("生成完成 run={} verdict={} 候选={} 簇={}", run.getRunNo(), diagnosis.verdict(),
                candidateIds.size(), quotaResult.clusterCount());
        return diagnosis;
    }

    // ================================================================== 落库

    private Long writeLog(long runId, Long sliceId, String stage,
                          GenerationDtos.GeneratedCandidate candidate, String outcome,
                          List<PreFilterStage.FilterReason> reasons, BigDecimal preScore,
                          String clusterId, Long dedupInto) {
        GenerationCandidateLog entity = new GenerationCandidateLog();
        entity.setRunId(runId);
        entity.setSliceId(sliceId);
        entity.setStage(stage);
        // tempRef 是「日志 → 落库候选」的唯一关联键：S5~S7 时候选还没 id，
        // 只在 S8 之后凭它回填 candidate_id。不存它就永远点不到对应候选
        entity.setTempRef(candidate.tempRef());
        entity.setSmiles(truncate(candidate.smiles(), 1900));
        entity.setSequenceHash(candidate.sequenceHash());
        entity.setInchikey(null);
        entity.setOutcome(outcome);
        if (reasons != null && !reasons.isEmpty()) {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (PreFilterStage.FilterReason r : reasons) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", r.code());
                item.put("severity", r.severity());
                item.put("message", r.message());
                payload.add(item);
            }
            entity.setFilterReason(jsonCodec.write(payload));
        }
        entity.setPreScore(preScore);
        entity.setClusterId(clusterId);
        entity.setDedupInto(dedupInto);
        logMapper.insert(entity);
        return entity.getId();
    }

    private void collectWarnings(Map<String, Long> counts, List<PreFilterStage.FilterReason> reasons) {
        if (reasons == null) {
            return;
        }
        for (PreFilterStage.FilterReason r : reasons) {
            if ("HARD".equals(r.severity())) {
                counts.merge(r.code(), 1L, Long::sum);
            }
        }
    }

    private List<Long> persistCandidates(
            GenerationRun run,
            GenerationDtos.GenerationContext context,
            List<DiversityQuotaStage.Entry> selected) {

        List<Long> ids = new ArrayList<>();
        for (DiversityQuotaStage.Entry e : selected) {
            GenerationDtos.GeneratedCandidate c = e.filterEntry().dedupEntry().candidate();

            CandidateMolecule entity = new CandidateMolecule();
            entity.setProjectId(run.getProjectId());
            entity.setRoundId(run.getRoundId());
            entity.setOriginRunId(run.getId());
            entity.setSmiles(truncate(c.smiles(), 1900));
            entity.setCanonicalSmiles(truncate(e.filterEntry().dedupEntry().canonicalSmiles(), 1900));
            entity.setInchikey(e.filterEntry().dedupEntry().inchikey());
            entity.setSequenceHash(c.sequenceHash());
            entity.setHeavyChain(c.heavyChain());
            entity.setLightChain(c.lightChain());
            entity.setExtraChains(c.extraChains() == null ? null : jsonCodec.write(c.extraChains()));
            entity.setModalityCode(context.modalityCode());
            entity.setStrategyName(c.strategyName());
            entity.setOriginType("GENERATED");
            entity.setGenerationParams(jsonCodec.write(c.generationParams()));
            entity.setPipelineStatus("passed");
            entity.setReviewStatus("NEW");
            entity.setClusterId(e.clusterId());
            entity.setPreScore(e.filterEntry().preScore());
            entity.setHardPassed(1);
            entity.setBatchTag(run.getRunNo());
            entity.setVersionNo(1);
            entity.setEditRound(0);
            entity.setBranchStatus("active");
            entity.setNeedsReEval(0);
            candidateMapper.insert(entity);
            ids.add(entity.getId());

            // 回填：把 S5~S7 期间写的日志与新候选关联起来。
            // 不回填的话「逐分子去向」点不到候选，诊断页只能看不能追
            logMapper.update(null, Wrappers.lambdaUpdate(GenerationCandidateLog.class)
                    .eq(GenerationCandidateLog::getRunId, run.getId())
                    .eq(GenerationCandidateLog::getTempRef, c.tempRef())
                    .set(GenerationCandidateLog::getCandidateId, entity.getId()));
        }
        return ids;
    }

    private GenerationOutputSnapshot writeSnapshot(
            GenerationRun run,
            GenerationDtos.GenerationPlan plan,
            List<Long> candidateIds,
            FunnelDiagnosis.Funnel funnel,
            DiversityQuotaStage.Result quotaResult) {

        Map<String, Object> clusterDist = new LinkedHashMap<>();
        for (DiversityQuotaStage.Entry e : quotaResult.selected()) {
            clusterDist.merge(e.clusterId(), 1, (a, b) -> (Integer) a + (Integer) b);
        }

        String candidateJson = jsonCodec.write(candidateIds);
        String funnelJson = jsonCodec.write(funnel.toMap());
        String clusterJson = jsonCodec.write(clusterDist);
        String planJson = jsonCodec.write(plan.toMap());

        GenerationOutputSnapshot snapshot = new GenerationOutputSnapshot();
        snapshot.setSnapshotNo(SerialNumbers.generationSnapshotNo());
        snapshot.setRunId(run.getId());
        snapshot.setCandidateIds(candidateJson);
        snapshot.setFilterSummary(funnelJson);
        snapshot.setClusterDist(clusterJson);
        snapshot.setPlanJson(planJson);
        snapshot.setToolVersions(jsonCodec.write(Map.of("rdkit", "runtime", "source", plan.strategy())));
        snapshot.setContentHash(sha256(candidateJson + "|" + funnelJson + "|" + clusterJson + "|" + planJson));
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    // ================================================================== 工具

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    private List<String> parseExtraChains(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            String[] parsed = jsonCodec.read(json, String[].class);
            return parsed == null || parsed.length == 0 ? null : List.of(parsed);
        } catch (Exception e) {
            log.debug("extra_chains 解析失败：{}", e.getMessage());
            return null;
        }
    }

    private static Integer intOf(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOf(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOf(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> map, String key) {
        if (map == null) {
            return Map.of();
        }
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
