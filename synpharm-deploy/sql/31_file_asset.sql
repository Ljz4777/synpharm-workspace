-- =============================================
-- AI 药物分子设计平台 · 对象存储资产（SHA256 内容寻址去重）
-- 版本：v1.0  依赖：无
-- 来源：docs/design/AI药物分子设计平台-详细设计书.md §3.3.2（权威版本）
-- 落实需求：大文件（PDB/SDF）存对象存储，DB 只存 URI；SHA256 去重
--
-- 写入流程（FileAssetService）：计算 sha256 → 命中 uk_sha256 则 ref_count+1 并复用 storage_uri
-- （零上传）；未命中则上传后插入。删除走「ref_count-1，归零后进入 GC 队列」，不做物理删除。
-- 本表只登记元数据；同 23 号约定：DECIMAL 不用 FLOAT/DOUBLE（此处无浮点字段）。
-- =============================================
USE synpharm_core;

CREATE TABLE IF NOT EXISTS file_asset (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '资产ID',
    sha256       CHAR(64)     NOT NULL COMMENT '内容哈希（小写十六进制），全局去重键',
    file_name    VARCHAR(300)          COMMENT '原始文件名',
    content_type VARCHAR(100)          COMMENT 'MIME，如 chemical/x-mdl-sdfile / chemical/x-pdb',
    size_bytes   BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    storage_type VARCHAR(20)  NOT NULL DEFAULT 'S3' COMMENT 'S3/LOCAL（开发环境常用 LOCAL）',
    bucket       VARCHAR(120)          COMMENT '对象存储桶',
    object_key   VARCHAR(500) NOT NULL COMMENT '对象键（内容寻址：assets/{sha256[0:2]}/{sha256}）',
    storage_uri  VARCHAR(800) NOT NULL COMMENT 's3://bucket/key 或 file:///abs/path',
    ref_count    INT          NOT NULL DEFAULT 0 COMMENT '被引用次数；0 表示可被回收任务清理',
    owner_id     BIGINT                COMMENT '上传人（系统生成时为 NULL）',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_sha256 (sha256),
    KEY idx_owner (owner_id, created_at),
    KEY idx_gc (deleted, ref_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对象存储资产';
