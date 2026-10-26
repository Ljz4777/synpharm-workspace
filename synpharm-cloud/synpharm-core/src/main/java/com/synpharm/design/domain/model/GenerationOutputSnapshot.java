package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * generation_output_snapshot · 生成完整回放包（06 §4.2，S8）。
 *
 * <p>凭这个 snapshot 能原样重放一次生成：候选集、四段漏斗、簇分布、S3 计划、工具版本。
 * 做不到这一点，前面九步就只是演示。
 *
 * @author SynPharm Team
 */
@Data
@TableName("generation_output_snapshot")
public class GenerationOutputSnapshot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String snapshotNo;

    private Long runId;

    /** 候选集 v1 的 id 列表（JSON 数组） */
    private String candidateIds;

    /** 四段漏斗统计 */
    private String filterSummary;

    private String clusterDist;

    /** S3 完整计划 */
    private String planJson;

    private String toolVersions;

    private String contentHash;

    private LocalDateTime createdAt;
}