package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.GenerationCandidateLogResponse;
import com.synpharm.design.api.dto.GenerationCreateRequest;
import com.synpharm.design.api.dto.GenerationResponse;
import com.synpharm.design.application.GenerationAppService;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.DesignRound;
import com.synpharm.design.domain.model.GenerationCandidateLog;
import com.synpharm.design.domain.model.GenerationOutputSnapshot;
import com.synpharm.design.domain.model.GenerationRun;
import com.synpharm.design.domain.model.GenerationSlice;
import com.synpharm.design.infrastructure.persistence.BaselineSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.DesignRoundMapper;
import com.synpharm.design.infrastructure.persistence.GenerationCandidateLogMapper;
import com.synpharm.design.infrastructure.persistence.GenerationOutputSnapshotMapper;
import com.synpharm.design.infrastructure.persistence.GenerationRunMapper;
import com.synpharm.design.infrastructure.persistence.GenerationSliceMapper;
import com.synpharm.design.support.SerialNumbers;
import com.synpharm.design.support.generation.CandidateSource;
import com.synpharm.design.support.generation.FunnelDiagnosis;
import com.synpharm.design.support.generation.GenerationDtos;
import com.synpharm.design.support.generation.GenerationPipeline;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 生成批次用例实现（06 §4）。
 *
 * <p><b>失败也是结果</b>：S4~S9 的执行失败（全部分片失败、粗筛后为 0）不抛异常给调用方，
 * 而是返回 {@code status=FAILED} 的批次详情。原因是失败的批次里带着完整的漏斗与
 * {@code filterReasonTop} —— 那是用户排查"为什么生成不出东西"的唯一线索，
 * 用一个 500 把它盖掉等于让用户从零开始猜。
 *
 * <p>S0 的输入校验失败（快照不完整、搜索空间为空）**仍然抛异常**：
 * 那是请求本身不成立，不是「执行了但结果不好」。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
public class GenerationAppServiceImpl implements GenerationAppService {

    private final GenerationRunMapper runMapper;
    private final GenerationSliceMapper sliceMapper;
    private final GenerationCandidateLogMapper logMapper;
    private final GenerationOutputSnapshotMapper snapshotMapper;
    private final BaselineSnapshotMapper baselineSnapshotMapper;
    private final DesignProjectMapper projectMapper;
    private final DesignRoundMapper roundMapper;
    private final CandidateMoleculeMapper candidateMapper;
    private final GenerationPipeline pipeline;
    private final JsonCodec jsonCodec;
    private final Map<String, CandidateSource> sources;

    public GenerationAppServiceImpl(
            GenerationRunMapper runMapper,
            GenerationSliceMapper sliceMapper,
            GenerationCandidateLogMapper logMapper,
            GenerationOutputSnapshotMapper snapshotMapper,
            BaselineSnapshotMapper baselineSnapshotMapper,
            DesignProjectMapper projectMapper,
            DesignRoundMapper roundMapper,
            CandidateMoleculeMapper candidateMapper,
            GenerationPipeline pipeline,
            JsonCodec jsonCodec,
            List<CandidateSource> candidateSources) {
        this.runMapper = runMapper;
        this.sliceMapper = sliceMapper;
        this.logMapper = logMapper;
        this.snapshotMapper = snapshotMapper;
        this.baselineSnapshotMapper = baselineSnapshotMapper;
        this.projectMapper = projectMapper;
        this.roundMapper = roundMapper;
        this.candidateMapper = candidateMapper;
        this.pipeline = pipeline;
        this.jsonCodec = jsonCodec;
        this.sources = candidateSources.stream()
                .collect(Collectors.toMap(CandidateSource::sourceType, Function.identity(), (a, b) -> a));
        log.info("可用候选来源：{}", sources.keySet());
    }

    // ================================================================== 提交

