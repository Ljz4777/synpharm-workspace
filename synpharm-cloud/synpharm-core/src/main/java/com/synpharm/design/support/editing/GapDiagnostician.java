package com.synpharm.design.support.editing;

import com.synpharm.design.support.evaluation.EvaluationDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * E1 缺口诊断：把一份评估快照变成「要修什么、能不能修、建议顺序」。
 *
 * <p><b>保护名单是这里最重要的产出</b>：已达标的维度必须显式列出，因为 E3 的回归守卫
 * 拿它当输入 —— 一个编辑动作如果会把已达标维度打坏，就该被 BLOCK。
 * 只输出「缺什么」而不输出「有什么」，守卫就无从判断。
 *
 * <p><b>归因恒为空</b>：把缺口归因到具体结构特征（「H3 第 102 位疏水暴露导致聚集」）
 * 需要结构归因模型，当前没有。塞一个猜测进去会让用户以为系统看懂了结构。
 *
 * @author SynPharm Team
 */
@Component
public class GapDiagnostician {

    /**
     * @param scores 评估冻结快照里的指标分数
     */
    public EditDtos.Diagnosis diagnose(Map<String, EvaluationDtos.MetricScore> scores) {
        List<EditDtos.Gap> gaps = new ArrayList<>();
        List<EditDtos.Gap> unfixable = new ArrayList<>();
        List<String> noGaps = new ArrayList<>();

        for (Map.Entry<String, EvaluationDtos.MetricScore> e : scores.entrySet()) {
            String metricCode = e.getKey();
            EvaluationDtos.MetricScore score = e.getValue();
            String dimension = GapDimensionMapper.of(metricCode);

            if (Boolean.TRUE.equals(score.passed())) {
                noGaps.add(dimension);
                continue;
            }

            EditDtos.Gap gap = toGap(metricCode, dimension, score);
            if (gap.editable()) {
                gaps.add(gap);
            } else {
                unfixable.add(gap);
            }
        }

        // 建议顺序：可修的在前，易修（EASY）优先，再按维度名稳定排序保证可复现
        gaps.sort(Comparator
                .comparingInt((EditDtos.Gap g) -> difficultyRank(g.difficulty()))
                .thenComparing(EditDtos.Gap::dimension)
                .thenComparing(EditDtos.Gap::metricCode));

        List<String> order = new ArrayList<>();
        for (EditDtos.Gap g : gaps) {
            if (!order.contains(g.dimension())) {
                order.add(g.dimension());
            }
        }

        String note = unfixable.isEmpty() ? null
                : "有 " + unfixable.size() + " 项缺口当前手段修不了（映射表里没有可信的定向动作），"
                  + "已单列，不计入建议修复顺序";
        return new EditDtos.Diagnosis(List.copyOf(gaps), List.copyOf(noGaps),
                List.copyOf(unfixable), List.copyOf(order), note);
    }

    private EditDtos.Gap toGap(String metricCode, String dimension, EvaluationDtos.MetricScore score) {
        boolean editable = GapDimensionMapper.isEditableDimension(dimension);

        // passed=null 有两种成因：没配置阈值（无法判定）或指标根本没算出来（value=null）。
        // 后者更严重，必须与「算了但不及格」区分开
        boolean notEvaluated = score.value() == null;
        String gapType = notEvaluated ? "NOT_EVALUATED" : "THRESHOLD_NOT_MET";

        String difficulty = !editable ? "UNFIXABLE"
                : notEvaluated ? "HARD"
                : "MEDIUM";

        String reason;
        if (notEvaluated) {
            reason = "该指标未算出值（" + (score.note() == null ? "计算层未返回" : score.note())
                    + "），无法判断是否达标";
        } else if (score.passed() == null) {
            reason = "该指标未配置判定阈值，无法判断是否达标";
        } else {
            reason = score.note() == null ? "未达标" : score.note();
        }

        return new EditDtos.Gap(metricCode, dimension, gapType, score.value(), null,
                null, editable, difficulty, reason);
    }

    private static int difficultyRank(String difficulty) {
        return switch (difficulty == null ? "" : difficulty) {
            case "EASY" -> 0;
            case "MEDIUM" -> 1;
            case "HARD" -> 2;
            default -> 3;
        };
    }

    /** 用历史成功率反推难度：成功率越低越难，样本量为 0 时一律按 MEDIUM（先验不足） */
    public String difficultyOf(BigDecimal successRate, int sampleSize) {
        if (successRate == null) {
            return "MEDIUM";
        }
        if (sampleSize == 0) {
            return "MEDIUM";
        }
        double rate = successRate.doubleValue();
        if (rate >= 0.5) {
            return "EASY";
        }
        return rate >= 0.3 ? "MEDIUM" : "HARD";
    }
}
