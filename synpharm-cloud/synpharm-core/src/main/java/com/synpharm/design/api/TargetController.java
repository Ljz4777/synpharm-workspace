package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.BindingPocketResponse;
import com.synpharm.design.api.dto.TargetResponse;
import com.synpharm.design.application.TargetAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 靶点库读端点（契约属于 `target` 标签的 3 个只读操作）。
 *
 * <p>路径分两段：靶点相关在 {@code /targets}，口袋挂在结构下（{@code /structures/{id}/pockets}）
 * —— 这是契约的路径形状，不做统一前缀的重排，否则前端拿到的 URL 与 `openapi.yaml` 对不上。
 *
 * <p>本组端点不做归属校验：{@code target} 是共享参考数据（表里没有属主列），
 * 与知识库同一性质。写路径（UniProt 导入、结构绑定、口袋检测）不在本组。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 靶点库", description = "靶点的分页检索与详情、结构口袋的读取")
public class TargetController {

    private final TargetAppService targetAppService;

    @GetMapping("/targets")
    @Operation(summary = "靶点库列表", description = """
            按关键词与分类筛选靶点，分页返回，**含结构与口袋**（与详情同一形状）。
            status 是封闭枚举，非空且非法时返回 400（不静默忽略成"查全量"）。
            可能返回：VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。""")
    public Result<PageResult<TargetResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String diseaseArea,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize 最小 1")
            @Max(value = 200, message = "pageSize 最大 200") int pageSize) {
        return Result.success(targetAppService.list(keyword, targetType, diseaseArea, status, page, pageSize));
    }

    @GetMapping("/targets/{id}")
    @Operation(summary = "靶点详情", description = """
            含该靶点的全部结构与每个结构的口袋。
            可能返回：TARGET_NOT_FOUND(40441)、UNAUTHORIZED(40100)。""")
    public Result<TargetResponse> get(@PathVariable Long id) {
        return Result.success(targetAppService.get(id));
    }

    @GetMapping("/structures/{id}/pockets")
    @Operation(summary = "结构口袋列表", description = """
            按口袋序号升序返回。结构不存在返回 404 而不是 200 + 空数组 ——
            后者会让「结构一个口袋都没有」与「结构根本不存在」变得无法区分。
            可能返回：STRUCTURE_NOT_FOUND(40454)、UNAUTHORIZED(40100)。""")
    public Result<List<BindingPocketResponse>> listPockets(@PathVariable Long id) {
        return Result.success(targetAppService.listPockets(id));
    }
}
