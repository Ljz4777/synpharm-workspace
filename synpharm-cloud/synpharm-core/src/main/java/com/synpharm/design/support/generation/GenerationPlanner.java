package com.synpharm.design.support.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * S2 搜索空间定义 + S3 生成策略拟定（06 §4.1）。
 *
 * <p>这两步合在一个类里，因为它们其实是一个判断的两面：
 * <b>能枚举出多大的空间</b>决定<b>该用哪种策略、采多少样</b>。
 * 拆开会让「空间为 0 却仍然拟了策略」这种错配成为可能。
 *
 * <p>核心纪律（照抄设计原文）：
 * <ul>
 *   <li>空间规模直接决定采样配额，<b>不许人工拍脑袋定 n_samples</b>；</li>
 *   <li>空间为空 → 失败，不允许带病往下走；</li>
 *   <li>降级到规则实现必须显式标注（{@code degraded}），否则用户分不清产出是模型造的还是规则拼的。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class GenerationPlanner {

    /** 每产生 1 个目标候选需要的采样倍数（粗筛会淘汰大部分，留余量） */
    static final int SAMPLE_MULTIPLIER = 3;

    /** 单批最大采样数：再多就会被后端 30s 超时打断 */
    static final int MAX_SAMPLES = 3000;

    /** 默认分片大小 */
    static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * S2：估算搜索空间。
     *
     * @param context S0 上下文
     * @return 空间清单；{@code spaceSize == 0} 表示无法生成，调用方必须失败
     */
    public GenerationDtos.SpaceManifest estimateSpace(GenerationDtos.GenerationContext context) {
        List<GenerationDtos.SeedMolecule> seeds = context.seeds();
        List<String> exclusions = new ArrayList<>();

        if (seeds.isEmpty()) {
            // 没有种子、且没有可用生成引擎 —— 这是当前环境最常见的情况
            return new GenerationDtos.SpaceManifest(
                    "NONE", 0, 0,
                    "没有可用的生成起点：请求未提供父本/种子分子，且从头生成引擎（crem）未部署",
                    List.of("NO_SEED_AND_NO_GENERATOR"));
        }

        if (context.seqRequired()) {
            long space = 0;
            int seqSeeds = 0;
            for (GenerationDtos.SeedMolecule seed : seeds) {
                if (!seed.isSequence()) {
                    exclusions.add("种子 " + seed.ref() + " 不是序列（当前分子形式要求序列）");
                    continue;
                }
                seqSeeds++;
                space += singlePointSpace(seed);
            }
            if (seqSeeds == 0) {
                return new GenerationDtos.SpaceManifest("SEQUENCE_MUTATION", 0, 0,
                        "分子形式要求序列，但提供的种子都不是序列", exclusions);
            }
            return new GenerationDtos.SpaceManifest(
                    "SEQUENCE_MUTATION", space, seqSeeds,
                    "单点突变空间：" + seqSeeds + " 条父本 × (序列长度 × 19 种替换) 之和 = " + space
                            + "（不含多点突变与 CDR 定向设计）",
                    exclusions);
        }

        // 小分子：没有结构编辑端点，只能原样导入再筛
        int smilesSeeds = 0;
        for (GenerationDtos.SeedMolecule seed : seeds) {
            if (seed.smiles() != null && !seed.smiles().isBlank()) {
                smilesSeeds++;
            } else {
                exclusions.add("种子 " + seed.ref() + " 没有 SMILES");
            }
        }
        if (smilesSeeds == 0) {
            return new GenerationDtos.SpaceManifest("SEED_LIBRARY", 0, 0,
                    "没有可导入的 SMILES 种子", exclusions);
        }
        exclusions.add("小分子结构编辑需要 /v1/molecules/edit（未实现），本轮不做结构创新");
        return new GenerationDtos.SpaceManifest(
                "SEED_LIBRARY", smilesSeeds, smilesSeeds,
                "导入 " + smilesSeeds + " 个候选进入筛选漏斗（去重 → 粗筛 → 配额），不做结构创新",
                exclusions);
    }

    /**
     * S3：拟定生成计划。
     *
     * @param context S0 上下文
     * @param space   S2 空间清单
     * @param seed    全局种子（请求给或随机生成）
     */
    public GenerationDtos.GenerationPlan plan(
            GenerationDtos.GenerationContext context,
            GenerationDtos.SpaceManifest space,
            long seed) {

        int targetCandidates = Math.max(1, context.configuredMinCandidates());

        // 采样数 = 目标产出 × 倍数，受空间上限与全局上限双重约束
        long wanted = (long) targetCandidates * SAMPLE_MULTIPLIER;
        int nSamples = (int) Math.min(Math.min(wanted, space.spaceSize()), MAX_SAMPLES);
        nSamples = Math.max(nSamples, Math.min(targetCandidates, (int) Math.min(space.spaceSize(), MAX_SAMPLES)));

        int batchSize = Math.min(DEFAULT_BATCH_SIZE, Math.max(1, nSamples));

        boolean degraded = !"ENGINE".equals(space.sourceType());
        String degradedReason = degraded
                ? switch (space.sourceType()) {
                    case "SEQUENCE_MUTATION" -> "使用规则式单点突变（无生成引擎）；未启用 CDR 保护（基线无 CDR 注释）";
                    case "SEED_LIBRARY" -> "用户提供的候选直接进入筛选，未做结构创新";
                    default -> "未使用生成引擎";
                }
                : null;

        String rationale = buildRationale(context, space, targetCandidates, nSamples);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nSamples", nSamples);
        params.put("targetCandidates", targetCandidates);
        params.put("seed", seed);

        // 规则式实现不占 GPU；诚实记 0，而不是给一个好看的估值
        BigDecimal estimatedGpuHours = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);

        return new GenerationDtos.GenerationPlan(
                space.sourceType(),
                List.of(new GenerationDtos.PlannedTool(
                        "SEQUENCE_MUTATION".equals(space.sourceType()) ? "rdkit" : "rdkit", params)),
                "SEED_LIBRARY",
                nSamples,
                batchSize,
                seed,
                estimatedGpuHours,
                rationale,
                degraded ? new BigDecimal("0.6000") : new BigDecimal("0.8500"),
                degraded,
                degradedReason);
    }

    private String buildRationale(
            GenerationDtos.GenerationContext context,
            GenerationDtos.SpaceManifest space,
            int targetCandidates,
            int nSamples) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("目标至少 ").append(targetCandidates).append(" 个候选（基线块7 min_candidates）");
        sb.append("；按 ").append(SAMPLE_MULTIPLIER).append(" 倍余量采样 ").append(nSamples).append(" 个");
        sb.append("（空间上限 ").append(space.spaceSize()).append("）");
        if (context.pocketAvailable()) {
            sb.append("；基线含可用口袋，但本轮策略不依赖口袋（规则式不做对接）");
        } else {
            sb.append("；基线无可用口袋，已排除依赖口袋的策略");
        }
        if (context.hardRules().isEmpty()) {
            sb.append("；基线无硬约束（理论上不该发生，冻结时应已拦住）");
        } else {
            sb.append("；S6 将按 ").append(context.hardRules().size()).append(" 条硬约束逐条判定");
        }
        return sb.toString();
    }

    /** 单条序列的单点突变空间 = 长度 × 19（排除自身） */
    private long singlePointSpace(GenerationDtos.SeedMolecule seed) {
        long space = 0;
        for (String chain : List.of(
                seed.heavyChain() == null ? "" : seed.heavyChain(),
                seed.lightChain() == null ? "" : seed.lightChain())) {
            int len = chain.replaceAll("\\s+", "").length();
            space += (long) len * 19;
        }
        if (seed.extraChains() != null) {
            for (String chain : seed.extraChains()) {
                if (chain != null) {
                    space += (long) chain.replaceAll("\\s+", "").length() * 19;
                }
            }
        }
        return space;
    }
}
