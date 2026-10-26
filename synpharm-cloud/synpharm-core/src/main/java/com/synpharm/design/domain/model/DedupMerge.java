package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * dedup_merge · 去重合并关系（06 §4.2，S5）。
 *
 * <p>记「谁被并到了谁、相似度多少、用什么方法」。分子被合并后仍然可追溯 ——
 * 否则用户看到候选数从 200 掉到 80，却不知道少掉的 120 个去哪了。
 *
 * @author SynPharm Team
 */
@Data
@TableName("dedup_merge")
public class DedupMerge {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** 被合并者 candidate_molecule.id（落库后才有） */
    private Long mergedId;

    /** 尚未落库时指向 generation_candidate_log.id */
    private Long mergedLogId;

    /** 代表者 candidate_molecule.id */
    private Long representativeId;

    private BigDecimal similarity;

    /** ECFP4/TMSCORE/EXACT */
    private String method;

    private LocalDateTime createdAt;
}
