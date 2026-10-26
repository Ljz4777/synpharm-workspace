package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * edit_action_mapping · 缺口→动作映射（06 §5.3）。
 *
 * <p>这是全篇最重要的一张表：编辑引擎、回归守卫、降级路径全部从它读，
 * 不在代码里硬编码「什么缺口用什么动作」。
 *
 * <p>{@code gapCause} 用空串而非 NULL —— MySQL 唯一键对 NULL 不去重，
 * 种子会被反复插入。
 *
 * @author SynPharm Team
 */
@Data
@TableName("edit_action_mapping")
public class EditActionMapping {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** AFFINITY / AGGREGATION / EXPRESSION / IMMUNOGENICITY / … */
    private String gapDimension;

    /** 归因码；空串表示不限 */
    private String gapCause;

    /** 首选动作：MUTATION / SIDE_CHAIN / HUMANIZATION / … */
    private String primaryAction;

    /** 次选动作 */
    private String fallbackAction;

    /** 适用形式，逗号分隔；{@code *} 表示全部 */
    private String appliesToModality;

    /** 需要的结构条件，JSON 数组 */
    private String requiredStructures;

    /** 副作用矩阵 {"AFFINITY":"MUST_DROP"} */
    private String sideEffectsJson;

    /** 历史成功率；sampleSize=0 时是估计值 */
    private BigDecimal successRate;

    /** 统计样本量；0 表示成功率是估计值而非实测 */
    private Integer sampleSize;

    private Integer priority;
    private Integer enabled;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
