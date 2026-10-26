package com.synpharm.design.support.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S7 多样性配额。
 *
 * <p>核心是<b>轮转填充</b>：不能因为某一簇分数高就把名额全给它，
 * 否则「多样性」只是口号。以及簇数不足时必须告警而不是静默放行。
 *
 * @author SynPharm Team
 */
class DiversityQuotaStageTest {

    private final DiversityQuotaStage stage = new DiversityQuotaStage();

    private static GenerationDtos.GeneratedCandidate candidate(String ref) {
        return new GenerationDtos.GeneratedCandidate(ref, "CCO", null, null, null,
                null, "SEED_LIBRARY", ref, null);
    }

    private static PreFilterStage.Entry entry(String ref, String cluster, String score) {
        DedupStage.Entry dedup = new DedupStage.Entry(
                candidate(ref), "CCO", null, null, cluster, null, null);
        return new PreFilterStage.Entry(dedup, new BigDecimal(score), List.of(), List.of());
    }

    @Test
    @DisplayName("空输入直接返回告警，不允许下游拿到空集")
    void emptyInputWarns() {
        DiversityQuotaStage.Result r = stage.allocate(List.of(), 5);

        assertThat(r.selected()).isEmpty();
        assertThat(r.clusterCount()).isZero();
        assertThat(r.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("轮转填充：三簇各 3 个、名额 6 → 每簇恰好取 2 个")
    void roundRobinFillsEvenly() {
        List<PreFilterStage.Entry> passed = new ArrayList<>();
        for (String cluster : List.of("A", "B", "C")) {
            // A 簇分数最高，若无轮转会被它独占
            String score = "A".equals(cluster) ? "0.9" : "0.5";
            for (int i = 0; i < 3; i++) {
                passed.add(entry(cluster + i, cluster, score));
            }
        }

        DiversityQuotaStage.Result r = stage.allocate(passed, 6);

        assertThat(r.selected()).hasSize(6);
        assertThat(r.clusterCount()).isEqualTo(3);
        assertThat(r.selected()).filteredOn(e -> "A".equals(e.clusterId())).hasSize(2);
        assertThat(r.selected()).filteredOn(e -> "B".equals(e.clusterId())).hasSize(2);
        assertThat(r.selected()).filteredOn(e -> "C".equals(e.clusterId())).hasSize(2);
        assertThat(r.cut()).hasSize(3);
    }

    @Test
    @DisplayName("簇内按 preScore 降序取，保证高分的先入选")
    void picksBestWithinCluster() {
        List<PreFilterStage.Entry> passed = List.of(
                entry("low", "A", "0.20"),
                entry("high", "A", "0.95"),
                entry("mid", "A", "0.60"));

        DiversityQuotaStage.Result r = stage.allocate(passed, 1);

        assertThat(r.selected()).hasSize(1);
        assertThat(r.selected().get(0).filterEntry().dedupEntry().candidate().tempRef())
                .isEqualTo("high");
    }

    @Test
    @DisplayName("有效簇 < 3 → 告警（覆盖不足），但仍返回候选")
    void warnsWhenTooFewClusters() {
        List<PreFilterStage.Entry> passed = List.of(
                entry("a1", "A", "0.8"),
                entry("a2", "A", "0.7"),
                entry("b1", "B", "0.6"));

        DiversityQuotaStage.Result r = stage.allocate(passed, 10);

        assertThat(r.clusterCount()).isEqualTo(2);
        assertThat(r.selected()).hasSize(3);
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("簇"));
        assertThat(r.clusterCount()).isLessThan(DiversityQuotaStage.MIN_VALID_CLUSTERS);
    }

    @Test
    @DisplayName("候选少于名额时全选，不凑数")
    void selectsAllWhenFewerThanQuota() {
        List<PreFilterStage.Entry> passed = List.of(entry("a1", "A", "0.8"));

        DiversityQuotaStage.Result r = stage.allocate(passed, 50);

        assertThat(r.selected()).hasSize(1);
        assertThat(r.cut()).isEmpty();
    }

    @Test
    @DisplayName("clusterId 缺失时按候选自身成簇，不会把无簇候选挤成一堆")
    void nullClusterTreatedAsOwnGroup() {
        List<PreFilterStage.Entry> passed = List.of(
                entry("x1", null, "0.5"),
                entry("x2", null, "0.5"));

        DiversityQuotaStage.Result r = stage.allocate(passed, 5);

        assertThat(r.clusterCount()).isEqualTo(2);
        assertThat(r.selected()).hasSize(2);
    }
}
