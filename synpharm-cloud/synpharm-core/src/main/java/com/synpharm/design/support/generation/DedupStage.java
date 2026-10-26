package com.synpharm.design.support.generation;

import com.synpharm.design.infrastructure.client.ChemistryClient;
import com.synpharm.design.infrastructure.client.ChemistryDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * S5 去重（06 §4.1）。
 *
 * <p>两类分子走两条完全不同的路，混用会出错：
 * <ul>
 *   <li><b>小分子</b>：调计算层 {@code /v1/chemistry/standardize} 做规范化 + ECFP4 Tanimoto 聚类。
 *       必须规范化后再比 —— {@code CC(=O)Oc1ccccc1C(=O)O} 与
 *       {@code OC(=O)c1ccccc1OC(C)=O} 是同一个分子，字符串比对会把它们当成两个候选。</li>
 *   <li><b>序列</b>：按 {@code sequenceHash} 精确去重。抗体没有"相似就合并"的语义 ——
 *       两条只差一个残基的抗体是两个真实不同的分子，相似度合并不适用。</li>
 * </ul>
 *
 * <p>S5 不会失败（纯确定性运算）。计算层不可用时<b>抛异常</b>而不是"跳过去重继续"：
 * 跳过去重会让重复分子占满候选名额，而用户完全看不出来。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DedupStage {

    /** ECFP4 Tanimoto 阈值：0.9 以上视为同一分子 */
    static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.90");

    private final ChemistryClient chemistryClient;

    /** 去重结果 */
    public record Result(
            List<Entry> kept,
            List<Entry> merged,
            int clusterCount
    ) {
    }

    /**
     * 去重后的一个条目。
     *
     * @param candidate  原始候选
     * @param canonicalSmiles 规范化 SMILES（小分子）
     * @param inchikey   全平台去重键（小分子）
     * @param murcko     骨架（簇标签来源之一）
     * @param clusterId  簇标识
     * @param mergedInto 被合并到哪个 tempRef；未被合并为 null
     * @param similarity 与代表者的相似度；未被合并为 null
     */
    public record Entry(
            GenerationDtos.GeneratedCandidate candidate,
            String canonicalSmiles,
            String inchikey,
            String murcko,
            String clusterId,
            String mergedInto,
            BigDecimal similarity
    ) {
        public boolean isMerged() {
            return mergedInto != null;
        }
    }

    public Result dedup(List<GenerationDtos.GeneratedCandidate> candidates) {
        if (candidates.isEmpty()) {
            return new Result(List.of(), List.of(), 0);
        }

        List<GenerationDtos.GeneratedCandidate> smallMolecules = new ArrayList<>();
        List<GenerationDtos.GeneratedCandidate> sequences = new ArrayList<>();
        for (GenerationDtos.GeneratedCandidate c : candidates) {
            if (c.smiles() != null && !c.smiles().isBlank()) {
                smallMolecules.add(c);
            } else {
                sequences.add(c);
            }
        }

        List<Entry> kept = new ArrayList<>();
        List<Entry> merged = new ArrayList<>();
        Set<String> clusterIds = new LinkedHashSet<>();

        if (!smallMolecules.isEmpty()) {
            dedupSmallMolecules(smallMolecules, kept, merged, clusterIds);
        }
        if (!sequences.isEmpty()) {
            dedupSequences(sequences, kept, merged, clusterIds);
        }

        log.info("S5 去重：输入 {} → 保留 {}，合并 {}，簇 {}", candidates.size(), kept.size(), merged.size(), clusterIds.size());
        return new Result(kept, merged, clusterIds.size());
    }

    // ------------------------------------------------------------------ 小分子

    private void dedupSmallMolecules(
            List<GenerationDtos.GeneratedCandidate> molecules,
            List<Entry> kept,
            List<Entry> merged,
            Set<String> clusterIds) {

        List<ChemistryDtos.MoleculeIn> payload = new ArrayList<>(molecules.size());
        for (GenerationDtos.GeneratedCandidate c : molecules) {
            payload.add(ChemistryDtos.MoleculeIn.of(c.tempRef(), c.smiles()));
        }

        ChemistryDtos.StandardizeResponse response =
                chemistryClient.standardize(payload, DEFAULT_THRESHOLD);

        Map<String, ChemistryDtos.MoleculeResult> byRef = new LinkedHashMap<>();
        for (ChemistryDtos.MoleculeResult r : response.getResults()) {
            byRef.put(r.getRef(), r);
        }

        // 代表者 → 被合并者（计算层给出的是 ref，这里翻成 tempRef 语义）
        Map<String, ChemistryDtos.DedupCluster> clusterOf = new LinkedHashMap<>();
        if (response.getDedup() != null && response.getDedup().getClusters() != null) {
            for (ChemistryDtos.DedupCluster cluster : response.getDedup().getClusters()) {
                clusterOf.put(cluster.getRepresentative(), cluster);
            }
        } else {
            // 没拿到聚类（理论上不该发生）：退化为"每个都是自己的代表者"，
            // 而不是静默跳过 —— 后面的簇计数与 S7 配额都依赖它
            log.warn("标准层未返回 dedup 聚类，退化为按 InChIKey 精确去重");
        }

        Map<String, String> representativeByInchikey = new LinkedHashMap<>();

        for (GenerationDtos.GeneratedCandidate c : molecules) {
            ChemistryDtos.MoleculeResult r = byRef.get(c.tempRef());
            if (r == null || !r.isOk()) {
                // 结构非法：不入簇，交给 S6 以解析失败为由筛掉（此处不判，只登记）
                kept.add(new Entry(c, null, null, null, null, null, null));
                continue;
            }

            String clusterId = clusterIdOf(c.tempRef(), clusterOf, r.getMurckoScaffold());
            clusterIds.add(clusterId);

            ChemistryDtos.DedupCluster cluster = clusterOf.get(c.tempRef());
            if (cluster != null && cluster.getMaxSimilarity() != null
                    && cluster.getMerged() != null && !cluster.getMerged().isEmpty()) {
                // 这个 ref 是代表者，忽略（它自己进 kept）
            }

            // 判断自己是否被别人合并
            String mergedInto = null;
            BigDecimal similarity = null;
            for (ChemistryDtos.DedupCluster cl : clusterOf.values()) {
                if (cl.getMerged() != null && cl.getMerged().contains(c.tempRef())) {
                    mergedInto = cl.getRepresentative();
                    similarity = cl.getMaxSimilarity();
                    break;
                }
            }

            // InChIKey 兜底去重（聚类阈值很高时可能漏掉同分子不同写法）
            if (mergedInto == null && r.getInchikey() != null) {
                String existing = representativeByInchikey.get(r.getInchikey());
                if (existing != null && !existing.equals(c.tempRef())) {
                    mergedInto = existing;
                    similarity = BigDecimal.ONE;
                } else {
                    representativeByInchikey.put(r.getInchikey(), c.tempRef());
                }
            }

            Entry entry = new Entry(c, r.getCanonicalSmiles(), r.getInchikey(), r.getMurckoScaffold(),
                    clusterId, mergedInto, similarity);
            if (entry.isMerged()) {
                merged.add(entry);
            } else {
                kept.add(entry);
            }
        }
    }

    private String clusterIdOf(String ref, Map<String, ChemistryDtos.DedupCluster> clusterOf,
                               String murcko) {
        // 簇标签优先用代表者（同簇必然同一个标签），否则用骨架
        for (ChemistryDtos.DedupCluster cl : clusterOf.values()) {
            if (cl.getRepresentative() != null
                    && (cl.getRepresentative().equals(ref)
                    || (cl.getMerged() != null && cl.getMerged().contains(ref)))) {
                return "CL-" + cl.getRepresentative();
            }
        }
        return murcko == null ? "CL-" + ref : "SCAF-" + Integer.toHexString(murcko.hashCode());
    }

    // ------------------------------------------------------------------ 序列

    private void dedupSequences(
            List<GenerationDtos.GeneratedCandidate> sequences,
            List<Entry> kept,
            List<Entry> merged,
            Set<String> clusterIds) {

        Map<String, String> firstByHash = new LinkedHashMap<>();
        for (GenerationDtos.GeneratedCandidate c : sequences) {
            String hash = c.sequenceHash();
            if (hash == null) {
                // 没有哈希无法去重：保留并登记，交给 S6 以"缺少序列"筛掉
                kept.add(new Entry(c, null, null, null, "CL-" + c.tempRef(), null, null));
                clusterIds.add("CL-" + c.tempRef());
                continue;
            }
            String existing = firstByHash.get(hash);
            String clusterId = "SEQ-" + hash.substring(0, 12);
            clusterIds.add(clusterId);
            if (existing == null) {
                firstByHash.put(hash, c.tempRef());
                kept.add(new Entry(c, null, null, null, clusterId, null, null));
            } else {
                merged.add(new Entry(c, null, null, null, clusterId, existing, BigDecimal.ONE));
            }
        }
    }
}
