package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.design.api.dto.DecisionVetoRuleRequest;
import com.synpharm.design.api.dto.DecisionVetoRuleResponse;
import com.synpharm.design.application.DecisionVetoRuleAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计域 · 业务性否决规则（07 §8，E2）。
 *
 * <p>与 {@code /api/design/constraints}（技术性约束）是两层：
 * 约束判「这个分子行不行」，否决规则判「这个项目现在能不能选它」。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 否决规则", description = "业务性否决规则的增删改查（全局规则只读）")
public class DecisionVetoRuleController {

    private final DecisionVetoRuleAppService vetoRuleAppService;

    @GetMapping("/projects/{projectId}/decision-veto-rules")
    @Operation(summary = "规则列表", description = "全局默认 + 该项目自有，按 sortNo 升序")
    public Result<List<DecisionVetoRuleResponse>> list(@PathVariable Long projectId) {
        return Result.success(vetoRuleAppService.list(projectId));
    }

    @PostMapping("/projects/{projectId}/decision-veto-rules")
    @Operation(summary = "新建规则", description = """
            humanReadable 必填（红线 R2：不允许存在用户看不到的规则）。
            可能返回：VALIDATION_FAILED、DESIGN_PROJECT_NOT_FOUND。""")
    public Result<DecisionVetoRuleResponse> create(@PathVariable Long projectId,
                                                   @Valid @RequestBody DecisionVetoRuleRequest request) {
        return Result.success(vetoRuleAppService.create(projectId, request));
    }

    @PutMapping("/decision-veto-rules/{ruleId}")
    @Operation(summary = "修改规则", description = """
            全局默认规则（projectId=0）只读。可能返回：DECISION_VETO_RULE_NOT_FOUND、
            DECISION_VETO_RULE_GLOBAL_IMMUTABLE、VALIDATION_FAILED。""")
    public Result<DecisionVetoRuleResponse> update(@PathVariable Long ruleId,
                                                   @Valid @RequestBody DecisionVetoRuleRequest request) {
        return Result.success(vetoRuleAppService.update(ruleId, request));
    }

    @DeleteMapping("/decision-veto-rules/{ruleId}")
    @Operation(summary = "删除规则", description = "全局默认规则不可删除")
    public Result<Void> delete(@PathVariable Long ruleId) {
        vetoRuleAppService.delete(ruleId);
        return Result.success(null);
    }
}