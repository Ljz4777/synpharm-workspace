package com.synpharm.design.support.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D6~D8 的三条边界。
 *
 * <p>这些不是「功能」，是**防止事后无法复盘**的护栏：
 * ① 确认后不可改；② 否决不可绕过（只能改数据重来）；③ 分不清「哪里不一样」的回放等于没回放。
 *
 * <p>之所以单独抽出来测：这三处一旦写歪，受害的是「上个月为什么选了这三个分子」这类问题 ——
 * 出错时没人会发现，发现时已经无从追溯。
 *
 * @author SynPharm Team
 */
class DecisionLifecycleTest {

    @Test
    @DisplayName("草稿可以改")
    void draftIsEditable() {
        DecisionLifecycle.requireDraft("DRAFT", 1L);
    }

    @Test
    @DisplayName("已确认/已暂缓/已取消都不能就地修改")
    void nonDraftIsNotEditable() {
        for (String status : List.of("CONFIRMED", "DEFERRED", "CANCELLED")) {
            assertThatThrownBy(() -> DecisionLifecycle.requireDraft(status, 9L))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                            .isEqualTo(ErrorCode.DECISION_NOT_EDITABLE));
        }
    }

    @Test
    @DisplayName("FAIL 桶的候选不能被人改成入选，但可以改成淘汰/暂缓")
    void failBucketCannotBeSelected() {
        assertThatThrownBy(() ->
                DecisionLifecycle.assertOverridable("FAIL", List.of(), "SELECTED"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DECISION_BLOCKED_BY_VETO));

        DecisionLifecycle.assertOverridable("FAIL", List.of(), "REJECTED");
        DecisionLifecycle.assertOverridable("FAIL", List.of(), "DEFERRED");
    }

    @Test
    @DisplayName("BLOCK 级否决命中后不能改成入选或备选 —— 否决不可绕过")
    void blockVetoCannotBeOverridden() {
        List<DecisionDtos.VetoHit> vetoes = List.of(
                new DecisionDtos.VetoHit("MUST_VERIFY_UNVERIFIED", "BLOCK", "必测项没有实测证据", "pendingItems[0]"));

        for (String outcome : List.of("SELECTED", "BACKUP")) {
            assertThatThrownBy(() -> DecisionLifecycle.assertOverridable("FULL_PASS", vetoes, outcome))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("MUST_VERIFY_UNVERIFIED");
        }
        // 往更严的方向调永远允许
        DecisionLifecycle.assertOverridable("FULL_PASS", vetoes, "REJECTED");
        DecisionLifecycle.assertOverridable("FULL_PASS", vetoes, "DEFERRED");
        assertThat(DecisionLifecycle.overridableTo("FULL_PASS", vetoes, "SELECTED")).isFalse();
    }

    @Test
    @DisplayName("WARN 级命中不拦：可以照常入选，只是要留痕")
    void warnVetoDoesNotBlock() {
        List<DecisionDtos.VetoHit> vetoes = List.of(
                new DecisionDtos.VetoHit("BUDGET_EXCEEDED", "WARN", "预算接近上限", "project.quota"));

        DecisionLifecycle.assertOverridable("FULL_PASS", vetoes, "SELECTED");
        assertThat(DecisionLifecycle.overridableTo("FULL_PASS", vetoes, "SELECTED")).isTrue();
    }

    @Test
    @DisplayName("调整理由为空直接拒绝（400 语义）")
    void reasonRequired() {
        assertThatThrownBy(() -> DecisionLifecycle.requireReason("  "))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.OVERRIDE_REASON_REQUIRED));
        DecisionLifecycle.requireReason("业务判断：先做这个骨架");
    }

    @Test
    @DisplayName("计数按去向汇总，未知去向算淘汰")
    void countsByOutcome() {
        DecisionLifecycle.Counts c = DecisionLifecycle.countsOf(
                Arrays.asList("SELECTED", "BACKUP", "BACKUP", "DEFERRED", "REJECTED", null));
        assertThat(c).isEqualTo(new DecisionLifecycle.Counts(1, 2, 2, 1));
    }

    @Test
    @DisplayName("回放差异要逐条说清改了哪几项")
    void diffReportsEachChange() {
        List<DecisionLifecycle.Snapshot> stored = List.of(
                new DecisionLifecycle.Snapshot(1L, "FULL_PASS", 1, "SELECTED"),
                new DecisionLifecycle.Snapshot(2L, "FULL_PASS", 2, "BACKUP"),
                new DecisionLifecycle.Snapshot(3L, "FAIL", null, "REJECTED"));
        List<DecisionLifecycle.Snapshot> fresh = List.of(
                new DecisionLifecycle.Snapshot(1L, "FULL_PASS", 2, "BACKUP"),
                new DecisionLifecycle.Snapshot(2L, "TOLERANCE", 1, "SELECTED"));
        // 3 号这次不再参与 —— 它应该被单独列成 removed，而不是混进 diff 的「变了」

        List<DecisionLifecycle.Change> changes = DecisionLifecycle.diff(stored, fresh);
        assertThat(changes).hasSize(2);

        DecisionLifecycle.Change c1 = changes.get(0);
        assertThat(c1.candidateId()).isEqualTo(1L);
        assertThat(c1.changes()).contains("名次 1 → 2", "去向 SELECTED → BACKUP");

        DecisionLifecycle.Change c2 = changes.get(1);
        assertThat(c2.candidateId()).isEqualTo(2L);
        assertThat(c2.changes()).contains("桶 FULL_PASS → TOLERANCE", "名次 2 → 1", "去向 BACKUP → SELECTED");

        assertThat(DecisionLifecycle.removed(stored, fresh)).containsExactly(3L);
        assertThat(DecisionLifecycle.added(stored, fresh)).isEmpty();
    }

    @Test
    @DisplayName("新增候选单独列，不混进「差异」——它不是变了，是多了")
    void addedCandidatesListedSeparately() {
        List<DecisionLifecycle.Snapshot> stored = List.of(
                new DecisionLifecycle.Snapshot(1L, "FULL_PASS", 1, "SELECTED"));
        List<DecisionLifecycle.Snapshot> fresh = List.of(
                new DecisionLifecycle.Snapshot(1L, "FULL_PASS", 1, "SELECTED"),
                new DecisionLifecycle.Snapshot(7L, "FULL_PASS", 2, "BACKUP"));

        assertThat(DecisionLifecycle.diff(stored, fresh)).isEmpty();
        assertThat(DecisionLifecycle.added(stored, fresh)).containsExactly(7L);
    }
}
