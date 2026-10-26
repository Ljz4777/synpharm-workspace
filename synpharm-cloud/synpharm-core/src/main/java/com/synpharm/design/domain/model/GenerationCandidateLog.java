package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * generation_candidate_log · 逐分子筛选日志（06 §4.2）。
 *
 * <p><b>这张表存在的唯一理由是「原因必记」</b>：被 S5/S6/S7 处理掉的每个分子都必须留下
 * 一条带 {@code filterReason} 的记录。没有它，后期永远分不清「筛太狠」还是「采太偏」。
 *
 * <p>不复用 {@code candidate_molecule} 的原因：后者有 {@code uk_project_inchikey} /
 * {@code uk_project_sequence} 唯一键，同一分子第二次被筛时插不进去，原因就丢了。
 *
 * @author SynPharm Team
 */
@Data
@TableName("generation_candidate_log")
public class GenerationCandidateLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long sliceId;

    /** S5_DEDUP / S6_PRE_FILTER / S7_QUOTA */
    private String stage;

    /**
     * 本批内的临时编号。
     *
     * <p>S5~S7 写日志时候选还没有数据库 id，只有 tempRef；S8 落库后凭它回填
     * {@code candidateId}。不存这一列，「日志 → 候选」就永远接不上，
     * 诊断页只能看被筛原因，点不到对应候选。
     */
    private String tempRef;

    private String smiles;

    private String sequenceHash;

    private String inchikey;

    /** 通过后才有值 */
    private Long candidateId;

    /** KEPT/FILTERED/DEDUPED/QUOTA_CUT */
    private String outcome;

    /** JSON 数组：[{"code":"PAINS_HIT","severity":"HARD","detail":{}}] */
    private String filterReason;

    private BigDecimal preScore;

    /** 被合并到哪个候选 */
    private Long dedupInto;

    private String clusterId;

    private LocalDateTime createdAt;
}
