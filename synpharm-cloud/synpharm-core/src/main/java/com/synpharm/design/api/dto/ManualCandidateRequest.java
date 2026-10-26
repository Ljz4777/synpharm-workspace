package com.synpharm.design.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用二维画布上画出来的结构建一个候选（阶段 A 的「从分子开始」入口）。
 *
 * <p>为什么不接受「调用方传来的分子量/InChIKey」：这些必须由引擎算，不能由前端填。
 * 前端能填，就能填出一个「InChIKey 看起来对、其实是另一个分子」的候选，
 * 而后续所有判定都建立在「这个结构到底是不是你以为的那个分子」之上。
 *
 * @author SynPharm Team
 */
@Data
public class ManualCandidateRequest {

    @NotBlank(message = "SMILES 不能为空")
    @Size(max = 2000, message = "SMILES 最长 2000 字符")
    private String smiles;

    /** 可选显示名；为空时用「画布-<InChIKey 前 8 位>」 */
    @Size(max = 200, message = "名称最长 200 字符")
    private String moleculeName;

    /** 为空表示挂到项目当前轮次；手工候选必须属于某一轮，否则进不了后续阶段 */
    private Long roundId;
}
