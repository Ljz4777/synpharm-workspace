package com.synpharm.design.support.editing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 编辑阶段（E0~E7）的内部数据结构。
 *
 * @author SynPharm Team
 */
public final class EditDtos {

    private EditDtos() {
    }

    /**
     * 一条缺口。
     *
     * @param metricCode  导致缺口的指标
     * @param dimension   对齐 {@code edit_action_mapping.gap_dimension} 的维度
     * @param gapType     THRESHOLD_NOT_MET（有阈值未达标）/ NOT_EVALUATED（根本没算出来）
     * @param value       当前值；未评估时为 null
     * @param threshold   阈值；无阈值为 null
     * @param attribution 归因到结构特征 —— <b>最小评估阶段恒为 null</b>，
     *                    需要结构归因模型，当前没有
     * @param editable    当前手段能否修
     * @param difficulty  EASY / MEDIUM / HARD / UNFIXABLE
     * @param reason      可直接展示的中文说明
     */
    public record Gap(
            String metricCode,
            String dimension,
            String gapType,
            BigDecimal value,
            BigDecimal threshold,
            String attribution,
            boolean editable,
            String difficulty,
            String reason
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("metricCode", metricCode);
            m.put("dimension", dimension);
            m.put("gapType", gapType);
            m.put("value", value);
            m.put("threshold", threshold);
            // 归因为空时显式写出原因，而不是让前端看到一个沉默的 null
            m.put("attribution", attribution);
            m.put("attributionNote", attribution == null ? "结构归因需要归因模型，当前未接入" : null);
            m.put("editable", editable);
            m.put("difficulty", difficulty);
            m.put("reason", reason);
            return m;
        }
    }

    /**
     * E1 诊断结果。
     *
     * @param gaps             待修缺口（按建议顺序）
     * @param noGaps           已达标的维度 —— <b>保护名单</b>，E3 会拿它当回归守卫的输入
     * @param unfixable        当前手段修不了的缺口（不含在 gaps 里）
     * @param recommendedOrder 建议修复顺序（dimension 列表）
     */
    public record Diagnosis(
            List<Gap> gaps,
            List<String> noGaps,
            List<Gap> unfixable,
            List<String> recommendedOrder,
            String note
    ) {
    }

    /**
     * E2 匹配出的候选动作。
     *
     * @param gapDimension  对应缺口维度
     * @param actionCode    动作码（primary 或 fallback）
     * @param isFallback    是否为次选
     * @param successRate   历史成功率；{@code sampleSize==0} 时是估计值
     * @param sampleSize    统计样本量
     * @param sideEffects   副作用矩阵 {"AFFINITY":"MUST_DROP"}
     */
    public record ActionOption(
            String gapDimension,
            String actionCode,
            boolean isFallback,
            BigDecimal successRate,
            int sampleSize,
            Map<String, Object> sideEffects
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("gapDimension", gapDimension);
            m.put("actionCode", actionCode);
            m.put("isFallback", isFallback);
            m.put("successRate", successRate);
            m.put("sampleSize", sampleSize);
            m.put("sideEffects", sideEffects);
            // 成功率是估计值时必须显式标注，否则会被当成实测统计
            m.put("rateIsEstimate", sampleSize == 0);
            return m;
        }
    }

    /**
     * E3 影响面评估。
     *
     * @param guardResult PASS / WARN / BLOCK
     * @param protectedOnes 受影响维度的预测
     */
    public record Impact(
            String actionCode,
            String guardResult,
            String blockReason,
            List<ProtectedImpact> protectedOnes
    ) {
    }

    /**
     * 一个受保护维度的预测影响。
     *
     * @param dimension        维度
     * @param risk             MUST_DROP（会掉） / WATCH（可能掉）
     * @param currentPassed    该维度当前是否已达标
     * @param predictedDelta   预测变化；<b>最小评估阶段恒为 null</b>，
     *                         需要「动作→指标」的量化模型，当前没有
     * @param note             说明
     */
    public record ProtectedImpact(
            String dimension,
            String risk,
            boolean currentPassed,
            BigDecimal predictedDelta,
            String note
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("dimension", dimension);
            m.put("risk", risk);
            m.put("currentPassed", currentPassed);
            m.put("predictedDelta", predictedDelta);
            m.put("note", note);
            return m;
        }
    }
}
