package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.DecisionActionRequest;
import com.synpharm.design.api.dto.DecisionCreateRequest;
import com.synpharm.design.api.dto.DecisionOverrideRequest;
import com.synpharm.design.api.dto.DecisionReplayResponse;
import com.synpharm.design.api.dto.DecisionResponse;
import com.synpharm.design.api.dto.RiskAcceptanceRequest;
import com.synpharm.design.application.DecisionAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计域 · 决策（07 §8，阶段 E2~E8）。
 *
 * <p>新建决策同步跑 D0~D5（装配证据包 → 技术性复核 → 业务性否决 → 分桶排序 → 名额分配），
 * 落 {@code DRAFT}；随后由人来拍板：调整（D6）、接受风险（D7）、确认或暂缓（D8）。
 *
 * <p>确认之所以要重算一遍而不是直接改状态：确认意味着「这几个分子要花钱做实验了」，
 * 而决策记录可能是几天前算的。输入或规则变了却照样确认，等于拿旧结论去下新单。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 决策", description = "证据打包 → 复核 → 否决 → 分桶排序 → 名额 → 人工调整 → 确认")
public class DecisionController {

    private final DecisionAppService decisionAppService;

    @PostMapping("/projects/{projectId}/decisions")
    @Operation(summary = "新建决策（D0~D5）",
            description = """
                    对项目当前轮次的候选跑一遍决策流程，返回 DRAFT 记录与候选建议。
                    候选集由系统推导（必须已有评估冻结快照），不接受调用方指定。
                    可能返回：DESIGN_PROJECT_NOT_FOUND、BASELINE_NOT_FOUND、
                    DECISION_NO_CANDIDATE、DECISION_EVIDENCE_MISSING。""")
    public Result<DecisionResponse> create(
            @PathVariable Long projectId,
            @RequestBody(required = false) DecisionCreateRequest request) {
        return Result.success(decisionAppService.create(projectId, request));
    }

    @GetMapping("/decisions/{decisionId}")
    @Operation(summary = "决策详情", description = """
            含候选明细、否决清单与风险接受记录。
            可能返回：DECISION_NOT_FOUND、DESIGN_PROJECT_NOT_FOUND。""")
    public Result<DecisionResponse> get(@PathVariable Long decisionId) {
        return Result.success(decisionAppService.get(decisionId));
    }

    @GetMapping("/projects/{projectId}/decisions")
    @Operation(summary = "决策历史", description = "新→旧；不含候选明细，列表页用")
    public Result<List<DecisionResponse>> listByProject(@PathVariable Long projectId) {
        return Result.success(decisionAppService.listByProject(projectId));
    }

    @PatchMapping("/decisions/{decisionId}/candidates/{candidateId}")
    @Operation(summary = "人工调整候选去向（D6）",
            description = """
                    `overrideReason` 必填（否则 400 OVERRIDE_REASON_REQUIRED）。
                    被 BLOCK 级规则否决或硬约束复核不通过的候选不能改为入选（409 DECISION_BLOCKED_BY_VETO）——
                    否决只能靠改数据后重新决策来消解。非草稿状态返回 409 DECISION_NOT_EDITABLE。""")
    public Result<DecisionResponse> override(
            @PathVariable Long decisionId,
            @PathVariable Long candidateId,
            @Valid @RequestBody DecisionOverrideRequest request) {
        return Result.success(decisionAppService.override(decisionId, candidateId, request));
    }

    @PostMapping("/decisions/{decisionId}/candidates/{candidateId}/accept-risk")
    @Operation(summary = "接受某个风险点（D7）",
            description = """
                    粒度是逐候选逐风险点。`reason` 必填。
                    风险点必须在证据包里（否则 404），且「必须验证」项不接受（409 RISK_ACCEPTANCE_FORBIDDEN）——
                    如果它可以被「接受」，它就是建议而不是要求。""")
    public Result<DecisionResponse> acceptRisk(
            @PathVariable Long decisionId,
            @PathVariable Long candidateId,
            @Valid @RequestBody RiskAcceptanceRequest request) {
        return Result.success(decisionAppService.acceptRisk(decisionId, candidateId, request));
    }

    @PostMapping("/decisions/{decisionId}:replay")
    @Operation(summary = "回放比对（D8 / B4）",
            description = """
                    用当前输入与当前规则重算一遍，逐候选返回桶/名次/去向的变化。
                    只关心「会不会得到同样的名单」，不改任何状态。""")
    public Result<DecisionReplayResponse> replay(@PathVariable Long decisionId) {
        return Result.success(decisionAppService.replay(decisionId));
    }

    @PostMapping("/decisions/{decisionId}:confirm")
    @Operation(summary = "确认冻结（D8）",
            description = """
                    前置三条：草稿状态、至少一个候选入选（否则 409 NO_VIABLE_CANDIDATE）、
                    回放输入指纹一致（否则 409 REPLAY_MISMATCH）。
                    确认后不可再改，要改请新建一版（revision_of 指向它）。""")
    public Result<DecisionResponse> confirm(
            @PathVariable Long decisionId,
            @RequestBody(required = false) DecisionActionRequest request) {
        return Result.success(decisionAppService.confirm(decisionId, request));
    }

    @PostMapping("/decisions/{decisionId}:defer")
    @Operation(summary = "转暂缓", description = "补数据后重开；`reason` 必填。仅草稿可用")
    public Result<DecisionResponse> defer(
            @PathVariable Long decisionId,
            @RequestBody(required = false) DecisionActionRequest request) {
        return Result.success(decisionAppService.defer(decisionId, request));
    }

    @PostMapping("/decisions/{decisionId}:cancel")
    @Operation(summary = "取消", description = "仅草稿可用；`reason` 必填")
    public Result<DecisionResponse> cancel(
            @PathVariable Long decisionId,
            @RequestBody(required = false) DecisionActionRequest request) {
        return Result.success(decisionAppService.cancel(decisionId, request));
    }
}
