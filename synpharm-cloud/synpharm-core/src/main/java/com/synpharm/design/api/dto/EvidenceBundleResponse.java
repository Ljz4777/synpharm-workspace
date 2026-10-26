package com.synpharm.design.api.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 证据包响应（07 §6）。
 *
 * <p>回传 {@code coversMetrics} 与 {@code missingMetrics}：加权分的分母只含有数据的指标，
 * 不把「缺了几项」显示出来的话，一个「4 个指标全满分」的候选会被误读成「全面优秀」。
 *
 * @author SynPharm Team
 */
@Data
public class EvidenceBundleResponse {

    private Long id;
    private String bundleNo;
    private Long candidateId;
    private Long baselineSnapshotId;
    private Long evaluationFreezeId;

    /** root → 当前 的血缘，只含 id 与编辑原因 */
    private List<Map<String, Object>> lineage;

    /** 逐条硬约束的复核结果 */
    private List<Map<String, Object>> constraintVerdicts;

    /** 项目风险关注点 */
    private List<Map<String, Object>> riskFlags;

    /** 客观遗漏项（必须验证但无实测证据） */
    private List<Map<String, Object>> pendingItems;

    private Integer coversMetrics;

    /** OK / PARTIAL / INCOMPLETE */
    private String completeness;

    private String contentHash;
}