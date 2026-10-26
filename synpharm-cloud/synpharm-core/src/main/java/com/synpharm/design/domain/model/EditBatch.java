package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * edit_batch · E4/E5 编辑批次（06 §5.2）。
 *
 * <p>{@code designSpaceJson} 要能回答「本轮一共枚举了多少、剪枝后剩多少」——
 * 不许人工拍脑袋定样本量。
 *
 * @author SynPharm Team
 */
@Data
@TableName("edit_batch")
public class EditBatch {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String batchNo;
    private Long projectId;
    private Long roundId;

    /** 本批基于哪个版本 */
    private Integer parentVersion;

    /** 本批使用的动作，逗号分隔 */
    private String actionCodes;

    /** 编辑预算 / 允许区域 / 枚举方式 */
    private String paramsJson;

    /** E4：规模 / 剪枝后 / 每动作方案数 */
    private String designSpaceJson;

    private String inputCandidateIds;
    private Integer outputVersionCount;
    private Integer filteredOutCount;

    /** PENDING / RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED */
    private String status;

    private Long jobId;
    private String errorMsg;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
