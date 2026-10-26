package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.AcceptedReportResponse;
import com.synpharm.design.api.dto.ReportCreateRequest;
import com.synpharm.design.api.dto.ReportDecisionRequest;
import com.synpharm.design.api.dto.ReportResponse;
import com.synpharm.design.application.ReportAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 报告与审批端点（契约 5 个操作）。
 *
 * <p>{@code POST} 的 {@code Idempotency-Key} 落在 {@code design_job.uk_idempotent_key} 唯一索引上：
 * 同键且原任务未终态 → 409（{@code details.existingId}）；已终态 → 复用同一任务与同一份报告，
 * 重放不会产生第二份业务数据。
 *
 * @author SynPharm Team
 */
@Slf4j
@RestController
@RequestMapping("/api/design/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportAppService reportAppService;

    /** 报告列表：支持按项目与审批结论过滤 */
    @GetMapping
    public Result<PageResult<ReportResponse>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String decision,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(reportAppService.list(projectId, decision, page, pageSize));
    }

    /** 生成报告：202 受理，正文由计算层消费 {@code design.report} 装配 */
    @PostMapping
    public ResponseEntity<Result<AcceptedReportResponse>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReportCreateRequest request) {
        return ResponseEntity.accepted()
                .body(Result.success(reportAppService.create(request, idempotencyKey)));
    }

    /** 报告详情：含 bodyJson 与 bodyMd */
    @GetMapping("/{id}")
    public Result<ReportResponse> get(@PathVariable Long id) {
        return Result.success(reportAppService.get(id));
    }

    /** 审批：APPROVED / REVISE / REJECTED；REVISE 同事务插入修订版占位 */
    @PatchMapping("/{id}/decision")
    public Result<ReportDecisionRequest.Response> decide(
            @PathVariable Long id,
            @Valid @RequestBody ReportDecisionRequest request) {
        return Result.success(reportAppService.decide(id, request));
    }

    /** 导出报告：返回文件流（非统一信封），默认 markdown */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long id,
            @RequestParam(defaultValue = "markdown") String format) {
        ReportAppService.ExportPayload payload = reportAppService.export(id, format);
        String encoded = URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, payload.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + payload.fileName() + "\"; filename*=UTF-8''" + encoded)
                .body(payload.body());
    }
}
