package com.synpharm.asset.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * file_asset · 文件资产（内容寻址）。
 *
 * <p>由 synpharm_asset schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-asset 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * <p>去重口径：{@code sha256} 命中则 {@code ref_count + 1} 并复用 {@code storage_uri}（零上传）；
 * 未命中才上传后插入。删除走 {@code ref_count - 1}，归零进 GC 队列，不做物理删除。
 *
 * @author SynPharm Team
 */
@Data
@TableName("file_asset")
public class FileAsset {

    /** 资产ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 内容 SHA256（唯一，去重依据） */
    private String sha256;

    /** 原始文件名 */
    private String fileName;

    /** MIME 类型 */
    private String contentType;

    /** 字节数 */
    private Long sizeBytes;

    /** 存储类型：S3 / LOCAL */
    private String storageType;

    /** 桶名 */
    private String bucket;

    /** 对象键 */
    private String objectKey;

    /** 可访问 URI（DB 只存 URI，大对象不落库） */
    private String storageUri;

    /** 引用计数；归零后进 GC 队列 */
    private Integer refCount;

    /** 属主 */
    private Long ownerId;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;
}
