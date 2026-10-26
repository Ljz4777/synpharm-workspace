package com.synpharm.design.support.decision;

import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.DecisionVetoRule;
import com.synpharm.design.domain.model.DesignProject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D2 兜底 + D3 业务性否决（07 §7）。
 *
 * <p><b>两层否决必须分开</b>：
 * <ul>
 *   <li>技术性（D2）：这个分子本身达不达标 —— 以本阶段重算的约束判定为准；</li>
 *   <li>业务性（D3）：这个项目现在能不能选它 —— 必测项没验证、FTO 风险、证据不完整。</li>
 * </ul>
 * 同一个分子完全可能技术上满分、业务上不能选（例如没做必测实验）。
 *
 * <p><b>规则配置错误时跳过并告警，不静默通过</b>：一条写错的规则如果被当成「没命中」，
 * 用户会以为它检查过了。宁可报「这条规则没生效」，也不要给出虚假的通过。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionVetoEngine {

    public static final String SEVERITY_BLOCK = "BLOCK";
    public static final String SEVERITY_WARN = "WARN";

    /** 决策阶段的淘汰原因码（FILTER_REASON 字典，stage=DECISION） */
    private static final Map<String, String> FILTER_REASON = new LinkedHashMap<>();

    static {
        FILTER_REASON.put("MUST_VERIFY_UNVERIFIED", "VETO_MUST_VERIFY_MISSING");
        FILTER_REASON.put("BUDGET_EXCEEDED", "VETO_BUDGET_CUT");
        FILTER_REASON.put("FTO_RISK_HIGH", "VETO_FTO_RISK");
        FILTER_REASON.put("DATA_INCOMPLETE", "VETO_DATA_INCOMPLETE");
        FILTER_REASON.put("HARD_RULE_VIOLATED", "VETO_HARD_RECHECK_FAILED");
        FILTER_REASON.put("MANUAL_VETO", "VETO_MANUAL");
    }

    private final JsonCodec jsonCodec;

    /** 否决结果：命中清单 + 规则层面的告警 */
    public record VetoResult(List<DecisionDtos.VetoHit> hits, List<String> warnings) {

        public boolean blocked() {
            return hits.stream().anyMatch(h -> SEVERITY_BLOCK.equals(h.severity()));
        }
    }

    /**
     * 跑一遍否决。
     *
     * @param bundle 已装配的证据包
     * @param rules  启用中的规则（全局 + 项目级）
     * @param project 项目（预算类规则需要）
     */
    public VetoResult evaluate(DecisionDtos.AssembledBundle bundle,
                               List<DecisionVetoRule> rules,
                               DesignProject project) {
        List<DecisionDtos.VetoHit> hits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (DecisionVetoRule rule : rules) {
            if (rule.getEnabled() != null && rule.getEnabled() == 0) {
                continue;
            }
            String code = rule.getRuleCode();
            if (code == null || code.isBlank()) {
                warnings.add("规则 #" + rule.getId() + " 缺少 ruleCode，已跳过");
                continue;
            }
            Map<String, Object> params = jsonCodec.readMap(rule.getParamsJson());

            switch (code) {
                case "MUST_VERIFY_UNVERIFIED" -> {
                    if (!bundle.pendingItems().isEmpty()) {
                        DecisionDtos.PendingItem first = bundle.pendingItems().get(0);
                        hits.add(hit(rule, first.item() + "：「" + first.reason() + "」", "pendingItems[0]"));
                    }
                }
                case "DATA_INCOMPLETE" -> {
                    List<String> required = stringList(params, "requireMetrics");
                    List<String> missingRequired = bundle.missingMetrics().stream()
                            .filter(required::contains)
                            .toList();
                    if (!missingRequired.isEmpty()) {
                        hits.add(hit(rule, "缺少指定指标：" + String.join("、", missingRequired),
                                "missingMetrics"));
                    } else if (required.isEmpty() && "INCOMPLETE".equals(bundle.completeness())) {
                        // 未指定关键指标时只拦 INCOMPLETE（一个指标都没有 = 证据包不可用）。
                        // PARTIAL（缺部分指标）不拦：本系统缺指标是常态（覆盖率约 33%），
                        // 用规则再否决一次会让整个决策阶段永远选不出任何候选；
                        // 「缺了哪几项」由 coversMetrics / missingMetrics 如实展示给决策者。
                        hits.add(hit(rule, "证据包完整性为 INCOMPLETE（没有任何可用指标）", "completeness"));
                    }
                }
                case "HARD_RULE_VIOLATED" -> {
                    List<DecisionDtos.ConstraintVerdict> failed = bundle.verdicts().stream()
                            .filter(v -> !v.hardPassed())
                            .toList();
                    if (!failed.isEmpty()) {
                        DecisionDtos.ConstraintVerdict first = failed.get(0);
                        hits.add(hit(rule, "复核未通过：" + first.label() + "，" + first.reason(),
                                "constraintVerdicts[0]"));
                    }
                }
                case "FTO_RISK_HIGH" -> {
                    String field = params == null ? null : stringOf(params, "metricField");
                    String metricCode = field == null || field.isBlank() ? "FTO_SIMILARITY_MAX" : field;
                    BigDecimal threshold = rule.getThresholdNum() == null
                            ? new BigDecimal("0.85") : rule.getThresholdNum();
                    BigDecimal actual = metricOf(bundle, metricCode);
                    if (actual != null && actual.compareTo(threshold) > 0) {
                        hits.add(hit(rule, metricCode + " = " + actual.toPlainString()
                                + "，超过阈值 " + threshold.toPlainString(), "metrics[" + metricCode + "]"));
                    }
                }
                case "BUDGET_EXCEEDED" -> {
                    // 名额类规则不逐个候选否决：它在 D5 表现为「超出名额转备选」
                    if (project == null) {
                        warnings.add("规则 BUDGET_EXCEEDED 需要项目配额，但未传入项目，已跳过");
                    }
                }
                case "MANUAL_VETO" -> {
                    // 人工否决只能在决策界面录入（E6），不参与自动判定
                }
                default -> warnings.add("规则「" + code + "」未实现判定逻辑，已跳过（不视为通过）");
            }
        }
        return new VetoResult(hits, warnings);
    }

    /** 规则码 → 决策阶段的原因码；未映射时原样返回 */
    public static String filterReasonOf(String ruleCode) {
        return FILTER_REASON.getOrDefault(ruleCode, ruleCode);
    }

    // ------------------------------------------------------------------ 工具

    private DecisionDtos.VetoHit hit(DecisionVetoRule rule, String message, String evidenceRef) {
        String severity = rule.getSeverity() == null ? SEVERITY_BLOCK : rule.getSeverity();
        String readable = rule.getHumanReadable() == null ? "" : rule.getHumanReadable();
        return new DecisionDtos.VetoHit(rule.getRuleCode(), severity,
                readable.isBlank() ? message : readable + "（" + message + "）",
                evidenceRef);
    }

    private static BigDecimal metricOf(DecisionDtos.AssembledBundle bundle, String code) {
        return bundle.metrics().stream()
                .filter(p -> p.code().equalsIgnoreCase(code))
                .map(DecisionDtos.MetricPoint::value)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String stringOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        if (map == null) {
            return List.of();
        }
        Object v = map.get(key);
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : raw) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