    /**
     * 提交生成任务并同步跑完 S0~S9。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：生成是同步长流程（S4 会调外部
     * RDKit，将来接真引擎是分钟级），把它套进一个大事务既占连接、又会让
     * 「失败必须留痕」失效——{@code rollbackFor = Exception} 会把 FAILED 状态一起回滚，
     * 库里就只剩一条永远 PENDING 的记录。流程的一致性靠状态机表达，不靠事务边界：
     * 每一步 {@code updateById} 自身是原子的，失败停在哪个状态是对外可观测的事实。
     */
    @Override
    public GenerationResponse submit(Long projectId, GenerationCreateRequest request) {
        Long userId = CurrentUser.requireUserId();
        DesignProject project = loadOwned(projectId, userId);

        // ---- 幂等：同键已存在则直接返回 ----
        if (StringUtils.hasText(request.getIdempotencyKey())) {
            GenerationRun existing = runMapper.selectOne(Wrappers.lambdaQuery(GenerationRun.class)
                    .eq(GenerationRun::getProjectId, projectId)
                    .eq(GenerationRun::getIdempotencyKey, request.getIdempotencyKey())
                    .last("LIMIT 1"));
            if (existing != null) {
                log.info("生成提交幂等命中 projectId={} key={} run={}", projectId,
                        request.getIdempotencyKey(), existing.getRunNo());
                return detail(existing.getId());
            }
        }

        // 先校验基线、再校验轮次：基线是「生成只读快照」的数据前提，
        // 轮次只是挂载容器。反过来先报「没开轮次」会把真正缺的东西藏起来
        BaselineSnapshot snapshot = resolveBaseline(projectId, request.getBaselineSnapshotId());
        DesignRound round = resolveRound(project);

        GenerationRun run = new GenerationRun();
        run.setRunNo(SerialNumbers.generationRunNo());
        run.setProjectId(projectId);
        run.setRoundId(round.getId());
        run.setUserId(userId);
        run.setStatus("PENDING");
        run.setProgress(BigDecimal.ZERO);
        run.setTargetCount(0);
        run.setGeneratedCount(0);
        run.setDedupedCount(0);
        run.setRejectedCount(0);
        run.setFinalCount(0);
        run.setIdempotencyKey(request.getIdempotencyKey());
        run.setBaselineSnapshotId(snapshot.getId());
        // S0 尚未定策略（S3 才产出并覆盖），但该列 NOT NULL，先占位
        run.setStrategies("PENDING");
        runMapper.insert(run);

        // ---- S0 校验 + S2/S3 计划 ----
        GenerationDtos.GenerationPlan plan;
        GenerationPipeline.Submission submission;
        long seed = request.getSeed() != null ? request.getSeed() : ThreadLocalRandom.current().nextLong();
        try {
            List<GenerationDtos.SeedMolecule> seeds = toSeeds(request.getSeeds());
            List<CandidateMolecule> existing = seeds.isEmpty() ? loadProjectCandidates(projectId) : List.of();
            submission = pipeline.prepare(run, snapshot, seeds, existing);
            plan = pipeline.planAndPersist(run, submission.context(), seed);
        } catch (BizException e) {
            // S0~S3 失败必须把 run 收尾：否则库里留一条永远 PENDING 的任务，
            // 配额既不释放也无法判断能否重试，用户看到的是"提交成功但什么都没发生"
            markFailed(run, e.getMessage());
            throw e;
        }

        // ---- S4~S9 ----
        CandidateSource source = sources.get(plan.strategy());
        if (source == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "没有可用的候选来源实现：" + plan.strategy(),
                    Map.of("available", sources.keySet()));
        }

        try {
            pipeline.execute(run, submission.context(), plan, source);
        } catch (BizException e) {
            // 执行阶段失败：run 已落 FAILED 与原因，返回详情而不是把漏斗信息丢掉
            log.warn("生成执行失败 run={}：{}", run.getRunNo(), e.getMessage());
        }

