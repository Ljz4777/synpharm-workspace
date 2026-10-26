package com.synpharm.design.application;

import com.synpharm.design.api.dto.AbandonResponse;
import com.synpharm.design.api.dto.CandidateTreeResponse;
import com.synpharm.design.api.dto.EditGapResponse;
import com.synpharm.design.api.dto.EditImpactResponse;

/**
 * 编辑用例（E0~E3 + 版本树，06 §5.2/§5.7）。
 *
 * <p>E4~E7（设计空间枚举 / 快筛 / 执行 / delta）另见 {@code EditBatchAppService}：
 * 那几条要真改结构，能力边界与前四条不同，分开更诚实。
 *
 * @author SynPharm Team
 */
public interface CandidateEditAppService {

    /**
     * E0+E1：诊断缺口并落 {@code edit_gap_report}。
     *
     * <p>E0 准入的五项检查：候选存在 → 归属正确 → 有评估冻结快照 → 快照未过期
     * （{@code needsReEval=0}）→ 分支未放弃。任一不过直接拒绝，
     * <b>不给默认值</b>：拿一份过期快照做缺口诊断，得出的结论必然误导。
     */
    EditGapResponse diagnose(Long candidateId);

    /** 取最近一次缺口诊断；没有则抛 {@code RESOURCE_NOT_FOUND} */
    EditGapResponse latestGaps(Long candidateId);

    /**
     * E3：影响面评估并落 {@code edit_impact_assessment}。
     *
     * <p>返回 BLOCK 时 {@code executable=false}，但记录仍然写入 ——
     * 「这次被拦了、为什么被拦」本身就是要留痕的事实。
     */
    EditImpactResponse previewImpact(Long candidateId, String actionCode, String targetRegion);

    /** 版本树（含 abandoned 分支） */
    CandidateTreeResponse tree(Long candidateId);

    /**
     * 放弃分支：只改 {@code branch_status}，不删除。
     *
     * <p>放弃的分支保留在树里，但不再参与后续筛选与决策 ——
     * 删掉会让「为什么当时那条路走不通」永久丢失。
     *
     * <p>返回轻量结果而不是诊断报告：一个从未诊断过的候选也应该能被放弃。
     */
    AbandonResponse abandon(Long candidateId);

    /**
     * 切回父版本：<b>新建一个与父同内容的新版本</b>，不是回滚。
     *
     * <p>历史只增不删：回滚会把「曾经改成什么样」抹掉，
     * 而那条被放弃的路径恰恰是回流沉淀的样本。
     *
     * @return 新版本候选 id
     */
    Long revert(Long candidateId);
}
