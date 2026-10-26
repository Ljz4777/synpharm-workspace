package com.synpharm.design.support.decision;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策生命周期规则（D6 / D8，07 §2.1 与 §2.2）。
 *
 * <p>这里只放<b>纯判断</b>：不查库、不写库。原因是这三条边界最容易写歪，
 * 而写歪的后果全是「事后无法复盘」：
 *
 * <ul>
 *   <li><b>确认即不可改</b>：只有草稿能改。已确认的决策被就地改掉，回放语义就没了。</li>
 *   <li><b>否决不可绕过</b>：BLOCK 级否决与硬约束复核不通过的候选，不能被人手动改成「入选」。
 *       想过它只有一条路 —— 改数据、重新决策。WARN 级不拦。</li>
 *   <li><b>回放要能逐条说清差异</b>：只回一个布尔值等于让人猜。</li>
 * </ul>
 *
 * @author SynPharm Team
 */
public final class DecisionLifecycle {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_DEFERRED = "DEFERRED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String BUCKET_FAIL = "FAIL";

    public static final String OUTCOME_SELECTED = "SELECTED";
    public static final String OUTCOME_BACKUP = "BACKUP";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_DEFERRED = "DEFERRED";

    public static final String SEVERITY_BLOCK = "BLOCK";

    private DecisionLifecycle() {
    }

    /** 非草稿一律拒绝修改（确认即不可改） */
    public static void requireDraft(String status, Long decisionId) {
        if (!STATUS_DRAFT.equals(status)) {
            throw new BizException(ErrorCode.DECISION_NOT_EDITABLE,
                    "决策 " + status + " 后不可修改；要改请基于当前结果新建一版（revision_of 指向它）",
                    Map.of("decisionId", decisionId, "status", status));
        }
    }

    /**
     * 人工调整的边界。
     *
     * @param bucket  该候选所在桶（FAIL 表示硬约束复核不通过）
     * @param vetoes  该候选的否决命中
     * @param outcome 想改成什么
     */
    public static void assertOverridable(String bucket, List<DecisionDtos.VetoHit> vetoes, String outcome) {
        if (outcome == null || OUTCOME_REJECTED.equals(outcome) || OUTCOME_DEFERRED.equals(outcome)) {
            // 往「更严」的方向调不拦：把入选改回淘汰/暂缓永远是允许的
            return;
        }
        if (BUCKET_FAIL.equals(bucket)) {
            throw new BizException(ErrorCode.DECISION_BLOCKED_BY_VETO,
                    "该候选硬约束复核不通过，不能改为入选；请改数据后重新决策",
                    Map.of("bucket", bucket, "outcome", outcome));
        }
        String blockCode = firstBlockCode(vetoes);
        if (blockCode != null) {
            throw new BizException(ErrorCode.DECISION_BLOCKED_BY_VETO,
                    "该候选被 BLOCK 级规则否决（" + blockCode + "），不能改为入选；请改数据后重新决策",
                    Map.of("ruleCode", blockCode, "outcome", outcome));
        }
    }