        return detail(run.getId());
    }

    // ================================================================== 查询

    @Override
    public PageResult<GenerationResponse> list(Long projectId, int page, int pageSize) {
        loadOwned(projectId, CurrentUser.requireUserId());
        long total = runMapper.selectCount(Wrappers.lambdaQuery(GenerationRun.class)
                .eq(GenerationRun::getProjectId, projectId));
        List<GenerationRun> rows = runMapper.selectList(Wrappers.lambdaQuery(GenerationRun.class)
                .eq(GenerationRun::getProjectId, projectId)
                .orderByDesc(GenerationRun::getId)
                .last("LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize));
        List<GenerationResponse> items = new ArrayList<>(rows.size());
        for (GenerationRun row : rows) {
            items.add(toResponse(row, false));
        }
        return new PageResult<>(items, total, page, pageSize,
                (long) page * pageSize < total);
    }

    @Override
    public GenerationResponse detail(Long runId) {
        GenerationRun run = loadOwnedRun(runId);
        return toResponse(run, true);
    }

    @Override
    public GenerationResponse funnel(Long runId) {
        GenerationRun run = loadOwnedRun(runId);
        GenerationResponse response = new GenerationResponse();
        response.setId(run.getId());
        response.setRunNo(run.getRunNo());
        response.setFunnel(loadFunnel(run));
        return response;
    }

    @Override
    public GenerationResponse diagnosis(Long runId) {
        GenerationRun run = loadOwnedRun(runId);
        GenerationResponse response = new GenerationResponse();
        response.setId(run.getId());
        response.setRunNo(run.getRunNo());
        response.setStatus(run.getStatus());
        response.setDiagnosis(buildDiagnosis(run));
        return response;
    }

    @Override
    public List<GenerationCandidateLogResponse> candidateLogs(Long runId, String stage, String outcome) {
        loadOwnedRun(runId);
        var query = Wrappers.lambdaQuery(GenerationCandidateLog.class)
                .eq(GenerationCandidateLog::getRunId, runId);
        if (StringUtils.hasText(stage)) {
            query.eq(GenerationCandidateLog::getStage, stage);
        }
        if (StringUtils.hasText(outcome)) {
            query.eq(GenerationCandidateLog::getOutcome, outcome);
        }
        query.orderByAsc(GenerationCandidateLog::getId).last("LIMIT 500");

        List<GenerationCandidateLog> rows = logMapper.selectList(query);
        List<GenerationCandidateLogResponse> items = new ArrayList<>(rows.size());
        for (GenerationCandidateLog row : rows) {
            GenerationCandidateLogResponse dto = new GenerationCandidateLogResponse();
            dto.setId(row.getId());
            dto.setStage(row.getStage());
            dto.setOutcome(row.getOutcome());
            dto.setSmiles(row.getSmiles());
            dto.setSequenceHash(row.getSequenceHash());
            dto.setInchikey(row.getInchikey());
            dto.setCandidateId(row.getCandidateId());
            dto.setFilterReason(row.getFilterReason());
            dto.setPreScore(row.getPreScore());
            dto.setClusterId(row.getClusterId());
            dto.setDedupInto(row.getDedupInto());
            dto.setCreatedAt(row.getCreatedAt());
            items.add(dto);
        }
        return items;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerationResponse cancel(Long runId) {
        GenerationRun run = loadOwnedRun(runId);
        if (!"PENDING".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.BUSINESS_CONFLICT,
                    "只有排队中或运行中的批次可以取消，当前状态：" + run.getStatus());
        }
        run.setStatus("CANCELLED");
        run.setCompletedAt(LocalDateTime.now());
        runMapper.updateById(run);
        return detail(runId);
    }

    // ================================================================== 组装

    private GenerationResponse toResponse(GenerationRun run, boolean withDetail) {
        GenerationResponse r = new GenerationResponse();
        r.setId(run.getId());
        r.setRunNo(run.getRunNo());
        r.setProjectId(run.getProjectId());
        r.setRoundId(run.getRoundId());
        r.setBaselineSnapshotId(run.getBaselineSnapshotId());
        r.setStatus(run.getStatus());
        r.setStrategy(run.getStrategies());
        r.setNSamples(run.getNSamples());
        r.setBatchSize(run.getBatchSize());
        r.setSeed(run.getSeed());
        r.setEstimatedGpuHours(run.getEstimatedGpuHours());
        r.setPlanConfidence(run.getPlanConfidence());
        r.setRationale(run.getRationale());
        r.setPlanHash(run.getPlanHash());
        r.setGeneratedCount(run.getGeneratedCount());
        r.setDedupedCount(run.getDedupedCount());
        r.setRejectedCount(run.getRejectedCount());
        r.setFinalCount(run.getFinalCount());
        r.setValidClusterCount(run.getValidClusterCount());
        r.setMinCandidates(run.getMinCandidates());
        r.setProgress(run.getProgress());
        r.setErrorMsg(run.getErrorMsg());
        r.setLatencyMs(run.getLatencyMs());
        r.setOutputSnapshotId(run.getOutputSnapshotId());
        r.setStartedAt(run.getStartedAt());
        r.setCompletedAt(run.getCompletedAt());
        r.setCreatedAt(run.getCreatedAt());

        Map<String, Object> plan = jsonCodec.readMap(run.getPlanJson());
        r.setPlan(plan);
        if (plan != null) {
            r.setDegraded(Boolean.TRUE.equals(plan.get("degraded")));
            Object reason = plan.get("degradedReason");
            r.setDegradedReason(reason == null ? null : String.valueOf(reason));
        }

        if (withDetail) {
            r.setFunnel(loadFunnel(run));
            r.setDiagnosis(buildDiagnosis(run));
            r.setSlices(loadSlices(run.getId()));
        }
        return r;
    }

    private Map<String, Object> loadFunnel(GenerationRun run) {
        if (run.getOutputSnapshotId() == null) {
            return null;
        }
        GenerationOutputSnapshot snapshot = snapshotMapper.selectById(run.getOutputSnapshotId());
        return snapshot == null ? null : jsonCodec.readMap(snapshot.getFilterSummary());
    }

    private Map<String, Object> buildDiagnosis(GenerationRun run) {
        Map<String, Object> funnelMap = loadFunnel(run);
        if (funnelMap == null) {
            return null;
        }
        FunnelDiagnosis.Funnel funnel = new FunnelDiagnosis.Funnel(
                intOf(funnelMap, "generated"),
                intOf(funnelMap, "afterDedup"),
                intOf(funnelMap, "afterPreFilter"),
                intOf(funnelMap, "quotaCut"),
                intOf(funnelMap, "afterQuota"),
                Map.of());
        int minCandidates = run.getMinCandidates() == null ? 1 : run.getMinCandidates();
        int clusters = run.getValidClusterCount() == null ? 0 : run.getValidClusterCount();
        List<String> warnings = StringUtils.hasText(run.getErrorMsg())
                ? List.of(run.getErrorMsg()) : List.of();
        return FunnelDiagnosis.diagnose(funnel, minCandidates, clusters, warnings).toMap();
    }

    private List<GenerationResponse.SliceView> loadSlices(Long runId) {
        List<GenerationSlice> slices = sliceMapper.selectList(Wrappers.lambdaQuery(GenerationSlice.class)
                .eq(GenerationSlice::getRunId, runId)
                .orderByAsc(GenerationSlice::getSliceNo));
        List<GenerationResponse.SliceView> views = new ArrayList<>(slices.size());
        for (GenerationSlice s : slices) {
            GenerationResponse.SliceView v = new GenerationResponse.SliceView();
            v.setSliceNo(s.getSliceNo());
            v.setStatus(s.getStatus());
            v.setProducedCount(s.getProducedCount());
            v.setRetryCount(s.getRetryCount());
            v.setSeed(s.getSeed());
            v.setErrorMsg(s.getErrorMsg());
            v.setCompletedAt(s.getCompletedAt());
            views.add(v);
        }
        return views;
    }

    private static int intOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================== 内部

    private DesignProject loadOwned(Long projectId, Long userId) {
        DesignProject project = projectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, Map.of("projectId", projectId));
        return project;
    }

    private GenerationRun loadOwnedRun(Long runId) {
        Long userId = CurrentUser.requireUserId();
        GenerationRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "生成批次不存在：" + runId);
        }
        loadOwned(run.getProjectId(), userId);
        return run;
    }

    private DesignRound resolveRound(DesignProject project) {
        if (project.getCurrentRoundId() != null) {
            DesignRound round = roundMapper.selectById(project.getCurrentRoundId());
            if (round != null) {
                return round;
            }
        }
        List<DesignRound> rounds = roundMapper.selectList(Wrappers.lambdaQuery(DesignRound.class)
                .eq(DesignRound::getProjectId, project.getId())
                .orderByDesc(DesignRound::getRoundNo)
                .last("LIMIT 1"));
        if (rounds.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "项目还没有开启任何轮次；生成必须挂在某一轮上，请先开启一轮");
        }
        return rounds.get(0);
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
                    "项目还没有冻结基线；生成任务只读快照，请先在需求分析中冻结基线");
        }
        return active;
    }

    private void markFailed(GenerationRun run, String reason) {
        run.setStatus("FAILED");
        run.setErrorMsg(reason == null ? "生成失败" : reason);
        run.setCompletedAt(LocalDateTime.now());
        run.setProgress(BigDecimal.ZERO);
        if (run.getStartedAt() != null) {
            run.setLatencyMs((int) Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis());
        }
        runMapper.updateById(run);
    }

    private List<CandidateMolecule> loadProjectCandidates(Long projectId) {
        return candidateMapper.selectList(Wrappers.lambdaQuery(CandidateMolecule.class)
                .eq(CandidateMolecule::getProjectId, projectId)
                .orderByDesc(CandidateMolecule::getId)
                .last("LIMIT 50"));
    }

    private List<GenerationDtos.SeedMolecule> toSeeds(List<GenerationCreateRequest.SeedInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<GenerationDtos.SeedMolecule> seeds = new ArrayList<>(inputs.size());
        for (GenerationCreateRequest.SeedInput input : inputs) {
            seeds.add(new GenerationDtos.SeedMolecule(
                    StringUtils.hasText(input.getRef()) ? input.getRef() : "SEED",
                    input.getSmiles(),
                    input.getHeavyChain(),
                    input.getLightChain(),
                    input.getExtraChains()));
        }
        return seeds;
    }
}
