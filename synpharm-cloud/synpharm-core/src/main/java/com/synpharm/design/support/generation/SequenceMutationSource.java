package com.synpharm.design.support.generation;

import com.synpharm.design.support.SequenceHasher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 序列单点突变生成器（S4 的默认实现）。
 *
 * <p>为什么这是当前环境下唯一能真跑的「生成」：从头生成需要 crem（未部署），
 * 小分子结构编辑需要 edit 端点（未实现），而<b>序列的单点突变是纯字符串操作</b> ——
 * Java 侧就能完成，不需要任何化学引擎。产出交给 S6 用真 RDKit（对序列类分子走
 * 理化/免疫原性之外的判定）与约束规则过滤。
 *
 * <p><b>可复现性</b>：随机数由 {@code (全局种子, 片序号)} 派生，
 * 同一份 plan 重放必然得到同一批候选。这是「凭快照回放」的最低要求。
 *
 * <p><b>明确不做的事</b>：
 * <ul>
 *   <li>不做 CDR 保护 —— 基线里没有 CDR 注释，无法判定哪些位置不能动。
 *       全局单点突变可能落在框架区以外的关键位点，这是已知风险，记在 plan.rationale 里。</li>
 *   <li>不做多点突变 —— 组合空间爆炸且难以追溯「哪几个点一起改的」。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class SequenceMutationSource implements CandidateSource {

    /** 20 种标准氨基酸 */
    static final char[] AMINO_ACIDS = "ACDEFGHIKLMNPQRSTVWY".toCharArray();

    @Override
    public String sourceType() {
        return "SEQUENCE_MUTATION";
    }

    @Override
    public List<GenerationDtos.GeneratedCandidate> produce(
            GenerationDtos.GenerationContext context,
            GenerationDtos.GenerationPlan plan,
            int sliceNo,
            int count) {

        List<GenerationDtos.SeedMolecule> seeds = context.seeds().stream()
                .filter(GenerationDtos.SeedMolecule::isSequence)
                .toList();
        if (seeds.isEmpty()) {
            return List.of();
        }

        // 种子由 (plan.seed, sliceNo) 决定：同一次生成的不同片必然不同，
        // 同一次生成重放时又完全一致
        SplittableRandom random = new SplittableRandom(plan.seed() * 1_000_003L + sliceNo);

        List<GenerationDtos.GeneratedCandidate> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GenerationDtos.SeedMolecule seed = seeds.get(i % seeds.size());
            String heavy = mutateOne(seed.heavyChain(), random);
            String light = mutateOne(seed.lightChain(), random);

            // 同一条链都没动（父本只有一条链且长度为 1 等极端情况）：跳过，避免产出与父本完全相同的候选
            if (equalsIgnoreCase(heavy, seed.heavyChain()) && equalsIgnoreCase(light, seed.lightChain())) {
                continue;
            }

            List<String> extras = seed.extraChains() == null ? List.of() : seed.extraChains();
            List<SequenceHasher.Chain> extraChains = new ArrayList<>();
            for (int e = 0; e < extras.size(); e++) {
                extraChains.add(new SequenceHasher.Chain("CHAIN" + (e + 2), extras.get(e)));
            }

            String sequenceHash = SequenceHasher.hash(heavy, light, extraChains);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("parentRef", seed.ref());
            params.put("mutatedHeavy", !equalsIgnoreCase(heavy, seed.heavyChain()));
            params.put("mutatedLight", !equalsIgnoreCase(light, seed.lightChain()));

            out.add(new GenerationDtos.GeneratedCandidate(
                    "S" + sliceNo + "-" + (i + 1),
                    null,
                    heavy,
                    light,
                    extras.isEmpty() ? null : extras,
                    sequenceHash,
                    sourceType(),
                    seed.ref(),
                    params));
        }
        return out;
    }

    /**
     * 单点突变：随机选一个位置换成另一种氨基酸。
     *
     * <p>位置在整个序列上均匀选取（不区分框架区 / CDR）——
     * 因为没有 CDR 注释。这个取舍写在 plan.rationale 里，用户能看见。
     */
    String mutateOne(String sequence, SplittableRandom random) {
        String norm = SequenceHasher.normalize(sequence);
        if (norm.isEmpty()) {
            return null;
        }
        if (norm.length() == 1) {
            // 单残基无法"替换成另一种"以外的突变，随机换即可
            char original = norm.charAt(0);
            char replacement = pickDifferent(original, random);
            return String.valueOf(replacement);
        }
        int pos = random.nextInt(norm.length());
        char original = norm.charAt(pos);
        char replacement = pickDifferent(original, random);
        return norm.substring(0, pos) + replacement + norm.substring(pos + 1);
    }

    private char pickDifferent(char original, SplittableRandom random) {
        char candidate = AMINO_ACIDS[random.nextInt(AMINO_ACIDS.length)];
        // 最多尝试几次换到不同残基；序列里出现非标准字符时也允许替换
        int guard = 0;
        while (candidate == original && guard++ < 8) {
            candidate = AMINO_ACIDS[random.nextInt(AMINO_ACIDS.length)];
        }
        return candidate;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        String normA = SequenceHasher.normalize(a);
        String normB = SequenceHasher.normalize(b);
        return normA.equals(normB);
    }
}
