<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import SpSelect from '@/components/ui/SpSelect.vue'
import type { ConstraintRule, ConstraintSet } from '@/api/requirement'
import {
  FIELD_OPTIONS,
  HARD_RULE_PRESETS,
  describeRange,
  fieldLabel,
  toRuleBody,
  type HardRulePreset
} from '@/utils/baselinePresets'

/**
 * 约束规则编辑器（基线块4 硬约束 / 块5 软约束）。
 *
 * 为什么不直接改现有约束集：后端只允许 PATCH `DRAFT` 状态的集合，一旦激活就锁定。
 * 那是刻意的 —— 历史基线引用规则集，规则被就地改写就等于篡改了过去的判定依据。
 * 所以这里的保存语义是**新建一版再激活**，旧版留在历史里。
 *
 * `humanReadable` 一律由本组件生成/可编辑，不允许为空：用户看到的规则必须就是
 * 实际生效的规则（红线 R2）。
 */
const props = defineProps<{
  current: ConstraintSet | null
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'save', rules: ConstraintRule[]): void
}>()

/* ------------------------------ 本地草稿 ------------------------------ */

/** 编辑中的规则；从 current 初始化，用户改动只落在这里直到点保存 */
const draft = ref<ConstraintRule[]>([])

/** 最近一次同步的集合 id，用来判断「外部真变了」而不是「父组件刚重渲染」 */
let syncedSetId: number | null = null
/** 是否被用户改过：改过就不因父组件刷新而丢弃草稿 */
const dirty = ref(false)

function fromServer(set: ConstraintSet | null): ConstraintRule[] {
  return (set?.rules ?? []).map((r) => ({ ...r, params: { ...(r.params ?? {}) } }))
}

watch(
  () => props.current,
  (set) => {
    const id = set?.id ?? null
    // 同一个集合的重复下发不覆盖用户正在编辑的草稿
    if (id === syncedSetId && dirty.value) return
    syncedSetId = id
    draft.value = fromServer(set)
    dirty.value = false
  },
  { immediate: true }
)

const hardCount = computed(() => draft.value.filter((r) => r.severity === 'HARD').length)
const softCount = computed(() => draft.value.filter((r) => r.severity === 'SOFT').length)

const statusText = computed(() => {
  if (!props.current) return '还没有任何约束：生成阶段不会过滤，任何分子都会进入评估。'
  const no = props.current.setNo ?? `#${props.current.id}`
  const state = props.current.status === 'ACTIVE' ? '生效中' : '草稿'
  return `${no}（${state}，v${props.current.version ?? 1}）· 共 ${props.current.rules?.length ?? 0} 条规则`
})

/* ------------------------------ 推荐模板 ------------------------------ */

/** 判断某条模板是否已在列表里（同 field + 同上下限即视为重复） */
function hasPreset(preset: HardRulePreset): boolean {
  return draft.value.some((r) => {
    const p = r.params ?? {}
    return (
      r.ruleType === 'NUMERIC_RANGE' &&
      String(p.field ?? '') === preset.field &&
      num(p.min) === (preset.min ?? null) &&
      num(p.max) === (preset.max ?? null)
    )
  })
}

