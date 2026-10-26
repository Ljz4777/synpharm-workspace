package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 生成逐分子日志（诊断页用）。
 *
 * <p>把 {@code generation_candidate_log} 暴露出来是刻意的：
 * 用户要能回答「这个分子为什么没进候选池」，而不是只看到一个总数。
 *
 * @author SynPharm Team
 */
@Data
public class GenerationCandidateLogResponse {

    private Long id;
    private String stage;
    private String outcome;
    private String smiles;
    private String sequenceHash;
    private String inchikey;
    private Long candidateId;

    /** 结构化原因（JSON 字符串，前端自行解析渲染） */
    private String filterReason;

    private BigDecimal preScore;
    private String clusterId;
    private Long dedupInto;
    private LocalDateTime createdAt;
}
