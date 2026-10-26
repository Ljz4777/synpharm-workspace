package com.synpharm.design.support.generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 种子库来源：把用户提供的候选原样作为 S4 产物，不改写结构。
 *
 * <p><b>这不是"假生成"，是"导入后筛选"</b>：S5 去重 / S6 粗筛 / S7 配额 / S8 快照
 * 全部照常执行，用的是真 RDKit 指标。区别只在于 S4 没有做结构创新。
 *
 * <p>为什么需要它：从头生成依赖 crem（未部署），小分子结构编辑需要 edit 端点（未实现）。
 * 如果不提供这条路，小分子项目在阶段 C 完全无法产出候选 —— 而"导入一批分子、
 * 用真指标筛出值得做的"本身就是研发里的常见动作。
 *
 * <p>产出数量受种子数与 {@code count} 双重限制：种子用完就停，不重复产出同一结构
 * （否则 S5 会把它们当重复合并掉，白白占用漏斗名额）。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class SeedLibrarySource implements CandidateSource {

    @Override
    public String sourceType() {
        return "SEED_LIBRARY";
    }

    @Override
    public List<GenerationDtos.GeneratedCandidate> produce(
            GenerationDtos.GenerationContext context,
            GenerationDtos.GenerationPlan plan,
            int sliceNo,
            int count) {

        List<GenerationDtos.SeedMolecule> seeds = context.seeds();
        if (seeds.isEmpty()) {
            return List.of();
        }

        SplittableRandom random = new SplittableRandom(plan.seed() * 1_000_003L + sliceNo);

        // 分片各取一段，避免多片重复产出同一批种子
        int total = seeds.size();
        int perSlice = Math.max(1, (int) Math.ceil((double) total / Math.max(1, plan.batchSize() > 0
                ? (int) Math.ceil((double) plan.nSamples() / plan.batchSize())
                : 1)));
        int from = Math.min(total, (sliceNo - 1) * perSlice);
        int to = Math.min(total, from + Math.min(count, perSlice));

        List<GenerationDtos.GeneratedCandidate> out = new ArrayList<>();
        for (int i = from; i < to; i++) {
            GenerationDtos.SeedMolecule seed = seeds.get(i);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("imported", true);
            params.put("seedIndex", i);
            // 记录一个未使用的随机数消耗，保证同一 plan 的后续片序列一致
            random.nextInt(7);

            out.add(new GenerationDtos.GeneratedCandidate(
                    "S" + sliceNo + "-" + (i + 1),
                    seed.smiles(),
                    seed.heavyChain(),
                    seed.lightChain(),
                    seed.extraChains(),
                    null,
                    sourceType(),
                    seed.ref(),
                    params));
        }
        int skipped = count - out.size();
        if (skipped > 0) {
            log.info("分片 {} 的种子已用尽：请求 {}，实际 {}", sliceNo, count, out.size());
        }
        return out;
    }
}