    /** 调整理由必填：没有理由的覆盖事后无法追溯 */
    public static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.OVERRIDE_REASON_REQUIRED,
                    "必须填写调整理由：没有理由的调整无法追溯", Map.of());
        }
    }

    /** 是否会绕过否决（给前端做按钮置灰用，语义与 {@link #assertOverridable} 一致） */
    public static boolean overridableTo(String bucket, List<DecisionDtos.VetoHit> vetoes, String outcome) {
        try {
            assertOverridable(bucket, vetoes, outcome);
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    private static String firstBlockCode(List<DecisionDtos.VetoHit> vetoes) {
        if (vetoes == null) {
            return null;
        }
        for (DecisionDtos.VetoHit hit : vetoes) {
            if (hit != null && SEVERITY_BLOCK.equals(hit.severity())) {
                return hit.ruleCode();
            }
        }
        return null;
    }

    /** 由候选去向汇总出的计数（人工调整后必须重算，不能沿用创建时那组数） */
    public record Counts(int selected, int backup, int rejected, int deferred) {
    }

    public static Counts countsOf(List<String> outcomes) {
        int selected = 0;
        int backup = 0;
        int rejected = 0;
        int deferred = 0;
        for (String o : outcomes) {
            if (OUTCOME_SELECTED.equals(o)) {
                selected++;
            } else if (OUTCOME_BACKUP.equals(o)) {
                backup++;
            } else if (OUTCOME_DEFERRED.equals(o)) {
                deferred++;
            } else {
                rejected++;
            }
        }
        return new Counts(selected, backup, rejected, deferred);
    }

    /** 回放比对所需的最小形状：桶、名次、去向 */
    public record Snapshot(Long candidateId, String bucket, Integer rankNo, String outcome) {
    }

    /** 一条候选的差异 */
    public record Change(Long candidateId, String storedBucket, String currentBucket,
                         Integer storedRankNo, Integer currentRankNo,
                         String storedOutcome, String currentOutcome,
                         List<String> changes) {
    }

    /**
     * 逐候选比对「记录里存的」与「现在重算的」。
     *
     * @param stored    决策记录里的候选行
     * @param recomputed 用当前输入重算出来的候选行
     */
    public static List<Change> diff(List<Snapshot> stored, List<Snapshot> recomputed) {
        Map<Long, Snapshot> before = index(stored);
        Map<Long, Snapshot> after = index(recomputed);
        List<Change> out = new ArrayList<>();

        for (Map.Entry<Long, Snapshot> e : before.entrySet()) {
            Snapshot b = e.getValue();
            Snapshot a = after.get(e.getKey());
            if (a == null) {
                // 这次不再参与（分支被放弃 / 快照被删）：单独由 removed() 报，
                // 不混进「变了」——它不是变了，是没了
                continue;
            }
            List<String> changes = new ArrayList<>();
            if (!eq(b.bucket(), a.bucket())) {
                changes.add("桶 " + nz(b.bucket()) + " → " + nz(a.bucket()));
            }
            if (!eq(b.rankNo(), a.rankNo())) {
                changes.add("名次 " + nz(b.rankNo()) + " → " + nz(a.rankNo()));
            }
            if (!eq(b.outcome(), a.outcome())) {
                changes.add("去向 " + nz(b.outcome()) + " → " + nz(a.outcome()));
            }
            if (!changes.isEmpty()) {
                out.add(new Change(b.candidateId(), b.bucket(), a.bucket(), b.rankNo(), a.rankNo(),
                        b.outcome(), a.outcome(), changes));
            }
        }
        // 新增候选（上次决策之后才被冻结的）单独列，不混进「差异」：它不是变了，是多了
        return out;
    }

    /** 上次没参与、这次参与的候选 id */
    public static List<Long> added(List<Snapshot> stored, List<Snapshot> recomputed) {
        Map<Long, Snapshot> before = index(stored);
        List<Long> out = new ArrayList<>();
        for (Snapshot s : recomputed) {
            if (!before.containsKey(s.candidateId())) {
                out.add(s.candidateId());
            }
        }
        return out;
    }

    /** 上次参与、这次不再参与的候选 id */
    public static List<Long> removed(List<Snapshot> stored, List<Snapshot> recomputed) {
        Map<Long, Snapshot> after = index(recomputed);
        List<Long> out = new ArrayList<>();
        for (Snapshot s : stored) {
            if (!after.containsKey(s.candidateId())) {
                out.add(s.candidateId());
            }
        }
        return out;
    }

    private static Map<Long, Snapshot> index(List<Snapshot> list) {
        Map<Long, Snapshot> map = new LinkedHashMap<>();
        if (list == null) {
            return map;
        }
        for (Snapshot s : list) {
            map.put(s.candidateId(), s);
        }
        return map;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }
}
