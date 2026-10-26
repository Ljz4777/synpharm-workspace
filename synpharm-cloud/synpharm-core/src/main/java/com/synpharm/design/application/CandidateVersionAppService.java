package com.synpharm.design.application;

import com.synpharm.design.api.dto.CandidateVersionRequest;
import com.synpharm.design.api.dto.CandidateVersionResponse;

/**
 * 候选版本写回（二维画布 → 同一个分子的下一版）。
 *
 * <p>为什么不用 `/v1/molecules/edit`：那条路是「引擎按动作改结构」（需要 crem，未部署）。
 * 而这里改结构的是**人** —— 画布给出新 SMILES，后端只负责把它变成版本树上的合法节点：
 * 算真值（InChIKey/分子式/理化指标）、挂父版本、写编辑日志、标记待重新评估。
 *
 * @author SynPharm Team
 */
public interface CandidateVersionAppService {

    /**
     * 用画布上的 SMILES 建一个子版本。
     *
     * <p>拒绝的三种情况：分支已放弃（409）、结构相对父版本没变（400 `MOLECULE_EDIT_NO_CHANGE`）、
     * 结构无法解析（400 `MOLECULE_EDIT_INVALID`）。
     * 建出的节点 `needsReEval=1`，必须先重新评估冻结才能进决策。
     */
    CandidateVersionResponse createVersion(Long candidateId, CandidateVersionRequest request);
}
