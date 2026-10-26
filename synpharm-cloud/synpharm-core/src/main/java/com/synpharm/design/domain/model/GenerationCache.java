package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * generation_cache · 生成结果缓存（06 §4.2）。
 *
 * <p>键是 {@code sha256(toolId|paramHash|inputHash)} 三元组：命中即复用产物、不计配额。
 * 缓存命中率低于 30% 说明参数哈希粒度或策略模板有问题（见 §4.1 的五个度量）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("generation_cache")
public class GenerationCache {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String cacheKey;

    private Long toolId;

    private String toolVersion;

    private String paramHash;

    private String inputHash;

    private String artifactUri;

    private Integer resultCount;

    private Integer hitCount;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt;
}