function num(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

const selectedPresetKey = ref('')

const addablePresets = computed(() => HARD_RULE_PRESETS.filter((p) => !hasPreset(p)))

const addablePresetOptions = computed(() =>
  addablePresets.value.map((p) => ({
    value: p.key,
    label: p.label,
    hint: p.severity === 'HARD' ? '硬约束' : '软约束'
  }))
)

const SEVERITY_RULE_OPTIONS = [
  { value: 'HARD', label: '硬约束（淘汰）' },
  { value: 'SOFT', label: '软约束（扣分）' }
]

const selectedPreset = computed(
  () => HARD_RULE_PRESETS.find((p) => p.key === selectedPresetKey.value) ?? null
)

function addPreset() {
  const preset = selectedPreset.value
  if (!preset) return
  draft.value = [...draft.value, toRuleBody(preset, draft.value.length) as ConstraintRule]
  dirty.value = true
  selectedPresetKey.value = ''
}

/** 一次把缺失的常用硬约束都补上 —— 「不知道怎么开始」时最省事的一条路 */
function addAllRecommended() {
  const missing = addablePresets.value
  if (missing.length === 0) return
  const start = draft.value.length
  draft.value = [
    ...draft.value,
    ...missing.map((p, i) => toRuleBody(p, start + i) as ConstraintRule)
  ]
  dirty.value = true
}

/* ------------------------------ 自定义规则 ------------------------------ */

const custom = reactive({
  open: false,
  field: 'MolWt' as string,
  min: '' as string,
  max: '' as string,
  severity: 'HARD' as 'HARD' | 'SOFT'
})

const customError = computed(() => {
  const min = custom.min.trim() === '' ? null : Number(custom.min)
  const max = custom.max.trim() === '' ? null : Number(custom.max)
  if (custom.min.trim() !== '' && !Number.isFinite(min)) return '下限必须是数字'
  if (custom.max.trim() !== '' && !Number.isFinite(max)) return '上限必须是数字'
  if (min === null && max === null) return '上下限至少填一个'
  if (min !== null && max !== null && min > max) return '下限不能大于上限'
  return ''
})

function addCustom() {
  if (customError.value) return
  const min = custom.min.trim() === '' ? null : Number(custom.min)
  const max = custom.max.trim() === '' ? null : Number(custom.max)
  const params: Record<string, unknown> = { field: custom.field }
  if (min !== null) params.min = min
  if (max !== null) params.max = max
  draft.value = [
    ...draft.value,
    {
      ruleType: 'NUMERIC_RANGE',
      severity: custom.severity,
      params,
      weight: custom.severity === 'SOFT' ? 1 : undefined,
      humanReadable: describeRange(custom.field, min, max),
      sortNo: draft.value.length,
      enabled: true
    }
  ]
  dirty.value = true
  custom.min = ''
  custom.max = ''
  custom.open = false
}

/* ------------------------------ 行操作 ------------------------------ */

function removeRule(index: number) {
  draft.value = draft.value.filter((_, i) => i !== index)
  dirty.value = true
}

function toggleSeverity(index: number) {
  const rule = draft.value[index]
  if (!rule) return
  const next = rule.severity === 'HARD' ? 'SOFT' : 'HARD'
  draft.value = draft.value.map((r, i) =>
    i === index ? { ...r, severity: next, weight: next === 'SOFT' ? (r.weight ?? 1) : undefined } : r
  )
  dirty.value = true
}

function editHumanReadable(index: number, value: string) {
  draft.value = draft.value.map((r, i) => (i === index ? { ...r, humanReadable: value } : r))
  dirty.value = true
}

/** 单条规则的参数摘要，让「分子量 150~600」之外的复杂类型也能看清 */
function ruleSummary(rule: ConstraintRule): string {
  const p = (rule.params ?? {}) as Record<string, unknown>
  if (rule.ruleType === 'NUMERIC_RANGE') {
    return describeRange(String(p.field ?? ''), num(p.min), num(p.max))
  }
  const text = Object.entries(p)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join('，')
  return text || rule.ruleType
}

function ruleField(rule: ConstraintRule): string {
  const p = (rule.params ?? {}) as Record<string, unknown>
  return String(p.field ?? '')
}

/** 保存前把空 humanReadable 补上，避免整批被后端 400 打回 */
const canSave = computed(() => {
  if (draft.value.length === 0) return false
  return draft.value.every((r) => (r.humanReadable ?? '').trim().length > 0)
})

const saveHint = computed(() => {
  if (draft.value.length === 0) return '至少需要一条规则才能保存'
  const blank = draft.value.filter((r) => (r.humanReadable ?? '').trim().length === 0).length
  if (blank > 0) return `有 ${blank} 条规则还没写人类可读描述，补上才能保存`
  return ''
})

function save() {
  if (!canSave.value) return
  // sortNo 重排，保证展示顺序与提交顺序一致
  const rules = draft.value.map((r, i) => ({ ...r, sortNo: i }))
  emit('save', rules)
  dirty.value = false
}

function reset() {
  draft.value = fromServer(props.current)
  dirty.value = false
}
</script>

<template>
  <div class="ce">
    <div class="ce__head">
      <div>
        <p class="ce__title">约束规则</p>
        <p class="ce__status" :class="{ 'ce__status--none': !current }">{{ statusText }}</p>
      </div>
      <div class="ce__counts" v-if="draft.length">
        <span class="ce__tag ce__tag--hard">硬约束 {{ hardCount }}</span>
        <span class="ce__tag ce__tag--soft">软约束 {{ softCount }}</span>
      </div>
    </div>

    <p class="ce__hint">
      硬约束：不满足直接淘汰，不进决策。<br />
      软约束：不满足只扣分，仍可进决策并在证据包里留痕。
    </p>

    <!-- 规则列表 -->
    <div v-if="draft.length" class="ce__list">
      <div v-for="(rule, i) in draft" :key="i" class="ce__row">
        <button
          type="button"
          class="ce__sev"
          :class="rule.severity === 'HARD' ? 'ce__sev--hard' : 'ce__sev--soft'"
          :title="'点击切换为' + (rule.severity === 'HARD' ? '软约束' : '硬约束')"
          @click="toggleSeverity(i)"
        >
          {{ rule.severity === 'HARD' ? '硬' : '软' }}
        </button>
        <div class="ce__body">
          <input
            class="ce__desc"
            :value="rule.humanReadable ?? ''"
            placeholder="规则描述（必填，会原样展示给下游）"
            @input="editHumanReadable(i, ($event.target as HTMLInputElement).value)"
          />
          <p class="ce__meta">
            <span v-if="ruleField(rule)">{{ fieldLabel(ruleField(rule)) }}</span>
            <span>{{ ruleSummary(rule) }}</span>
            <span class="ce__type">{{ rule.ruleType }}</span>
          </p>
        </div>
        <button type="button" class="ce__del" title="删除这条规则" @click="removeRule(i)">×</button>
      </div>
    </div>
    <p v-else class="ce__empty">
      还没有约束规则。下面挑一条推荐模板，或者自己写一条 ——
      没有约束时生成阶段不做任何过滤。
    </p>

    <!-- 添加区 -->
    <div class="ce__add">
      <div class="ce__addrow">
        <SpSelect
          v-model="selectedPresetKey"
          :min-width="200"
          :options="addablePresetOptions"
          placeholder="选择一条常用规则…"
        />
        <button type="button" class="ce__btn" :disabled="!selectedPresetKey" @click="addPreset">
          ＋ 添加
        </button>
        <button
          type="button"
          class="ce__btn ce__btn--ghost"
          :disabled="addablePresets.length === 0"
          @click="addAllRecommended"
        >
          一次补齐 {{ addablePresets.length }} 条
        </button>
      </div>
      <p v-if="selectedPreset" class="ce__why">{{ selectedPreset.why }}</p>

      <button type="button" class="ce__link" @click="custom.open = !custom.open">
        {{ custom.open ? '收起自定义' : '＋ 自定义一条数值区间规则' }}
      </button>

      <div v-if="custom.open" class="ce__custom">
        <label class="ce__field">
          <span>字段</span>
          <SpSelect v-model="custom.field" :min-width="200" :options="FIELD_OPTIONS" />
        </label>
        <label class="ce__field">
          <span>下限</span>
          <input v-model="custom.min" class="ce__input" placeholder="留空表示不限" />
        </label>
        <label class="ce__field">
          <span>上限</span>
          <input v-model="custom.max" class="ce__input" placeholder="留空表示不限" />
        </label>
        <label class="ce__field">
          <span>类型</span>
          <SpSelect v-model="custom.severity" :min-width="200" :options="SEVERITY_RULE_OPTIONS" />
        </label>
        <button type="button" class="ce__btn" :disabled="!!customError" @click="addCustom">
          ＋ 加入
        </button>
        <p v-if="customError" class="ce__err">{{ customError }}</p>
        <p v-else class="ce__preview">
          将生成：{{ describeRange(custom.field, custom.min === '' ? null : Number(custom.min), custom.max === '' ? null : Number(custom.max)) }}
        </p>
      </div>
    </div>

    <!-- 保存 -->
    <div class="ce__foot">
      <span v-if="dirty" class="ce__dirty">有未保存的改动</span>
      <span v-else-if="saveHint" class="ce__dirty">{{ saveHint }}</span>
      <span v-else class="ce__ok">{{ current ? '与生效版本一致' : '改动后点右侧按钮生效' }}</span>
      <div class="ce__footbtns">
        <button v-if="dirty" type="button" class="ce__btn ce__btn--ghost" @click="reset">
          撤销改动
        </button>
        <button
          type="button"
          class="ce__btn ce__btn--primary"
          :disabled="!canSave || busy"
          :title="canSave ? '新建一版约束集并激活' : saveHint"
          @click="save"
        >
          {{ busy ? '保存中…' : '保存为新版本并启用' }}
        </button>
      </div>
    </div>
    <p class="ce__note">
      保存会新建一个约束集版本并激活；旧版本保留在历史里，已冻结的基线不会因此改变。
    </p>
  </div>
</template>

<style scoped lang="scss">
.ce {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px;
  background: var(--surface);

  &__head {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;
    flex-wrap: wrap;
  }

  &__title {
    margin: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--text-strong);
  }

  &__status {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--text-muted);

    &--none {
      color: var(--warning, #b45309);
    }
  }

  &__counts {
    display: flex;
    gap: 8px;
  }

  &__tag {
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 999px;

    &--hard {
      background: rgba(220, 38, 38, 0.1);
      color: #b91c1c;
    }

    &--soft {
      background: rgba(37, 99, 235, 0.1);
      color: #1d4ed8;
    }
  }

  &__hint {
    margin: 10px 0 14px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--text-muted);
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__row {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 10px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--surface-2, rgba(0, 0, 0, 0.015));
  }

  &__sev {
    flex: 0 0 auto;
    width: 28px;
    height: 28px;
    border-radius: 8px;
    border: 1px solid transparent;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;

    &--hard {
      background: rgba(220, 38, 38, 0.12);
      color: #b91c1c;
      border-color: rgba(220, 38, 38, 0.28);
    }

    &--soft {
      background: rgba(37, 99, 235, 0.12);
      color: #1d4ed8;
      border-color: rgba(37, 99, 235, 0.28);
    }
  }

  &__body {
    flex: 1 1 auto;
    min-width: 0;
  }

  &__desc {
    width: 100%;
    border: 1px solid transparent;
    border-radius: 6px;
    background: transparent;
    padding: 3px 6px;
    font-size: 13px;
    color: var(--text-strong);
    font-family: inherit;

    &:hover {
      border-color: var(--line);
    }

    &:focus {
      outline: none;
      border-color: var(--brand, #0f766e);
      background: var(--surface);
    }
  }

  &__meta {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    margin: 4px 0 0;
    padding-left: 6px;
    font-size: 11px;
    color: var(--text-muted);
  }

  &__type {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    opacity: 0.7;
  }

  &__del {
    flex: 0 0 auto;
    width: 26px;
    height: 26px;
    border-radius: 6px;
    border: 1px solid var(--line);
    background: var(--surface);
    color: var(--text-muted);
    cursor: pointer;
    line-height: 1;

    &:hover {
      color: #b91c1c;
      border-color: rgba(220, 38, 38, 0.4);
    }
  }

  &__empty {
    margin: 0 0 14px;
    padding: 14px;
    border: 1px dashed var(--line);
    border-radius: 10px;
    font-size: 12px;
    line-height: 1.7;
    color: var(--text-muted);
  }

  &__add {
    margin-top: 14px;
    padding-top: 14px;
    border-top: 1px dashed var(--line);
  }

  &__addrow {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    align-items: center;
  }

  &__select,
  &__input {
    border: 1px solid var(--line);
    border-radius: 8px;
    padding: 6px 8px;
    font-size: 12px;
    background: var(--surface);
    color: var(--text-strong);
    font-family: inherit;
    min-width: 0;
  }

  &__select {
    flex: 1 1 200px;
    max-width: 340px;
  }

  &__why {
    margin: 8px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--text-muted);
  }

  &__link {
    margin-top: 10px;
    background: none;
    border: none;
    padding: 0;
    font-size: 12px;
    color: var(--brand, #0f766e);
    cursor: pointer;
  }

  &__custom {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: flex-end;
    margin-top: 12px;
    padding: 12px;
    border-radius: 10px;
    background: var(--surface-2, rgba(0, 0, 0, 0.02));
    border: 1px solid var(--line);
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 11px;
    color: var(--text-muted);
    min-width: 0;

    .ce__select,
    .ce__input {
      flex: 0 0 auto;
    }
  }

  &__err {
    flex: 1 0 100%;
    margin: 0;
    font-size: 12px;
    color: #b91c1c;
  }

  &__preview {
    flex: 1 0 100%;
    margin: 0;
    font-size: 12px;
    color: var(--text-muted);
  }

  &__btn {
    border-radius: 8px;
    border: 1px solid var(--line);
    padding: 6px 12px;
    font-size: 12px;
    background: var(--surface);
    color: var(--text-strong);
    cursor: pointer;
    font-family: inherit;

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }

    &--ghost {
      background: transparent;
    }

    &--primary {
      background: var(--brand, #0f766e);
      border-color: var(--brand, #0f766e);
      color: #fff;
      font-weight: 600;
    }
  }

  &__foot {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    margin-top: 14px;
  }

  &__footbtns {
    display: flex;
    gap: 8px;
  }

  &__dirty {
    font-size: 12px;
    color: var(--warning, #b45309);
  }

  &__ok {
    font-size: 12px;
    color: var(--text-muted);
  }

  &__note {
    margin: 8px 0 0;
    font-size: 11px;
    color: var(--text-muted);
  }
}
</style>
