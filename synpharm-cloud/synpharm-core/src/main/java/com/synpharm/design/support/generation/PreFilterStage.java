package com.synpharm.design.support.generation;

import com.synpharm.design.infrastructure.client.ChemistryClient;
import com.synpharm.design.infrastructure.client.ChemistryDtos;
import com.synpharm.design.support.BoundaryJudge;
import com.synpharm.design.support.MetricFieldMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * S6 粗筛（06 §4.1）。
 *
 * <p>用真 RDKit 指标 + 基线硬约束逐条判定。三条不可让步的纪律：
 *
 * <ol>
 *   <li><b>原因必记</b>：每个被筛掉的分子都带机器码与中文说明。
 *       只记数量的话，后期永远分不清「筛太狠」还是「采太偏」。</li>
 *   <li><b>判不了必须显式说判不了</b>：{@code SMARTS_REQUIRED}、{@code SIMILARITY_RANGE}
 *       这类规则需要计算层没有的能力。它们被记为 {@code SKIPPED} 并在诊断里单列，
 *       <b>绝不当作通过</b> —— 「没检查」和「检查通过」是两件事。</li>
 *   <li><b>不返回 0 值填充</b>：指标算不出来时该条记 {@code success=false}，
 *       不用 0 冒充 —— 0 的 LogP 和算不出的 LogP 在决策阶段含义完全不同。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreFilterStage {

    /** 粗筛用的指标（全部 RDKit 原生可算） */
    static final List<String> FILTER_METRICS = List.of(
            "MOL_WT", "MOL_LOGP", "TPSA", "NUM_H_DONORS", "NUM_H_ACCEPTORS",
            "NUM_ROTATABLE", "QED", "LIPINSKI_PASS", "PAINS_HITS", "BRENK_HITS",
            "COMPLEXITY_PENALTY", "NUM_RINGS");

    private final ChemistryClient chemistryClient;
    private final BoundaryJudge boundaryJudge;

    // ------------------------------------------------------------------ 结果

    public record FilterReason(String code, String severity, String message) {
    }

    public record Entry(
            DedupStage.Entry dedupEntry,
            BigDecimal preScore,
            List<FilterReason> reasons,
            List<String> skippedRules
    ) {
        public boolean isPassed() {
            return reasons.stream().noneMatch(r -> "HARD".equals(r.severity()));
        }
    }

    public record Result(
            List<Entry> passed,
            List<Entry> filtered,
            /** 规则码 → 是否可判定；判不了的会进 {@code skippedRules} 汇总 */
            List<String> skippedRules,
            Map<String, Object> metricsSnapshot
    ) {
    }

    // ------------------------------------------------------------------ 主流程

    public Result filter(List<DedupStage.Entry> dedupKept, GenerationDtos.GenerationContext context) {
        if (dedupKept.isEmpty()) {
            return new Result(List.of(), List.of(), List.of(), Map.of());
        }

        // 一次调用算完所有需要的指标（含 diversity 已由 S5 算过，这里不需要）
        List<ChemistryDtos.MoleculeIn> payload = new ArrayList<>();
        for (DedupStage.Entry e : dedupKept) {
            String smiles = e.canonicalSmiles() != null ? e.canonicalSmiles() : e.candidate().smiles();
            // 序列类分子没有 SMILES：不送计算层，走"无法判定"分支
            payload.add(ChemistryDtos.MoleculeIn.of(e.candidate().tempRef(), smiles));
        }

        Map<String, ChemistryDtos.MoleculeResult> metricByRef = new LinkedHashMap<>();
        boolean anySmallMolecule = payload.stream().anyMatch(p -> p.getSmiles() != null && !p.getSmiles().isBlank());
        if (anySmallMolecule) {
            List<ChemistryDtos.MoleculeIn> valid = payload.stream()
                    .filter(p -> p.getSmiles() != null && !p.getSmiles().isBlank())
                    .toList();
            ChemistryDtos.PropertiesResponse response =
                    chemistryClient.properties(valid, FILTER_METRICS, false);
            for (ChemistryDtos.MoleculeResult r : response.getResults()) {
                metricByRef.put(r.getRef(), r);
            }
        }

        List<Entry> passed = new ArrayList<>();
        List<Entry> filtered = new ArrayList<>();
        List<String> skippedAll = new ArrayList<>();
        int metricRowCount = 0;

        for (DedupStage.Entry e : dedupKept) {
            String ref = e.candidate().tempRef();
            ChemistryDtos.MoleculeResult metrics = metricByRef.get(ref);

            List<FilterReason> reasons = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            if (e.candidate().smiles() != null && !e.candidate().smiles().isBlank()) {
                if (metrics == null || !metrics.isOk()) {
                    // 结构无法解析：明确记录，不用 0 值指标继续判
                    String detail = metrics == null ? "计算层未返回该分子" : metrics.getError();
                    reasons.add(new FilterReason("ILLEGAL_RESIDUE", "HARD", "结构无法解析：" + detail));
                } else {
                    metricRowCount++;
                    applyHardRules(metrics, context.hardRules(), reasons, skipped);
                    applySafetyCatalog(metrics, reasons);
                }
            } else {
                if (e.candidate().sequenceHash() == null) {
                    reasons.add(new FilterReason("ILLEGAL_RESIDUE", "HARD", "候选既无 SMILES 也无有效序列"));
                } else {
                    skipped.add("SEQUENCE_METRICS_UNAVAILABLE");
                }
            }

            BigDecimal preScore = computePreScore(metrics);
            Entry entry = new Entry(e, preScore, reasons, skipped);
            for (String s : skipped) {
                if (!skippedAll.contains(s)) {
                    skippedAll.add(s);
                }
            }
            if (entry.isPassed()) {
                passed.add(entry);
            } else {
                filtered.add(entry);
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("input", dedupKept.size());
        snapshot.put("passed", passed.size());
        snapshot.put("filtered", filtered.size());
        snapshot.put("metricsComputed", metricRowCount);
        snapshot.put("skippedRules", skippedAll);

        log.info("S6 粗筛：输入 {} → 通过 {}，淘汰 {}，判不了的规则 {}", dedupKept.size(), passed.size(),
                filtered.size(), skippedAll);
        return new Result(passed, filtered, skippedAll, snapshot);
    }

    // ------------------------------------------------------------------ 硬约束

    private void applyHardRules(
            ChemistryDtos.MoleculeResult metrics,
            List<GenerationDtos.HardRule> rules,
            List<FilterReason> reasons,
            List<String> skipped) {

        for (GenerationDtos.HardRule rule : rules) {
            String ruleType = rule.ruleType();
            if ("FILTER_CATALOG".equalsIgnoreCase(ruleType)) {
                // 由 applySafetyCatalog 单独处理
                continue;
            }
            if (!"NUMERIC_RANGE".equalsIgnoreCase(ruleType)) {
                // SMARTS / 骨架 / 相似度 等需要计算层未提供的能力
                String code = "SKIPPED_" + (ruleType == null ? "UNKNOWN" : ruleType.toUpperCase());
                if (!skipped.contains(code)) {
                    skipped.add(code);
                }
                continue;
            }

            String field = stringParam(rule.params(), "field");
            String metricCode = mapField(field);
            if (metricCode == null) {
                skipped.add("SKIPPED_UNMAPPED_FIELD:" + field);
                continue;
            }
            ChemistryDtos.MetricEntry entry = metrics.getMetrics() == null
                    ? null : metrics.getMetrics().get(metricCode);
            if (entry == null || entry.getValue() == null) {
                skipped.add("SKIPPED_NO_METRIC:" + metricCode);
                continue;
            }

            BigDecimal min = numericParam(rule.params(), "min");
            BigDecimal max = numericParam(rule.params(), "max");
            if (min == null) {
                min = rule.thresholdNum();
            }
            if (max == null) {
                max = rule.thresholdMax();
            }

            String operator;
            if (min != null && max != null) {
                operator = "BETWEEN";
            } else if (min != null) {
                operator = "GTE";
            } else if (max != null) {
                operator = "LTE";
            } else {
                skipped.add("SKIPPED_NO_THRESHOLD:" + metricCode);
                continue;
            }

            String policy = rule.boundaryPolicy() == null ? BoundaryJudge.STRICT : rule.boundaryPolicy();
            BoundaryJudge.Verdict verdict = boundaryJudge.judge(
                    entry.getValue(), operator, min, max, rule.tolerance(), policy);

            String label = rule.humanReadable() != null ? rule.humanReadable()
                    : metricCode + " " + operator;
            switch (verdict.getOutcome()) {
                case BoundaryJudge.FAIL ->
                        reasons.add(new FilterReason("HARD_CONSTRAINT_VIOLATION", "HARD",
                                label + "：" + verdict.getReason()));
                case BoundaryJudge.ESCALATE ->
                        reasons.add(new FilterReason("HARD_CONSTRAINT_ESCALATED", "WARN",
                                label + "：" + verdict.getReason() + "（按策略转软扣分）"));
                case BoundaryJudge.PASS_WITH_WARNING ->
                        reasons.add(new FilterReason("BOUNDARY_WARNING", "WARN",
                                label + "：" + verdict.getReason()));
                default -> {
                    // PASS：不记录，避免日志噪音
                }
            }
        }
    }

    /** PAINS / BRENK 命中即淘汰（对应基线 FILTER_CATALOG + mode=must_pass） */
    private void applySafetyCatalog(ChemistryDtos.MoleculeResult metrics, List<FilterReason> reasons) {
        if (metrics.getMetrics() == null) {
            return;
        }
        addHits(reasons, metrics.getMetrics().get("PAINS_HITS"), "PAINS_HIT", "PAINS");
        addHits(reasons, metrics.getMetrics().get("BRENK_HITS"), "BRENK_HIT", "BRENK");
    }

    private void addHits(List<FilterReason> reasons, ChemistryDtos.MetricEntry entry,
                         String code, String label) {
        if (entry == null || entry.getValue() == null) {
            return;
        }
        if (entry.getValue().signum() > 0) {
            int count = entry.getValue().intValue();
            reasons.add(new FilterReason(code, "HARD",
                    label + " 命中 " + count + " 条结构警报：" + entry.getDetail()));
        }
    }

    // ------------------------------------------------------------------ 打分

    /**
     * 粗筛得分（0~100）。
     *
     * <p>刻意只用四个可解释的项，不做加权玄学：
     * 成药性 40% + 合成简单度 30% + 类药规则 20% + 结构安全 10%。
     * 每一项都能反查到具体指标，用户能看懂为什么 A 排在 B 前面。
     *
     * <p>序列类分子没有这些指标，返回 {@code null} 而不是 0 —— 0 分会让人以为"算出来很差"。
     */
    BigDecimal computePreScore(ChemistryDtos.MoleculeResult metrics) {
        if (metrics == null || !metrics.isOk() || metrics.getMetrics() == null) {
            return null;
        }
        var m = metrics.getMetrics();
        Double qed = doubleOf(m.get("QED"));
        Double complexity = doubleOf(m.get("COMPLEXITY_PENALTY"));
        Double lipinski = doubleOf(m.get("LIPINSKI_PASS"));
        Double pains = doubleOf(m.get("PAINS_HITS"));
        Double brenk = doubleOf(m.get("BRENK_HITS"));

        if (qed == null || complexity == null) {
            return null;
        }
        double safetyHits = (pains == null ? 0 : pains) + (brenk == null ? 0 : brenk);
        double score = 0.40 * qed
                + 0.30 * clamp01(1.0 - complexity / 3.0)
                + 0.20 * clamp01((lipinski == null ? 0 : lipinski) / 4.0)
                + 0.10 * clamp01(1.0 - Math.min(safetyHits, 3.0) / 3.0);
        return BigDecimal.valueOf(score * 100).setScale(3, RoundingMode.HALF_UP);
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private Double doubleOf(ChemistryDtos.MetricEntry entry) {
        return entry == null || entry.getValue() == null ? null : entry.getValue().doubleValue();
    }

    // ------------------------------------------------------------------ 参数映射

    /**
     * 约束 params.field → 计算层指标码。
     *
     * <p>只映射到<b>计算层真实提供</b>的指标；映射不上的一律走 SKIPPED，
     * 不猜、不用近似值顶替。
     */
    static String mapField(String field) {
        // 委托给公共映射表：最小评估用同一张表，避免两处慢慢漂移
        return MetricFieldMapper.mapField(field);
    }

    private String stringParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private BigDecimal numericParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
