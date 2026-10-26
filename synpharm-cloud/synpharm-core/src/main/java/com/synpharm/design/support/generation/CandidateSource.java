package com.synpharm.design.support.generation;

import java.util.List;

/**
 * 候选来源（S4 的执行者）。
 *
 * <p>把「怎么产生候选」从流水线里抽出来，是因为当前环境有三种现实情况必须共存：
 * <ul>
 *   <li><b>SEED_LIBRARY</b> —— 用户直接提供候选集。不做任何改写，只走 S5~S9 的筛选。
 *       小分子在 crem 未部署、edit 端点未实现的情况下，这是唯一能真跑通的路径。</li>
 *   <li><b>SEQUENCE_MUTATION</b> —— 基于父本序列做单点突变。纯字符串操作，Java 侧即可实现，
 *       结果可复现（种子固定则突变固定）。</li>
 *   <li><b>ENGINE</b> —— 调计算层生成引擎（crem）。当前 NOT_IMPLEMENTED，接口先留。</li>
 * </ul>
 *
 * <p><b>三种来源产出的候选含金量不同，必须在 {@code plan.degraded} 上体现</b>，
 * 否则用户无法区分"模型生成的"和"我粘进去的"。
 *
 * @author SynPharm Team
 */
public interface CandidateSource {

    /** 来源类型，与 {@code generation_run.plan_json.strategy} 对应 */
    String sourceType();

    /**
     * 产生一批候选。
     *
     * @param context  S0 上下文（种子、约束、配额）
     * @param plan     S3 计划
     * @param sliceNo  片序号（从 1 开始），用于生成稳定且互不相同的随机序列
     * @param count    本片应产出数量
     * @return 候选列表；数量可以少于 {@code count}（空间不足时），不可为 null
     */
    List<GenerationDtos.GeneratedCandidate> produce(
            GenerationDtos.GenerationContext context,
            GenerationDtos.GenerationPlan plan,
            int sliceNo,
            int count);
}
