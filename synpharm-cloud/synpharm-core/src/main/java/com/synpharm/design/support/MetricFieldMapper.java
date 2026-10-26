package com.synpharm.design.support;

import java.util.Set;

/**
 * 约束 {@code params.field} → 计算层指标码。
 *
 * <p>抽成公共类是因为<b>两处</b>都要用它：S6 粗筛（生成阶段）与最小评估（编辑阶段的
 * 前置）。复制一份的后果是两边映射表慢慢漂移——用户在约束里写 {@code MolWt}，
 * 生成阶段判得出、评估阶段判不出，同一份基线得出两个结论。
 *
 * <p>纪律：只映射到<b>计算层真实提供</b>的指标；映射不上返回 {@code null}，
 * 由调用方记 SKIPPED。<b>不猜、不用近似值顶替。</b>
 *
 * @author SynPharm Team
 */
public final class MetricFieldMapper {

    /** 计算层目前能算的指标码（与 {@code /v1/evaluate/properties} 的 capabilities 对齐） */
    public static final Set<String> KNOWN_METRIC_CODES = Set.of(
            "MOL_WT", "MOL_LOGP", "TPSA", "NUM_H_DONORS", "NUM_H_ACCEPTORS",
            "NUM_ROTATABLE", "NUM_RINGS", "QED", "FRACTION_CSP3",
            "LIPINSKI_PASS", "COMPLEXITY_PENALTY", "PAINS_HITS", "BRENK_HITS");

    private MetricFieldMapper() {
    }

    /**
     * @param field 约束里的字段名，大小写与下划线写法都容忍
     * @return 指标码；映射不上返回 {@code null}
     */
    public static String mapField(String field) {
        if (field == null) {
            return null;
        }
        String key = field.trim().toLowerCase();
        if (key.isEmpty()) {
            return null;
        }
        return switch (key) {
            case "molwt", "molecular_weight", "mw", "mol_wt" -> "MOL_WT";
            case "logp", "mollogp", "xlogp", "mol_logp" -> "MOL_LOGP";
            case "tpsa" -> "TPSA";
            case "hbd", "hdonors", "num_h_donors" -> "NUM_H_DONORS";
            case "hba", "hacceptors", "num_h_acceptors" -> "NUM_H_ACCEPTORS";
            case "rotatablebonds", "num_rotatable" -> "NUM_ROTATABLE";
            case "rings", "num_rings", "ringcount" -> "NUM_RINGS";
            case "qed" -> "QED";
            case "fractioncsp3", "fraction_csp3", "fsp3" -> "FRACTION_CSP3";
            case "lipinski", "lipinski_pass" -> "LIPINSKI_PASS";
            case "pains", "pains_hits" -> "PAINS_HITS";
            case "brenk", "brenk_hits" -> "BRENK_HITS";
            default -> null;
        };
    }

    /**
     * 结构化约束的 {@code dimension} 可能就是指标码本身（如 {@code QED}）。
     * 这不是映射，是「原样认领」；认不出就返回 null。
     */
    public static String asMetricCode(String dimension) {
        if (dimension == null) {
            return null;
        }
        String code = dimension.trim().toUpperCase();
        return KNOWN_METRIC_CODES.contains(code) ? code : null;
    }
}
