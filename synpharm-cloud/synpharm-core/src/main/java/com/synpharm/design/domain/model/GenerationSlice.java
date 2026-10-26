package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * generation_slice · 生成分片（06 §4.2，S4）。
 *
 * <p>分片是「可独立重试的最小执行单元」：一片失败不影响其他片，
 * 全部分片失败才是任务失败。{@code cacheHit=1} 的片复用了历史产物，**不消耗配额**。
 *
 * @author SynPharm Team
 */
@Data
@TableName("generation_slice")
public class GenerationSlice {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long jobId;

    /** 片序号，从 1 开始 */
    private Integer sliceNo;

    private Long seed;

    /** 参数哈希（缓存键之一） */
    private String paramHash;

    /** 输入哈希（缓存键之一） */
    private String inputHash;

    private Long toolId;

    private String toolVersion;

    /** PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED */
    private String status;

    private Integer retryCount;

    private Integer producedCount;

    private BigDecimal gpuHoursUsed;

    /** 1=复用缓存，不消耗配额 */
    private Integer cacheHit;

    private String artifactUri;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
}
