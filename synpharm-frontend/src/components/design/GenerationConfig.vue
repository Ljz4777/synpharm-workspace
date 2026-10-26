<template>
  <section class="config">
    <header class="config__head">
      <div>
        <h3 class="config__title">发起一次生成</h3>
        <p class="config__hint">
          当前没有部署生成引擎，小分子只能「导入后筛选」，抗体序列能做规则式单点突变。
          这两条路径产出的批次都会标 degraded —— 那不是 bug，是让你一眼看出
          「这批候选是算出来的还是模型生成的」。
        </p>
      </div>
    </header>

    <p v-if="!canSubmit" class="banner banner--danger">{{ blockReason }}</p>

    <div class="config__grid">
      <label class="field">
        <span class="field__label">来源形态</span>
        <select v-model="mode" class="field__control">
          <option value="smiles">小分子（SMILES，导入筛选）</option>
          <option value="sequence">抗体序列（单点突变）</option>
        </select>
      </label>

      <label class="field">
        <span class="field__label">全局种子</span>
        <input v-model.number="seed" type="number" class="field__control" placeholder="留空则随机（结果可复现）" />
      </label>

      <label class="field">
        <span class="field__label">幂等键</span>
        <input
          v-model.trim="idempotencyKey"
          type="text"
          maxlength="64"
          class="field__control"
          placeholder="留空则每次都新建批次"
        />
      </label>
    </div>

    <!-- 小分子：一行一个 SMILES -->
    <template v-if="mode === 'smiles'">
      <label class="field field--block">
        <span class="field__label">待筛选分子（一行一个 SMILES）</span>
        <textarea v-model="smilesText" rows="6" class="field__control field__control--mono" spellcheck="false" />
      </label>
      <p class="config__note">
        硬约束与 PAINS/BRENK 会在这里被真判定；被筛掉的分子会逐条记原因，可在「逐分子去向」里查。
      </p>
    </template>

    <!-- 序列：重/轻链 -->
    <template v-else>
      <label class="field field--block">
        <span class="field__label">重链（VH）</span>
        <textarea v-model="heavyText" rows="4" class="field__control field__control--mono" spellcheck="false" />
      </label>
      <label class="field field--block">
        <span class="field__label">轻链（VL，可留空）</span>
        <textarea v-model="lightText" rows="4" class="field__control field__control--mono" spellcheck="false" />
      </label>
      <div class="config__grid">
        <label class="field">
          <span class="field__label">父本标识</span>
          <input v-model.trim="parentRef" type="text" class="field__control" placeholder="例如 PARENT-1" />
        </label>
      </div>
      <p class="config__note config__note--warn">
        序列类分子目前没有可用的 RDKit 指标，粗筛阶段会记
        <code>SEQUENCE_METRICS_UNAVAILABLE</code> 并明确标注「未判定」——
        没检查不等于检查通过，这一点在诊断里会写清楚。
      </p>
    </template>

    <div class="config__actions">
      <button type="button" class="btn btn--primary" :disabled="!canSubmit || submitting" @click="submit">
        {{ submitting ? '生成中…' : '提交生成' }}
      </button>
      <span class="config__preview">{{ seedSummary }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GenerationCreateRequest, GenerationSeedInput } from '@/api/generation'

/**
 * 生成配置。
 *
 * 只做客户端能确定的事：把输入整理成 seeds、给出「有几个种子」这类可验证提示。
 * 采样量、批次大小、是否降级全部由后端 S2/S3 从基线快照算 —— 前端不提供
 * 「n_samples 填多少」的输入框，因为那正是设计里明确禁止的「人工拍脑袋定采样」。
 */
const props = defineProps<{
  /** 项目是否已冻结基线；未冻结时不允许提交 */
  hasBaseline: boolean
  /** 是否已开启轮次；生成必须挂在某一轮上 */
  hasRound: boolean
  submitting: boolean
}>()

const emit = defineEmits<{
  (e: 'submit', body: GenerationCreateRequest): void
}>()

const mode = ref<'smiles' | 'sequence'>('smiles')
const seed = ref<number | undefined>(undefined)
const idempotencyKey = ref('')
const smilesText = ref('')
const heavyText = ref('')
const lightText = ref('')
const parentRef = ref('')

const smilesList = computed(() =>
  smilesText.value
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
)

const canSubmit = computed(() => {
  if (!props.hasBaseline || props.hasRound === false) return false
  if (props.submitting) return false
  if (mode.value === 'smiles') return smilesList.value.length > 0
  return heavyText.value.trim().length > 0 || lightText.value.trim().length > 0
})

const blockReason = computed(() => {
  if (!props.hasBaseline) return '项目还没有冻结基线。生成只读快照，请先在需求分析里冻结。'
  if (props.hasRound === false) return '项目还没有开启任何轮次。生成必须挂在某一轮上，请先开启一轮。'
  if (mode.value === 'smiles' && smilesList.value.length === 0) return '请至少填入一个 SMILES。'
  if (mode.value === 'sequence' && !canSubmit.value) return '请至少填入重链或轻链。'
  return ''
})

const seedSummary = computed(() =>
  mode.value === 'smiles' ? `${smilesList.value.length} 个 SMILES` : '1 条父本序列'
)

function submit() {
  if (!canSubmit.value) return
  const seeds: GenerationSeedInput[] =
    mode.value === 'smiles'
      ? smilesList.value.map((smiles, i) => ({ ref: `SEED-${i + 1}`, smiles }))
      : [
          {
            ref: parentRef.value || 'PARENT-1',
            heavyChain: heavyText.value.trim() || undefined,
            lightChain: lightText.value.trim() || undefined
          }
        ]

  const body: GenerationCreateRequest = {
    seeds,
    seed: seed.value === undefined || seed.value === null ? undefined : Number(seed.value),
    idempotencyKey: idempotencyKey.value || undefined
  }
  emit('submit', body)
}
</script>

<style lang="scss" scoped>
.config {
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
    max-width: 780px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
    gap: $spacing-sm;
    margin-bottom: $spacing-sm;
  }

  &__note {
    margin: $spacing-sm 0 0;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;

    &--warn {
      color: $warning-color;
    }

    code {
      font-family: monospace;
      font-size: $font-size-xs;
    }
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-md;
    align-items: center;
    margin-top: $spacing-md;
    padding-top: $spacing-md;
    border-top: 1px solid $color-border;
  }

  &__preview {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;

  &--block {
    margin-bottom: $spacing-sm;
  }

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__control {
    padding: 7px 9px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    font-size: $font-size-sm;
    color: $color-text;
    background: $color-surface;

    &--mono {
      font-family: monospace;
      font-size: $font-size-xs;
      line-height: 1.6;
      resize: vertical;
    }
  }
}
</style>
