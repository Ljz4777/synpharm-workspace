package com.synpharm.design.support.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.DecisionVetoRule;
import com.synpharm.design.domain.model.DesignProject;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D2 兜底 + D3 业务性否决。
 *
 * <p>重点不是「命中了」，而是两条纪律：
 * ① 规则配置有问题时**跳过并告警**，绝不静默当成通过；
 * ② BLOCK 与 WARN 的区分 —— WARN 进得了排序，只是留痕；BLOCK 直接出局。
 *
 * @author SynPharm Team
 */
class DecisionVetoEngineTest {

    private final DecisionVetoEngine engine =
            new DecisionVetoEngine(new JsonCodec(new ObjectMapper()));

    @Test
    @DisplayName("必测项无实测证据时 MUST_VERIFY_UNVERIFIED 命中 BLOCK")
    void mustVerifyVeto() {
        DecisionDtos.AssembledBundle bundle = bundle(
                List.of(), List.of(pending("PTM_HOTSPOT 实测验证")), "PARTIAL", List.of());

        DecisionVetoEngine.VetoResult result = engine.evaluate(bundle,
                List.of(rule("MUST_VERIFY_UNVERIFIED", "BLOCK", null, "{\"requireExperiment\":true}")), project());

        assertThat(result.blocked()).isTrue();
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).ruleCode()).isEqualTo("MUST_VERIFY_UNVERIFIED");
        assertThat(result.hits().get(0).evidenceRef()).isEqualTo("pendingItems[0]");
    }

    @Test
    @DisplayName("没有遗漏项时不命中 MUST_VERIFY_UNVERIFIED")
    void mustVerifyNoPending_noHit() {
        DecisionDtos.AssembledBundle bundle = bundle(List.of(), List.of(), "OK", List.of());

        DecisionVetoEngine.VetoResult result = engine.evaluate(bundle,
                List.of(rule("MUST_VERIFY_UNVERIFIED", "BLOCK", null, null)), project());

        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("DATA_INCOMPLETE：requireMetrics 为空时只拦 INCOMPLETE（一个指标都没有）")
    void dataIncompleteByCompleteness() {
        DecisionVetoEngine.VetoResult incomplete = engine.evaluate(
                bundle(List.of(), List.of(), "INCOMPLETE", List.of("QED")),
                List.of(rule("DATA_INCOMPLETE", "BLOCK", null, "{\"requireMetrics\":[]}")), project());
        assertThat(incomplete.blocked()).isTrue();
        assertThat(incomplete.hits().get(0).message()).contains("INCOMPLETE");

        // PARTIAL 是常态（缺部分指标），不拦 —— 否则整个决策阶段选不出任何候选
        DecisionVetoEngine.VetoResult partial = engine.evaluate(
                bundle(List.of(), List.of(), "PARTIAL", List.of("DTI_CONFIDENCE")),
                List.of(rule("DATA_INCOMPLETE", "BLOCK", null, "{\"requireMetrics\":[]}")), project());
        assertThat(partial.hits()).isEmpty();
    }

    @Test
    @DisplayName("DATA_INCOMPLETE：指定指标缺失才命中，其它指标缺失不拦")
    void dataIncompleteByRequiredMetrics() {
        DecisionDtos.AssembledBundle bundle = bundle(
                List.of(), List.of(), "PARTIAL", List.of("SA_SCORE", "DTI_CONFIDENCE"));

        // 只要求 SA_SCORE —— 它在缺失列表里，应命中
        DecisionVetoEngine.VetoResult hit = engine.evaluate(bundle,
                List.of(rule("DATA_INCOMPLETE", "BLOCK", null, "{\"requireMetrics\":[\"SA_SCORE\"]}")), project());
        assertThat(hit.hits()).hasSize(1);

        // 只要求 MOL_WT —— 它没有缺失，不应命中
        DecisionVetoEngine.VetoResult miss = engine.evaluate(bundle,
                List.of(rule("DATA_INCOMPLETE", "BLOCK", null, "{\"requireMetrics\":[\"MOL_WT\"]}")), project());
        assertThat(miss.hits()).isEmpty();
    }

    @Test
    @DisplayName("HARD_RULE_VIOLATED：复核有 FAIL 时兜底命中")
    void hardRuleViolatedFallback() {
        DecisionDtos.ConstraintVerdict failed = new DecisionDtos.ConstraintVerdict(
                1L, "分子量 150~600 Da", "MOL_WT", "BETWEEN", new BigDecimal("150"),
                new BigDecimal("600"), null, "STRICT", "FAIL", false, BigDecimal.TEN, "越界");
        DecisionDtos.AssembledBundle bundle = bundle(
                List.of(failed), List.of(), "PARTIAL", List.of());

        DecisionVetoEngine.VetoResult result = engine.evaluate(bundle,
                List.of(rule("HARD_RULE_VIOLATED", "BLOCK", null, "{\"source\":\"D2_RECHECK\"}")), project());

        assertThat(result.blocked()).isTrue();
        assertThat(result.hits().get(0).evidenceRef()).isEqualTo("constraintVerdicts[0]");
    }

    @Test
    @DisplayName("FTO_RISK_HIGH 用规则阈值判定，阈值缺失时回落到 0.85")
    void ftoRiskUsesThreshold() {
        DecisionDtos.AssembledBundle bundle = bundle(List.of(), List.of(), "OK", List.of(),
                List.of(new DecisionDtos.MetricPoint("fto_similarity_max",
                        BigDecimal.ONE, new BigDecimal("0.91"), new BigDecimal("0.91"))));

        DecisionVetoEngine.VetoResult withThreshold = engine.evaluate(bundle,
                List.of(rule("FTO_RISK_HIGH", "BLOCK", "0.85",
                        "{\"metricField\":\"fto_similarity_max\"}")), project());
        assertThat(withThreshold.blocked()).isTrue();

        DecisionVetoEngine.VetoResult belowThreshold = engine.evaluate(
                bundle(List.of(), List.of(), "OK", List.of(),
                        List.of(new DecisionDtos.MetricPoint("fto_similarity_max",
                                BigDecimal.ONE, new BigDecimal("0.70"), new BigDecimal("0.70")))),
                List.of(rule("FTO_RISK_HIGH", "BLOCK", "0.85",
                        "{\"metricField\":\"fto_similarity_max\"}")), project());
        assertThat(belowThreshold.hits()).isEmpty();
    }

    @Test
    @DisplayName("WARN 级规则命中但不阻断")
    void warnDoesNotBlock() {
        DecisionVetoEngine.VetoResult result = engine.evaluate(
                bundle(List.of(), List.of(pending("X")), "PARTIAL", List.of()),
                List.of(rule("MUST_VERIFY_UNVERIFIED", "WARN", null, null)), project());

        assertThat(result.hits()).hasSize(1);
        assertThat(result.blocked()).isFalse();
    }

    @Test
    @DisplayName("未知 ruleCode 只告警，不视为通过也不阻断")
    void unknownRuleCodeWarns() {
        DecisionVetoEngine.VetoResult result = engine.evaluate(
                bundle(List.of(), List.of(), "OK", List.of()),
                List.of(rule("SOMETHING_NEW", "BLOCK", null, null)), project());

        assertThat(result.hits()).isEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("SOMETHING_NEW"));
    }

    @Test
    @DisplayName("BUDGET_EXCEEDED 不在逐个候选处命中：名额由 D5 处理")
    void budgetRuleHandledByRanker() {
        DecisionVetoEngine.VetoResult result = engine.evaluate(
                bundle(List.of(), List.of(), "OK", List.of()),
                List.of(rule("BUDGET_EXCEEDED", "WARN", null, "{\"quotaField\":\"quota_wet_lab\"}")),
                project());

        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("停用的规则不参与判定")
    void disabledRuleIgnored() {
        DecisionVetoRule rule = rule("MUST_VERIFY_UNVERIFIED", "BLOCK", null, null);
        rule.setEnabled(0);

        DecisionVetoEngine.VetoResult result = engine.evaluate(
                bundle(List.of(), List.of(pending("X")), "PARTIAL", List.of()),
                List.of(rule), project());

        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("规则码 → 决策阶段原因码映射")
    void filterReasonMapping() {
        assertThat(DecisionVetoEngine.filterReasonOf("MUST_VERIFY_UNVERIFIED"))
                .isEqualTo("VETO_MUST_VERIFY_MISSING");
        assertThat(DecisionVetoEngine.filterReasonOf("HARD_RULE_VIOLATED"))
                .isEqualTo("VETO_HARD_RECHECK_FAILED");
        assertThat(DecisionVetoEngine.filterReasonOf("NOT_MAPPED")).isEqualTo("NOT_MAPPED");
    }

    // ------------------------------------------------------------------ 构造工具

    private static DecisionDtos.PendingItem pending(String item) {
        return new DecisionDtos.PendingItem(item, "项目标记为必须验证，当前没有任何实测记录");
    }

    private static DecisionDtos.AssembledBundle bundle(
            List<DecisionDtos.ConstraintVerdict> verdicts,
            List<DecisionDtos.PendingItem> pending,
            String completeness,
            List<String> missing) {
        return bundle(verdicts, pending, completeness, missing, List.of());
    }

    private static DecisionDtos.AssembledBundle bundle(
            List<DecisionDtos.ConstraintVerdict> verdicts,
            List<DecisionDtos.PendingItem> pending,
            String completeness,
            List<String> missing,
            List<DecisionDtos.MetricPoint> metrics) {
        return new DecisionDtos.AssembledBundle(List.of(), verdicts, List.of(), pending,
                metrics, metrics.size(), metrics.size() + missing.size(), missing, completeness, "h");
    }

    private static DecisionVetoRule rule(String code, String severity, String threshold, String paramsJson) {
        DecisionVetoRule r = new DecisionVetoRule();
        r.setId(1L);
        r.setProjectId(0L);
        r.setRuleCode(code);
        r.setSeverity(severity);
        r.setThresholdNum(threshold == null ? null : new BigDecimal(threshold));
        r.setParamsJson(paramsJson);
        r.setHumanReadable("规则 " + code);
        r.setEnabled(1);
        return r;
    }

    private static DesignProject project() {
        DesignProject p = new DesignProject();
        p.setId(1L);
        p.setQuotaWetLab(8);
        p.setQuotaWetUsed(0);
        return p;
    }
}
