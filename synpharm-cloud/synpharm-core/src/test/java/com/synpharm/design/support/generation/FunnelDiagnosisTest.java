package com.synpharm.design.support.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S9 诊断判定。
 *
 * <p>核心纪律：verdict 由代码算，不由文案决定。这里锁住四种分支的边界，
 * 一旦阈值判断被人改坏，前端会开始给用户「可以进入编辑」的假绿灯。
 *
 * @author SynPharm Team
 */
class FunnelDiagnosisTest {

    private static FunnelDiagnosis.Funnel funnel(int generated, int deduped, int preFiltered,
                                                 int quotaCut, int finalCount) {
        return new FunnelDiagnosis.Funnel(generated, deduped, preFiltered, quotaCut,
                finalCount, Map.of());
    }

    @Test
    @DisplayName("数量达标且簇数达标 → PASS，开放编辑")
    void pass() {
        FunnelDiagnosis.Diagnosis d = FunnelDiagnosis.diagnose(
                funnel(30, 28, 20, 5, 15), 10, 4, List.of());

        assertThat(d.verdict()).isEqualTo(FunnelDiagnosis.PASS);
        assertThat(d.readyForEdit()).isTrue();
    }

    @Test
    @DisplayName("数量够但有效簇 < 3 → PASS_WITH_WARNING（覆盖不足仍放行）")
    void passWithWarningWhenFewClusters() {
        FunnelDiagnosis.Diagnosis d = FunnelDiagnosis.diagnose(
                funnel(30, 28, 20, 5, 15), 10, 2, List.of());

        assertThat(d.verdict()).isEqualTo(FunnelDiagnosis.PASS_WITH_WARNING);
        assertThat(d.readyForEdit()).isTrue();
        assertThat(d.findings()).anySatisfy(f ->
                assertThat(f.message()).contains("簇"));
    }

    @Test
    @DisplayName("数量不足但 > 0 → PASS_WITH_WARNING，允许编辑但强制告警")
    void passWithWarningWhenShortOfMinCandidates() {
        FunnelDiagnosis.Diagnosis d = FunnelDiagnosis.diagnose(
                funnel(30, 28, 8, 0, 4), 10, 5, List.of());

        assertThat(d.verdict()).isEqualTo(FunnelDiagnosis.PASS_WITH_WARNING);
        assertThat(d.readyForEdit()).isTrue();
    }

    @Test
    @DisplayName("候选数为 0 → FAIL 且禁止进入编辑（空集绝不允许往下游流）")
    void failOnEmpty() {
        FunnelDiagnosis.Diagnosis d = FunnelDiagnosis.diagnose(
                funnel(30, 28, 0, 0, 0), 10, 0, List.of());

        assertThat(d.verdict()).isEqualTo(FunnelDiagnosis.FAIL);
        assertThat(d.readyForEdit()).isFalse();
    }

    @Test
    @DisplayName("阶段告警会被并入 findings，而不是丢掉")
    void keepsStageWarnings() {
        FunnelDiagnosis.Diagnosis d = FunnelDiagnosis.diagnose(
                funnel(30, 28, 20, 5, 15), 10, 4, List.of("某阶段降级了"));

        assertThat(d.findings()).anySatisfy(f ->
                assertThat(f.message()).contains("某阶段降级了"));
    }

    @Test
    @DisplayName("转化率按段计算，分母为 0 时返回 null 而不是 0（区分「没数据」与「全灭」）")
    void ratesAreNullWhenNoInput() {
        Map<String, Object> empty = new FunnelDiagnosis.Funnel(0, 0, 0, 0, 0, Map.of()).toMap();
        assertThat(empty.get("dedupRate")).isNull();
        assertThat(empty.get("overallYield")).isNull();

        Map<String, Object> half = funnel(10, 5, 5, 0, 5).toMap();
        assertThat(half.get("overallYield")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("topReasons 按次数降序并截断")
    void topReasonsSortedAndLimited() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("A", 1L);
        counts.put("B", 9L);
        counts.put("C", 5L);

        Map<String, Long> top = FunnelDiagnosis.topReasons(counts, 2);

        assertThat(top).hasSize(2);
        assertThat(top.keySet()).containsExactly("B", "C");
    }
}
