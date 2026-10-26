package com.synpharm.asset.application.impl;

import com.synpharm.asset.api.dto.FileAssetResponse;
import com.synpharm.asset.application.FileAssetService;
import com.synpharm.asset.domain.model.FileAsset;
import com.synpharm.asset.infrastructure.persistence.FileAssetMapper;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 文件资产实现。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAssetServiceImpl implements FileAssetService {

    private final FileAssetMapper assetMapper;

    @Override
    public FileAssetResponse get(Long id) {
        return toResponse(load(id), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileAssetResponse ref(Long id) {
        FileAsset asset = load(id);
        int next = (asset.getRefCount() == null ? 0 : asset.getRefCount()) + 1;
        asset.setRefCount(next);
        assetMapper.updateById(asset);
        return toResponse(asset, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileAssetResponse unref(Long id) {
        FileAsset asset = load(id);
        int current = asset.getRefCount() == null ? 0 : asset.getRefCount();
        if (current == 0) {
            log.warn("资产 {} 的 ref_count 已是 0，仍收到 unref（多为调用方重复释放）——不再下调", id);
            return toResponse(asset, false);
        }
        asset.setRefCount(current - 1);
        assetMapper.updateById(asset);
        if (asset.getRefCount() == 0) {
            log.info("资产 {} 引用归零，进入 GC 队列（不做物理删除）", id);
        }
        return toResponse(asset, false);
    }

    // ------------------------------------------------------------------ 内部

    private FileAsset load(Long id) {
        FileAsset asset = assetMapper.selectById(id);
        if (asset == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "文件资产不存在: " + id, Map.of("id", id));
        }
        return asset;
    }

    private FileAssetResponse toResponse(FileAsset entity, boolean reused) {
        FileAssetResponse response = new FileAssetResponse();
        response.setId(entity.getId());
        response.setSha256(entity.getSha256());
        response.setFileName(entity.getFileName());
        response.setContentType(entity.getContentType());
        response.setSizeBytes(entity.getSizeBytes());
        response.setStorageType(entity.getStorageType());
        response.setBucket(entity.getBucket());
        response.setObjectKey(entity.getObjectKey());
        response.setStorageUri(entity.getStorageUri());
        response.setRefCount(entity.getRefCount());
        response.setOwnerId(entity.getOwnerId());
        response.setReused(reused);
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
