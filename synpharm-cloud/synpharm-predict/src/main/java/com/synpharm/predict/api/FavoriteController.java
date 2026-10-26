package com.synpharm.predict.api;

import com.synpharm.predict.api.dto.FavoriteRequest;
import com.synpharm.predict.api.dto.FavoritePageResponse;
import com.synpharm.predict.application.FavoriteService;
import com.synpharm.common.security.CurrentUser;import com.synpharm.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 收藏控制器（修复方案 5.7）。
 *
 * <p>收藏 / 取消收藏 / 查询我的收藏。所有操作以 JWT 用户为隔离边界。
 *
 * @author SynPharm Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@Tag(name = "收藏管理", description = "预测结果收藏接口")
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping
    @Operation(summary = "收藏结果", description = "收藏本人的预测结果（重复收藏幂等）")
    public Result<Long> addFavorite(
            @Valid @RequestBody FavoriteRequest request) {
        Long userId = CurrentUser.requireUserId();
        return Result.success(favoriteService.addFavorite(userId, request.getResultId(), request.getNote()));
    }

    @GetMapping
    @Operation(summary = "我的收藏", description = "分页查询当前用户的收藏列表")
    public Result<FavoritePageResponse> listFavorites(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = CurrentUser.requireUserId();
        return Result.success(favoriteService.listFavorites(userId, page, pageSize));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "取消收藏", description = "删除指定收藏（仅本人，物理删除）")
    public Result<Void> removeFavorite(
            @PathVariable Long id) {
        Long userId = CurrentUser.requireUserId();
        favoriteService.removeFavorite(userId, id);
        return Result.success();
    }
}
