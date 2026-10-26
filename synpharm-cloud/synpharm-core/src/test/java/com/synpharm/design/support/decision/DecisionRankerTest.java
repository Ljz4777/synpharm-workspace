package com.synpharm.design.support.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.synpharm.design.domain.model.DesignProject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D4 分桶排序 + D5 名额分配（B1 / B2）。
 *
 * <p>这组断言锁住四件容易写错的事：
 * ① 加权分的分母只含**有数据**的指标（不补零分）；
 * ② BLOCK 否决落 FAIL 桶且不占名额；
 * ③ 名额 = 档位算出的数与湿实验余额取小，且至少 1；
 * ④ 排序必须可复现（同分时按必测项数、候选 id 兜底）。
 *
 * @author SynPharm Team
 */
class DecisionRankerTest {

    private final DecisionRanker ranker = new DecisionRanker();

    @Test
    @DisplayName("加权分只对含数据的指标求和，缺数据的指标不进分母")
    void weightedScoreIgnoresMissingMetrics() {
        // 只有 2 个指标有值：0.6×0.8 + 0.4×0.5 = 0.68 → 68 分（不是 4 个指标的一半）
        DecisionDtos.AssembledBundle bundle = bundleOf(List.of(
                metric("QED", "0.6", "0.80", "0.8"),
                metric("SA_SCORE", "0.4", "5.0", "0.5"),
                metric("DTI_CONFIDENCE", "1.0", null, null)));

        BigDecimal score = ranker.weightedScore(bundle);

        assertThat(score).isEqualByComparingTo("68");
        assertThat(bundle.coversMetrics()).isEqualTo(2);
        assertThat(bundle.missingMetrics()).containsExactly("DTI_CONFIDENCE");
    }

    @Test
    @DisplayName("一个指标都没有值时返回 null，而不是 0 分")
    void weightedScoreNullWhenNoData() {
        DecisionDtos.AssembledBundle bundle = bundleOf(List.of(
                metric("QED", "1.0", null, null)));

        assertThat(ranker.weightedScore(bundle)).isNull();
    }

    @Test
    @DisplayName("三桶判定：全 PASS→FULL_PASS，含容差告警→TOLERANCE，含 FAIL→FAIL")
    void bucketOfVerdicts() {
        DesignProject project = projectWith("RATIO", "1.0", 10, 0);

        DecisionDtos.RankOutcome out = ranker.rank(List.of(
                input(1L, bundleWithVerdicts(List.of(verdict("PASS", true))), List.of(), 0),
                input(2L, bundleWithVerdicts(List.of(verdict("PASS_WITH_WARNING", true))), List.of(), 0),
                input(3L, bundleWithVerdicts(List.of(verdict("FAIL", false))), List.of(), 0)
        ), project);

        assertThat(bucketOf(out, 1L)).isEqualTo(DecisionRanker.BUCKET_FULL_PASS);
        assertThat(bucketOf(out, 2L)).isEqualTo(DecisionRanker.BUCKET_TOLERANCE);
        assertThat(bucketOf(out, 3L)).isEqualTo(DecisionRanker.BUCKET_FAIL);
    }

    @Test
    @DisplayName("BLOCK 否决落 FAIL 桶并转 REJECTED，原因码映射到 VETO_* 字典")
    void blockVetoGoesToFailBucket() {
        DesignProject project = projectWith("RATIO", "1.0", 10, 0);
        List<DecisionDtos.VetoHit> vetoes = List.of(
                new DecisionDtos.VetoHit("MUST_VERIFY_UNVERIFIED", "BLOCK", "必测项未验证", "pendingItems[0]"));

        DecisionDtos.RankOutcome out = ranker.rank(
                List.of(input(9L, bundleWithVerdicts(List.of(verdict("PASS", true))), vetoes, 1)),
                project);

        DecisionDtos.RankedCandidate row = out.candidates().get(0);
        assertThat(row.bucket()).isEqualTo(DecisionRanker.BUCKET_FAIL);
        assertThat(row.outcome()).isEqualTo(DecisionRanker.OUTCOME_REJECTED);
        assertThat(row.rejectReason()).isEqualTo("VETO_MUST_VERIFY_MISSING");
        // FAIL 桶不排名次
        assertThat(row.rankNo()).isNull();
        assertThat(out.selectedCount()).isZero();
    }

    @Test
    @DisplayName("RATIO 档位：5 个可入选候选 × 0.2 = 1 个名额，其余转 BACKUP")
    void ratioCapacity() {
        DesignProject project = projectWith("RATIO", "0.200", 10, 0);

        List<DecisionRanker.CandidateInput> inputs = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            inputs.add(input(id, bundleOf(List.of(metric("QED", "1.0", "0.9", "0.9"))), List.of(), 0));
        }

        DecisionDtos.RankOutcome out = ranker.rank(inputs, project);

