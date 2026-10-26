package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ExperimentCreateRequest;
import com.synpharm.design.api.dto.ExperimentResponse;
import com.synpharm.design.application.ExperimentAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 湿实验台账端点（契约 4 个同步操作）。
 *
 * <p>{@code POST /experiments/import}（CSV，202）与 {@code /experiments/feedback} 两族
 * 需要任务队列与校准管线，尚未实现。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentAppService experimentAppService;

    /** 实验台账 */
    @GetMapping
    public Result<PageResult<ExperimentResponse>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String assayType,
            @RequestParam(required = false) String resultStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(experimentAppService.list(projectId, assayType, resultStatus, page, pageSize));
    }

    /** 录入湿实验结果（同步，服务端算 errorDelta / isHit） */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<ExperimentResponse> create(@Valid @RequestBody ExperimentCreateRequest request) {
        return Result.success(experimentAppService.create(request));
    }

    /** 详情（含预测-实测对比） */
    @GetMapping("/{id}")
    public Result<ExperimentResponse> get(@PathVariable Long id) {
        return Result.success(experimentAppService.get(id));
    }

    /** 补录/更正结果：省略的可空字段保持原值 */
    @PatchMapping("/{id}")
    public Result<ExperimentResponse> patch(@PathVariable Long id,
                                            @Valid @RequestBody ExperimentCreateRequest request) {
        return Result.success(experimentAppService.patch(id, request));
    }
}
