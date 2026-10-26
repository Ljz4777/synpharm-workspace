package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 把画布上的结构写成某个候选的**新版本**（版本树里挂在它下面）。
 *
 * <p>与「存为新候选」的区别：新候选是独立根（`parent_mol_id` 为空），
 * 写新版本则是同一个分子的下一版 —— 版本树、缺口诊断、影响面守卫都靠这条关系讲清「改了什么、代价是什么」。
 *
 * <p>{@code describeText} 必填：版本树里每个节点都要能回答「这一版改了什么」。
 * 没有说明的节点，事后只能看 SMILES 猜 —— 而 SMILES 不解释意图。
 *
 * @author SynPharm Team
 */
@Data
public class CandidateVersionRequest {

    @NotBlank(message = "SMILES 不能为空")
    @Size(max = 2000, message = "SMILES 最长 2000 字符")
    private String smiles;

    /** 必填：这一版改了什么（会写进 molecule_edit_log，决策证据的血缘也读它） */
    @NotBlank(message = "必须填写改动说明：版本树靠它解释这一版改了什么")
    @Size(max = 500, message = "改动说明最长 500 字符")
    private String describeText;

    /** 作用区域/位点（可选），如 "C12 苯环对位" */
    @Size(max = 100, message = "作用区域最长 100 字符")
    private String targetRegion;
}