        assertThat(out.capacity()).isEqualTo(1);
        assertThat(out.selectedCount()).isEqualTo(1);
        assertThat(out.backupCount()).isEqualTo(4);
        assertThat(out.candidates().get(0).outcome()).isEqualTo(DecisionRanker.OUTCOME_SELECTED);
        assertThat(out.candidates().get(1).rejectReason()).isEqualTo("VETO_BUDGET_CUT");
    }

    @Test
    @DisplayName("名额受湿实验余额封顶：余额 0 时全部转备选并告警")
    void capacityCappedByWetLabBalance() {
        DesignProject project = projectWith("ABSOLUTE", "3", 5, 5);

        DecisionDtos.RankOutcome out = ranker.rank(
                List.of(input(1L, bundleOf(List.of(metric("QED", "1.0", "0.9", "0.9"))), List.of(), 0)),
                project);

        assertThat(out.capacity()).isZero();
        assertThat(out.selectedCount()).isZero();
        assertThat(out.candidates().get(0).outcome()).isEqualTo(DecisionRanker.OUTCOME_BACKUP);
        assertThat(out.warnings()).anyMatch(w -> w.contains("湿实验名额已用尽"));
    }

    @Test
    @DisplayName("排序可复现：分数相同则必测项少者优先，再同则候选 id 小者优先")
    void orderingIsDeterministic() {
        DesignProject project = projectWith("ABSOLUTE", "3", 10, 0);

        DecisionDtos.RankOutcome out = ranker.rank(List.of(
                input(30L, bundleOf(List.of(metric("QED", "1.0", "0.9", "0.9"))), List.of(), 2),
                input(20L, bundleOf(List.of(metric("QED", "1.0", "0.9", "0.9"))), List.of(), 0),
                input(10L, bundleOf(List.of(metric("QED", "1.0", "0.9", "0.9"))), List.of(), 0)
        ), project);

        assertThat(out.candidates()).extracting(DecisionDtos.RankedCandidate::candidateId)
                .containsExactly(10L, 20L, 30L);
        assertThat(out.candidates()).extracting(DecisionDtos.RankedCandidate::rankNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("全部落 FAIL 桶时给出 NO_VIABLE_CANDIDATE 告警，但记录仍可生成")
    void noViableCandidateWarning() {
        DesignProject project = projectWith("RATIO", "0.2", 10, 0);

        DecisionDtos.RankOutcome out = ranker.rank(
                List.of(input(1L, bundleWithVerdicts(List.of(verdict("FAIL", false))), List.of(), 0)),
                project);

        assertThat(out.candidates()).hasSize(1);
        assertThat(out.warnings()).anyMatch(w -> w.startsWith("NO_VIABLE_CANDIDATE"));
    }

    // ------------------------------------------------------------------ 构造工具

    private static DecisionDtos.MetricPoint metric(String code, String weight, String value, String normalized) {
        return new DecisionDtos.MetricPoint(code, new BigDecimal(weight),
                value == null ? null : new BigDecimal(value),
                normalized == null ? null : new BigDecimal(normalized));
    }

    private static DecisionDtos.ConstraintVerdict verdict(String outcome, boolean hardPassed) {
        return new DecisionDtos.ConstraintVerdict(1L, "分子量 150~600 Da", "MOL_WT",
                "BETWEEN", new BigDecimal("150"), new BigDecimal("600"), null, "STRICT",
                outcome, hardPassed, BigDecimal.ZERO, "测试用例");
    }

    private static DecisionDtos.AssembledBundle bundleOf(List<DecisionDtos.MetricPoint> metrics) {
        long covers = metrics.stream().filter(DecisionDtos.MetricPoint::hasValue).count();
        List<String> missing = metrics.stream()
                .filter(p -> !p.hasValue())
                .map(DecisionDtos.MetricPoint::code)
                .toList();
        return new DecisionDtos.AssembledBundle(List.of(), List.of(), List.of(), List.of(),
                metrics, (int) covers, metrics.size(), missing, "PARTIAL", "hash-" + covers);
    }

    private static DecisionDtos.AssembledBundle bundleWithVerdicts(
            List<DecisionDtos.ConstraintVerdict> verdicts) {
        return new DecisionDtos.AssembledBundle(List.of(), verdicts, List.of(), List.of(),
                List.of(metric("QED", "1.0", "0.8", "0.8")), 1, 1, List.of(), "PARTIAL", "hash-v");
    }

    private static DecisionRanker.CandidateInput input(Long id, DecisionDtos.AssembledBundle bundle,
                                                       List<DecisionDtos.VetoHit> vetoes, int mustVerify) {
        return new DecisionRanker.CandidateInput(id, bundle, vetoes, mustVerify);
    }

    private static DesignProject projectWith(String mode, String value, Integer wetLab, Integer wetUsed) {
        DesignProject p = new DesignProject();
        p.setId(1L);
        p.setDecisionCapacityMode(mode);
        p.setDecisionCapacityValue(new BigDecimal(value));
        p.setQuotaWetLab(wetLab);
        p.setQuotaWetUsed(wetUsed);
        return p;
    }

    private static String bucketOf(DecisionDtos.RankOutcome out, Long candidateId) {
        return out.candidates().stream()
                .filter(c -> candidateId.equals(c.candidateId()))
                .map(DecisionDtos.RankedCandidate::bucket)
                .findFirst()
                .orElse(null);
    }
}
