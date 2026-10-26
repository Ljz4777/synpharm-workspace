package com.synpharm.design.application;

import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.AcceptedReportResponse;
import com.synpharm.design.api.dto.ReportCreateRequest;
import com.synpharm.design.api.dto.ReportDecisionRequest;
import com.synpharm.design.api.dto.ReportResponse;

/**
 * 报告与审批用例。
 *
 * <p>{@code status}（正文生成到哪一步）与 {@code decision}（人怎么批）是两条正交的线：
 * 正文没生成完就不该被审批，所以决策前先校验 {@code status}。
 *
 * @author SynPharm Team
 */
public interface ReportAppService {

    /** 报告列表（按项目/结论过滤） */
    PageResult<ReportResponse> list(Long projectId, String decision, int page, int pageSize);

    /** 报告详情（bodyJson + bodyMd） */
    ReportResponse get(Long id);

    /** 生成报告（202）：登记报告 + 登记任务 + 投递 {@code design.report}，正文由计算层装配 */
    AcceptedReportResponse create(ReportCreateRequest request, String idempotencyKey);

    /** 审批：APPROVED / REVISE / REJECTED；REVISE 同事务插入修订版占位 */
    ReportDecisionRequest.Response decide(Long id, ReportDecisionRequest request);

    /** 导出：markdown / json / pdf */
    ExportPayload export(Long id, String format);

    /**
     * 导出产物。
     *
     * @param contentType 响应的 Content-Type
     * @param fileName    下载文件名
     * @param body        文件内容
     */
    record ExportPayload(String contentType, String fileName, byte[] body) {
    }
}
