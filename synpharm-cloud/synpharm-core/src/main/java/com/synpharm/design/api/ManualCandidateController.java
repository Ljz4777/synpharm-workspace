package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.ManualCandidateRequest;
import com.synpharm.design.api.dto.ManualCandidateResponse;
import com.synpharm.design.application.ManualCandidateAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计域 · 手工候选（二维画布 → 候选）。
 *
 * <p>补上「从分子开始」这条路：在此之前，画布上画出来的结构只能复制 SMILES 走人 ——
 * 进不了评估，也进不了决策。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 手工候选", description = "用 SMILES 建候选（结构与指标由计算层实算）")
public class ManualCandidateController {

    private final ManualCandidateAppService manualCandidateAppService;

    @PostMapping("/projects/{projectId}/candidates")
    @Operation(summary = "用 SMILES 建一个候选",
            description = """
                    归一化 SMILES、InChIKey、分子式与理化指标全部由计算层实算，不接受调用方填写。
                    挂到项目当前轮次（可用 roundId 指定）；同项目同轮次同 InChIKey 不重复建，
                    返回已有那条并标 existed=true。
                    可能返回：INVALID_SMILES（400）、ROUND_NOT_FOUND（404，项目还没开轮次）、
                    FASTAPI_UNAVAILABLE（503，引擎不可用）、DESIGN_PROJECT_NOT_FOUND（404）。""")
    public Result<ManualCandidateResponse> create(
            @PathVariable Long projectId,
            @Valid @RequestBody ManualCandidateRequest request) {
        return Result.success(manualCandidateAppService.create(projectId, request));
    }
}
