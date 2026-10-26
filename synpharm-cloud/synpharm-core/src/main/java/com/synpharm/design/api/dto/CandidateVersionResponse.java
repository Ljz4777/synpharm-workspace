package com.synpharm.design.api.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 新建的候选版本（画布写回）。
 *
 * <p>带 `canonicalSmiles` / `inchikey` / 分子式与理化指标，是为了让界面当场核对
 * 「存下去的到底是不是我画的那个分子」—— 只回 id 的话没有别的办法确认。
 *
 * <p>`needsReEval` 恒为 true：新版本还没有任何分数，必须重新冻结才能进决策。
 *
 * @author SynPharm Team
 */
@Data
public class CandidateVersionResponse {

    private Long candidateId;
    private Long parentCandidateId;
    private Integer versionNo;
    private Integer editRound;

    private String smiles;
    private String inchikey;
    private String formula;

    private BigDecimal mw;
    private BigDecimal logp;
    private BigDecimal qed;
    /** 引擎这次实际返回的指标条数（没返回的留空，不补 0） */
    private Integer metricCount;

    private String originType;
    private Boolean needsReEval;

    /** 写进 molecule_edit_log 的那条记录 id */
    private Long editLogId;
}
