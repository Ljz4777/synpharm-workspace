package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.AcceptedNextRoundResponse;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.LeaderboardResponse;
import com.synpharm.design.api.dto.NextRoundRequest;
import com.synpharm.design.api.dto.ProjectCreatedResponse;
import com.synpharm.design.api.dto.ProjectCreateRequest;
import com.synpharm.design.api.dto.ProjectPatchRequest;
import com.synpharm.design.api.dto.ProjectResponse;
import com.synpharm.design.api.dto.ProjectTargetBindRequest;
import com.synpharm.design.api.dto.ProjectTargetResponse;
import com.synpharm.design.api.dto.RoundOpenRequest;
import com.synpharm.design.api.dto.RoundResponse;
import com.synpharm.design.application.LeaderboardAppService;
import com.synpharm.design.application.ProjectAppService;
import com.synpharm.design.application.RoundAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 设计域 · 项目与立项。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/projects")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 项目", description = "立项、编辑、靶点绑定、约束集绑定")
public class ProjectController {

    private final ProjectAppService projectAppService;
    private final RoundAppService roundAppService;
    private final LeaderboardAppService leaderboardAppService;

    @GetMapping("/{id}/leaderboard")
    @Operation(summary = "排行榜", description = """
            按加权总分（默认）或任一「本轮启用过的指标」排序。
            响应必带 weightsSnapshot 与 redistributed（红线 R1：名次必须可复现、缺失指标必须说明）。
            偏页 > 1000 时返回 409 USE_CURSOR，改用 afterScore + afterId 键集分页（此时响应不含 total）。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、VALIDATION_FAILED(40000)、USE_CURSOR(40952)、UNAUTHORIZED(40100)。""")
    public Result<LeaderboardResponse> leaderboard(
            @PathVariable Long id,
            @RequestParam Long roundId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) BigDecimal afterScore,
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 从 1 开始") int page,
            @RequestParam(defaultValue = "50") @Min(value = 1, message = "pageSize 最小 1")
            @Max(value = 200, message = "pageSize 最大 200") int pageSize) {
        return Result.success(leaderboardAppService.leaderboard(
                id, roundId, sortBy, order, afterScore, afterId, page, pageSize));
    }

    @GetMapping
    @Operation(summary = "我的项目列表", description = "可能返回：VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。")
    public Result<PageResult<ProjectResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize 最小 1")
            @Max(value = 200, message = "pageSize 最大 200") int pageSize) {
        return Result.success(projectAppService.list(status, keyword, page, pageSize));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建立项", description = """
            校验顺序：name 非空 → modalityId 属 MODALITY → targets 至少 1 且恰好 1 个主靶点
            → 主靶点唯一键前置校验(DESIGN_PRIMARY_TARGET_EXISTS 40944) → 目标维度属 8 大维度。
            响应含 readyBlockers，显式列出进入 READY 还缺什么（红线 R2：不得静默就绪）。
            内联 constraintRules 会在同一事务内建约束集并激活；规则非法则整体回滚(40040)。""")
    public Result<ProjectCreatedResponse> create(@Valid @RequestBody ProjectCreateRequest request) {
        return Result.success(projectAppService.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情（含靶点/轮次/候选统计）", description = "不存在或非本人返回 DESIGN_PROJECT_NOT_FOUND(40440)。")
    public Result<ProjectResponse> get(@PathVariable Long id) {
        return Result.success(projectAppService.get(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "编辑项目", description = """
            仅 DRAFT / READY 可改，否则 DESIGN_PROJECT_STATUS_INVALID(40941)。
            注意 {@code version} 字段当前只接收不校验：design_project 没有 version 列。""")
    public Result<ProjectResponse> update(@PathVariable Long id,
                                         @Valid @RequestBody ProjectPatchRequest request) {
        return Result.success(projectAppService.update(id, request));
    }

    @PostMapping("/{id}/targets")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "绑定靶点", description = """
            重复绑定返回 DESIGN_TARGET_DUPLICATED(40943)；
            已有主靶点时再绑主靶点返回 DESIGN_PRIMARY_TARGET_EXISTS(40944)；
            项目已冻结返回 DESIGN_PROJECT_STATUS_INVALID(40941)。""")
    public Result<ProjectTargetResponse> bindTarget(@PathVariable Long id,
                                                   @RequestBody ProjectTargetBindRequest request) {
        return Result.success(projectAppService.bindTarget(id, request));
    }

    @DeleteMapping("/{id}/targets/{targetId}")
    @Operation(summary = "解绑靶点", description = "未绑定该靶点返回 TARGET_NOT_FOUND(40441)。")
    public Result<Void> unbindTarget(@PathVariable Long id, @PathVariable Long targetId) {
        projectAppService.unbindTarget(id, targetId);
        return Result.success();
    }

    @GetMapping("/{id}/constraints")
    @Operation(summary = "当前生效约束集（含规则）", description = "尚未绑定时 data 为 null。")
    public Result<ConstraintSetResponse> getConstraints(@PathVariable Long id) {
        return Result.success(projectAppService.getProjectConstraints(id));
    }

    @PostMapping("/{id}/constraints")
    @Operation(summary = "绑定 / 激活约束集", description = """
            activate=true（默认）时 DRAFT 约束集会先自检再激活；
            activate=false 且约束集未激活则返回 BUSINESS_CONFLICT(40901)。""")
    public Result<ConstraintSetResponse> bindConstraints(@PathVariable Long id,
                                                         @RequestBody BindConstraintRequest request) {
        if (request == null || request.getConstraintSetId() == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "constraintSetId 不能为空");
        }
        boolean activate = request.getActivate() == null || request.getActivate();
        return Result.success(projectAppService.bindConstraintSet(id, request.getConstraintSetId(), activate));
    }

    /** 绑定约束集请求体：{@code {"constraintSetId":1,"activate":true}} */
    @Data
    public static class BindConstraintRequest {

        private Long constraintSetId;

        private Boolean activate;
    }

    @PostMapping("/{id}/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "开启新一轮", description = """
            项目须已推导为 READY，否则 DESIGN_PROJECT_NOT_READY(40940)，details 里带 readyBlockers；
            已有未关闭轮次返回 DESIGN_ROUND_ALREADY_OPEN(40942)。
            成功后项目 READY → ACTIVE。""")
    public Result<RoundResponse> openRound(@PathVariable Long id,
                                          @Valid @RequestBody(required = false) RoundOpenRequest request) {
        return Result.success(roundAppService.open(id, request == null ? new RoundOpenRequest() : request));
    }

    @PostMapping("/{id}/next-round")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "启动下一轮迭代（202）", description = """
            上一轮必须已关闭（否则 DESIGN_ROUND_ALREADY_OPEN 40942）；
            项目须为 ACTIVE，否则 DESIGN_PROJECT_STATUS_INVALID(40941)。
            当前 autoStartGeneration=true 会返回 DEPENDENCY_UNAVAILABLE(50300)：生成任务管线尚未落地，
            刻意不静默忽略。响应 jobId/status 为空表示只开了轮次。""")
    public Result<AcceptedNextRoundResponse> nextRound(@PathVariable Long id,
                                                      @RequestBody NextRoundRequest request) {
        return Result.success(roundAppService.nextRound(id, request == null ? new NextRoundRequest() : request));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "结项", description = """
            ACTIVE / READY → COMPLETED。存在未关闭轮次时返回 BUSINESS_CONFLICT(40901)，
            避免留下永远关不掉的悬空轮次。""")
    public Result<ProjectResponse> close(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        String summary = body == null ? null : (String) body.get("summary");
        return Result.success(projectAppService.close(id, summary));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "归档", description = "COMPLETED / CANCELLED → ARCHIVED，否则 DESIGN_PROJECT_STATUS_INVALID(40941)。")
    public Result<ProjectResponse> archive(@PathVariable Long id) {
        return Result.success(projectAppService.archive(id));
    }
}
