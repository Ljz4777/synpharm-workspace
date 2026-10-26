package com.synpharm.design.support.decision;

import com.synpharm.design.domain.model.DesignProject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D4 分桶排序 + D5 名额分配（07 §7、B1、B2）。
 *
 * <p><b>桶的判定只看约束三态</b>：全部 PASS → {@code FULL_PASS}；
 * 有 PASS_WITH_WARNING 且无 FAIL → {@code TOLERANCE}；有 FAIL → {@code FAIL}。
 * 有 BLOCK 级否决的候选直接落 {@code FAIL} 桶 —— 它们在业务上已经出局，
 * 再排进 TOLERANCE 只会让人误以为「还可以争取一下」。
 *
 * <p><b>加权分的分母只算有数据的指标</b>（B1）。这意味着测了 8 个指标的候选
 * 和只测了 3 个的候选分母不同，分数不可直接横比 —— 所以 {@code coversMetrics}
 * 必须一起返回，UI 要显示「这个 92 分来自 4 个指标」。宁可缺数据也不补零分：
 * 补零会把「没测」变成「测了但很差」，那是对事实的篡改。
 *
 * <p><b>FAIL 桶不排序、不占名额，但必须保留</b>：它们是回流阶段的负样本。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class DecisionRanker {

    public static final String BUCKET_FULL_PASS = "FULL_PASS";
    public static final String BUCKET_TOLERANCE = "TOLERANCE";
    public static final String BUCKET_FAIL = "FAIL";

    public static final String OUTCOME_SELECTED = "SELECTED";
    public static final String OUTCOME_BACKUP = "BACKUP";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_DEFERRED = "DEFERRED";

    /** 一个候选进入排序前的样子 */
    public record CandidateInput(
            Long candidateId,
            DecisionDtos.AssembledBundle bundle,
            List<DecisionDtos.VetoHit> vetoes,
            int mustVerifyCount) {
    }

    /**
     * 排序并分配名额。
     *
     * @param inputs   已装配、已否决的候选
     * @param project  取名额档位与湿实验余额
     */
    public DecisionDtos.RankOutcome rank(List<CandidateInput> inputs, DesignProject project) {
        List<String> warnings = new ArrayList<>();

        List<DecisionDtos.RankedCandidate> blocked = new ArrayList<>();
        List<DecisionDtos.RankedCandidate> eligible = new ArrayList<>();
        List<String> notEvaluatedMetrics = new ArrayList<>();

        for (CandidateInput in : inputs) {
            String bucket = bucketOf(in.bundle(), in.vetoes());
            BigDecimal score = weightedScore(in.bundle());
            boolean isBlocked = hasBlockVeto(in.vetoes());
            String rejectReason = isBlocked
                    ? DecisionVetoEngine.filterReasonOf(firstBlockCode(in.vetoes()))
                    : (BUCKET_FAIL.equals(bucket) ? failReasonOf(in.bundle()) : null);

            for (DecisionDtos.ConstraintVerdict v : in.bundle().verdicts()) {
                if (!v.hardPassed()
                        && EvidenceBundleAssembler.OUTCOME_NOT_EVALUATED.equals(v.outcome())
                        && v.metricCode() != null
                        && !notEvaluatedMetrics.contains(v.metricCode())) {
                    notEvaluatedMetrics.add(v.metricCode());
                }
            }

            DecisionDtos.RankedCandidate row = new DecisionDtos.RankedCandidate(
                    in.candidateId(),
                    bucket,
                    null,
                    score,
                    in.bundle().coversMetrics(),
                    in.bundle().missingMetrics(),
                    null,
                    rejectReason,
                    isBlocked,
                    in.mustVerifyCount(),
                    in.vetoes());

            if (BUCKET_FAIL.equals(bucket)) {
                blocked.add(row);
            } else {
                eligible.add(row);
            }
        }

        // 桶内排序：加权分降序 → 必测项少者优先 → 候选 id 小者优先（保证可复现）
        eligible.sort(Comparator
                .comparing((DecisionDtos.RankedCandidate c) -> bucketOrder(c.bucket()))
                .thenComparing(DecisionDtos.RankedCandidate::weightedScore,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparingInt(DecisionDtos.RankedCandidate::mustVerifyCount)
                .thenComparing(DecisionDtos.RankedCandidate::candidateId));

        int capacity = capacityOf(project, eligible.size(), warnings);

        List<DecisionDtos.RankedCandidate> ranked = new ArrayList<>();
        int rank = 1;
        int selected = 0;
        for (DecisionDtos.RankedCandidate c : eligible) {
            boolean withinCapacity = selected < capacity;
            if (withinCapacity) {
                selected++;
            }
            ranked.add(new DecisionDtos.RankedCandidate(
                    c.candidateId(), c.bucket(), rank++, c.weightedScore(),
                    c.coversMetrics(), c.missingMetrics(),
                    withinCapacity ? OUTCOME_SELECTED : OUTCOME_BACKUP,
                    withinCapacity ? null : DecisionVetoEngine.filterReasonOf("BUDGET_EXCEEDED"),
                    false, c.mustVerifyCount(), c.vetoes()));
        }

        List<DecisionDtos.RankedCandidate> rejected = new ArrayList<>();
        for (DecisionDtos.RankedCandidate c : blocked) {
            rejected.add(new DecisionDtos.RankedCandidate(
                    c.candidateId(), BUCKET_FAIL, null, c.weightedScore(),
                    c.coversMetrics(), c.missingMetrics(),
                    OUTCOME_REJECTED, c.rejectReason(),
                    c.blocked(), c.mustVerifyCount(), c.vetoes()));
        }

        List<DecisionDtos.RankedCandidate> all = new ArrayList<>(ranked);
        all.addAll(rejected);

        if (selected == 0 && !eligible.isEmpty()) {
            warnings.add("NO_VIABLE_CANDIDATE：可用名额为 " + capacity + "，没有任何候选被选中");
        }
        if (eligible.isEmpty() && !rejected.isEmpty()) {
            warnings.add("NO_VIABLE_CANDIDATE：全部候选均被否决或复核不通过");
        }
        if (!notEvaluatedMetrics.isEmpty()) {
            warnings.add("硬约束无法复核：冻结快照里缺少指标 " + String.join("、", notEvaluatedMetrics)
                    + " 的值，相关候选按「不通过」处理。请先补齐评估冻结（该指标可能不在基线指标块内）");
        }

        return new DecisionDtos.RankOutcome(
                all, selected, eligible.size() - selected, rejected.size(), capacity, warnings);
    }

    // ------------------------------------------------------------------ 桶与分

    private String bucketOf(DecisionDtos.AssembledBundle bundle,
                            List<DecisionDtos.VetoHit> vetoes) {
        if (hasBlockVeto(vetoes)) {
            return BUCKET_FAIL;
        }
        boolean anyFail = bundle.verdicts().stream().anyMatch(v -> !v.hardPassed());
        if (anyFail) {
            return BUCKET_FAIL;
        }
        boolean anyWarning = bundle.verdicts().stream()
                .anyMatch(v -> "PASS_WITH_WARNING".equals(v.outcome()));
        return anyWarning ? BUCKET_TOLERANCE : BUCKET_FULL_PASS;
    }

    /** FULL_PASS 排在 TOLERANCE 之前 */
    private static int bucketOrder(String bucket) {
        return BUCKET_FULL_PASS.equals(bucket) ? 0 : 1;
    }

    /**
     * FAIL 桶的淘汰原因。
     *
     * <p>「分子不达标」（VETO_HARD_RECHECK_FAILED）与「快照里没这个值，判不了」
     * （VETO_DATA_INCOMPLETE）必须分开：前者要换分子，后者要补评估 ——
     * 混成一句话会让人去改错的地方。
     */
    private static String failReasonOf(DecisionDtos.AssembledBundle bundle) {
        boolean genuineFail = bundle.verdicts().stream()
                .anyMatch(v -> !v.hardPassed()
                        && !EvidenceBundleAssembler.OUTCOME_NOT_EVALUATED.equals(v.outcome()));
        return genuineFail ? "VETO_HARD_RECHECK_FAILED" : "VETO_DATA_INCOMPLETE";
    }

    /**
     * Σ(w × normalized) / Σw，只对**有值**的指标求和。
     *
     * <p>返回 0~100 便于阅读；没有任何可用指标时返回 {@code null}（不是 0）——
     * 0 分意味着「算过且很差」，null 才是「没算」。
     */
    BigDecimal weightedScore(DecisionDtos.AssembledBundle bundle) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;

        for (DecisionDtos.MetricPoint p : bundle.metrics()) {
            if (!p.hasValue() || p.weight() == null || p.normalized() == null) {
                continue;
            }
            if (p.weight().signum() <= 0) {
                continue;
            }
            weighted = weighted.add(p.weight().multiply(p.normalized()));
            weightSum = weightSum.add(p.weight());
        }
        if (weightSum.signum() == 0) {
            return null;
        }
        return weighted.multiply(BigDecimal.valueOf(100))
                .divide(weightSum, 4, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ 名额

    /**
     * 名额（B2）：档位算出的数 与 湿实验余额 取小，且至少 1（有候选时）。
     *
     * <p>不猜默认值：档位缺失直接给 0 并告警。给个「看起来合理」的默认名额，
     * 会让用户以为系统替他把人数定了。
     */
    private int capacityOf(DesignProject project, int eligibleCount, List<String> warnings) {
        if (eligibleCount == 0) {
            return 0;
        }
        if (project == null) {
            warnings.add("缺少项目上下文，无法计算名额，按 1 个处理");
            return 1;
        }
        String mode = project.getDecisionCapacityMode();
        BigDecimal value = project.getDecisionCapacityValue();

        int byMode;
        if ("RATIO".equalsIgnoreCase(mode)) {
            if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
                warnings.add("决策名额比例应在 0~1 之间，当前为 " + value + "，按 1 个处理");
                byMode = 1;
            } else {
                byMode = Math.max(1, (int) Math.floor(eligibleCount * value.doubleValue()));
            }
        } else if ("ABSOLUTE".equalsIgnoreCase(mode)) {
            if (value == null || value.signum() <= 0) {
                warnings.add("决策名额个数应为正整数，当前为 " + value + "，按 1 个处理");
                byMode = 1;
            } else {
                byMode = Math.max(1, value.setScale(0, RoundingMode.DOWN).intValue());
            }
        } else {
            warnings.add("未配置决策名额档位（decision_capacity_mode），按 1 个处理");
            byMode = 1;
        }

        Integer quota = project.getQuotaWetLab();
        Integer used = project.getQuotaWetUsed();
        int balance = Integer.MAX_VALUE;
        if (quota != null) {
            balance = quota - (used == null ? 0 : used);
        }

        int capacity = Math.min(byMode, balance);
        if (capacity <= 0) {
            warnings.add("湿实验名额已用尽（配额 " + quota + "，已用 " + used + "），本次只能产出备选");
            return 0;
        }
        return capacity;
    }

    // ------------------------------------------------------------------ 工具

    private static boolean hasBlockVeto(List<DecisionDtos.VetoHit> vetoes) {
        return vetoes != null && vetoes.stream()
                .anyMatch(v -> DecisionVetoEngine.SEVERITY_BLOCK.equals(v.severity()));
    }

    private static String firstBlockCode(List<DecisionDtos.VetoHit> vetoes) {
        if (vetoes == null) {
            return "MANUAL_VETO";
        }
        return vetoes.stream()
                .filter(v -> DecisionVetoEngine.SEVERITY_BLOCK.equals(v.severity()))
                .map(DecisionDtos.VetoHit::ruleCode)
                .findFirst()
                .orElse("MANUAL_VETO");
    }
}
