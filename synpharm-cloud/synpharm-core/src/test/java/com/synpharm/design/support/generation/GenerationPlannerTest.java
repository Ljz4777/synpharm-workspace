package com.synpharm.design.support.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S2 空间估算 + S3 计划拟定。
 *
 * <p>这里守的是「n_samples 由空间和配额算出来，不许人工拍脑袋」这条纪律，
 * 以及 degraded 标注 —— 没有它，用户看到「生成成功」却不知道产出是规则拼的。
 *
 * @author SynPharm Team
 */
class GenerationPlannerTest {

    private final GenerationPlanner planner = new GenerationPlanner();

    private static GenerationDtos.HardRule oneRule() {
        return new GenerationDtos.HardRule(1L, null, null, null, null, null,
                "STRICT", "MW 150~600", "NUMERIC_RANGE",
                Map.of("field", "MolWt", "min", 150, "max", 600));
    }

    private static GenerationDtos.GenerationContext ctx(int minCandidates,
                                                        List<GenerationDtos.SeedMolecule> seeds) {
        return new GenerationDtos.GenerationContext(
                1L, 2L, "SMALL_MOLECULE", false, "INHIBITOR",
                minCandidates, new BigDecimal("48"), BigDecimal.ZERO, 8,
                minCandidates, List.of(oneRule()), seeds, false);
    }

    /** 序列类形式：S2 只按「长度 × 19」估算空间，因此必须置 seqRequired */
    private static GenerationDtos.GenerationContext seqCtx(int minCandidates,
                                                           List<GenerationDtos.SeedMolecule> seeds) {
        return new GenerationDtos.GenerationContext(
                1L, 2L, "ANTIBODY", true, "INHIBITOR",
                minCandidates, new BigDecimal("48"), BigDecimal.ZERO, 8,
                minCandidates, List.of(oneRule()), seeds, false);
    }

    private static GenerationDtos.SeedMolecule smilesSeed() {
        return new GenerationDtos.SeedMolecule("S1", "CCO", null, null, null);
    }

    // ------------------------------------------------------------------ S2

    @Test
    @DisplayName("导入式小分子：空间 = 种子数")
    void spaceOfSeedLibrary() {
        GenerationDtos.SpaceManifest space = planner.estimateSpace(ctx(5, List.of(smilesSeed())));

        assertThat(space.sourceType()).isEqualTo("SEED_LIBRARY");
        assertThat(space.spaceSize()).isEqualTo(1);
        assertThat(space.seedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("序列：单点突变空间按 长度 × 19 累加")
    void spaceOfSequenceMutation() {
        String heavy = "EVQLVESGGGLVQPGGSLRLSCAAS";   // 25
        GenerationDtos.SeedMolecule seed =
                new GenerationDtos.SeedMolecule("P1", null, heavy, null, null);

        GenerationDtos.SpaceManifest space = planner.estimateSpace(seqCtx(5, List.of(seed)));

        assertThat(space.sourceType()).isEqualTo("SEQUENCE_MUTATION");
        assertThat(space.spaceSize()).isEqualTo(25L * 19L);
    }

    @Test
    @DisplayName("无种子且无引擎 → 空间为 0（S0 据此拒绝，而不是产出空集）")
    void emptySpaceWhenNoSeed() {
        GenerationDtos.SpaceManifest space = planner.estimateSpace(ctx(5, List.of()));

        assertThat(space.spaceSize()).isZero();
        assertThat(space.description()).contains("没有可用的生成起点");
    }

    // ------------------------------------------------------------------ S3

    @Test
    @DisplayName("nSamples = 目标数 × 倍数，且不超过空间上限")
    void nSamplesBoundedBySpace() {
        GenerationDtos.GenerationContext c = ctx(5, List.of(smilesSeed()));
        GenerationDtos.SpaceManifest space = planner.estimateSpace(c);

        GenerationDtos.GenerationPlan plan = planner.plan(c, space, 42L);

        // 目标 5 × 3 = 15，但空间只有 1 → 取 1，绝不越过空间规模
        assertThat(plan.nSamples()).isEqualTo(1);
        assertThat(plan.batchSize()).isEqualTo(1);
        assertThat(plan.seed()).isEqualTo(42L);
    }

    @Test
    @DisplayName("大空间时 nSamples 受 MAX_SAMPLES 封顶")
    void nSamplesCappedByMax() {
        String heavy = "ACDEFGHIKLMNPQRSTVWY".repeat(20);   // 400 长 → 7600 突变位点
        GenerationDtos.SeedMolecule seed =
                new GenerationDtos.SeedMolecule("P1", null, heavy, null, null);
        // 目标 1200 × 3 = 3600 > MAX_SAMPLES，空间 7600 也够大 → 应被 MAX_SAMPLES 截住
        GenerationDtos.GenerationContext c = seqCtx(1200, List.of(seed));
        GenerationDtos.SpaceManifest space = planner.estimateSpace(c);

        GenerationDtos.GenerationPlan plan = planner.plan(c, space, 7L);

        assertThat(plan.nSamples()).isEqualTo(GenerationPlanner.MAX_SAMPLES);
        assertThat(plan.batchSize()).isEqualTo(GenerationPlanner.DEFAULT_BATCH_SIZE);
    }

    @Test
    @DisplayName("规则式来源必须标 degraded 并给出可读原因")
    void degradedForNonEngineSources() {
        GenerationDtos.GenerationContext c = ctx(5, List.of(smilesSeed()));
        GenerationDtos.GenerationPlan plan = planner.plan(c, planner.estimateSpace(c), 1L);

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.degradedReason()).contains("未做结构创新");
        // 降级产出的置信度必须低于引擎，否则前端无法据此提示
        assertThat(plan.confidence()).isLessThan(new BigDecimal("0.85"));
    }

    @Test
    @DisplayName("规则式实现不占 GPU：estimate 记 0，而不是给一个好看的估值")
    void noGpuCostForRuleBased() {
        GenerationDtos.GenerationContext c = ctx(5, List.of(smilesSeed()));
        GenerationDtos.GenerationPlan plan = planner.plan(c, planner.estimateSpace(c), 1L);

        assertThat(plan.estimatedGpuHours()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("计划可复现：同参数两次生成的 plan map 完全一致")
    void planIsReproducible() {
        GenerationDtos.GenerationContext c = ctx(5, List.of(smilesSeed()));

        Map<String, Object> first = planner.plan(c, planner.estimateSpace(c), 99L).toMap();
        Map<String, Object> second = planner.plan(c, planner.estimateSpace(c), 99L).toMap();

        assertThat(first).isEqualTo(second);
    }
}
