package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 手工建立的候选（二维画布 → 候选）。
 *
 * <p>返回里带 {@code canonicalSmiles} / {@code inchikey} / {@code formula}，
 * 是为了让界面能当场核对「建出来的到底是不是我画的那个分子」——
 * 只回一个 id 的话，用户没有别的办法确认。
 *
 * @author SynPharm Team
 */
@Data
public class ManualCandidateResponse {

    private Long candidateId;
    private Long projectId;
    private Long roundId;

    /** 用户输入的 SMILES（原样保留，便于对照） */
    private String inputSmiles;
    /** 引擎归一化后的 SMILES */
    private String smiles;
    private String inchikey;
    private String formula;

    private BigDecimal mw;
    private BigDecimal logp;
    private BigDecimal qed;

    /** 引擎实际返回的指标条数（其余指标这次没算出来，不填 0） */
    private Integer metricCount;

    /** IMPORTED = 人手工给的分子，不是生成漏斗产出的 */
    private String originType;

    /** 该分子此前已存在时为 true（此时 candidateId 指向已有那条） */
    private Boolean existed;
}
