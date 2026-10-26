package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报告审批请求（契约 {@code ReportDecisionRequest}）。
 *
 * @author SynPharm Team
 */
@Data
public class ReportDecisionRequest {

    @NotBlank(message = "decision 不能为空")
    private String decision;

    /** REVISE / REJECTED 必填 */
    private String note;

    /** 乐观锁占位：design_report 无 version 列（有 version_no，语义不同），当前不校验 */
    private Integer version;

    /** 审批响应（契约 {@code ReportDecisionResponse}） */
    @Data
    public static class Response {

        private Long id;

        private String decision;

        private Long decisionBy;

        private LocalDateTime decisionAt;

        private String decisionNote;

        private Integer versionNo;

        /** REVISE 时同事务插入的修订版占位报告 ID；其它结论为 null */
        private Long revisionReportId;
    }
}
