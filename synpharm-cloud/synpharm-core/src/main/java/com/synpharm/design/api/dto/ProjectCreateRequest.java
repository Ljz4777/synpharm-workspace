package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 创建项目请求（契约 {@code ProjectCreateRequest}）。
 *
 * <p>契约要求 {@code name} / {@code modalityId} / {@code targets} 必填，且
 * {@code targets} 至少一条、**恰好一个 {@code isPrimary=true}**。
 *
 * @author SynPharm Team
 */
@Data
public class ProjectCreateRequest {

    @NotBlank(message = "name 不能为空")
    @Size(max = 200, message = "name 最长 200 字符")
    private String name;

    private String goalText;

    /** 疾病，须属知识库 {@code DISEASE} 分类 */
    private Long diseaseId;

    /** 分子形式，须属知识库 {@code MODALITY} 分类 */
    @NotNull(message = "modalityId 不能为空")
    private Long modalityId;

    /** 作用机制码，须属知识库 {@code MOA} 分类（如 INHIBITOR / DEGRADER）；可空，缺失时基线只告警 */
    private String moaCode;

    /** 生成后置闸门：最少候选数（&lt;1 会导致基线块7 不完整，无法冻结） */
    private Integer minCandidates;

    /** GPU 小时配额；null 表示不限（基线告警），≤0 视为非法 */
    private BigDecimal quotaGpuHours;

    /** 湿实验名额；null 表示不限（基线告警），负数视为非法 */
    private Integer quotaWetLab;

    /** 决策名额档位：RATIO（按候选数比例，默认）/ ABSOLUTE（固定个数） */
    private String decisionCapacityMode;

    /** 档位参数：RATIO 时 0~1 的比例，ABSOLUTE 时正整数；仍受湿实验余额封顶 */
    private BigDecimal decisionCapacityValue;

    /** 绑定靶点，至少一条且恰好一个主靶点 */
    @NotNull(message = "targets 不能为空")
    private List<ProjectTargetBindRequest> targets;

    /** 优化目标：{@code [{dimension, operator, threshold, unit, weight}]} */
    private List<ObjectiveDto> objectives;

    /** 内联约束规则：同一事务内建约束集并激活 */
    private List<ConstraintRuleDto> constraintRules;

    /** 结构化目标指标（落 {@code goal_metric_json}） */
    private Map<String, Object> goalMetric;
}
