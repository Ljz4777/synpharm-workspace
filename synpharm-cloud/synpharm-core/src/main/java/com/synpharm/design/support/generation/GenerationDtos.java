package com.synpharm.design.support.generation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成阶段的内部数据结构（S0~S4 的输入输出）。
 *
 * <p>放在一起是因为它们互相引用且都很小；拆成十个文件会让「一次生成到底经过了哪些判断」
 * 变得难读。
 *
 * @author SynPharm Team
 */
public final class GenerationDtos {

    private GenerationDtos() {
    }

    // ------------------------------------------------------------------ S0 上下文

    /**
     * S0 校验通过的上下文。八块内容全部来自<b>冻结的基线快照</b>，
     * 不读项目当前状态 —— 用户中途改约束不影响已提交的任务。
     */
    public record GenerationContext(
            long projectId,
            long baselineSnapshotId,
            String modalityCode,
            boolean seqRequired,
            String moaCode,
            Integer minCandidates,
            BigDecimal quotaGpuHours,
            BigDecimal quotaGpuUsed,
            Integer quotaWetLab,
            int configuredMinCandidates,
            List<HardRule> hardRules,
            List<SeedMolecule> seeds,
            boolean pocketAvailable
    ) {
        /** 剩余 GPU 小时；null 表示不限 */
        public BigDecimal remainingGpuHours() {
            if (quotaGpuHours == null) {
                return null;
            }
            BigDecimal used = quotaGpuUsed == null ? BigDecimal.ZERO : quotaGpuUsed;
            return quotaGpuHours.subtract(used);
        }
    }

    /** 基线块4 里的一条硬约束（判定在 S6 用 BoundaryJudge 执行） */
    public record HardRule(
            Long ruleId,
            String dimension,
            String operator,
            BigDecimal thresholdNum,
            BigDecimal thresholdMax,
            BigDecimal tolerance,
            String boundaryPolicy,
            String humanReadable,
            String ruleType,
            Map<String, Object> params
    ) {
    }

    /**
     * 生成种子：父本候选或用户提供的结构。
     *
     * <p>为什么需要「用户提供的种子」：从头生成依赖 crem（未部署），
     * 而小分子的结构编辑也需要 edit 端点（未实现）。允许用户粘几个 SMILES/序列进来，
     * 系统就能做定向衍生并用真 RDKit 粗筛 —— 这是当前环境下唯一能真跑通的路径。
     */
    public record SeedMolecule(
            String ref,
            String smiles,
            String heavyChain,
            String lightChain,
            List<String> extraChains
    ) {
        public boolean isSequence() {
            return (heavyChain != null && !heavyChain.isBlank())
                    || (lightChain != null && !lightChain.isBlank())
                    || (extraChains != null && !extraChains.isEmpty());
        }
    }

    // ------------------------------------------------------------------ S2 空间

    /**
     * S2「搜索空间定义」的产出。
     *
     * <p>{@code spaceSize} 直接决定 S3 的采样配额上限 —— 不许人工拍脑袋定 n_samples。
     */
    public record SpaceManifest(
            String sourceType,
            long spaceSize,
            int seedCount,
            String description,
            List<String> exclusions
    ) {
    }

    // ------------------------------------------------------------------ S3 计划

    /**
     * S3 的生成计划。落到 {@code generation_run.plan_json}。
     *
     * <p>{@code degraded} 与 {@code degradedReason} 是这套设计里最重要的两个字段：
     * 当首选引擎不可用而退化到规则实现时，必须显式标注。
     * 否则用户看到"生成成功"，却不知道产出是规则拼的而不是模型生成的。
     */
    public record GenerationPlan(
            String strategy,
            List<PlannedTool> tools,
            String fallbackStrategy,
            int nSamples,
            int batchSize,
            long seed,
            BigDecimal estimatedGpuHours,
            String rationale,
            BigDecimal confidence,
            boolean degraded,
            String degradedReason
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategy", strategy);
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (PlannedTool t : tools) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("toolCode", t.toolCode());
                item.put("params", t.params());
                toolList.add(item);
            }
            m.put("tools", toolList);
            m.put("fallbackStrategy", fallbackStrategy);
            m.put("nSamples", nSamples);
            m.put("batchSize", batchSize);
            m.put("seed", seed);
            m.put("estimatedGpuHours", estimatedGpuHours);
            m.put("rationale", rationale);
            m.put("confidence", confidence);
            m.put("degraded", degraded);
            m.put("degradedReason", degradedReason);
            return m;
        }
    }

    public record PlannedTool(String toolCode, Map<String, Object> params) {
    }

    // ------------------------------------------------------------------ S4 产物

    /**
     * 一个生成出来的原始候选（尚未落库、尚未去重、尚未筛）。
     *
     * <p>{@code tempRef} 是本次生成内的临时编号，用于把 S5/S6/S7 的日志
     * 与最终落库的 candidate 关联起来 —— 计算层回传的 ref 就是它。
     */
    public record GeneratedCandidate(
            String tempRef,
            String smiles,
            String heavyChain,
            String lightChain,
            List<String> extraChains,
            String sequenceHash,
            String strategyName,
            String sourceRef,
            Map<String, Object> generationParams
    ) {
    }
}
