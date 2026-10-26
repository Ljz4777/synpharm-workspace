package com.synpharm.design.api;

import com.synpharm.common.api.Result;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.LibraryItemNodeResponse;
import com.synpharm.design.api.dto.LibraryItemResponse;
import com.synpharm.design.api.dto.LibraryItemUpsertRequest;
import com.synpharm.design.application.LibraryAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * 设计域 · 知识库（疾病 / 靶点 / 分子形式 / 约束模板 / 目标 / 检测类型）。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/api/design/library")
@RequiredArgsConstructor
@Validated
@Tag(name = "设计域 · 知识库", description = "知识项的分页检索、层级树与维护")
public class LibraryController {

    private final LibraryAppService libraryAppService;

    @GetMapping
    @Operation(summary = "知识库检索", description = """
            按分类分页检索知识项。
            可能返回：VALIDATION_FAILED(40000)、UNAUTHORIZED(40100)。""")
    public Result<PageResult<LibraryItemResponse>> list(
            @RequestParam String category,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page 从 1 开始") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "pageSize 最小 1")
            @Max(value = 200, message = "pageSize 最大 200") int pageSize) {
        return Result.success(libraryAppService.list(category, parentId, keyword, enabled, page, pageSize));
    }

    @GetMapping("/tree")
    @Operation(summary = "某分类完整层级树", description = "一次拉全，驱动下拉/级联。可能返回：VALIDATION_FAILED(40000)。")
    public Result<List<LibraryItemNodeResponse>> tree(
            @RequestParam String category,
            @RequestParam(defaultValue = "true") boolean enabledOnly) {
        return Result.success(libraryAppService.tree(category, enabledOnly));
    }

    @GetMapping("/{id}")
    @Operation(summary = "知识项详情", description = "可能返回：LIBRARY_ITEM_NOT_FOUND(40450)。")
    public Result<LibraryItemResponse> get(@PathVariable Long id) {
        return Result.success(libraryAppService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新建知识项", description = """
            同分类 code 重复返回 LIBRARY_CODE_DUPLICATED(40957)；
            父级不存在或会造成环返回 LIBRARY_PARENT_INVALID(40051)。""")
    public Result<LibraryItemResponse> create(@Valid @RequestBody LibraryItemUpsertRequest request) {
        return Result.success(libraryAppService.create(request));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "更新知识项", description = """
            builtin=1 仅允许改 enabled / sortNo / remark，其余字段变化返回 LIBRARY_BUILTIN_IMMUTABLE(40958)。
            契约对 POST / PATCH 复用同一 schema，因此请求需带齐 category / code / name。""")
    public Result<LibraryItemResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody LibraryItemUpsertRequest request) {
        return Result.success(libraryAppService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "逻辑删除知识项", description = "存在子节点时返回 LIBRARY_HAS_CHILDREN(40959)。")
    public Result<Void> delete(@PathVariable Long id) {
        libraryAppService.delete(id);
        return Result.success();
    }
}
