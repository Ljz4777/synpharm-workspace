package com.synpharm.asset.api;

import com.synpharm.asset.api.dto.FileAssetResponse;
import com.synpharm.asset.application.FileAssetService;
import com.synpharm.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件资产内网端点（供 design / predict 服务调用）。
 *
 * <p><b>为什么是 {@code /internal/**} 而不是 {@code /api/**}</b>：这些接口不面向浏览器 ——
 * 网关**没有** {@code /internal/**} 的路由，所以它们天然无法从公网经网关到达；服务间通过
 * 服务名直连（Feign：{@code @FeignClient(name = "synpharm-asset", path = "/internal/files")}）。
 * 身份仍由 {@code X-Internal-Token} 守门（fail-closed），不因为"内网"就免鉴权。
 *
 * <p>契约里 asset 共 4 个内网操作，本类实现其中 3 个；{@code POST /internal/files:presign}
 * 需要存储后端（S3/MinIO 或本地卷）与 Redis 预签名缓存（{@code asset:presign:{assetId}}，TTL 10min），
 * 待存储选型确定后实现。
 *
 * @author SynPharm Team
 */
@RestController
@RequestMapping("/internal/files")
@RequiredArgsConstructor
public class InternalFileController {

    private final FileAssetService fileAssetService;

    /** 取元数据（含 storageUri，调用方据此读取对象） */
    @GetMapping("/{id}")
    public Result<FileAssetResponse> get(@PathVariable Long id) {
        return Result.success(fileAssetService.get(id));
    }

    /** 引用计数 +1：被引用方在**自己的事务内**调用 */
    @PostMapping("/{id}:ref")
    public Result<FileAssetResponse> ref(@PathVariable Long id) {
        return Result.success(fileAssetService.ref(id));
    }

    /** 引用计数 -1；归零后进 GC 队列（不做物理删除） */
    @PostMapping("/{id}:unref")
    public Result<FileAssetResponse> unref(@PathVariable Long id) {
        return Result.success(fileAssetService.unref(id));
    }
}
