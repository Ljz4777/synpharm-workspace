package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.AbandonResponse;
import com.synpharm.design.api.dto.CandidateTreeResponse;
import com.synpharm.design.api.dto.EditGapResponse;
import com.synpharm.design.api.dto.EditImpactResponse;
import com.synpharm.design.application.CandidateEditAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计域 · 编辑（E0~E3 + 版本树，06 §5.7）。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/candidates")
@RequiredArgsConstructor
@Tag(name = "设计域 · 编辑", description = "缺口诊断（E1）/ 动作匹配（E2）/ 影响面（E3）/ 版本树")
public class CandidateEditController {

    private final CandidateEditAppService candidateEditAppService;

    @PostMapping("/{candidateId}/gaps:diagnose")
    @Operation(summary = "E1 触发缺口诊断", description = """
            准入 5 项：候选存在 / 归属正确 / 有冻结快照 / 快照未过期 / 分支未放弃。
            可能返回：RESOURCE_NOT_FOUND(无冻结快照)、STALE_OBJECT(需重评)、BUSINESS_CONFLICT(分支已放弃)。""")
    public Result<EditGapResponse> diagnose(@PathVariable Long candidateId) {
        return Result.success(candidateEditAppService.diagnose(candidateId));
    }

    @GetMapping("/{candidateId}/gaps")
    @Operation(summary = "E1 最新缺口诊断")
    public Result<EditGapResponse> gaps(@PathVariable Long candidateId) {
        return Result.success(candidateEditAppService.latestGaps(candidateId));
    }

    @PostMapping("/{candidateId}/actions:preview")
    @Operation(summary = "E3 影响面评估", description = """
            回归守卫：动作会打坏已达标维度时返回 BLOCK 且 executable=false。
            记录仍会落库 —— 「这次被拦了、为什么」本身要留痕。""")
    public Result<EditImpactResponse> previewImpact(@PathVariable Long candidateId,
                                                    @RequestBody ImpactRequest request) {
        String actionCode = request == null ? null : request.getActionCode();
        String region = request == null ? null : request.getTargetRegion();
        return Result.success(candidateEditAppService.previewImpact(candidateId, actionCode, region));
    }

    @GetMapping("/{candidateId}/tree")
    @Operation(summary = "版本树", description = "含 abandoned 分支：删掉会让「为什么那条路走不通」永久丢失")
    public Result<CandidateTreeResponse> tree(@PathVariable Long candidateId) {
        return Result.success(candidateEditAppService.tree(candidateId));
    }

    @PostMapping("/{candidateId}:abandon")
    @Operation(summary = "放弃分支", description = "只改 branch_status，不删除")
    public Result<AbandonResponse> abandon(@PathVariable Long candidateId) {
        return Result.success(candidateEditAppService.abandon(candidateId));
    }

    @PostMapping("/{candidateId}:revert")
    @Operation(summary = "切回父版本", description = """
            新建一个与父同内容的新版本，不是回滚 —— 历史只增不删。返回新版本 candidateId。""")
    public Result<Map<String, Object>> revert(@PathVariable Long candidateId) {
        Long newId = candidateEditAppService.revert(candidateId);
        return Result.success(Map.of("candidateId", newId, "sourceCandidateId", candidateId));
    }

    @Data
    public static class ImpactRequest {
        private String actionCode;
        private String targetRegion;
    }
}
