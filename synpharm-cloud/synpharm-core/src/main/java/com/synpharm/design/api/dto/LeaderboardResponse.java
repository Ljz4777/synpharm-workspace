package com.synpharm.design.api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排行榜（契约 {@code GET /api/design/projects/{id}/leaderboard}）。
 *
 * <p><b>两种分页形状共用一个 DTO</b>，因为契约与规范 §4.2 各定义了一半：
 * <ul>
 *   <li><b>偏移模式</b>（默认）：返回 {@code total / page / pageSize / hasNext}；</li>
 *   <li><b>键集模式</b>（带 {@code afterScore}/{@code afterId}）：返回
 *       {@code nextAfterScore / nextAfterId / hasNext}，**{@code total} 为 null**。</li>
 * </ul>
 * 规范明确要求键集模式的响应**无 {@code total}** —— 在 10 万行的 {@code evaluation_result} 上
 * 每次翻页都 `COUNT(*)` 正是这条规则要避免的事。键集模式因此不回填 {@code total}，
 * 而不是"填了但不准"。
 *
 * <p><b>红线 R1 的两个字段不能省</b>：{@code weightsSnapshot} 是本次排序实际使用的权重快照
 * （取自 {@code evaluation_run.metric_weights}），{@code redistributed} 说明哪些指标没参与计算、
 * 为什么、权重去了哪里。少了它们，同一批数据在不同时间打开会得到不同的名次却无法解释。
 *
 * @author SynPharm Team
 */
@Data
public class LeaderboardResponse {

    /** 表头：参与本次排序的指标列（含权重），顺序与 {@code list[].metrics} 的插入顺序一致 */
    private List<MetricColumn> metricColumns = new ArrayList<>();

    private List<LeaderboardRow> list = new ArrayList<>();

    /** 本次排序所用权重快照（红线 R1：与 evaluation_run 一致才能复现名次） */
    private Map<String, BigDecimal> weightsSnapshot = new LinkedHashMap<>();

    /** 未参与计算的指标 + 原因 + 权重去向（红线 R1） */
    private List<RedistributedMetric> redistributed = new ArrayList<>();

    // ---- 偏移模式 ----

    /** 总条数；**键集模式下为 null**（见类注释） */
    private Long total;

    private Long page;

    private Long pageSize;

    /** 是否还有下一页（两种模式都返回） */
    private Boolean hasNext;

    // ---- 键集模式 ----

    /** 下一页的游标：本页最后一条的排序值 */
    private BigDecimal nextAfterScore;

    /** 下一页的游标：本页最后一条的主键（同分时兜底，保证翻页不重不漏） */
    private Long nextAfterId;

    /** 表头列（契约 {@code metricColumns[]}） */
    @Data
    @Builder
    public static class MetricColumn {
        private String metricCode;
        private String name;
        private String unit;
        /** HIGHER / LOWER / NONE */
        private String better;
        private BigDecimal weight;
    }

    /** 一行（契约 {@code list[]}） */
    @Data
    @Builder
    public static class LeaderboardRow {
        private Long candidateId;
        private String moleculeName;
        private String modalityCode;
        private BigDecimal totalScore;
        /** NEW / QUALIFIED / REJECTED / TOP / SELECTED */
        private String reviewStatus;
        /** REAL / SEMI_REAL / HEURISTIC / SIMULATED */
        private String realness;
        /** LOW / MEDIUM / HIGH / CRITICAL */
        private String riskLevel;
        /** 指标码 → 原始值；未算出（该指标失败或无结果）的指标不出现在此表中 */
        private Map<String, BigDecimal> metrics = new LinkedHashMap<>();
        /** 本次没有算出的指标码（前端据此显示"—"而不是 0） */
        private List<String> unavailableMetrics = new ArrayList<>();
    }

    /** 未参与计算的指标（契约 {@code redistributed[]}） */
    @Data
    @Builder
    public static class RedistributedMetric {
        private String metricCode;
        /** 可读原因，例如「vina 未安装」「全部候选计算失败: <error>」 */
        private String reason;
        /** 该指标原本占的权重（被重新分配给其它指标） */
        private BigDecimal redistributedWeight;
    }
}
