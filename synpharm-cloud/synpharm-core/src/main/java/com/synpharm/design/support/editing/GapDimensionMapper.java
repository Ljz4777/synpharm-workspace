package com.synpharm.design.support.editing;

import java.util.Map;

/**
 * 指标码 → 缺口维度（{@code edit_action_mapping.gap_dimension}）。
 *
 * <p>为什么需要这层映射：{@code metric_definition.dimension} 对小分子指标几乎全是
 * NULL（只有抗体类才填了 STABILITY / DEVELOPABILITY 等），而缺口诊断必须给出
 * 一个能与映射表对齐的维度，否则 E2 永远匹配不到动作。
 *
 * <p>映射不到 → {@link #UNMAPPED}：这些维度的缺口会被归入 {@code unfixable_json}
 * —— 当前手段确实修不了，如实说清比硬塞一个动作好。
 *
 * @author SynPharm Team
 */
public final class GapDimensionMapper {

    /** 映射不到任何可编辑维度 */
    public static final String UNMAPPED = "UNMAPPED";

    /**
     * 指标码 → 缺口维度。
     *
     * <p>取值必须与 {@code sql/42_edit.sql} 里 {@code edit_action_mapping} 种子一致；
     * 新增维度前先确认映射表里有对应行，否则诊断出的缺口连一个候选动作都没有。
     */
    private static final Map<String, String> BY_METRIC = Map.ofEntries(
            // 结合力与选择性
            Map.entry("DTI_CONFIDENCE", "AFFINITY"),
            Map.entry("VINA_AFFINITY", "AFFINITY"),
            Map.entry("SELECTIVITY_INDEX", "SELECTIVITY"),
            // 可开发性
            Map.entry("AGGREGATION_RISK", "AGGREGATION"),
            Map.entry("VISCOSITY_RISK", "DEVELOPABILITY"),
            Map.entry("QED", "DEVELOPABILITY"),
            Map.entry("LIPINSKI_PASS", "DEVELOPABILITY"),
            Map.entry("MOL_WT", "DEVELOPABILITY"),
            Map.entry("MOL_LOGP", "DEVELOPABILITY"),
            Map.entry("TPSA", "DEVELOPABILITY"),
            Map.entry("NUM_H_DONORS", "DEVELOPABILITY"),
            Map.entry("NUM_H_ACCEPTORS", "DEVELOPABILITY"),
            Map.entry("NUM_ROTATABLE", "DEVELOPABILITY"),
            Map.entry("FRACTION_CSP3", "DEVELOPABILITY"),
            // 表达与稳定性
            Map.entry("EXPRESSION_YIELD_PRED", "EXPRESSION"),
            Map.entry("TM_STABILITY", "STABILITY"),
            Map.entry("PEPTIDE_HALF_LIFE", "STABILITY"),
            Map.entry("SERUM_STABILITY_PCT", "STABILITY"),
            // 免疫原性
            Map.entry("IMMUNOGENICITY_SCORE", "IMMUNOGENICITY"),
            // 合成与安全
            Map.entry("SA_SCORE", "SYNTHESIZABILITY"),
            Map.entry("COMPLEXITY_PENALTY", "SYNTHESIZABILITY"),
            Map.entry("PAINS_HITS", "SAFETY"),
            Map.entry("BRENK_HITS", "SAFETY"),
            Map.entry("DDI_PROBABILITY", "SAFETY"),
            // 新颖性
            Map.entry("MAX_SIMILARITY", "NOVELTY"));

    private GapDimensionMapper() {
    }

    public static String of(String metricCode) {
        if (metricCode == null) {
            return UNMAPPED;
        }
        return BY_METRIC.getOrDefault(metricCode, UNMAPPED);
    }

    /**
     * 该维度当前是否有可编辑手段。
     *
     * <p>与映射表种子同步：SAFETY / NOVELTY / DEVELOPABILITY 目前没有可信的
     * 定向编辑动作，诊断时归入 {@code unfixable}，不假装能改。
     */
    public static boolean isEditableDimension(String dimension) {
        return switch (dimension == null ? "" : dimension) {
            case "AFFINITY", "AGGREGATION", "EXPRESSION", "IMMUNOGENICITY",
                 "SELECTIVITY", "STABILITY", "SYNTHESIZABILITY" -> true;
            default -> false;
        };
    }
}
