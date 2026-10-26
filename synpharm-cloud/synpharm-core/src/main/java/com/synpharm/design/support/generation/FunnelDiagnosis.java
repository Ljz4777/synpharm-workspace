package com.synpharm.design.support.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S9 漏斗解读与建议（06 §4.1，Agent② 介入点）。
 *
 * <p><b>verdict 由这段代码判定，不由模型判定</b>（设计原文的硬约束）。
 * 这里的理由很直接：如果 LLM 能自己决定 PASS/FAIL，它就能把一次失败说成成功，
 * 而"能不能进下一步"是个必须有唯一答案的判断。LLM 只写解释性文案。
 *
 * <p>四段漏斗转化率是这份诊断的核心：它不是给人看的漂亮图，而是
 * 「下一轮该调什么」的依据 —— 去重掉太多说明采样同质，粗筛掉太多说明约束或策略不匹配。
 *
 * @author SynPharm Team
 */
public final class FunnelDiagnosis {

    public static final String PASS = "PASS";
    public static final String PASS_WITH_WARNING = "PASS_WITH_WARNING";
    public static final String FAIL = "FAIL";

    private FunnelDiagnosis() {
    }

    /** 漏斗四段 */
    public record Funnel(
            int generated,
            int deduped,
            int preFiltered,
            int quotaCut,
            int finalCount,
            Map<String, Long> filterReasonTop
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("generated", generated);
            m.put("afterDedup", deduped);
            m.put("afterPreFilter", preFiltered);
            m.put("afterQuota", finalCount);
            m.put("quotaCut", quotaCut);
            m.put("dedupRate", rate(generated, deduped));
            m.put("preFilterPassRate", rate(deduped, preFiltered));
            m.put("quotaKeepRate", rate(preFiltered, finalCount));
            m.put("overallYield", rate(generated, finalCount));
            m.put("filterReasonTop", filterReasonTop);
            return m;
        }

        private static Double rate(int from, int to) {
            if (from <= 0) {
                return null;
            }
            return Math.round(to * 10000.0 / from) / 10000.0;
        }
    }

    /** 一条诊断发现 */
    public record Finding(String level, String message, String suggestion) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("message", message);
            if (suggestion != null) {
                m.put("suggestion", suggestion);
            }
            return m;
        }
    }

    /** 诊断结果 */
    public record Diagnosis(
            String verdict,
            Funnel funnel,
            List<Finding> findings,
            boolean readyForEdit,
            String recommendedNext
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", verdict);
            m.put("funnel", funnel.toMap());
            List<Map<String, Object>> fs = new ArrayList<>();
            for (Finding f : findings) {
                fs.add(f.toMap());
            }
            m.put("findings", fs);
            m.put("readyForEdit", readyForEdit);
            m.put("recommendedNext", recommendedNext);
            return m;
        }
    }

    /**
     * 计算诊断。
     *
     * @param minCandidates 基线块7 的闸门阈值
     * @param validClusters S7 的有效簇数
     */
    public static Diagnosis diagnose(Funnel funnel, int minCandidates, int validClusters,
                                     List<String> stageWarnings) {
        List<Finding> findings = new ArrayList<>();
        String verdict;
        boolean readyForEdit;
        String next;

        int finalCount = funnel.finalCount();

        if (finalCount == 0) {
            verdict = FAIL;
            readyForEdit = false;
            next = "禁止进入编辑：回 S2 检查搜索空间与种子，或回 S3 更换策略（最多 2 轮）";
            findings.add(new Finding("ERROR", "候选数为 0，不能往下游流（平台可信度的底线）",
                    "检查种子是否为空、约束是否过严、计算层是否可用"));
        } else if (finalCount < minCandidates) {
            verdict = PASS_WITH_WARNING;
            readyForEdit = true;
            next = "允许进入编辑，但候选数低于闸门（" + finalCount + " < " + minCandidates
                    + "）；建议回 S2/S3 重跑以补足";
            findings.add(new Finding("WARN",
                    "候选数 " + finalCount + " 低于基线要求的最小值 " + minCandidates,
                    "扩大种子集或放宽过严的硬约束后重跑"));
        } else if (validClusters < DiversityQuotaStage.MIN_VALID_CLUSTERS) {
            verdict = PASS_WITH_WARNING;
            readyForEdit = true;
            next = "允许进入编辑，但多样性不足（有效簇 " + validClusters + " < "
                    + DiversityQuotaStage.MIN_VALID_CLUSTERS + "）；建议调整策略模板";
            findings.add(new Finding("WARN",
                    "有效簇数仅 " + validClusters + "，候选池覆盖不足",
                    "换更分散的种子或调整突变范围"));
        } else {
            verdict = PASS;
            readyForEdit = true;
            next = "可以进入编辑";
        }

        // 漏斗归因：哪一段掉得最狠
        if (funnel.generated() > 0) {
            double dedupLoss = 1.0 - (double) funnel.deduped() / funnel.generated();
            if (dedupLoss > 0.7) {
                findings.add(new Finding("WARN",
                        "去重阶段淘汰了 " + Math.round(dedupLoss * 100) + "% 的候选",
                        "采样同质化严重：种子的结构差异太小，或突变范围过窄"));
            }
            double preFilterLoss = funnel.deduped() > 0
                    ? 1.0 - (double) funnel.preFiltered() / funnel.deduped() : 0;
            if (preFilterLoss > 0.8) {
                findings.add(new Finding("WARN",
                        "粗筛阶段淘汰了 " + Math.round(preFilterLoss * 100) + "% 的候选",
                        "硬约束可能过严，或种子本身成药性偏差；查看 filterReasonTop 定位主因"));
            }
        }

        if (!funnel.filterReasonTop().isEmpty()) {
            Map.Entry<String, Long> top = funnel.filterReasonTop().entrySet().iterator().next();
            findings.add(new Finding("INFO",
                    "最主要的淘汰原因是 " + top.getKey() + "（" + top.getValue() + " 个）",
                    null));
        }

        for (String warning : stageWarnings) {
            findings.add(new Finding("WARN", warning, null));
        }

        return new Diagnosis(verdict, funnel, findings, readyForEdit, next);
    }

    /** 把逐条筛选日志聚合成「原因 → 次数」，降序取前 5 */
    public static Map<String, Long> topReasons(Map<String, Long> countsByReason, int limit) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(countsByReason.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<String, Long> top = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            top.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return top;
    }
}
