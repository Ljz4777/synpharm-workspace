package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * edit_delta_report · E6 前后变化（06 §5.2）。
 *
 * <p>{@code residualJson} 是判断「是否进下一轮」的唯一依据，不是装饰字段。
 *
 * @author SynPharm Team
 */
@Data
@TableName("edit_delta_report")
public class EditDeltaReport {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String reportNo;
    private Long editBatchId;
    private Long candidateId;

    private Integer parentVersion;
    private Integer childVersion;

    /** 改前评估快照 */
    private Long parentFreezeId;

    /** 改后评估快照 */
    private Long childFreezeId;

    /** {dimension: {before, after, delta, threshold, ok}} */
    private String deltaJson;

    /** 剩余缺口 */
    private String residualJson;

    private String guardResult;

    /** PASS / PASS_WITH_RESIDUAL / FAIL */
    private String verdict;

    private Long agentReportId;

    private LocalDateTime createdAt;
}
