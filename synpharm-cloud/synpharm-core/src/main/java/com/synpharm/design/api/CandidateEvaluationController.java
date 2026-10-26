package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.EvaluationFreezeResponse;
import com.synpharm.design.application.EvaluationFreezeAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计域 · 最小评估与冻结（06 §5.1）。
 *
 * <p>端点命名与 {@code baseline:preview} / {@code baseline:freeze} 保持一致：
 * 动作用 {@code :} 后缀，资源用裸路径。冻结是显式动作，不是后台副作用。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/candidates")
@RequiredArgsConstructor
@Tag(name = "设计域 · 最小评估", description = "理化指标 + 硬约束判定 → evaluation_freeze")
public class CandidateEvaluationController {

    private final EvaluationFreezeAppService evaluationFreezeAppService;

    @PostMapping("/{candidateId}/evaluation:freeze")
    @Operation(summary = "评估并冻结",
            description = """
                    对候选跑最小评估（RDKit 理化指标 + 基线硬约束 + 权重归一化综合分），落 evaluation_freeze。
                    幂等：同候选 + 同版本 + 同内容哈希返回已有快照。
                    可能返回：RESOURCE_NOT_FOUND、BASELINE_NOT_FOUND、DEPENDENCY_UNAVAILABLE(计算层不可用)。""")
    public Result<EvaluationFreezeResponse> freeze(
            @PathVariable Long candidateId,
            @RequestBody(required = false) FreezeRequest request) {
        Long snapshotId = request == null ? null : request.getBaselineSnapshotId();
        return Result.success(evaluationFreezeAppService.freeze(candidateId, snapshotId));
    }

    @GetMapping("/{candidateId}/evaluation")
    @Operation(summary = "最新冻结快照", description = """
            没有冻结过返回 RESOURCE_NOT_FOUND —— 编辑与决策都以冻结快照为输入，不给默认值。""")
    public Result<EvaluationFreezeResponse> latest(@PathVariable Long candidateId) {
        return Result.success(evaluationFreezeAppService.latest(candidateId));
    }

    @GetMapping("/{candidateId}/evaluation/history")
    @Operation(summary = "冻结历史", description = "新→旧，用于对照「换基线后分数怎么变」")
    public Result<List<EvaluationFreezeResponse>> history(@PathVariable Long candidateId) {
        return Result.success(evaluationFreezeAppService.history(candidateId));
    }

    @Data
    public static class FreezeRequest {
        /** 为空取项目当前 ACTIVE 快照；显式可传是为了重跑历史批次的对照 */
        private Long baselineSnapshotId;
    }
}
