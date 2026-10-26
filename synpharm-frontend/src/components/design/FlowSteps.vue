<template>
  <nav class="flow" aria-label="主业务流程">
    <template v-for="(step, index) in steps" :key="step.key">
      <button
        type="button"
        class="flow__step"
        :class="[
          `flow__step--${stateOf(step.key)}`,
          { 'flow__step--clickable': canGo(step.key) }
        ]"
        :disabled="!canGo(step.key)"
        :title="hintOf(step.key)"
        @click="go(step)"
      >
        <span class="flow__no">{{ index + 1 }}</span>
        <span class="flow__label">{{ step.label }}</span>
      </button>
      <span v-if="index < steps.length - 1" class="flow__arrow" aria-hidden="true">→</span>
    </template>

    <span class="flow__hint">{{ currentHint }}</span>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

/**
 * 主业务流程条：需求分析 → 候选生成 → 评估与编辑 → 决策。
 *
 * 加它是因为三个阶段的页面彼此没有任何指引：冻结完基线不知道去哪生成，
 * 生成完只能从「逐分子日志」里点进评估，进了评估就回不到项目。
 * 用户必须记住三个 URL 才能走完流程 —— 这就是「乱」的来源。
 *
 * 只负责导航与当前位置，不查就绪状态：状态判断在项目列表页做，
 * 这里多查一次会多三个请求，而用户看的是「我在第几步」。
 */
type Stage = 'baseline' | 'generation' | 'evaluation' | 'decision'

const props = defineProps<{
  projectId: number | null
  /** 当前所处阶段 */
  current: Stage
  /** 评估阶段需要；没有就禁用第三步 */
  candidateId?: number | null
  /** 生成批次 id；有则「评估」步直接跳到该批次的候选 */
  runId?: number | null
}>()

const router = useRouter()

const steps: Array<{ key: Stage; label: string }> = [
  { key: 'baseline', label: '需求分析' },
  { key: 'generation', label: '候选生成' },
  { key: 'evaluation', label: '评估与编辑' },
  { key: 'decision', label: '决策' }
]

const HINTS: Record<Stage, string> = {
  baseline: '冻结基线：生成只读快照，不读项目当前状态',
  generation: '跑一批候选：去重 → 粗筛 → 配额，每步都留原因',
  evaluation: '冻结分数 → 诊断缺口 → 挑动作 → 看影响面',
  decision: '装配证据 → 否决复核 → 分桶排名 → 名额 → 回放指纹'
}

const currentHint = computed(() => HINTS[props.current])

function stateOf(key: Stage): 'current' | 'done' | 'todo' {
  const order = steps.map((s) => s.key)
  const cur = order.indexOf(props.current)
  const idx = order.indexOf(key)
  if (idx === cur) return 'current'
  return idx < cur ? 'done' : 'todo'
}

function canGo(key: Stage): boolean {
  if (key === props.current) return false
  if (!props.projectId) return false
  if (key === 'evaluation') return Boolean(props.candidateId)
  return true
}

function hintOf(key: Stage): string {
  if (!props.projectId) return '缺少项目上下文'
  if (key === 'evaluation' && !props.candidateId) return '还没有候选可以评估 —— 先跑一批生成'
  return HINTS[key]
}

function go(step: { key: Stage }) {
  if (!canGo(step.key)) return
  if (step.key === 'baseline') {
    router.push(`/design/projects/${props.projectId}/baseline`)
  } else if (step.key === 'generation') {
    router.push(`/design/projects/${props.projectId}/generation`)
  } else if (step.key === 'decision') {
    router.push(`/design/projects/${props.projectId}/decision`)
  } else {
    router.push(`/design/candidates/${props.candidateId}/evaluation`)
  }
}
</script>

<style lang="scss" scoped>
.flow {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-xs;
  align-items: center;
  padding: 10px 14px;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &__step {
    display: flex;
    gap: 6px;
    align-items: center;
    padding: 4px 12px 4px 6px;
    border: 1px solid transparent;
    border-radius: $radius-pill;
    font-size: $font-size-sm;
    color: $color-text-faint;
    background: transparent;
    cursor: default;

    &--clickable {
      cursor: pointer;

      &:hover {
        background: $color-surface-alt;
        border-color: $color-border;
      }
    }

    &--current {
      color: #fff;
      background: $color-brand;
      cursor: default;

      .flow__no {
        color: $color-brand;
        background: #fff;
      }
    }

    &--done {
      color: $success-color;

      .flow__no {
        color: #fff;
        background: $success-color;
      }
    }

    &--todo {
      color: $text-muted;

      .flow__no {
        color: $text-muted;
        background: $color-surface-sunken;
      }
    }

    &:disabled {
      opacity: 0.75;
    }
  }

  &__no {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    font-size: 10px;
    font-weight: $font-weight-semibold;
    background: $color-surface-sunken;
  }

  &__label {
    font-weight: $font-weight-medium;
  }

  &__arrow {
    color: $text-light;
    font-size: $font-size-xs;
  }

  &__hint {
    margin-left: auto;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}
</style>
