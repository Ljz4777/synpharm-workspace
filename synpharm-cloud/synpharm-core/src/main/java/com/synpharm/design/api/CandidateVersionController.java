package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.CandidateVersionRequest;
import com.synpharm.design.api.dto.CandidateVersionResponse;
import com.synpharm.design.application.CandidateVersionAppService;
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
 * 设计域 · 候选版本写回（二维画布 → 同一个分子的下一版）。
 *
 * <p>补上「改完能存」这最后一截：在此之前，画布上改完的结构只能存成**新候选**（独立根），
 * 版本树看不出「它是从哪个分子改来的、改了什么」。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/candidates")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 候选版本", description = "画布改结构 → 版本树新增一版（InChIKey/指标由计算层实算）")
public class CandidateVersionController {

    private final CandidateVersionAppService candidateVersionAppService;

    @PostMapping("/{candidateId}/versions")
    @Operation(summary = "用画布上的结构派生新版本",
            description = """
                    父版本只增不删：新结构落成一个新节点（`parent_mol_id` 指向父版本、版本号 +1、
                    `origin_type=EDITED`），并写 `molecule_edit_log` 记录改动说明 ——
                    决策阶段的证据血缘读的就是这条说明。
                    新版本 `needsReEval=1`，必须先 `evaluation:freeze` 才能进决策。
                    可能返回：EDIT_REASON_REQUIRED（400）、MOLECULE_EDIT_INVALID（400，结构无法解析）、
                    MOLECULE_EDIT_NO_CHANGE（400，InChIKey 与父版本一致）、
                    BUSINESS_CONFLICT（409，分支已放弃）、CANDIDATE_NOT_FOUND（404）、
                    FASTAPI_UNAVAILABLE（503，引擎不可用）。""")
    public Result<CandidateVersionResponse> createVersion(
            @PathVariable Long candidateId,
            @Valid @RequestBody CandidateVersionRequest request) {
        return Result.success(candidateVersionAppService.createVersion(candidateId, request));
    }
}
