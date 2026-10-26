package com.synpharm.design.support.decision;

import com.synpharm.common.support.JsonCodec;
import com.synpharm.design.domain.model.BaselineSnapshot;
import com.synpharm.design.domain.model.CandidateMolecule;
import com.synpharm.design.domain.model.EvaluationFreeze;
import com.synpharm.design.domain.model.MoleculeEditLog;
import com.synpharm.design.domain.model.ProjectRiskItem;
import com.synpharm.design.infrastructure.persistence.CandidateMoleculeMapper;
import com.synpharm.design.infrastructure.persistence.MoleculeEditLogMapper;
import com.synpharm.design.support.BoundaryJudge;
import com.synpharm.design.support.Hashes;
import com.synpharm.design.support.evaluation.EvaluationContextBuilder;
import com.synpharm.design.support.evaluation.EvaluationDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D1 · 证据打包。
 *
 * <p><b>只读 {@code evaluation_freeze}，不读候选当前状态</b>（07 §1）。候选的分数可能
 * 在冻结之后又被重算过，读当前值会让同一份决策记录在不同时间重放得出不同结论。
 *
 * <p><b>只装引用与摘要，不装决策产物</b>（A2）：这里没有任何「选它/不选它」的判断，
 * 判断在 D3~D5。混在一起的话，回放时无法区分「当时的证据」与「当时的结论」。
 *
 * <p><b>同一份冻结快照装配两次必须得到同一个 contentHash</b>：{@link #assemble} 的返回值
 * 只由入参决定，不含时间戳；{@link #contentHashOf} 把参与哈希的字段按固定顺序序列化。
 * 这条性质是 {@code decision_record.inputSnapshotHash} 能当回放指纹的前提。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvidenceBundleAssembler {

    /** 血缘最多回溯这么多代：再长基本是脚本刷出来的，保护性截断比无限递归好 */
    private static final int MAX_LINEAGE_DEPTH = 20;

    /** 复核判定为「无法复核」时的 outcome：不是通过，也不是不达标 */
    public static final String OUTCOME_NOT_EVALUATED = "NOT_EVALUATED";

    private final JsonCodec jsonCodec;
    private final EvaluationContextBuilder contextBuilder;
    private final BoundaryJudge boundaryJudge;
    private final CandidateMoleculeMapper candidateMapper;
    private final MoleculeEditLogMapper editLogMapper;

    /**
     * 装配一份证据包。
     *
     * @param candidate       被评估的候选（取血缘与版本）
     * @param freeze          评估冻结快照（分数来源，唯一可信来源）
     * @param snapshot        需求基线快照（判定阈值来源）
     * @param risks           项目风险关注点（推导 pendingItems）
     * @param experimentCount 该候选已有的实测记录数；0 表示「必须验证」的风险点都还没有证据
     */
    public DecisionDtos.AssembledBundle assemble(CandidateMolecule candidate,
                                                 EvaluationFreeze freeze,
                                                 BaselineSnapshot snapshot,
                                                 List<ProjectRiskItem> risks,
                                                 int experimentCount) {

        EvaluationDtos.EvalContext context = contextBuilder.build(snapshot);
        Map<String, ScoreView> scores = readScores(freeze.getScoresJson());

        List<DecisionDtos.MetricPoint> metrics = buildMetricPoints(context, scores);
        int covers = (int) metrics.stream().filter(DecisionDtos.MetricPoint::hasValue).count();
        List<String> missing = metrics.stream()
                .filter(p -> !p.hasValue())
                .map(DecisionDtos.MetricPoint::code)
                .toList();

        List<DecisionDtos.ConstraintVerdict> verdicts = recheck(context, scores);
        List<DecisionDtos.RiskFlag> riskFlags = buildRiskFlags(risks);
        List<DecisionDtos.PendingItem> pending = buildPendingItems(riskFlags, experimentCount);
        List<DecisionDtos.LineageStep> lineage = buildLineage(candidate);

        String completeness = completenessOf(covers, metrics.size(),
                verdicts.stream().anyMatch(v -> !v.hardPassed()), missing);

        DecisionDtos.Assembly assembly = new DecisionDtos.Assembly(
                lineage, verdicts, riskFlags, pending, metrics,
                covers, metrics.size(), missing, completeness);

        return new DecisionDtos.AssembledBundle(
                lineage, verdicts, riskFlags, pending, metrics,
                covers, metrics.size(), missing, completeness, contentHashOf(assembly));
    }

    /**
     * 参与回放的内容哈希。
     *
     * <p>不含时间、不含 id、不含结论 —— 这三样一变，同一份证据就会算出不同指纹，
     * 回放校验随即变得没有意义。
     */
    public String contentHashOf(DecisionDtos.Assembly assembly) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("lineage", assembly.lineageMaps());
        canonical.put("constraintVerdicts", assembly.verdictMaps());
        canonical.put("riskFlags", assembly.riskFlagMaps());
        canonical.put("pendingItems", assembly.pendingItemMaps());
        canonical.put("metrics", assembly.metricMaps());
        canonical.put("coversMetrics", assembly.coversMetrics());
        canonical.put("missingMetrics", assembly.missingMetrics());
        canonical.put("completeness", assembly.completeness());
        return Hashes.sha256(jsonCodec.write(canonical));
    }

    // ------------------------------------------------------------------ 指标点

    private List<DecisionDtos.MetricPoint> buildMetricPoints(EvaluationDtos.EvalContext context,
                                                             Map<String, ScoreView> scores) {
        List<DecisionDtos.MetricPoint> points = new ArrayList<>();
        for (EvaluationDtos.ScoredMetric m : context.metrics()) {
            ScoreView s = scores.get(m.code());
            points.add(new DecisionDtos.MetricPoint(
                    m.code(), m.weight(),
                    s == null ? null : s.value(),
                    s == null ? null : s.normalized()));
        }
        return points;
    }

    // ------------------------------------------------------------------ D2 复核

    /**
     * 技术性复核：用**冻结快照里的原始值**重判一遍硬约束。
     *
     * <p>为什么不采信生成/编辑阶段的结论：那两个阶段的判定可能基于旧阈值，
     * 或干脆没记原因。本阶段以自己的重算为准，不一致时由调用方记 {@code verdict_drift}。
     */
    private List<DecisionDtos.ConstraintVerdict> recheck(EvaluationDtos.EvalContext context,
                                                         Map<String, ScoreView> scores) {
        List<DecisionDtos.ConstraintVerdict> list = new ArrayList<>();
        for (EvaluationDtos.HardRuleView rule : context.hardRules()) {
            ScoreView score = rule.metricCode() == null ? null : scores.get(rule.metricCode());
            BigDecimal actual = score == null ? null : score.value();

            // 快照里没有这个指标的值 → 不能判「通过」，也不能含糊地说「不达标」。
            // 单列 NOT_EVALUATED：桶仍然按 FAIL 处理（没验证过的硬约束不允许放行），
            // 但淘汰原因要指向「数据缺失」，而不是「分子不行」。
            if (actual == null) {
                list.add(new DecisionDtos.ConstraintVerdict(
                        parseLong(rule.code()),
                        rule.label(),
                        rule.metricCode(),
                        rule.operator(),
                        rule.min(),
                        rule.max(),
                        rule.tolerance(),
                        rule.policy(),
                        OUTCOME_NOT_EVALUATED,
                        false,
                        null,
                        rule.metricCode() == null
                                ? "规则无法映射到指标，无法复核"
                                : "冻结快照里没有指标 " + rule.metricCode() + " 的值，无法复核该硬约束"));
                continue;
            }

            BoundaryJudge.Verdict v = boundaryJudge.judge(
                    actual, rule.operator(), rule.min(), rule.max(),
                    rule.tolerance(), rule.policy());

            list.add(new DecisionDtos.ConstraintVerdict(
                    parseLong(rule.code()),
                    rule.label(),
                    rule.metricCode(),
                    rule.operator(),
                    rule.min(),
                    rule.max(),
                    rule.tolerance(),
                    v.getPolicy(),
                    v.getOutcome(),
                    v.isHardPassed(),
                    v.getDeviation(),
                    v.getReason()));
        }
        return list;
    }

    // ------------------------------------------------------------------ 风险与遗漏

    private List<DecisionDtos.RiskFlag> buildRiskFlags(List<ProjectRiskItem> risks) {
        List<DecisionDtos.RiskFlag> flags = new ArrayList<>();
        if (risks == null) {
            return flags;
        }
        for (ProjectRiskItem r : risks) {
            if (r.getDeleted() != null && r.getDeleted() != 0) {
                continue;
            }
            flags.add(new DecisionDtos.RiskFlag(
                    r.getRiskCode(),
                    r.getSeverity() == null ? "MEDIUM" : r.getSeverity(),
                    r.getMustVerify() != null && r.getMustVerify() == 1,
                    r.getSource() == null ? "MANUAL" : r.getSource()));
        }
        return flags;
    }

    /**
     * 客观遗漏项 = 「必须验证」但没有实测证据的风险点。
     *
     * <p>这与「决策者认为还该测什么」是两回事：后者是意见，进 {@code decision_note}，
     * 不进证据包。这里只认项目上已经声明的 {@code must_verify}。
     */
    private List<DecisionDtos.PendingItem> buildPendingItems(List<DecisionDtos.RiskFlag> flags,
                                                            int experimentCount) {
        List<DecisionDtos.PendingItem> items = new ArrayList<>();
        if (experimentCount > 0) {
            return items;
        }
        for (DecisionDtos.RiskFlag f : flags) {
            if (!f.mustVerify()) {
                continue;
            }
            items.add(new DecisionDtos.PendingItem(
                    f.riskCode() + " 实测验证",
                    "项目标记为必须验证，当前没有任何实测记录"));
        }
        return items;
    }

    // ------------------------------------------------------------------ 血缘

    /**
     * root → 当前 的血缘。只存 {@code molId} 与编辑原因，不拷贝结构。
     *
     * <p>遇到断链或超过深度上限就停在已有部分：血缘不完整是事实，
     * 直接截断比抛错更合适（证据包的 {@code completeness} 会体现缺口）。
     */
    private List<DecisionDtos.LineageStep> buildLineage(CandidateMolecule candidate) {
        List<DecisionDtos.LineageStep> chain = new ArrayList<>();
        CandidateMolecule cursor = candidate;
        int guard = 0;

        while (cursor != null && guard++ < MAX_LINEAGE_DEPTH) {
            chain.add(new DecisionDtos.LineageStep(
                    cursor.getId(),
                    editReasonOf(cursor.getId()),
                    cursor.getOriginType() == null ? "GENERATED" : cursor.getOriginType()));
            Long parentId = cursor.getParentMolId();
            if (parentId == null) {
                break;
            }
            cursor = candidateMapper.selectById(parentId);
        }

        Collections.reverse(chain);
        return chain;
    }

    /** 该分子是被什么编辑动作产出的（来自 molecule_edit_log 的可读描述） */
    private String editReasonOf(Long molId) {
        if (molId == null) {
            return null;
        }
        List<MoleculeEditLog> logs = editLogMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<MoleculeEditLog>lambdaQuery()
                        .eq(MoleculeEditLog::getChildMolId, molId)
                        .orderByDesc(MoleculeEditLog::getId)
                        .last("limit 1"));
        if (logs == null || logs.isEmpty()) {
            return null;
        }
        MoleculeEditLog log = logs.get(0);
        return log.getDescribeText() != null ? log.getDescribeText() : log.getEditType();
    }

    // ------------------------------------------------------------------ 完整性

    /**
     * 完整性等级。
     *
     * <p>注意「缺指标」在本系统是常态而不是异常（小分子 10 个指标只有 4 个算得出来），
     * 所以缺指标只降级为 {@code PARTIAL}，不报错也不阻断 —— 阻断会让整个决策阶段无法使用。
     * 真正会阻断的是「一个指标都没有」，那说明冻结快照本身是空的。
     */
    private String completenessOf(int covers, int total, boolean anyHardFailed, List<String> missing) {
        if (covers == 0) {
            return "INCOMPLETE";
        }
        if (anyHardFailed || !missing.isEmpty()) {
            return "PARTIAL";
        }
        return "OK";
    }

    // ------------------------------------------------------------------ 工具

    /** 冻结快照里的一个分数点 */
    private record ScoreView(BigDecimal value, BigDecimal normalized) {
    }

    private Map<String, ScoreView> readScores(String scoresJson) {
        Map<String, ScoreView> map = new LinkedHashMap<>();
        Map<String, Object> root = jsonCodec.readMap(scoresJson);
        if (root == null) {
            return map;
        }
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            map.put(e.getKey(), new ScoreView(decimalOf(m, "value"), decimalOf(m, "normalized")));
        }
        return map;
    }

    private static BigDecimal decimalOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
