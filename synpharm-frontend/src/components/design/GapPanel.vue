<template>
  <section class="gaps">
    <header class="gaps__head">
      <div>
        <h3 class="gaps__title">
          缺口诊断
          <code class="gaps__no">{{ report.reportNo }}</code>
        </h3>
        <p class="gaps__hint">
          缺口只精确到「指标 + 维度」，归因到具体结构特征需要归因模型，当前未接入。
          「已达标」清单是保护名单 —— 影响面评估会拿它判断动作能不能执行。
        </p>
      </div>
    </header>

    <!-- 可修缺口 -->
    <section class="block">
      <h4 class="block__title">
        待修缺口
        <span class="block__count">{{ report.gaps.length }}</span>
      </h4>
      <p v-if="!report.gaps.length" class="block__empty">
        没有可修的缺口。可能是全部达标，也可能是缺口都落在当前手段修不了的维度上。
      </p>
      <ul v-else class="list">
        <li v-for="(gap, i) in report.gaps" :key="`${gap.metricCode}-${i}`" class="gap">
          <div class="gap__top">
            <code class="gap__metric">{{ gap.metricCode }}</code>
            <span class="dim">{{ dimensionLabel(gap.dimension) }}</span>
            <span class="tag" :class="`tag--${difficultyMeta(gap.difficulty).tone}`">
              {{ difficultyMeta(gap.difficulty).label }}
            </span>
            <span v-if="gap.gapType === 'NOT_EVALUATED'" class="tag tag--neutral">未评估</span>
            <span v-if="i === 0" class="tag tag--step">建议先修</span>
          </div>
          <p class="gap__reason">{{ gap.reason }}</p>
          <p v-if="gap.attributionNote" class="gap__attribution">{{ gap.attributionNote }}</p>

          <!-- 该缺口可用的动作 -->
          <div v-if="actionsOf(gap.dimension).length" class="actions">
            <span class="actions__label">候选动作</span>
            <button
              v-for="a in actionsOf(gap.dimension)"
              :key="`${a.actionCode}-${a.isFallback}`"
              type="button"
              class="action"
              :class="{ 'action--active': selected === a.actionCode }"
              @click="$emit('preview', a.actionCode)"
            >
              <span class="action__name">{{ actionLabel(a.actionCode) }}</span>
              <span class="action__code">{{ a.actionCode }}</span>
              <span class="action__rate">
                成功率 {{ rateText(a) }}
                <em v-if="a.rateIsEstimate" class="action__est">估计值</em>
              </span>
              <span v-if="a.isFallback" class="action__fallback">次选</span>
            </button>
          </div>
        </li>
      </ul>
    </section>

    <!-- 修不了的缺口 -->
    <section v-if="report.unfixable.length" class="block">
      <h4 class="block__title">
        当前手段修不了
        <span class="block__count">{{ report.unfixable.length }}</span>
      </h4>
      <p class="block__note">
        映射表里没有可信的定向动作。硬塞一个动作只会白跑一轮 —— 这些维度需要别的手段。
      </p>
      <ul class="list list--muted">
        <li v-for="(gap, i) in report.unfixable" :key="`u-${gap.metricCode}-${i}`" class="gap">
          <div class="gap__top">
            <code class="gap__metric">{{ gap.metricCode }}</code>
            <span class="dim">{{ dimensionLabel(gap.dimension) }}</span>
            <span class="tag tag--neutral">修不了</span>
          </div>
          <p class="gap__reason">{{ gap.reason }}</p>
        </li>
      </ul>
    </section>

    <!-- 保护名单 -->
    <section v-if="report.noGaps.length" class="block">
      <h4 class="block__title">
        已达标（保护名单）
        <span class="block__count">{{ report.noGaps.length }}</span>
      </h4>
      <p class="block__note">
        这些维度已经到手。影响面评估会拦住「把它们打坏」的动作 —— 不能拿已到手的东西去换。
      </p>
      <div class="protected">
        <span v-for="d in uniqueDims" :key="d" class="protected__item">{{ dimensionLabel(d) }}</span>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { actionLabel, difficultyMeta, dimensionLabel } from '@/api/evaluation'
import type { EditActionOption, EditGapReport } from '@/api/evaluation'

/**
 * E1 缺口面板。
 *
 * 刻意把「修不了的缺口」与「可修缺口」分成两块：混在一起会让用户以为
 * 每个缺口都能改，而实际上 SAFETY / NOVELTY / DEVELOPABILITY 当前没有可信动作。
 */
const props = defineProps<{
  report: EditGapReport
  /** 当前正在预览的动作码，用于高亮 */
  selected?: string
}>()

defineEmits<{
  (e: 'preview', actionCode: string): void
}>()

function actionsOf(dimension: string): EditActionOption[] {
  return (props.report.actions ?? []).filter((a) => a.gapDimension === dimension)
}

function rateText(a: EditActionOption): string {
  if (a.successRate === null || a.successRate === undefined) return '—'
  return `${Math.round(a.successRate * 100)}%`
}

const uniqueDims = computed(() => Array.from(new Set(props.report.noGaps)))
</script>

<style lang="scss" scoped>
.gaps {
  &__head {
    margin-bottom: $spacing-md;
  }

  &__title {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
    margin: 0 0 6px;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__no {
    font-family: monospace;
    font-size: $font-size-sm;
    font-weight: $font-weight-normal;
    color: $color-text-faint;
  }

  &__hint {
    max-width: 760px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }
}

.block {
  margin-bottom: $spacing-lg;

  &__title {
    display: flex;
    gap: $spacing-sm;
    align-items: center;
    margin: 0 0 $spacing-sm;
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__count {
    padding: 1px 8px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    color: $color-text-soft;
    background: $color-surface-sunken;
  }

  &__empty,
  &__note {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;
  }
}

.list {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  margin: 0;
  padding: 0;
  list-style: none;

  &--muted .gap {
    background: $color-surface-sunken;
  }
}

.gap {
  padding: $spacing-sm $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;

  &__top {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
  }

  &__metric {
    font-family: monospace;
    font-size: $font-size-xs;
    color: $color-text-soft;
  }

  &__reason {
    margin: 6px 0 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text;
  }

  &__attribution {
    margin: 4px 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.dim {
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  color: $color-text;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-xs;
  align-items: center;
  margin-top: $spacing-sm;
  padding-top: $spacing-sm;
  border-top: 1px dashed $color-border;

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.action {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 5px 10px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  font-size: $font-size-xs;
  text-align: left;
  color: $color-text;
  background: $color-surface;
  cursor: pointer;

  &:hover {
    border-color: $color-brand;
    background: $color-brand-softer;
  }

  &--active {
    border-color: $color-brand;
    background: $color-brand-soft;
  }

  &__name {
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
  }

  &__code {
    font-family: monospace;
    color: $color-text-faint;
  }

  &__rate {
    color: $color-text-soft;
  }

  &__est {
    margin-left: 4px;
    font-style: normal;
    color: $warning-color;
  }

  &__fallback {
    color: $text-muted;
  }
}

.protected {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-xs;

  &__item {
    padding: 3px 10px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    color: $success-color;
    background: rgba($success-color, 0.12);
  }
}

.tag {
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;

  @each $tone, $color in (success: $success-color, warning: $warning-color, danger: $error-color, neutral: $text-muted) {
    &--#{$tone} {
      color: $color;
      background: rgba($color, 0.12);
    }
  }

  &--step {
    color: $color-brand-strong;
    background: $color-brand-soft;
  }
}
</style>
