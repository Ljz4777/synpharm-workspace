package com.synpharm.asset.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件资产响应。
 *
 * <p>{@code reused} 是契约要求的语义标记（规范 §4.1「`uk_sha256` 不报错：ref_count+1 并返回既有 asset」）：
 * 调用方据此知道这次是**命中已有内容**（零上传），而不是新落了一份。
 *
 * @author SynPharm Team
 */
@Data
public class FileAssetResponse {

    private Long id;

    private String sha256;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    /** S3 / LOCAL */
    private String storageType;

    private String bucket;

    private String objectKey;

    private String storageUri;

    /** 引用计数；归零后进 GC 队列 */
    private Integer refCount;

    private Long ownerId;

    /** 本次调用是否命中既有资产（去重成功） */
    private Boolean reused;

    private LocalDateTime createdAt;
}
