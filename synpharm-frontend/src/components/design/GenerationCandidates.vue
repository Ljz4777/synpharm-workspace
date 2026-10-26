<template>
  <section class="logs">
    <header class="logs__head">
      <div>
        <h3 class="logs__title">逐分子去向</h3>
        <p class="logs__hint">
          被筛掉的分子必须留下原因。只记数量的话，后期永远分不清是「约束筛太狠」
          还是「采样采太偏」—— 这一页就是回答那个问题的唯一入口。
        </p>
      </div>
      <div class="logs__filters">
        <label class="field">
          <span class="field__label">阶段</span>
          <SpSelect
            v-model="stage"
            block
            :options="stageSelectOptions"
            @change="reload"
          />
        </label>
        <label class="field">
          <span class="field__label">结局</span>
          <SpSelect
            v-model="outcome"
            block
            :options="outcomeSelectOptions"
            @change="reload"
          />
        </label>
        <button type="button" class="btn" :disabled="loading" @click="reload">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </div>
    </header>

    <p v-if="error" class="banner banner--danger">{{ error }}</p>

    <div v-if="!loading && !rows.length" class="logs__empty">
      <EmptyState title="没有匹配的记录" description="换一个阶段或结局筛选。" />
    </div>

    <div v-else class="logs__table-wrap">
      <table class="logs__table">
        <thead>
          <tr>
            <th>阶段</th>
            <th>结局</th>
            <th>分子</th>
            <th>簇</th>
            <th class="num">预筛分</th>
            <th>原因</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" :class="{ 'row--filtered': isFiltered(row) }">
            <td><code>{{ row.stage }}</code></td>
            <td>
              <span class="tag" :class="`tag--${outcomeTone(row.outcome)}`">{{ row.outcome }}</span>
            </td>
            <td class="mol">
              <span v-if="row.smiles" class="mol__smiles" :title="row.smiles">{{ shortSmiles(row.smiles) }}</span>
              <span v-else-if="row.sequenceHash" class="mol__hash" :title="row.sequenceHash">
                seq {{ row.sequenceHash.slice(0, 10) }}…
              </span>
              <span v-else class="mol__none">—</span>
              <button
                v-if="row.candidateId"
                type="button"
                class="mol__link"
                :title="`去评估候选 #${row.candidateId}`"
                @click="goEvaluate(row.candidateId)"
              >
                #{{ row.candidateId }} 去评估
              </button>
            </td>
            <td><code class="cluster">{{ row.clusterId || '—' }}</code></td>
            <td class="num">{{ row.preScore ?? '—' }}</td>
            <td>
              <ul v-if="reasonsOf(row).length" class="reasons">
                <li v-for="(reason, i) in reasonsOf(row)" :key="i" class="reasons__item">
                  <span class="reasons__label" :class="`reasons__label--${reasonTone(reason.severity)}`">
                    {{ filterReasonLabel(reason.code) }}
                  </span>
                  <span class="reasons__msg">{{ reason.message }}</span>
                  <code class="reasons__code">{{ reason.code }}</code>
                </li>
              </ul>
              <span v-else class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="rows.length && !loading" class="logs__count">共 {{ rows.length }} 条</p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/ui/EmptyState.vue'
import SpSelect from '@/components/ui/SpSelect.vue'
import { generationApi, filterReasonLabel, parseFilterReasons } from '@/api/generation'
import type { FilterReasonItem, GenerationCandidateLog } from '@/api/generation'
import type { ApiError } from '@/utils/request'

/**
 * 逐分子日志。
 *
 * 一次性拉全量（生成阶段的日志量在千级以内），不做分页 —— 排查时更常见的是
 * 「筛掉的全部列出来看规律」，翻页反而挡路。
 */
const props = defineProps<{
  runId: number
}>()

const STAGE_OPTIONS = [
  { value: 'S5_DEDUP', label: 'S5 去重' },
  { value: 'S6_PRE_FILTER', label: 'S6 粗筛' },
  { value: 'S7_QUOTA', label: 'S7 配额' }
] as const

const OUTCOME_OPTIONS = [
  { value: 'KEPT', label: '保留' },
  { value: 'FILTERED', label: '筛除' },
  { value: 'MERGED', label: '合并' },
  { value: 'QUOTA_CUT', label: '配额裁掉' }
] as const

