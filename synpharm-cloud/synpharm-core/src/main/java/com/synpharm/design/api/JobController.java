package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.JobResponse;
import com.synpharm.design.application.JobAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务端点（契约 4 个操作：列表 / 查询 / 取消 / SSE 流）。
 *
 * <p>SSE {@code GET /api/design/stream/{jobNo}} 需要 Redis 进度快照与网关侧长连接路由，
 * 尚未实现；轮询 {@code GET /jobs/{jobId}} 是可用的降级路径（契约本身也把它列为兜底）。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobAppService jobAppService;

    /** 任务列表 */
    @GetMapping
    public Result<PageResult<JobResponse>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(jobAppService.list(projectId, jobType, status, page, pageSize));
    }

    /** 查询任务状态；{@code jobId} 可为数字 ID 或 jobNo */
    @GetMapping("/{jobId}")
    public Result<JobResponse> get(@PathVariable String jobId) {
        return Result.success(jobAppService.get(jobId));
    }

    /** 取消任务；已终态返回 409 */
    @PostMapping("/{jobId}/cancel")
    public Result<Void> cancel(@PathVariable String jobId) {
        jobAppService.cancel(jobId);
        return Result.success(null);
    }
}
