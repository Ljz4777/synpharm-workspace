package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.ConstraintRuleDto;
import com.synpharm.design.api.dto.ConstraintSetResponse;
import com.synpharm.design.api.dto.ConstraintSetUpsertRequest;
import com.synpharm.design.api.dto.RuleValidationResponse;
import com.synpharm.design.application.ConstraintAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设计域 · 约束集（硬/软约束规则）。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/constraints")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 约束集", description = "约束集 CRUD、规则自检、激活与归档")
public class ConstraintController {

    private final ConstraintAppService constraintAppService;

    @GetMapping
    @Operation(summary = "我的约束集列表", description = "可能返回：VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。")
    public Result<PageResult<ConstraintSetResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize 最小 1")
            @Max(value = 200, message = "pageSize 最大 200") int pageSize) {
        return Result.success(constraintAppService.list(status, page, pageSize));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新建约束集（可含规则）", description = "规则参数不匹配返回 CONSTRAINT_RULE_INVALID(40040)。")
    public Result<ConstraintSetResponse> create(@Valid @RequestBody ConstraintSetUpsertRequest request) {
        return Result.success(constraintAppService.create(request));
    }

    @PostMapping("/validate")
    @Operation(summary = "校验一组规则", description = "同步，驱动编辑器实时反馈。始终返回 code=0，问题逐条列在 data.errors。")
    public Result<RuleValidationResponse> validate(@RequestBody ValidateRequest request) {
        return Result.success(constraintAppService.validate(request == null ? List.of() : request.getRules()));
    }

    @GetMapping("/meta")
    @Operation(summary = "可用 ruleType / dimension / operator / catalog 枚举")
    public Result<Map<String, Object>> meta() {
        return Result.success(constraintAppService.meta());
    }

    @GetMapping("/{id}")
    @Operation(summary = "约束集详情（含规则）", description = "不存在或非本人返回 CONSTRAINT_SET_NOT_FOUND(40449)。")
    public Result<ConstraintSetResponse> get(@PathVariable Long id) {
        return Result.success(constraintAppService.get(id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "编辑约束集", description = "仅 DRAFT 可改，否则 CONSTRAINT_SET_NOT_EDITABLE(40947)。")
    public Result<ConstraintSetResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody ConstraintSetUpsertRequest request) {
        return Result.success(constraintAppService.update(id, request));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "激活（先自检）", description = """
            空集或规则自检不通过返回 CONSTRAINT_RULE_INVALID(40040)；
            非 DRAFT 状态返回 BUSINESS_CONFLICT(40901)。""")
    public Result<ConstraintSetResponse> activate(@PathVariable Long id) {
        return Result.success(constraintAppService.activate(id));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "归档", description = "已归档时返回 BUSINESS_CONFLICT(40901)。")
    public Result<ConstraintSetResponse> archive(@PathVariable Long id) {
        return Result.success(constraintAppService.archive(id));
    }

    /** 规则自检的请求体：{@code {"rules":[...]}} */
    @Data
    public static class ValidateRequest {

        private List<ConstraintRuleDto> rules;
    }
}
