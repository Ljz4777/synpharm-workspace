package com.synpharm.design.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 边界判定测试。
 *
 * <p>重点在两组容易写错的地方：
 * <ol>
 *   <li><b>等号归属</b>：{@code LTE 100} 的 100 达标，{@code LT 100} 的 100 不达标。
 *       容差是叠加在算子之上的，不能替代它。</li>
 *   <li><b>容差只向放宽方向外扩</b>：下限型放宽到 {@code threshold - tol}，
 *       上限型放宽到 {@code threshold + tol}，区间型两端同时外扩。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
class BoundaryJudgeTest {

    private final BoundaryJudge judge = new BoundaryJudge();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // ------------------------------------------------------------------ STRICT

    @Test
    @DisplayName("STRICT + LTE：等号归达标（100 ≤ 100）")
    void strictLteInclusiveBoundary() {
        BoundaryJudge.Verdict v = judge.judge(d("100"), "LTE", d("100"), null, null, BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.PASS);
        assertThat(v.isHardPassed()).isTrue();
        assertThat(v.isBoundaryWarning()).isFalse();
        assertThat(v.getDeviation()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("STRICT + LT：等号归不达标（100 不 < 100）")
    void strictLtExclusiveBoundary() {
        BoundaryJudge.Verdict v = judge.judge(d("100"), "LT", d("100"), null, null, BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.isHardPassed()).isFalse();
    }

    @Test
    @DisplayName("STRICT + LTE：越界即淘汰（110 > 100），偏差 10")
    void strictLteViolation() {
        BoundaryJudge.Verdict v = judge.judge(d("110"), "LTE", d("100"), null, d("10"), BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.isHardPassed()).isFalse();
        assertThat(v.getDeviation()).isEqualByComparingTo("10");
        // STRICT 下即使配了容差也不放宽
        assertThat(v.getReason()).contains("超出 10");
    }

    // ------------------------------------------------------------------ WITHIN_TOLERANCE

    @Test
    @DisplayName("WITHIN_TOLERANCE + 容差 10：110 落在 100+10 内，达标但带警告")
    void withinTolerancePassWithWarning() {
        BoundaryJudge.Verdict v = judge.judge(d("110"), "LTE", d("100"), null, d("10"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.PASS_WITH_WARNING);
        assertThat(v.isHardPassed()).isTrue();
        assertThat(v.isBoundaryWarning()).isTrue();
        assertThat(v.getDeviation()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("WITHIN_TOLERANCE + 容差 10：111 超出 110，仍淘汰")
    void withinToleranceExceeded() {
        BoundaryJudge.Verdict v = judge.judge(d("111"), "LTE", d("100"), null, d("10"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.isHardPassed()).isFalse();
        assertThat(v.getReason()).contains("超出容差 10");
    }

    @Test
    @DisplayName("WITHIN_TOLERANCE 但容差为 null：退化为 STRICT，不静默放行")
    void withinToleranceWithoutToleranceFallsBackToStrict() {
        BoundaryJudge.Verdict v = judge.judge(d("110"), "LTE", d("100"), null, null,
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.getReason()).contains("未配置容差");
    }

    @Test
    @DisplayName("WITHIN_TOLERANCE + GTE：下限型向 -tol 放宽（90 ≥ 100-10 达标）")
    void withinToleranceLowerBound() {
        BoundaryJudge.Verdict v = judge.judge(d("90"), "GTE", d("100"), null, d("10"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.PASS_WITH_WARNING);
        assertThat(v.getDeviation()).isEqualByComparingTo("10");

        BoundaryJudge.Verdict below = judge.judge(d("89"), "GTE", d("100"), null, d("10"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(below.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
    }

    @Test
    @DisplayName("WITHIN_TOLERANCE + BETWEEN：两端各自外扩（[10,20] ±2）")
    void withinToleranceBetween() {
        BoundaryJudge.Verdict high = judge.judge(d("22"), "BETWEEN", d("10"), d("20"), d("2"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(high.getOutcome()).isEqualTo(BoundaryJudge.PASS_WITH_WARNING);

        BoundaryJudge.Verdict low = judge.judge(d("8"), "BETWEEN", d("10"), d("20"), d("2"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(low.getOutcome()).isEqualTo(BoundaryJudge.PASS_WITH_WARNING);

        BoundaryJudge.Verdict out = judge.judge(d("23"), "BETWEEN", d("10"), d("20"), d("2"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(out.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(out.getDeviation()).isEqualByComparingTo("3");
    }

    // ------------------------------------------------------------------ ESCALATE

    @Test
    @DisplayName("ESCALATE_TO_SOFT：越界不判硬性达标，转扣分")
    void escalateToSoft() {
        BoundaryJudge.Verdict v = judge.judge(d("110"), "LTE", d("100"), null, d("10"),
                BoundaryJudge.ESCALATE_TO_SOFT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.ESCALATE);
        assertThat(v.isHardPassed()).isFalse();
        assertThat(v.isBoundaryWarning()).isFalse();
        assertThat(v.getReason()).contains("转软约束扣分");
    }

    @Test
    @DisplayName("ESCALATE_TO_SOFT：未越界时仍正常达标")
    void escalateToSoftStillPassesWhenSatisfied() {
        BoundaryJudge.Verdict v = judge.judge(d("90"), "LTE", d("100"), null, null,
                BoundaryJudge.ESCALATE_TO_SOFT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.PASS);
        assertThat(v.isHardPassed()).isTrue();
    }

    // ------------------------------------------------------------------ 异常输入

    @Test
    @DisplayName("缺实测值：FAIL，且不参与容差（不能把「没有数据」当成达标）")
    void nullActualFails() {
        BoundaryJudge.Verdict v = judge.judge(null, "LTE", d("100"), null, d("10"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.getReason()).contains("缺少实测值");
    }

    @Test
    @DisplayName("缺阈值：FAIL，提示约束配置不完整")
    void nullThresholdFails() {
        BoundaryJudge.Verdict v = judge.judge(d("1"), "LTE", null, null, null, BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.getReason()).contains("未配置阈值");
    }

    @Test
    @DisplayName("未知算子：FAIL，不猜默认方向")
    void unknownOperatorFails() {
        BoundaryJudge.Verdict v = judge.judge(d("1"), "APPROX", d("100"), null, null, BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
        assertThat(v.getReason()).contains("无法识别的运算符");
    }

    @Test
    @DisplayName("未知策略：归一为 STRICT，不静默放宽")
    void unknownPolicyFallsBackToStrict() {
        BoundaryJudge.Verdict v = judge.judge(d("110"), "LTE", d("100"), null, d("50"), "LENIENT");
        assertThat(v.getPolicy()).isEqualTo(BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
    }

    @Test
    @DisplayName("策略为 null：默认 STRICT")
    void nullPolicyIsStrict() {
        BoundaryJudge.Verdict v = judge.judge(d("110"), "LTE", d("100"), null, null, null);
        assertThat(v.getPolicy()).isEqualTo(BoundaryJudge.STRICT);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
    }

    @Test
    @DisplayName("EQ + 容差：按绝对偏差放宽")
    void eqWithTolerance() {
        assertThat(judge.judge(d("100.5"), "EQ", d("100"), null, d("1"), BoundaryJudge.WITHIN_TOLERANCE)
                .getOutcome()).isEqualTo(BoundaryJudge.PASS_WITH_WARNING);
        assertThat(judge.judge(d("101.5"), "EQ", d("100"), null, d("1"), BoundaryJudge.WITHIN_TOLERANCE)
                .getOutcome()).isEqualTo(BoundaryJudge.FAIL);
    }

    @Test
    @DisplayName("容差为负：按无容差处理（不产生「负容差更严格」这种反直觉语义）")
    void negativeToleranceTreatedAsZero() {
        BoundaryJudge.Verdict v = judge.judge(d("101"), "LTE", d("100"), null, d("-10"),
                BoundaryJudge.WITHIN_TOLERANCE);
        assertThat(v.getOutcome()).isEqualTo(BoundaryJudge.FAIL);
    }
}