const stageSelectOptions = [{ value: '', label: '全部阶段' }, ...STAGE_OPTIONS]
const outcomeSelectOptions = [{ value: '', label: '全部去向' }, ...OUTCOME_OPTIONS]

const rows = ref<GenerationCandidateLog[]>([])
const loading = ref(false)
const error = ref('')
const stage = ref('')
const outcome = ref('')
const router = useRouter()

/** 日志 → 候选的关联键是 temp_ref 回填出来的 candidate_id；没有 id 就不给跳转入口 */
function goEvaluate(candidateId: number) {
  router.push(`/design/candidates/${candidateId}/evaluation`)
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await generationApi.candidateLogs(
      props.runId,
      stage.value || undefined,
      outcome.value || undefined
    )
  } catch (e) {
    const err = e as ApiError
    error.value = err?.message || '加载逐分子日志失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(reload)

function reasonsOf(row: GenerationCandidateLog): FilterReasonItem[] {
  return parseFilterReasons(row.filterReason)
}

function isFiltered(row: GenerationCandidateLog): boolean {
  return row.outcome === 'FILTERED' || row.outcome === 'QUOTA_CUT'
}

function outcomeTone(outcome: string): 'ok' | 'warn' | 'muted' {
  if (outcome === 'KEPT') return 'ok'
  if (outcome === 'FILTERED') return 'warn'
  return 'muted'
}

function reasonTone(severity: string): 'hard' | 'soft' {
  return severity === 'HARD' ? 'hard' : 'soft'
}

/** 长 SMILES 中间截断，避免把表格撑破；完整值在 title 里 */
function shortSmiles(smiles: string): string {
  if (smiles.length <= 42) return smiles
  return `${smiles.slice(0, 24)}…${smiles.slice(-14)}`
}
</script>

<style lang="scss" scoped>
.logs {
  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-md;
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
    max-width: 640px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__filters {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: flex-end;
  }

  &__empty {
    padding: $spacing-xl 0;
  }

  &__table-wrap {
    overflow-x: auto;
    border: 1px solid $color-border;
    border-radius: $radius-control;
  }

  &__table {
    width: 100%;
    border-collapse: collapse;
    font-size: $font-size-sm;

    th,
    td {
      padding: 8px 10px;
      text-align: left;
      vertical-align: top;
      border-bottom: 1px solid $color-border-soft;
    }

    th {
      font-size: $font-size-xs;
      font-weight: $font-weight-medium;
      color: $color-text-faint;
      background: $color-surface-alt;
      white-space: nowrap;
    }

    tbody tr:last-child td {
      border-bottom: none;
    }

    .num {
      text-align: right;
      white-space: nowrap;
    }
  }

  &__count {
    margin: $spacing-sm 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.row--filtered {
  background: rgba($warning-color, 0.05);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__control {
    padding: 6px 8px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-sm;
    color: $color-text;
    background: $color-surface;
  }
}

.tag {
  padding: 2px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  white-space: nowrap;

  &--ok {
    color: $success-color;
    background: rgba($success-color, 0.12);
  }

  &--warn {
    color: $error-color;
    background: rgba($error-color, 0.1);
  }

  &--muted {
    color: $text-muted;
    background: rgba($text-muted, 0.14);
  }
}

.mol {
  max-width: 300px;

  &__smiles,
  &__hash {
    font-family: monospace;
    font-size: $font-size-xs;
    word-break: break-all;
    color: $color-text;
  }

  &__none {
    color: $color-text-faint;
  }

  &__link {
    display: block;
    margin-top: 2px;
    padding: 0;
    border: none;
    font-size: $font-size-xs;
    color: $color-brand-strong;
    background: none;
    cursor: pointer;
    text-decoration: underline;

    &:hover {
      color: $color-brand;
    }
  }
}

.cluster {
  font-size: $font-size-xs;
  color: $color-text-soft;
}

.reasons {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;

  &__item {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: baseline;
  }

  &__label {
    padding: 1px 6px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    white-space: nowrap;

    &--hard {
      color: $error-color;
      background: rgba($error-color, 0.1);
    }

    &--soft {
      color: $warning-color;
      background: rgba($warning-color, 0.14);
    }
  }

  &__msg {
    font-size: $font-size-xs;
    color: $color-text-soft;
  }

  &__code {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.muted {
  color: $color-text-faint;
}
</style>
