package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * constraint_rule · 约束规则。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("constraint_rule")
public class ConstraintRule {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** bigint，非空 */
    private Long setId;

    /** 逻辑分组；同一 group_key 内按 group_logic 组合 */
    private String groupKey;

    /** 该组的组内逻辑 AND/OR */
    private String groupLogic;

    /** NUMERIC_RANGE/SMARTS_REQUIRED/SMARTS_FORBIDDEN/SCAFFOLD_REQUIRED/SCAFFOLD_FORBIDDEN/FILTER_CATALOG/SIMILARITY_RANGE/SIMILARITY_TO_SET/RING_COUNT/SUBSTITUENT_ALLOWED/RULE_SET */
    private String ruleType;

    /** HARD 淘汰 / SOFT 加权扣分 */
    private String severity;

    /** 评估维度：AFFINITY/.../IMMUNOGENICITY（驱动加权） */
    private String dimension;

    /** GT/GTE/LT/LTE/EQ/BETWEEN */
    private String operator;

    /** 阈值（单边） */
    private BigDecimal thresholdNum;

    /** 区间上界（operator=BETWEEN 时使用） */
    private BigDecimal thresholdMax;

    /**
     * 方法学误差带；NULL = 无容差。
     *
     * <p><b>为什么需要它</b>（06 文档 §3.5）：硬约束写「亲和力 ≤ 100 pM」而预测值 110 pM 时，
     * 「算不算达标」必须在立项时就定死 —— 否则会在报告阶段爆出两份结论相悖的报告。
     * 本字段与 {@link #boundaryPolicy} 一起，让这件事在<b>立项时</b>显式回答。
     */
    private BigDecimal tolerance;

    /**
     * 边界判定方式：
     * <ul>
     *   <li>{@code STRICT} —— 严格比较，110 &gt; 100 即不达标，综合分强制 0；</li>
     *   <li>{@code WITHIN_TOLERANCE} —— 110 ≤ 100 + tolerance 视为达标，但带 boundary_warning；</li>
     *   <li>{@code ESCALATE_TO_SOFT} —— 不进 hard_passed，转 soft 扣分，交决策阶段人工判断。</li>
     * </ul>
     * 判定逻辑由代码执行，<b>不允许 Agent 用「接近」来修饰</b>。
     */
    private String boundaryPolicy;

    /** 适用分子形式，逗号分隔；NULL = 全部 */
    private String appliesToModality;

    /** 阈值单位，如 nM / kcal/mol / Da */
    private String unit;

    /** SOFT 规则的扣分权重 */
    private Integer weight;

    /** 规则参数，结构随 rule_type 而定（见文件尾注释） */
    private String paramsJson;

    /** 人类可读描述，直接展示给用户（红线 R2：不做隐藏规则） */
    private String humanReadable;

    /** int，非空 */
    private Integer sortNo;

    /** tinyint，非空 */
    private Integer enabled;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

}
