<template>
  <section class="funnel">
    <header class="funnel__head">
      <div>
        <h3 class="funnel__title">筛选漏斗</h3>
        <p class="funnel__hint">
          生成环节的交付物不只是一批分子，还有一份「为什么是这些、为什么不是那些」的说明。
          四段转化率是调参的唯一依据：粗筛掉太多说明约束过严，去重掉太多说明采样太集中。
        </p>
      </div>
    </header>

    <div v-if="!funnel" class="funnel__empty">
      <EmptyState title="暂无漏斗数据" description="批次跑完后才有四段统计。" />
    </div>

    <template v-else>
      <div class="funnel__stages">
        <template v-for="(stage, index) in FUNNEL_STAGES" :key="stage.key">
          <article class="stage">
            <span class="stage__label">{{ stage.label }}</span>
            <strong class="stage__value">{{ countOf(stage.key) }}</strong>
            <span class="stage__hint">{{ stage.hint }}</span>
          </article>
          <div v-if="index < FUNNEL_STAGES.length - 1" class="arrow" aria-hidden="true">
            <span class="arrow__rate">{{ rateLabel(rateAfter(index)) }}</span>
            <span class="arrow__line">→</span>
          </div>
        </template>
      </div>

      <dl class="funnel__rates">
        <div class="rate">
          <dt>总体产出率</dt>
          <dd>{{ rateLabel(funnel.overallYield) }}</dd>
        </div>
        <div class="rate">
          <dt>被配额裁掉</dt>
          <dd>{{ funnel.quotaCut }} 个</dd>
        </div>
        <div class="rate">
          <dt>有效簇</dt>
          <dd>{{ clusterCount }} 个<em v-if="clusterCount < 3" class="rate__warn">覆盖不足</em></dd>
        </div>
      </dl>

      <section v-if="topReasons.length" class="reasons">
        <h4 class="reasons__title">主要淘汰原因</h4>
        <ul class="reasons__list">
          <li v-for="item in topReasons" :key="item.code" class="reasons__item">
            <span class="reasons__code">{{ filterReasonLabel(item.code) }}</span>
            <span class="reasons__raw">{{ item.code }}</span>
            <span class="reasons__count">{{ item.count }}</span>
          </li>
        </ul>
        <p class="reasons__note">
          原因码照抄后端，不做美化 —— 排查问题时对着日志搜得到的才是有效的码。
        </p>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { FUNNEL_STAGES, filterReasonLabel } from '@/api/generation'
import type { GenerationFunnel } from '@/api/generation'

/**
 * 漏斗看板。
 *
 * 刻意不做「好看的渐变漏斗图」：这里要回答的是「哪一段掉得最多」，
 * 数字并排比图形面积更容易比较，也更容易在评审会上直接念出来。
 */
const props = defineProps<{
  funnel?: GenerationFunnel | null
  clusterCount?: number
}>()

const funnel = computed(() => props.funnel ?? null)
const clusterCount = computed(() => props.clusterCount ?? 0)

type StageKey = (typeof FUNNEL_STAGES)[number]['key']

function countOf(key: StageKey): number {
  const f = funnel.value
  if (!f) return 0
  return Number(f[key] ?? 0)
}

/** 「某段之后」的通过率：生成→去重用 dedupRate，其余同理 */
function rateAfter(index: number): number | null | undefined {
  const f = funnel.value
  if (!f) return null
  const RATE_BY_INDEX = [f.dedupRate, f.preFilterPassRate, f.quotaKeepRate]
  return RATE_BY_INDEX[index]
}

function rateLabel(rate?: number | null): string {
  if (rate === null || rate === undefined) return '—'
  return `${Math.round(rate * 1000) / 10}%`
}

const topReasons = computed(() => {
  const raw = funnel.value?.filterReasonTop
  if (!raw) return []
  return Object.entries(raw)
    .map(([code, count]) => ({ code, count: Number(count) }))
    .sort((a, b) => b.count - a.count)
})
</script>

<style lang="scss" scoped>
.funnel {
  &__head {
    margin-bottom: $spacing-md;
  }

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__hint {
    max-width: 760px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__empty {
    padding: $spacing-xl 0;
  }

  &__stages {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: stretch;
  }

  &__rates {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-lg;
    margin: $spacing-md 0 0;
    padding: $spacing-md 0 0;
    border-top: 1px solid $color-border;
  }
}

.stage {
  flex: 1 1 160px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__value {
    font-size: 26px;
    font-weight: $font-weight-semibold;
    line-height: 1.1;
    color: $color-text;
  }

  &__hint {
    font-size: $font-size-xs;
    line-height: 1.5;
    color: $color-text-faint;
  }
}

.arrow {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 58px;

  &__rate {
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    color: $color-text-faint;
  }

  &__line {
    color: $text-muted;
  }
}

.rate {
  min-width: 120px;

  dt {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  dd {
    margin: 2px 0 0;
    font-size: $font-size-base;
    font-weight: $font-weight-medium;
    color: $color-text;
  }

  &__warn {
    margin-left: 6px;
    font-size: $font-size-xs;
    font-style: normal;
    color: $warning-color;
  }
}

.reasons {
  margin-top: $spacing-lg;

  &__title {
    margin: 0 0 $spacing-sm;
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__item {
    display: flex;
    gap: $spacing-sm;
    align-items: baseline;
    padding: 6px 10px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-sm;
  }

  &__code {
    color: $color-text;
  }

  &__raw {
    flex: 1;
    font-family: monospace;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__count {
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__note {
    margin: $spacing-sm 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}
</style>
