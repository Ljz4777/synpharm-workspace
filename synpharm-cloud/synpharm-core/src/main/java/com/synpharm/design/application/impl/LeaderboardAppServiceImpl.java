package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.security.OwnershipGuard;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.api.dto.LeaderboardResponse;
import com.synpharm.design.application.LeaderboardAppService;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.EvaluationResult;
import com.synpharm.design.domain.model.EvaluationRun;
import com.synpharm.design.domain.model.MetricDefinition;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.DesignProjectMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationResultMapper;
import com.synpharm.design.infrastructure.persistence.EvaluationRunMapper;
import com.synpharm.design.infrastructure.persistence.MetricDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 排行榜实现。
 *
 * <p><b>三条必须守住的线</b>：
 * <ol>
 *   <li><b>偏移分页有硬上限</b>（规范 §4.2）：{@code page*pageSize > 1000} 直接 409 {@code USE_CURSOR}，
 *       引导调用方改用 {@code afterScore}/{@code afterId} 键集分页。在十万行的
 *       {@code evaluation_result} 上让 {@code OFFSET} 无限增长是必然的性能悬崖，而"能跑但慢"
 *       比"明确拒绝"更难被发现。</li>
 *   <li><b>{@code sortBy} 只走白名单</b>：{@code total_score} 或 <b>该 run 实际启用过的 metricCode</b>。
 *       即使白名单通过，拼进 {@code ORDER BY} 前仍要过一遍 {@code ^[A-Z][A-Z0-9_]{0,39}$} ——
 *       名字最终会进 SQL 文本，参数化救不了 ORDER BY。</li>
 *   <li><b>{@code weightsSnapshot} 与 {@code redistributed} 必须有值</b>（红线 R1）：前者取
 *       {@code evaluation_run.metric_weights} 原样，后者由「启用但没算出结果」的指标推导。</li>
 * </ol>
 *
 * <p><b>{@code sortBy=metricCode} 的语义</b>：只对<b>算了该指标</b>的候选排名（没有值就无法排名），
 * 这也是为什么它走 {@code evaluation_result} 而不是 {@code candidate_molecule}。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardAppServiceImpl implements LeaderboardAppService {

    /** 偏移分页的硬上限（规范 §4.2） */
    private static final int OFFSET_LIMIT = 1000;

    /** 默认排序字段：{@code candidate_molecule.total_score} */
    private static final String TOTAL_SCORE = "total_score";

    /** 指标码的形状约束 —— 拼进 ORDER BY 前的最后一道闸 */
    private static final Pattern METRIC_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");

    private final DesignProjectMapper projectMapper;
    private final CandidateMoleculeMapper candidateMapper;
    private final EvaluationResultMapper resultMapper;
    private final EvaluationRunMapper runMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final JsonCodec jsonCodec;

    @Override
    public LeaderboardResponse leaderboard(Long projectId, Long roundId, String sortBy, String order,
                                           BigDecimal afterScore, Long afterId, int page, int pageSize) {
        Long userId = CurrentUser.requireUserId();

        DesignProject project = projectId == null ? null : projectMapper.selectById(projectId);
        OwnershipGuard.assertOwned(userId, project == null ? null : project.getUserId(),
                ErrorCode.DESIGN_PROJECT_NOT_FOUND, projectId == null ? Map.of() : Map.of("projectId", projectId));

        if (roundId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "roundId 必填：排行榜的主体是某一轮的候选");
        }
        boolean desc = !"asc".equalsIgnoreCase(order == null ? "" : order.trim());

        EvaluationRun run = findRun(projectId, roundId);
        Set<String> enabledMetrics = splitCodes(run == null ? null : run.getEnabledMetrics());

        String sortField = sortBy == null || sortBy.isBlank() ? TOTAL_SCORE : sortBy.trim();
        boolean byMetric = !TOTAL_SCORE.equalsIgnoreCase(sortField);
        if (byMetric) {
            // 白名单：必须是该 run 启用过的指标（否则拼进 ORDER BY 等于开了 SQL 注入口）
            if (!enabledMetrics.contains(sortField)) {
                throw new BizException(ErrorCode.VALIDATION_FAILED,
                        "sortBy 只允许 total_score 或本轮启用过的指标，可选 " + enabledMetrics);
            }
            if (!METRIC_CODE.matcher(sortField).matches()) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "非法指标码: " + sortField);
            }
        }

        boolean keyset = afterScore != null || afterId != null;
        if (!keyset && (long) page * pageSize > OFFSET_LIMIT) {
            throw new BizException(ErrorCode.USE_CURSOR,
                    "偏页过深（> " + OFFSET_LIMIT + "），请改用 afterScore + afterId 键集分页");
        }

        // 取多一条用于判断 hasNext（避免额外 COUNT）
        int fetch = pageSize + 1;
        List<LeaderboardResponse.LeaderboardRow> rows;
        Long total = null;
        if (byMetric) {
            rows = listByMetric(projectId, roundId, run, sortField, desc, keyset, afterScore, afterId,
                    keyset ? fetch : (page - 1) * pageSize, fetch, keyset);
            if (!keyset) {
                total = countByMetric(run == null ? null : run.getId(), sortField);
            }
        } else {
            rows = listByTotalScore(projectId, roundId, desc, keyset, afterScore, afterId,
                    keyset ? fetch : (page - 1) * pageSize, fetch);
            if (!keyset) {
                total = countCandidates(projectId, roundId);
            }
        }

        // 第二段：批量填充指标值（无论一页多少行，固定 1 次 IN 查询）
        fillMetrics(rows, run == null ? null : run.getId(), enabledMetrics);

        boolean hasNext = rows.size() > pageSize;
        List<LeaderboardResponse.LeaderboardRow> pageRows = hasNext ? rows.subList(0, pageSize) : rows;

        LeaderboardResponse response = new LeaderboardResponse();
        response.setList(new ArrayList<>(pageRows));
        response.setMetricColumns(buildColumns(enabledMetrics, run));
        response.setWeightsSnapshot(readWeights(run));
        response.setRedistributed(buildRedistributed(rows, enabledMetrics, response.getWeightsSnapshot(),
                run == null ? null : run.getId()));
        response.setHasNext(hasNext);
        response.setPageSize((long) pageSize);
        if (keyset) {
            // 规范 §4.2：键集模式**不返回 total**（每次翻页 COUNT(*) 正是该规则要避免的）
            if (!pageRows.isEmpty()) {
                LeaderboardResponse.LeaderboardRow last = pageRows.get(pageRows.size() - 1);
                response.setNextAfterScore(byMetric ? last.getMetrics().get(sortField) : last.getTotalScore());
                response.setNextAfterId(last.getCandidateId());
            }
        } else {
            response.setTotal(total);
            response.setPage((long) page);
        }
        return response;
    }

    // ------------------------------------------------------------------ 查询

    /** 该轮最新一次评估运行（优先取有结果的），用于权重快照与指标集合 */
    private EvaluationRun findRun(Long projectId, Long roundId) {
        List<EvaluationRun> runs = runMapper.selectList(Wrappers.lambdaQuery(EvaluationRun.class)
                .eq(EvaluationRun::getProjectId, projectId)
                .eq(EvaluationRun::getRoundId, roundId)
                .orderByDesc(EvaluationRun::getId)
                .last("LIMIT 1"));
        return runs.isEmpty() ? null : runs.get(0);
    }

    private long countCandidates(Long projectId, Long roundId) {
        return candidateMapper.selectCount(Wrappers.lambdaQuery(CandidateMolecule.class)
                .eq(CandidateMolecule::getProjectId, projectId)
                .eq(CandidateMolecule::getRoundId, roundId));
    }

    private List<LeaderboardResponse.LeaderboardRow> listByTotalScore(Long projectId, Long roundId, boolean desc,
                                                                      boolean keyset, BigDecimal afterScore,
                                                                      Long afterId, int offset, int limit) {
        LambdaQueryWrapper<CandidateMolecule> query = Wrappers.lambdaQuery(CandidateMolecule.class)
                .eq(CandidateMolecule::getProjectId, projectId)
                .eq(CandidateMolecule::getRoundId, roundId);
        if (keyset && afterScore != null) {
            // 键集条件：(score, id) 严格越过游标；同分用 id 兜底，保证不重不漏
            query.and(w -> w.lt(desc, CandidateMolecule::getTotalScore, afterScore)
                    .or()
                    .nested(n -> n.eq(CandidateMolecule::getTotalScore, afterScore)
                            .lt(desc, CandidateMolecule::getId, afterId)));
        }
        if (desc) {
            query.orderByDesc(CandidateMolecule::getTotalScore).orderByDesc(CandidateMolecule::getId);
        } else {
            query.orderByAsc(CandidateMolecule::getTotalScore).orderByAsc(CandidateMolecule::getId);
        }
        if (!keyset) {
            query.last("LIMIT " + offset + ", " + limit);
        } else {
            query.last("LIMIT " + limit);
        }
        return candidateMapper.selectList(query).stream().map(this::toRow).collect(Collectors.toList());
    }

    /**
     * 按指标排序：先定位该指标的值与分子 id，再批量取候选。
     *
     * <p>只排「算了该指标」的候选 —— 没有值无从排名。{@code success=1} 且 {@code value_num} 非空才计入。
     */
    private List<LeaderboardResponse.LeaderboardRow> listByMetric(Long projectId, Long roundId, EvaluationRun run,
                                                                  String metricCode, boolean desc, boolean keyset,
                                                                  BigDecimal afterScore, Long afterId,
                                                                  int offset, int limit, boolean keysetMode) {
        if (run == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<EvaluationResult> query = Wrappers.lambdaQuery(EvaluationResult.class)
                .eq(EvaluationResult::getRunId, run.getId())
                .eq(EvaluationResult::getMetricCode, metricCode)
                .eq(EvaluationResult::getSuccess, 1)
                .isNotNull(EvaluationResult::getValueNum);
        if (keysetMode && afterScore != null) {
            query.and(w -> w.lt(desc, EvaluationResult::getValueNum, afterScore)
                    .or()
                    .nested(n -> n.eq(EvaluationResult::getValueNum, afterScore)
                            .lt(desc, EvaluationResult::getMoleculeId, afterId)));
        }
        if (desc) {
            query.orderByDesc(EvaluationResult::getValueNum).orderByDesc(EvaluationResult::getMoleculeId);
        } else {
            query.orderByAsc(EvaluationResult::getValueNum).orderByAsc(EvaluationResult::getMoleculeId);
        }
        query.last("LIMIT " + offset + ", " + limit);

        List<EvaluationResult> ordered = resultMapper.selectList(query);
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = ordered.stream().map(EvaluationResult::getMoleculeId).toList();
        Map<Long, CandidateMolecule> byId = candidateMapper.selectBatchIds(ids).stream()
                .filter(c -> c.getProjectId().equals(projectId) && roundId.equals(c.getRoundId()))
                .collect(Collectors.toMap(CandidateMolecule::getId, c -> c));

        List<LeaderboardResponse.LeaderboardRow> rows = new ArrayList<>(ordered.size());
        for (EvaluationResult r : ordered) {
            CandidateMolecule candidate = byId.get(r.getMoleculeId());
            if (candidate == null) {
                // 候选已删 / 不在本轮 → 跳过（不能凭空造一行）
                continue;
            }
            LeaderboardResponse.LeaderboardRow row = toRow(candidate);
            row.getMetrics().put(metricCode, r.getValueNum());
            rows.add(row);
        }
        return rows;
    }

    private long countByMetric(Long runId, String metricCode) {
        if (runId == null) {
            return 0L;
        }
        return resultMapper.selectCount(Wrappers.lambdaQuery(EvaluationResult.class)
                .eq(EvaluationResult::getRunId, runId)
                .eq(EvaluationResult::getMetricCode, metricCode)
                .eq(EvaluationResult::getSuccess, 1)
                .isNotNull(EvaluationResult::getValueNum));
    }

    /** 一次 IN 查询补齐这一页的指标值（不要逐行查） */
    private void fillMetrics(List<LeaderboardResponse.LeaderboardRow> rows, Long runId, Set<String> enabledMetrics) {
        if (rows.isEmpty() || runId == null || enabledMetrics.isEmpty()) {
            rows.forEach(r -> r.setUnavailableMetrics(new ArrayList<>(enabledMetrics)));
            return;
        }
        List<Long> ids = rows.stream().map(LeaderboardResponse.LeaderboardRow::getCandidateId).toList();
        List<EvaluationResult> results = resultMapper.selectList(Wrappers.lambdaQuery(EvaluationResult.class)
                .eq(EvaluationResult::getRunId, runId)
                .in(EvaluationResult::getMoleculeId, ids)
                .eq(EvaluationResult::getSuccess, 1)
                .isNotNull(EvaluationResult::getValueNum));
        Map<Long, List<EvaluationResult>> byMolecule = results.stream()
                .collect(Collectors.groupingBy(EvaluationResult::getMoleculeId));

        for (LeaderboardResponse.LeaderboardRow row : rows) {
            List<EvaluationResult> mine = byMolecule.getOrDefault(row.getCandidateId(), List.of());
            List<String> unavailable = new ArrayList<>();
            for (EvaluationResult r : mine) {
                if (enabledMetrics.contains(r.getMetricCode())) {
                    row.getMetrics().put(r.getMetricCode(), r.getValueNum());
                }
            }
            for (String code : enabledMetrics) {
                if (!row.getMetrics().containsKey(code)) {
                    unavailable.add(code);
                }
            }
            row.setUnavailableMetrics(unavailable);
        }
    }

    // ------------------------------------------------------------------ 组装

    private LeaderboardResponse.LeaderboardRow toRow(CandidateMolecule c) {
        return LeaderboardResponse.LeaderboardRow.builder()
                .candidateId(c.getId())
                .moleculeName(c.getMoleculeName())
                .modalityCode(c.getModalityCode())
                .totalScore(c.getTotalScore())
                .reviewStatus(c.getReviewStatus())
                .realness(null) // realness 是"每个指标的产物"，行级取值为空；见 metrics 与 redistributed
                .riskLevel(null)
                .metrics(new LinkedHashMap<>())
                .unavailableMetrics(new ArrayList<>())
                .build();
    }

    private List<LeaderboardResponse.MetricColumn> buildColumns(Set<String> enabledMetrics, EvaluationRun run) {
        if (enabledMetrics.isEmpty()) {
            return List.of();
        }
        Map<String, MetricDefinition> defs = metricDefinitionMapper.selectList(
                        Wrappers.lambdaQuery(MetricDefinition.class)
                                .in(MetricDefinition::getMetricCode, enabledMetrics))
                .stream()
                .collect(Collectors.toMap(MetricDefinition::getMetricCode, d -> d, (a, b) -> a));
        Map<String, BigDecimal> weights = readWeights(run);

        List<LeaderboardResponse.MetricColumn> columns = new ArrayList<>();
        for (String code : enabledMetrics) {
            MetricDefinition def = defs.get(code);
            columns.add(LeaderboardResponse.MetricColumn.builder()
                    .metricCode(code)
                    // 指标定义缺失时用指标码兜底，而不是让这一列消失
                    .name(def == null ? code : def.getMetricName())
                    .unit(def == null ? null : def.getUnit())
                    .better(def == null || def.getBetter() == null ? "NONE" : def.getBetter())
                    // 权重以「本次 run 的快照」为准；定义表里的权重是"默认值"，可能已被改过
                    .weight(weights.getOrDefault(code, def == null ? null : def.getWeight()))
                    .build());
        }
        return columns;
    }

    /** 权重快照（红线 R1）：优先 run 的快照，缺失时才退回指标定义 */
    private Map<String, BigDecimal> readWeights(EvaluationRun run) {
        Map<String, Object> raw = run == null ? null : jsonCodec.readMap(run.getMetricWeights());
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof Number n) {
                weights.put(k, new BigDecimal(n.toString()));
            }
        });
        return weights;
    }

    /**
     * 推导「启用了但没算出结果」的指标（红线 R1：必须说清哪些指标没参与、为什么、权重去了哪）。
     *
     * <p>判定依据是**本页数据**里该指标一次都没出现（{@code fillMetrics} 已把每行缺失的指标收集起来）。
     * 之所以按页推导而非全量统计：全量要再扫一遍结果表，而这里的目的只是让用户知道"这一列为什么空"。
     * 只要该指标在本页任何一行出现过，就认为它参与了计算。
     */
    private List<LeaderboardResponse.RedistributedMetric> buildRedistributed(
            List<LeaderboardResponse.LeaderboardRow> rows, Set<String> enabledMetrics,
            Map<String, BigDecimal> weights, Long runId) {
        if (enabledMetrics.isEmpty() || runId == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        rows.forEach(r -> seen.addAll(r.getMetrics().keySet()));
        Set<String> missing = enabledMetrics.stream()
                .filter(code -> !seen.contains(code))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (missing.isEmpty()) {
            return List.of();
        }

        Map<String, String> reasons = failureReasons(runId, missing);
        List<LeaderboardResponse.RedistributedMetric> out = new ArrayList<>();
        for (String code : missing) {
            out.add(LeaderboardResponse.RedistributedMetric.builder()
                    .metricCode(code)
                    .reason(reasons.getOrDefault(code, "本轮无该指标的有效计算结果"))
                    .redistributedWeight(weights.get(code))
                    .build());
        }
        return out;
    }

    /**
     * 一次查出这些缺失指标的失败原因（指标码 → 可读原因）。
     *
     * <p><b>必须批量</b>：早先是"每个缺失指标查一次"，本轮启用 8 个指标全失败就是 8 次额外查询 ——
     * 而这段代码恰好只在"大量指标失败"时才会走到，正是最不该放大查询次数的时候。
     */
    private Map<String, String> failureReasons(Long runId, Set<String> missingCodes) {
        List<EvaluationResult> failed = resultMapper.selectList(Wrappers.lambdaQuery(EvaluationResult.class)
                .eq(EvaluationResult::getRunId, runId)
                .in(EvaluationResult::getMetricCode, missingCodes)
                .eq(EvaluationResult::getSuccess, 0)
                .orderByDesc(EvaluationResult::getId));
        Map<String, String> reasons = new LinkedHashMap<>();
        for (EvaluationResult r : failed) {
            // 已按 id 降序，首次出现即该指标最新的一条失败样本
            if (reasons.containsKey(r.getMetricCode())) {
                continue;
            }
            String msg = r.getErrorMsg();
            reasons.put(r.getMetricCode(), msg == null || msg.isBlank()
                    ? "本轮无该指标的有效计算结果"
                    : "全部候选计算失败：" + msg);
        }
        return reasons;
    }

    private static Set<String> splitCodes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
