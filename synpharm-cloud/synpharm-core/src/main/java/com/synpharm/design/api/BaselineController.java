package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.BaselinePreviewResponse;
import com.synpharm.design.api.dto.BaselineResponse;
import com.synpharm.design.application.BaselineAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设计域 · 需求基线（06 文档 §3）。
 *
 * <p>基线的唯一用途是给生成任务当输入。所以这里的接口只做三件事：
 * <b>看缺什么</b>（preview）、<b>冻下来</b>（freeze）、<b>看冻了什么</b>（current / history）。
 * 没有「修改基线」—— 变更走「重新冻结产生新版本」，旧版本自动 SUPERSEDED。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/projects")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 需求基线", description = "八块完整性预检、冻结快照、版本历史")
public class BaselineController {

    private final BaselineAppService baselineAppService;

    @PostMapping("/{projectId}/baseline:preview")
    @Operation(summary = "预检基线完整性", description = """
            逐块检查八块，返回 OK / WARN / MISSING 与缺项清单。不落库。
            响应里的 contentHash 是当前内容的指纹：用它作为 freeze 的 expectedContentHash，
            可以挡住「预检完别人又改了项目」的并发写入。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、UNAUTHORIZED(40100)。""")
    public Result<BaselinePreviewResponse> preview(@PathVariable Long projectId) {
        return Result.success(baselineAppService.preview(projectId));
    }

    @PostMapping("/{projectId}/baseline:freeze")
    @Operation(summary = "冻结基线快照", description = """
            八块不齐返回 409 BASELINE_INCOMPLETE 并附缺项清单，不产生快照。
            同内容重复冻结幂等返回已有快照，不升版本；内容变化则旧版本置 SUPERSEDED、project_version + 1。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、BASELINE_INCOMPLETE(40960)、
            BASELINE_STALE(40961)、UNAUTHORIZED(40100)。""")
    public Result<BaselineResponse> freeze(@PathVariable Long projectId,
                                           @RequestBody(required = false) FreezeRequest request) {
        String expected = request == null ? null : request.getExpectedContentHash();
        return Result.success(baselineAppService.freeze(projectId, expected));
    }

    @GetMapping("/{projectId}/baseline")
    @Operation(summary = "当前生效基线", description = """
            返回 status=ACTIVE 的快照。项目还没冻结过时返回 404 BASELINE_NOT_FOUND。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、BASELINE_NOT_FOUND(40455)、UNAUTHORIZED(40100)。""")
    public Result<BaselineResponse> current(@PathVariable Long projectId) {
        return Result.success(baselineAppService.current(projectId));
    }

    @GetMapping("/{projectId}/baseline/history")
    @Operation(summary = "基线版本历史", description = """
            新→旧全量返回，含已被取代的版本。用于追溯「这一轮用的是哪版需求」。
            可能返回：DESIGN_PROJECT_NOT_FOUND(40440)、UNAUTHORIZED(40100)。""")
    public Result<List<BaselineResponse>> history(@PathVariable Long projectId) {
        return Result.success(baselineAppService.history(projectId));
    }

    /** freeze 的请求体：只为带一个并发校验指纹，允许整体省略。 */
    @Data
    public static class FreezeRequest {

        /** preview 返回的 contentHash；为空则不校验 */
        private String expectedContentHash;
    }
}
