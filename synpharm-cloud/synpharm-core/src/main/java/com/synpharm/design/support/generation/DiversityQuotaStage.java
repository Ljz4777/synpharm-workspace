package com.synpharm.design.support.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * S7 多样性配额（06 §4.1）。
 *
 * <p>目的不是"选最好的 N 个"，而是<b>保证候选池里有足够的选择空间</b>：
 * 如果前 20 名都是同一个骨架的类似物，决策阶段其实没有选择余地。
 *
 * <p>做法：按簇分组 → 每簇按 preScore 取 top-k → 轮转填充直到名额用完。
 * 轮转（而不是"每簇固定配额"）的效果是：<b>簇少的池子自然把名额让给簇多的</b>，
 * 不需要额外调参。
 *
 * <p>有效簇数 &lt; 3 时给严重告警但仍放行 —— 覆盖不足是策略问题，不是数据错误，
 * 硬拦下来会让用户连"看看这批有多差"都做不到。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component
public class DiversityQuotaStage {

    /** 有效簇数下限：低于此值说明多样性不足 */
    static final int MIN_VALID_CLUSTERS = 3;

    public record Entry(
            PreFilterStage.Entry filterEntry,
            String clusterId,
            boolean selected,
            String cutReason
    ) {
    }

    public record Result(
            List<Entry> selected,
            List<Entry> cut,
            int clusterCount,
            List<String> warnings
    ) {
    }

    /**
     * @param passed     S6 通过的候选
     * @param quota      名额上限（来自基线块7 与决策档位无关 —— 这里只受 min_candidates 与数量约束）
     */
    public Result allocate(List<PreFilterStage.Entry> passed, int quota) {
        if (passed.isEmpty()) {
            return new Result(List.of(), List.of(), 0,
                    List.of("粗筛后无候选，不允许往下游流（需回 S2/S3 重拟策略）"));
        }

        // 按簇分组
        Map<String, List<PreFilterStage.Entry>> byCluster = new LinkedHashMap<>();
        for (PreFilterStage.Entry e : passed) {
            String cluster = e.dedupEntry().clusterId();
            if (cluster == null) {
                cluster = "CL-" + e.dedupEntry().candidate().tempRef();
            }
            byCluster.computeIfAbsent(cluster, k -> new ArrayList<>()).add(e);
        }

        // 簇内按 preScore 降序（null 排最后），同分按 tempRef 保证可复现
        Comparator<PreFilterStage.Entry> order = Comparator
                .comparing((PreFilterStage.Entry e) -> e.preScore(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(e -> e.dedupEntry().candidate().tempRef());
        for (List<PreFilterStage.Entry> list : byCluster.values()) {
            list.sort(order);
        }

        // 轮转填充：每轮每簇取 1 个，直到名额用完或全部取尽
        List<Entry> selected = new ArrayList<>();
        Map<String, Integer> cursor = new LinkedHashMap<>();
        for (String cluster : byCluster.keySet()) {
            cursor.put(cluster, 0);
        }

        boolean progressed = true;
        while (selected.size() < quota && progressed) {
            progressed = false;
            for (Map.Entry<String, List<PreFilterStage.Entry>> group : byCluster.entrySet()) {
                if (selected.size() >= quota) {
                    break;
                }
                int idx = cursor.get(group.getKey());
                if (idx < group.getValue().size()) {
                    selected.add(new Entry(group.getValue().get(idx), group.getKey(), true, null));
                    cursor.put(group.getKey(), idx + 1);
                    progressed = true;
                }
            }
        }

        // 剩下的都是被配额裁掉的
        List<Entry> cut = new ArrayList<>();
        for (Map.Entry<String, List<PreFilterStage.Entry>> group : byCluster.entrySet()) {
            int from = cursor.get(group.getKey());
            for (int i = from; i < group.getValue().size(); i++) {
                cut.add(new Entry(group.getValue().get(i), group.getKey(), false,
                        "超出名额 " + quota + "（该簇排名 " + (i + 1) + "）"));
            }
        }

        List<String> warnings = new ArrayList<>();
        if (byCluster.size() < MIN_VALID_CLUSTERS) {
            String msg = "有效簇数仅 " + byCluster.size() + "（少于 " + MIN_VALID_CLUSTERS
                    + "），候选覆盖不足；建议回 S2/S3 调整策略或扩大种子";
            warnings.add(msg);
            log.warn(msg);
        }
        if (cut.size() > passed.size() / 2) {
            warnings.add("超过一半候选被配额裁掉（" + cut.size() + "/" + passed.size()
                    + "），可能是名额过小或采样过度集中在少数簇");
        }

        log.info("S7 配额：通过 {} → 选 {}，裁 {}，簇 {}", passed.size(), selected.size(), cut.size(),
                byCluster.size());
        return new Result(selected, cut, byCluster.size(), warnings);
    }
}
