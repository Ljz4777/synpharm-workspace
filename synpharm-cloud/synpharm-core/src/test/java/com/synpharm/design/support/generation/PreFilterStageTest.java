package com.synpharm.design.support.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S6 粗筛的纯逻辑部分（不触达计算层）。
 *
 * <p>这里锁的是两条纪律：
 * <ul>
 *   <li>字段映射只认计算层真实提供的指标，映射不上就返回 null 走 SKIPPED；</li>
 *   <li>{@code paramsJson} 在基线快照里是二次编码的字符串，解析必须能吃下两种形态。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
class PreFilterStageTest {

    // ------------------------------------------------------------------ mapField

    @Test
    @DisplayName("常见 field 写法都能映射到计算层指标码")
    void mapsKnownFields() {
        assertThat(PreFilterStage.mapField("MolWt")).isEqualTo("MOL_WT");
        assertThat(PreFilterStage.mapField("molecular_weight")).isEqualTo("MOL_WT");
        assertThat(PreFilterStage.mapField("MW")).isEqualTo("MOL_WT");
        assertThat(PreFilterStage.mapField("LogP")).isEqualTo("MOL_LOGP");
        assertThat(PreFilterStage.mapField("TPSA")).isEqualTo("TPSA");
        assertThat(PreFilterStage.mapField("HBD")).isEqualTo("NUM_H_DONORS");
        assertThat(PreFilterStage.mapField("Num_Rotatable")).isEqualTo("NUM_ROTATABLE");
        assertThat(PreFilterStage.mapField("  molwt  ")).isEqualTo("MOL_WT");
    }

    @Test
    @DisplayName("映射不上就返回 null（调用方记 SKIPPED，绝不当作通过）")
    void returnsNullForUnknownField() {
        assertThat(PreFilterStage.mapField(null)).isNull();
        assertThat(PreFilterStage.mapField("Immunogenicity")).isNull();
        assertThat(PreFilterStage.mapField("")).isNull();
    }

    // ------------------------------------------------------------------ 指标清单契约

    @Test
    @DisplayName("FILTER_METRICS 不含计算层拿不到的指标（否则会静默算不出）")
    void filterMetricsAreAllAvailable() {
        assertThat(PreFilterStage.FILTER_METRICS).doesNotContain("SA_SCORE");
        assertThat(PreFilterStage.FILTER_METRICS).contains("MOL_WT", "QED", "PAINS_HITS");
    }

    // ------------------------------------------------------------------ FilterReason

    @Test
    @DisplayName("只有 HARD 级别的理由才会把候选判为不通过")
    void onlyHardReasonsReject() {
        DedupStage.Entry dedup = new DedupStage.Entry(
                new GenerationDtos.GeneratedCandidate("r1", "CCO", null, null, null,
                        null, "SEED_LIBRARY", "r1", null),
                "CCO", null, null, "CL-1", null, null);

        PreFilterStage.Entry warnOnly = new PreFilterStage.Entry(dedup, BigDecimal.ONE,
                List.of(new PreFilterStage.FilterReason("BOUNDARY_WARNING", "WARN", "临界")),
                List.of());
        assertThat(warnOnly.isPassed()).isTrue();

        PreFilterStage.Entry hardFail = new PreFilterStage.Entry(dedup, BigDecimal.ONE,
                List.of(new PreFilterStage.FilterReason("PAINS_HIT", "HARD", "PAINS")),
                List.of());
        assertThat(hardFail.isPassed()).isFalse();
    }
}
