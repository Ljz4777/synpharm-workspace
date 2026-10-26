<template>
  <section class="frozen">
    <header class="frozen__head">
      <div>
        <h3 class="frozen__title">
          评估冻结快照
          <code class="frozen__no">{{ freeze.freezeNo }}</code>
        </h3>
        <p class="frozen__hint">
          编辑与决策都以这份快照为输入，不看「当前分数」—— 否则改前改后不是同一把尺子。
        </p>
      </div>
      <div class="frozen__badges">
        <span class="tag" :class="freeze.hardPassed ? 'tag--ok' : 'tag--bad'">
          {{ freeze.hardPassed ? '硬约束通过' : '硬约束违例' }}
        </span>
        <span class="tag tag--neutral">hash {{ freeze.contentHash.slice(0, 8) }}</span>
      </div>
    </header>

    <!-- 覆盖率警告：综合分只代表一部分指标，必须显眼 -->
    <p v-if="coverageIncomplete" class="banner banner--warn">
      <strong>综合分只代表 {{ percent(freeze.weightCoverage) }} 的权重。</strong>
      {{ freeze.missingMetrics.length }} 个指标当前算不出来（{{ freeze.missingMetrics.join('、') }}），
      它们<strong>没有被判为不达标，而是根本没算</strong> —— 不要把这个分数当成完整结论。
    </p>

    <dl class="summary">
      <div class="summary__item">
        <dt>综合分</dt>
        <dd>{{ freeze.totalScore ?? '—' }}</dd>
      </div>
      <div class="summary__item">
        <dt>权重覆盖率</dt>
        <dd>
          {{ percent(freeze.weightCoverage) }}
          <em v-if="coverageIncomplete" class="summary__flag">不完整</em>
        </dd>
      </div>
      <div class="summary__item">
        <dt>版本</dt>
        <dd>v{{ freeze.versionNo }}</dd>
      </div>
      <div class="summary__item">
        <dt>硬约束违例</dt>
        <dd>{{ freeze.violations.length }} 项</dd>
      </div>
    </dl>

    <ul v-if="freeze.violations.length" class="violations">
      <li v-for="(v, i) in freeze.violations" :key="i" class="violations__item">{{ v }}</li>
    </ul>

    <div v-if="freeze.skippedRules.length" class="skipped">
      <span class="skipped__title">未能判定的规则（不是通过）</span>
      <code class="skipped__code">{{ freeze.skippedRules.join('、') }}</code>
    </div>

    <table class="scores">
      <thead>
        <tr>
          <th>指标</th>
          <th class="num">值</th>
          <th class="num">归一化</th>
          <th>达标</th>
          <th>说明</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.code" :class="{ 'row--missing': row.missing }">
          <td><code>{{ row.code }}</code></td>
          <td class="num">{{ row.missing ? '—' : row.value }}</td>
          <td class="num">{{ row.normalized ?? '—' }}</td>
          <td>
            <span v-if="row.passed === null" class="muted">未判定</span>
            <span v-else-if="row.passed" class="pill pill--ok">达标</span>
            <span v-else class="pill pill--bad">未达标</span>
          </td>
          <td class="note">
            {{ row.note || (row.missing ? '计算层未返回该指标（不是 0）' : '—') }}
          </td>
        </tr>
      </tbody>
    </table>

    <p class="frozen__foot">
      算不出来的指标一律留空而不是填 0：<code>LogP=0</code> 和「算不出 LogP」在决策阶段含义完全不同。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { EvaluationFreeze } from '@/api/evaluation'

/**
 * 评估冻结快照展示。
 *
 * 最重要的一件事：把「覆盖率不完整」顶到用户脸上。
 * 一个 92 分如果只来自 33% 的权重，它就不该被当成 92 分用。
 */
const props = defineProps<{
  freeze: EvaluationFreeze
}>()

const coverageIncomplete = computed(() => {
  const c = props.freeze.weightCoverage
  return c !== null && c !== undefined && c < 0.999
})

const rows = computed(() =>
  Object.entries(props.freeze.scores ?? {}).map(([code, s]) => ({
    code,
    value: s.value,
    normalized: s.normalized,
    passed: s.passed === undefined ? null : s.passed,
    note: s.note,
    missing: s.value === null || s.value === undefined
  }))
)

function percent(v?: number | null): string {
  if (v === null || v === undefined) return '—'
  return `${Math.round(v * 1000) / 10}%`
}
</script>

<style lang="scss" scoped>
.frozen {
  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: flex-start;
    justify-content: space-between;
    margin-bottom: $spacing-md;
  }

  &__title {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
    margin: 0 0 4px;
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
    max-width: 680px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__badges {
    display: flex;
    gap: $spacing-xs;
  }

  &__foot {
    margin: $spacing-md 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;

    code {
      font-family: monospace;
    }
  }
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-lg;
  margin: 0 0 $spacing-md;
  padding: $spacing-md 0;
  border-top: 1px solid $color-border;
  border-bottom: 1px solid $color-border;

  &__item {
    min-width: 110px;

    dt {
      font-size: $font-size-xs;
      color: $color-text-faint;
    }

    dd {
      margin: 2px 0 0;
      font-size: $font-size-xl;
      font-weight: $font-weight-semibold;
      color: $color-text;
    }
  }

  &__flag {
    margin-left: 6px;
    font-size: $font-size-xs;
    font-style: normal;
    font-weight: $font-weight-normal;
    color: $warning-color;
  }
}

.violations {
  margin: 0 0 $spacing-md;
  padding: $spacing-sm $spacing-md;
  border-radius: $radius-control;
  background: rgba($error-color, 0.08);
  list-style: none;

  &__item {
    font-size: $font-size-sm;
    color: $error-color;
  }
}

.skipped {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
  align-items: baseline;
  margin-bottom: $spacing-md;
  padding: $spacing-sm $spacing-md;
  border-radius: $radius-control;
  background: rgba($warning-color, 0.1);

  &__title {
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $warning-color;
  }

  &__code {
    font-family: monospace;
    font-size: $font-size-xs;
    color: $color-text-soft;
  }
}

.scores {
  width: 100%;
  border-collapse: collapse;
  font-size: $font-size-sm;

  th,
  td {
    padding: 7px 10px;
    text-align: left;
    border-bottom: 1px solid $color-border-soft;
  }

  th {
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    color: $color-text-faint;
    background: $color-surface-alt;
  }

  .num {
    text-align: right;
  }

  code {
    font-family: monospace;
    font-size: $font-size-xs;
  }
}

.row--missing {
  background: rgba($text-muted, 0.06);
}

.note {
  font-size: $font-size-xs;
  color: $color-text-faint;
}

.pill {
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;

  &--ok {
    color: $success-color;
    background: rgba($success-color, 0.12);
  }

  &--bad {
    color: $error-color;
    background: rgba($error-color, 0.12);
  }
}

.tag {
  padding: 2px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;

  &--ok {
    color: $success-color;
    background: rgba($success-color, 0.12);
  }

  &--bad {
    color: $error-color;
    background: rgba($error-color, 0.12);
  }

  &--neutral {
    color: $text-muted;
    background: rgba($text-muted, 0.14);
  }
}

.muted {
  color: $color-text-faint;
}
</style>
