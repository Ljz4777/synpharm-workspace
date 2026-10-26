package com.synpharm.design.support;

import java.math.BigDecimal;
import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * 约束/指标的边界判定（06 文档 §3.5）。
 *
 * <p>这里只回答一个问题：<b>实测值相对阈值算不算达标，若不算又该走哪条路</b>。
 * 三种策略的差别不是宽松程度，而是「谁来承担风险」：
 *
 * <ul>
 *   <li>{@link #STRICT} —— 越界即淘汰。硬约束的默认态度。</li>
 *   <li>{@link #WITHIN_TOLERANCE} —— 落在容差带里算达标，但打上 {@code boundaryWarning}，
 *       进证据包时会被单独列出，供决策阶段人工确认。容差是<b>单侧外扩</b>，
 *       不会把阈值本身挪走。</li>
 *   <li>{@link #ESCALATE_TO_SOFT} —— 不判硬性达标也不淘汰，转成软约束扣分。
 *       适合「超了不好但不到淘汰程度」的指标。</li>
 * </ul>
 *
 * <p><b>边界值语义（最容易写错的地方）</b>：
 * {@code LTE 100} 的 {@code 100} 是<b>达标</b>的，{@code LT 100} 的 {@code 100} 是<b>不达标</b>的。
 * 等号归属由 operator 决定，容差再叠加在上面 —— 两者互不替代。
 *
 * @author SynPharm Team
 */
@Component
public class BoundaryJudge {

    /** 越界即淘汰 */
    public static final String STRICT = "STRICT";

    /** 容差内视为达标，但留警告 */
    public static final String WITHIN_TOLERANCE = "WITHIN_TOLERANCE";

    /** 不判硬性达标，转软约束扣分 */
    public static final String ESCALATE_TO_SOFT = "ESCALATE_TO_SOFT";

    /** 判定结果 */
    public static final String PASS = "PASS";
    public static final String PASS_WITH_WARNING = "PASS_WITH_WARNING";
    public static final String FAIL = "FAIL";
    public static final String ESCALATE = "ESCALATE";

    /**
     * 判定一次。
     *
     * @param actual       实测值；{@code null} 视为「没有数据」，按 {@link #FAIL} 处理且不参与容差
     * @param operator     GTE / GT / LTE / LT / EQ / BETWEEN（大小写不敏感，未知按 EQ 之外的保守处理）
     * @param thresholdNum 阈值下限（BETWEEN 时是区间左端）
     * @param thresholdMax 阈值上限，仅 BETWEEN 使用
     * @param tolerance    容差；{@code null} 或 ≤0 时 WITHIN_TOLERANCE 退化为 STRICT
     * @param policy       STRICT / WITHIN_TOLERANCE / ESCALATE_TO_SOFT；{@code null} 按 STRICT
     */
    public Verdict judge(BigDecimal actual, String operator,
                         BigDecimal thresholdNum, BigDecimal thresholdMax,
                         BigDecimal tolerance, String policy) {

        String effPolicy = normalizePolicy(policy);
        String op = operator == null ? "" : operator.trim().toUpperCase();

        if (actual == null) {
            return new Verdict(effPolicy, FAIL, false, false, null,
                    "缺少实测值，无法判定");
        }
        if (thresholdNum == null && thresholdMax == null) {
            return new Verdict(effPolicy, FAIL, false, false, null,
                    "约束未配置阈值，无法判定");
        }

        BigDecimal tol = (tolerance == null || tolerance.signum() <= 0) ? BigDecimal.ZERO : tolerance;
        boolean hasTol = tol.signum() > 0;

        // 先按 operator 判「严格达标」，再看容差能放宽到哪
        Boolean strictOk = strictSatisfied(actual, op, thresholdNum, thresholdMax);
        if (strictOk == null) {
            return new Verdict(effPolicy, FAIL, false, false, null,
                    "无法识别的运算符「" + operator + "」");
        }
        if (strictOk) {
            return new Verdict(effPolicy, PASS, true, false, BigDecimal.ZERO,
                    "达标（" + describe(actual, op, thresholdNum, thresholdMax) + "）");
        }

        BigDecimal deviation = deviation(actual, op, thresholdNum, thresholdMax);
        String strictReason = "越界（" + describe(actual, op, thresholdNum, thresholdMax)
                + "，超出 " + deviation.toPlainString() + "）";

        if (ESCALATE_TO_SOFT.equals(effPolicy)) {
            return new Verdict(effPolicy, ESCALATE, false, false, deviation,
                    strictReason + "，按策略转软约束扣分");
        }

        if (WITHIN_TOLERANCE.equals(effPolicy) && hasTol && withinTolerance(actual, op, tolerance, thresholdNum, thresholdMax)) {
            return new Verdict(effPolicy, PASS_WITH_WARNING, true, true, deviation,
                    strictReason + "，但在容差 " + tol.toPlainString() + " 内，需人工确认");
        }

        String failReason = strictReason;
        if (WITHIN_TOLERANCE.equals(effPolicy)) {
            failReason += hasTol
                    ? "，且超出容差 " + tol.toPlainString()
                    : "，且未配置容差（按严格处理）";
        }
        return new Verdict(effPolicy, FAIL, false, false, deviation, failReason);
    }

    // ------------------------------------------------------------------ 内部

    private String normalizePolicy(String policy) {
        if (policy == null) {
            return STRICT;
        }
        String p = policy.trim().toUpperCase();
        if (WITHIN_TOLERANCE.equals(p) || ESCALATE_TO_SOFT.equals(p) || STRICT.equals(p)) {
            return p;
        }
        return STRICT;
    }

    /**
     * 严格判定（不含容差）。
     *
     * @return {@code null} 表示运算符不可识别
     */
    private Boolean strictSatisfied(BigDecimal actual, String op,
                                    BigDecimal thresholdNum, BigDecimal thresholdMax) {
        switch (op) {
            case "GTE":
                return thresholdNum != null && actual.compareTo(thresholdNum) >= 0;
            case "GT":
                return thresholdNum != null && actual.compareTo(thresholdNum) > 0;
            case "LTE":
                return thresholdNum != null && actual.compareTo(thresholdNum) <= 0;
            case "LT":
                return thresholdNum != null && actual.compareTo(thresholdNum) < 0;
            case "EQ":
                return thresholdNum != null && actual.compareTo(thresholdNum) == 0;
            case "NEQ":
                return thresholdNum != null && actual.compareTo(thresholdNum) != 0;
            case "BETWEEN":
                if (thresholdNum == null || thresholdMax == null) {
                    return false;
                }
                return actual.compareTo(thresholdNum) >= 0 && actual.compareTo(thresholdMax) <= 0;
            default:
                return null;
        }
    }

    /** 容差带是否覆盖实际值。容差一律向「放宽」方向外扩。 */
    private boolean withinTolerance(BigDecimal actual, String op, BigDecimal tolerance,
                                    BigDecimal thresholdNum, BigDecimal thresholdMax) {
        switch (op) {
            case "GTE":
            case "GT":
                // 下限型：放宽到 threshold - tol
                return thresholdNum != null
                        && actual.compareTo(thresholdNum.subtract(tolerance)) >= 0;
            case "LTE":
            case "LT":
                // 上限型：放宽到 threshold + tol
                return thresholdNum != null
                        && actual.compareTo(thresholdNum.add(tolerance)) <= 0;
            case "EQ":
                return thresholdNum != null
                        && actual.subtract(thresholdNum).abs().compareTo(tolerance) <= 0;
            case "BETWEEN":
                if (thresholdNum == null || thresholdMax == null) {
                    return false;
                }
                return actual.compareTo(thresholdNum.subtract(tolerance)) >= 0
                        && actual.compareTo(thresholdMax.add(tolerance)) <= 0;
            case "NEQ":
                // 不等式的「容差」= 落在阈值附近也算不满足，反向理解无意义，直接不放宽
                return false;
            default:
                return false;
        }
    }

    /** 越界量（恒为正数）；仅用于展示与排序，不参与判定。 */
    private BigDecimal deviation(BigDecimal actual, String op,
                                 BigDecimal thresholdNum, BigDecimal thresholdMax) {
        switch (op) {
            case "GTE":
            case "GT":
                return thresholdNum == null ? BigDecimal.ZERO : thresholdNum.subtract(actual).max(BigDecimal.ZERO);
            case "LTE":
            case "LT":
                return thresholdNum == null ? BigDecimal.ZERO : actual.subtract(thresholdNum).max(BigDecimal.ZERO);
            case "EQ":
                return thresholdNum == null ? BigDecimal.ZERO : actual.subtract(thresholdNum).abs();
            case "BETWEEN":
                if (thresholdNum == null || thresholdMax == null) {
                    return BigDecimal.ZERO;
                }
                if (actual.compareTo(thresholdNum) < 0) {
                    return thresholdNum.subtract(actual);
                }
                if (actual.compareTo(thresholdMax) > 0) {
                    return actual.subtract(thresholdMax);
                }
                return BigDecimal.ZERO;
            default:
                return BigDecimal.ZERO;
        }
    }

    private String describe(BigDecimal actual, String op, BigDecimal thresholdNum, BigDecimal thresholdMax) {
        String symbol;
        switch (op) {
            case "GTE": symbol = "≥"; break;
            case "GT": symbol = ">"; break;
            case "LTE": symbol = "≤"; break;
            case "LT": symbol = "<"; break;
            case "EQ": symbol = "="; break;
            case "NEQ": symbol = "≠"; break;
            case "BETWEEN": symbol = "∈"; break;
            default: symbol = op;
        }
        String range = "BETWEEN".equals(op)
                ? "[" + str(thresholdNum) + ", " + str(thresholdMax) + "]"
                : str(thresholdNum);
        return "实测 " + actual.stripTrailingZeros().toPlainString() + " " + symbol + " " + range;
    }

    private String str(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    /** 单次判定结果。 */
    @Getter
    public static class Verdict {

        /** 实际生效的策略（入参为空或非法时会被归一为 STRICT） */
        private final String policy;

        /** PASS / PASS_WITH_WARNING / FAIL / ESCALATE */
        private final String outcome;

        /** 是否计入硬性达标。ESCALATE 恒为 false —— 它不走硬判定这条路 */
        private final boolean hardPassed;

        /** 是否踩在容差带上（进证据包要单列） */
        private final boolean boundaryWarning;

        /** 越界量，未越界时为 0，无数据时为 null */
        private final BigDecimal deviation;

        /** 中文说明，直接展示给用户（红线 R2：不做隐藏规则） */
        private final String reason;

        Verdict(String policy, String outcome, boolean hardPassed, boolean boundaryWarning,
                BigDecimal deviation, String reason) {
            this.policy = policy;
            this.outcome = outcome;
            this.hardPassed = hardPassed;
            this.boundaryWarning = boundaryWarning;
            this.deviation = deviation;
            this.reason = reason;
        }
    }
}
