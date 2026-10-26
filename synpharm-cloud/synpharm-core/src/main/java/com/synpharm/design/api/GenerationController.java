package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.GenerationCandidateLogResponse;
import com.synpharm.design.api.dto.GenerationCreateRequest;
import com.synpharm.design.api.dto.GenerationResponse;
import com.synpharm.design.application.GenerationAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计域 · 分子生成（06 §4.6）。
 *
 * <p>提交返回 {@code 202}：即使当前实现是同步跑完，语义上也应表达"已受理"，
 * 这样将来改成真正异步时前端不需要改判断。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 分子生成", description = "S0~S9 流水线、漏斗诊断、逐分子筛选日志")
public class GenerationController {

    private final GenerationAppService generationAppService;

    @PostMapping("/projects/{projectId}/generations")
    @Operation(summary = "提交生成", description = """
            同步跑完 S0~S9。幂等：同 idempotencyKey 返回已有批次。
            S0 校验失败抛 4xx（基线缺失/空间为空/无轮次）；S4 之后执行失败返回 200 + status=FAILED，
            并带上完整漏斗与淘汰原因 —— 失败的诊断信息比一个 500 有用得多。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、BASELINE_NOT_FOUND(40455)、
            BASELINE_INCOMPLETE(40960)、VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。""")
    public ResponseEntity<Result<GenerationResponse>> submit(
            @PathVariable Long projectId,
            @Valid @RequestBody GenerationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success(generationAppService.submit(projectId, request)));
    }

    @GetMapping("/generations")
    @Operation(summary = "批次列表", description = """
            按项目查生成批次（新→旧）。可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、UNAUTHORIZED(40100)。""")
    public Result<PageResult<GenerationResponse>> list(
            @RequestParam Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(generationAppService.list(projectId, page, pageSize));
    }

    @GetMapping("/generations/{id}")
    @Operation(summary = "批次详情", description = """
            含 plan / 分片状态 / 漏斗 / 诊断。可能返回：VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。""")
    public Result<GenerationResponse> detail(@PathVariable Long id) {
        return Result.success(generationAppService.detail(id));
    }

    @GetMapping("/generations/{id}/funnel")
    @Operation(summary = "漏斗四段转化率", description = """
            生成 → 去重 → 粗筛 → 配额，附主要淘汰原因 Top5。可能返回：VALIDATION_FAILED(40000)。""")
    public Result<GenerationResponse> funnel(@PathVariable Long id) {
        return Result.success(generationAppService.funnel(id));
    }

    @GetMapping("/generations/{id}/diagnosis")
    @Operation(summary = "S9 诊断", description = """
            verdict 由代码判定（PASS / PASS_WITH_WARNING / FAIL），findings 给归因与建议。
            LLM 只负责文案，不参与 PASS/FAIL 判定。可能返回：VALIDATION_FAILED(40000)。""")
    public Result<GenerationResponse> diagnosis(@PathVariable Long id) {
        return Result.success(generationAppService.diagnosis(id));
    }

    @GetMapping("/generations/{id}/candidates")
    @Operation(summary = "逐分子筛选日志", description = """
            回答「这个分子为什么没进候选池」。可按 stage（S5_DEDUP/S6_PRE_FILTER/S7_QUOTA）
            与 outcome（KEPT/FILTERED/DEDUPED/QUOTA_CUT）过滤。
            可能返回：VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。""")
    public Result<List<GenerationCandidateLogResponse>> candidateLogs(
            @PathVariable Long id,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String outcome) {
        return Result.success(generationAppService.candidateLogs(id, stage, outcome));
    }

    @PostMapping("/generations/{id}:cancel")
    @Operation(summary = "取消批次", description = """
            仅 PENDING / RUNNING 可取消。可能返回：VALIDATION_FAILED(40000)、BUSINESS_CONFLICT(40901)。""")
    public Result<GenerationResponse> cancel(@PathVariable Long id) {
        return Result.success(generationAppService.cancel(id));
    }
}
