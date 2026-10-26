package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * edit_impact_assessment · E3 影响面评估（06 §5.2）。
 *
 * <p>回归守卫的落点：动作会不会把已达标维度打坏。BLOCK 的记录也要留 ——
 * 「这次被拦了、为什么被拦」本身就是要留痕的事实。
 *
 * @author SynPharm Team
 */
@Data
@TableName("edit_impact_assessment")
public class EditImpactAssessment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long gapReportId;
    private Long candidateId;
    private String actionCode;
    private String targetRegion;

    /** {items: [{dimension, risk, currentPassed, predictedDelta, note}]} */
    private String protectedJson;

    /** PASS / WARN / BLOCK */
    private String guardResult;
    private String blockReason;

    /** 负责人手动解锁 */
    private Integer overridden;
    private Long overrideBy;
    private String overrideNote;

    private LocalDateTime createdAt;
}
