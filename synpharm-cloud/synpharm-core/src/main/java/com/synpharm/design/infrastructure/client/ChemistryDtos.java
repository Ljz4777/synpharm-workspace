package com.synpharm.design.infrastructure.client;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 计算层 {@code POST /v1/evaluate/properties} 的请求/响应契约。
 *
 * <p>字段名与计算层一一对应，不做「驼峰美化」以外任何改写：
 * 任何一层自作主张的重命名，都会让"明明测通了、到了下游就空"这类问题极难定位。
 *
 * @author SynPharm Team
 */
@Data
public class ChemistryDtos {

    /** 请求里的一个分子 */
    @Data
    public static class MoleculeIn {
        /** 调用方自定义标识，原样回传（生成阶段用临时编号，落库前还没有 candidate id） */
        private String ref;
        private String smiles;

        public static MoleculeIn of(String ref, String smiles) {
            MoleculeIn m = new MoleculeIn();
            m.setRef(ref);
            m.setSmiles(smiles);
            return m;
        }
    }

    @Data
    public static class PropertiesRequest {
        private List<MoleculeIn> molecules;
        /** 留空表示算全部 RDKit 原生指标 */
        private List<String> metrics;
        private Boolean includeDiversity;
    }

    /** 单个指标的值；{@code value} 为 null 表示该指标算不出来（此时 detail 里带 error） */
    @Data
    public static class MetricEntry {
        private BigDecimal value;
        /** 命中列表（PAINS/BRENK）或明细（Lipinski violations）；结构随指标而定 */
        private Object detail;
        /** GOOD / WARN / BAD，可空 */
        private String grade;
    }

    @Data
    public static class MoleculeResult {
        private String ref;
        private Boolean success;
        private String error;
        private Map<String, MetricEntry> metrics;
        private String canonicalSmiles;
        private String inchikey;
        private String formula;
        private String murckoScaffold;
        private Diversity diversity;

        public boolean isOk() {
            return Boolean.TRUE.equals(success);
        }
    }

    @Data
    public static class Diversity {
        /** 与同批其他分子的最高 Tanimoto 相似度 */
        private BigDecimal maxSimilarity;
    }

    @Data
    public static class PropertiesResponse {
        /** SUCCEEDED / PARTIAL / FAILED */
        private String status;
        private List<MoleculeResult> results;
        private Map<String, Object> engine;
        private List<String> metrics;
    }

    // ------------------------------------------------------------------ standardize

    @Data
    public static class StandardizeRequest {
        private List<MoleculeIn> molecules;
        /** 给出时一并做相似度聚类（生成 S5） */
        private BigDecimal dedupThreshold;
    }

    @Data
    public static class DedupCluster {
        private String representative;
        private List<String> merged;
        private BigDecimal maxSimilarity;
    }

    @Data
    public static class DedupResult {
        private BigDecimal threshold;
        private Integer clusterCount;
        private Integer mergedCount;
        private List<DedupCluster> clusters;
    }

    @Data
    public static class StandardizeResponse {
        private String status;
        private List<MoleculeResult> results;
        private Map<String, Object> engine;
        private DedupResult dedup;
    }
}
