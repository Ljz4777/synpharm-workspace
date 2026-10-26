<template>
  <section class="tree">
    <header class="tree__head">
      <div>
        <h3 class="tree__title">版本树</h3>
        <p class="tree__hint">
          分子不是孤立存在，而是一棵版本树。放弃的分支也保留 —— 删掉会让
          「为什么当时那条路走不通」永久丢失。
        </p>
      </div>
      <div class="tree__legend">
        <span class="legend legend--focus">当前</span>
        <span class="legend legend--abandoned">已放弃</span>
        <span class="legend legend--noeval">未评估</span>
      </div>
    </header>

    <p v-if="error" class="banner banner--danger">{{ error }}</p>

    <ul class="nodes">
      <li
        v-for="node in sortedNodes"
        :key="node.id"
        class="node"
        :class="[
          `node--${node.branchStatus}`,
          { 'node--focus': node.isFocus, 'node--noeval': node.needsReEval === 1 }
        ]"
        :style="{ marginLeft: `${(node.depth ?? 0) * 26}px` }"
      >
        <span class="node__cursor" aria-hidden="true">{{ (node.depth ?? 0) > 0 ? '└─' : '●' }}</span>
        <div class="node__body">
          <div class="node__top">
            <strong class="node__id">#{{ node.id }}</strong>
            <span class="node__ver">v{{ node.versionNo ?? 1 }}</span>
            <span v-if="node.editRound" class="node__round">第 {{ node.editRound }} 轮</span>
            <span v-if="node.isFocus" class="legend legend--focus">当前</span>
            <span v-if="node.branchStatus === 'abandoned'" class="legend legend--abandoned">已放弃</span>
            <span v-if="node.needsReEval === 1" class="legend legend--noeval">待重评</span>
            <span v-if="node.originType === 'REVERT'" class="node__tag">回退新建</span>
          </div>
          <div class="node__meta">
            <span v-if="node.smiles" class="node__smiles" :title="node.smiles">{{ shortSmiles(node.smiles) }}</span>
            <span v-else-if="node.sequenceHash" class="node__hash">seq {{ node.sequenceHash.slice(0, 10) }}…</span>
            <span v-if="node.totalScore !== null && node.totalScore !== undefined" class="node__score">
              综合分 {{ node.totalScore }}
            </span>
            <span v-if="node.hasFreeze" class="node__freeze">已冻结</span>
            <span v-else class="node__nofreeze">未评估</span>
          </div>
          <p v-if="node.note" class="node__note">{{ node.note }}</p>
        </div>
        <div class="node__actions">
          <button
            v-if="!node.isFocus"
            type="button"
            class="btn btn--sm"
            :disabled="busy"
            @click="$emit('focus', node.id)"
          >
            切到此版本
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CandidateTree, CandidateTreeNode } from '@/api/evaluation'

/**
 * 版本树。
 *
 * 用缩进而不是图形库：这棵树最多几十个节点，缩进 + 深度标签比引入
 * 一个图可视化依赖更容易读懂，也更容易在评审会上截图说明。
 */
const props = defineProps<{
  tree: CandidateTree
  error?: string
  busy?: boolean
}>()

defineEmits<{
  (e: 'focus', candidateId: number): void
}>()

/** 按深度 + id 排序，让父子相邻、同层按创建顺序 */
const sortedNodes = computed<CandidateTreeNode[]>(() => {
  const nodes = [...(props.tree.nodes ?? [])]
  return nodes.sort((a, b) => {
    const da = a.depth ?? 0
    const db = b.depth ?? 0
    if (da !== db) return da - db
    return (a.id ?? 0) - (b.id ?? 0)
  })
})

function shortSmiles(smiles: string): string {
  if (smiles.length <= 40) return smiles
  return `${smiles.slice(0, 24)}…${smiles.slice(-12)}`
}
</script>

<style lang="scss" scoped>
.tree {
  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: $spacing-md;
  }

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__hint {
    max-width: 660px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__legend {
    display: flex;
    gap: $spacing-xs;
  }
}

.nodes {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.node {
  display: flex;
  gap: $spacing-sm;
  align-items: flex-start;
  padding: 8px 12px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;

  &--focus {
    border-color: $color-brand;
    background: $color-brand-softer;
  }

  &--abandoned {
    opacity: 0.65;
    border-style: dashed;
  }

  &--noeval {
    border-left: 3px solid $warning-color;
  }

  &__cursor {
    padding-top: 2px;
    font-family: monospace;
    font-size: $font-size-xs;
    color: $text-muted;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__top {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-xs;
    align-items: center;
  }

  &__id {
    font-size: $font-size-sm;
    color: $color-text;
  }

  &__ver {
    padding: 1px 6px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    color: $color-text-soft;
    background: $color-surface-sunken;
  }

  &__round,
  &__tag {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__meta {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    margin-top: 4px;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__smiles,
  &__hash {
    font-family: monospace;
    color: $color-text-soft;
  }

  &__score {
    color: $color-text;
  }

  &__freeze {
    color: $success-color;
  }

  &__nofreeze {
    color: $warning-color;
  }

  &__note {
    margin: 4px 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__actions {
    flex: 0 0 auto;
  }
}

.legend {
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;

  &--focus {
    color: $color-brand-strong;
    background: $color-brand-soft;
  }

  &--abandoned {
    color: $text-muted;
    background: rgba($text-muted, 0.16);
  }

  &--noeval {
    color: $warning-color;
    background: rgba($warning-color, 0.14);
  }
}

.btn--sm {
  padding: 3px 9px;
  font-size: $font-size-xs;
}
</style>
