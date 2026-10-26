package com.synpharm.design.domain.model;

/**
 * 需求基线的八块（06 文档 §3.2）。
 *
 * <p>生成任务 S0 会逐块校验完整性；缺 {@link #required} 的块则<b>不创建任务</b>，
 * 只返回缺项清单（「返回缺项清单，不创建子任务，项目状态不变」）。
 *
 * @author SynPharm Team
 */
public enum BaselineBlock {

    /** 块1：靶点列表 + 主靶点角色（必须恰好 1 个 isPrimary=1） */
    TARGETS("TARGETS", "靶点与主靶点", true),

    /** 块2：靶点结构 + 口袋（至少 1 个可用结构；口袋可选） */
    STRUCTURE("STRUCTURE", "靶点结构", true),

    /** 块3：分子形式（必填） */
    MODALITY("MODALITY", "分子形式", true),

    /** 块4：硬约束（≥1 条） */
    HARD_RULES("HARD_RULES", "硬约束", true),

    /** 块5：软约束 + 权重（可空） */
    SOFT_RULES("SOFT_RULES", "软约束", false),

    /** 块6：评估指标清单（≥1 条，且指标码在 metric_definition 存在） */
    METRICS("METRICS", "评估指标", true),

    /** 块7：配额 + min_candidates（配额 > 0，min_candidates ≥ 1） */
    QUOTA("QUOTA", "配额与最小候选数", true),

    /** 块8：风险关注点（可空） */
    RISKS("RISKS", "风险关注点", false);

    private final String code;
    private final String label;
    private final boolean required;

    BaselineBlock(String code, String label, boolean required) {
        this.code = code;
        this.label = label;
        this.required = required;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }
}
