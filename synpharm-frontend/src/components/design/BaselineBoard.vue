<template>
  <section class="board">
    <header class="board__head">
      <div>
        <h3 class="board__title">需求基线八块</h3>
        <p class="board__hint">
          基线是生成任务的唯一输入。八块里前六块必需、后两块可选；必需块缺失时不允许冻结 ——
          半个工单比没有工单更危险，因为生成结果看起来正常却缺了判断依据。
        </p>
      </div>
      <div class="board__summary">
        <span class="board__count" :class="`board__count--${overallTone}`">
          {{ okCount }}/{{ blocks.length }} 已齐
        </span>
      </div>
    </header>

    <div v-if="!preview" class="board__empty">
      <EmptyState title="尚未预检" description="点上方「重新预检」逐块检查当前项目还缺什么。" />
    </div>

    <template v-else>
      <div class="board__grid">
        <article
          v-for="(block, index) in blocks"
          :key="block.code"
          class="block"
          :class="[`block--${block.state.toLowerCase()}`, { 'block--open': expanded === block.code }]"
        >
          <button type="button" class="block__head" @click="toggle(block.code)">
            <span class="block__no">{{ index + 1 }}</span>
            <span class="block__label">
              {{ block.label }}
              <em v-if="!block.required" class="block__optional">可选</em>
            </span>
            <span class="block__state" :class="`block__state--${blockStateMeta(block.state).tone}`">
              {{ blockStateMeta(block.state).label }}
            </span>
            <span class="block__caret" aria-hidden="true">{{ expanded === block.code ? '▾' : '▸' }}</span>
          </button>

          <p class="block__message" :class="{ 'block__message--error': block.state === 'MISSING' }">
            {{ block.message || '—' }}
          </p>

          <ul v-if="gapsOf(block.code).length" class="block__gaps">
            <li
              v-for="gap in gapsOf(block.code)"
              :key="gap.code"
              class="gap"
              :class="`gap--${gap.severity.toLowerCase()}`"
            >
              <code class="gap__code">{{ gap.code }}</code>
              <span class="gap__text">{{ gap.message }}</span>
            </li>
          </ul>

          <div v-if="expanded === block.code" class="block__detail">
            <!-- 展开后是**人话**，不是原始 JSON：冻结快照里存的是八块内容原样，
                 但把它当接口报文贴给用户看等于什么都没说 -->
            <template v-for="detail in [detailOf(block.code)]" :key="detail.kind">
              <!-- 键值对：配额这类扁平内容 -->
              <dl v-if="detail.kind === 'kv'" class="kv">
                <div v-for="row in detail.rows" :key="row.label" class="kv__row">
                  <dt class="kv__label">{{ row.label }}</dt>
                  <dd class="kv__value">{{ row.value }}</dd>
                </div>
              </dl>

              <!-- 表格：约束 / 指标 / 结构 -->
              <template v-else-if="detail.kind === 'table'">
                <table class="mini">
                  <thead>
                    <tr>
                      <th v-for="col in detail.cols" :key="col">{{ col }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, i) in detail.rows" :key="i">
                      <td v-for="(cell, j) in row" :key="j" :class="{ 'mini__num': detail.numCols?.includes(j) }">
                        {{ cell }}
                      </td>
                    </tr>
                  </tbody>
                </table>
                <table v-if="detail.second" class="mini mini--second">
                  <caption class="mini__caption">{{ detail.second.title }}</caption>
                  <thead>
                    <tr>
                      <th v-for="col in detail.second.cols" :key="col">{{ col }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, i) in detail.second.rows" :key="i">
                      <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
                    </tr>
                  </tbody>
                </table>
                <p v-if="detail.note" class="detail__note">{{ detail.note }}</p>
              </template>

              <!-- 卡片列表：靶点 / 风险 -->
              <ul v-else-if="detail.kind === 'list'" class="cards">
                <li v-for="(item, i) in detail.items" :key="i" class="cards__item">
                  <div class="cards__top">
                    <span class="cards__title">{{ item.title }}</span>
                    <span v-for="tag in item.tags" :key="tag.text" class="tag" :class="tag.tone">
                      {{ tag.text }}
                    </span>
                  </div>
                  <p v-if="item.meta.length" class="cards__meta">{{ item.meta.join(' · ') }}</p>
                </li>
              </ul>

              <!-- 一句话（分子形式 / 说明） -->
              <p v-else class="detail__text">{{ detail.text }}</p>
            </template>

            <p v-if="jumpTargetOf(block.code) || libraryTargetId(block.code)" class="block__jump">
              <button v-if="jumpTargetOf(block.code)" type="button" class="link" @click="emit('jump', jumpTargetOf(block.code)!)">
                去「{{ stepLabel(jumpTargetOf(block.code)!) }}」补齐 →
              </button>
              <button
                v-if="libraryTargetId(block.code)"
                type="button"
                class="link"
                @click="openTargetLibrary(block.code)"
              >
                去靶点库看结构与口袋 →
              </button>
            </p>
          </div>
        </article>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/ui/EmptyState.vue'
import {
  blockStateMeta,
  type BaselineBlockCode,
  type BaselineGap,
  type BaselinePreview,
  type BaselineSnapshot
} from '@/api/requirement'
import {
  BETTER_LABEL,
  BOUNDARY_POLICY_LABEL,
  CAPACITY_MODE_LABEL,
  MODALITY_LABEL,
  MOA_LABEL,
  OPERATOR_LABEL,
  RULE_TYPE_LABEL,
  TARGET_ROLE_LABEL,
  labelOf
} from '@/utils/designLabels'

/**
 * 八块看板。
 *
 * 只读展示：它不提供任何「改一下让它变绿」的入口。缺项必须回到对应步骤补真实数据，
 * 这条边界把「看板」和「伪造达标」分开。
 *
 * 展开时展示的是**快照里的原始 JSON**（若已冻结）或预检的块摘要 —— 用户能直接看到
 * 生成阶段将来读到的内容长什么样，而不是一层转述。
 */
const props = defineProps<{
  preview: BaselinePreview | null
  /** 已冻结快照；有值时展开块展示快照内容而不是预检摘要 */
  snapshot?: BaselineSnapshot | null
  /** 每块缺项时应该跳去向导的哪一步 */
  jumpMap?: Partial<Record<BaselineBlockCode, string>>
}>()

const emit = defineEmits<{
  /** 跳到向导某一步 */
  (e: 'jump', stepKey: string): void
}>()

const STEP_LABELS: Record<string, string> = {
  goal: '目标与形式',
  target: '靶点与结构',
  constrain: '约束',
  metric: '指标与风险',
  quota: '配额'
}

const expanded = ref<BaselineBlockCode | null>(null)

const blocks = computed(() => props.preview?.blocks ?? [])

const okCount = computed(() => blocks.value.filter((b) => b.state === 'OK').length)

const overallTone = computed(() => {
  if (!props.preview) return 'neutral'
  if (props.preview.ready) return 'success'
  return blocks.value.some((b) => b.state === 'MISSING') ? 'danger' : 'warning'
})

function toggle(code: BaselineBlockCode) {
  expanded.value = expanded.value === code ? null : code
}

function gapsOf(code: BaselineBlockCode): BaselineGap[] {
  return (props.preview?.gaps ?? []).filter((g) => g.block === code)
}

function jumpTargetOf(code: BaselineBlockCode): string | undefined {
  return props.jumpMap?.[code]
}

function stepLabel(key: string): string {
  return STEP_LABELS[key] ?? key
}

/* ------------------------------------------------------------------ */
/* 块1/块2 → 靶点库：这两块的内容就是靶点库里的那条靶点                  */
/* 之前它们彼此独立，用户在基线页看到「结构缺失」也不知道该去哪儿看可用结构 */
/* ------------------------------------------------------------------ */

const router = useRouter()

/** 该块对应的靶点 id（只有靶点/结构两块有意义） */
function libraryTargetId(code: BaselineBlockCode): number | null {
  if (code !== 'TARGETS' && code !== 'STRUCTURE') return null
  const snap = props.snapshot
  if (!snap) return null
  if (code === 'TARGETS') {
    const items = (snap.targets as { items?: Array<{ targetId?: number }> } | null)?.items ?? []
    const primary = items.find((t) => t.targetId)?.targetId
    return primary ?? null
  }
  const structure = snap.structure as { targetId?: number } | null
  return structure?.targetId ?? null
}

function openTargetLibrary(code: BaselineBlockCode) {
  const targetId = libraryTargetId(code)
  if (!targetId) return
  void router.push({ path: '/targets', query: { targetId: String(targetId) } })
}

/** 块在快照里对应的字段名 */
const SNAPSHOT_FIELD: Record<BaselineBlockCode, keyof BaselineSnapshot> = {
  TARGETS: 'targets',
  STRUCTURE: 'structure',
  MODALITY: 'modalityCode',
  HARD_RULES: 'hardRules',
  SOFT_RULES: 'softRules',
  METRICS: 'metrics',
  QUOTA: 'quota',
  RISKS: 'risks'
}

/* ------------------------------------------------------------------ */
/* 展开后的内容：一律转成人话                                           */
/*                                                                     */
/* 之前这里直接把快照里的 JSON 原样贴在 <pre> 里。理由是「让用户看到生成  */
/* 阶段将来读到的原文」，但实际效果是谁也看不懂 —— 靶点、配额、十条指标   */
/* 全挤在一段没有结构的字符串里。快照的意义是**内容**，不是**格式**。     */
/* ------------------------------------------------------------------ */

interface DetailKv {
  kind: 'kv'
  rows: Array<{ label: string; value: string }>
}

interface DetailTable {
  kind: 'table'
  cols: string[]
  rows: string[][]
  /** 需要右对齐的列序号（数值列） */
  numCols?: number[]
  /** 第二张表（结构 + 口袋） */
  second?: { title: string; cols: string[]; rows: string[][] }
  note?: string
}

interface DetailList {
  kind: 'list'
  items: Array<{ title: string; tags: Array<{ text: string; tone: string }>; meta: string[] }>
}

interface DetailText {
  kind: 'text'
  text: string
}

type Detail = DetailKv | DetailTable | DetailList | DetailText

function pick(map: Record<string, string>, code: string): string {
  return labelOf(map, code)
}

/** 没有快照时，讲清「这一块要什么」——比贴一段预检 JSON 有用得多 */
const BLOCK_REQUIREMENT: Record<BaselineBlockCode, string> = {
  TARGETS:
    '至少一个靶点，且恰好一个主靶点。生成阶段会按主靶点判断「是不是打对了东西」，' +
    '没有靶点的候选在评估里无法回答「打中了什么」。',
  STRUCTURE:
    '主靶点需要一条可用结构（晶体或预测均可）。结构决定口袋，口袋决定对接类指标能不能算；' +
    '一块结构都没有时，对接相关指标会整体缺席，而不是给 0 分。',
  MODALITY:
    '分子形式决定后续能算哪些指标、能做哪些编辑动作。小分子与抗体走的是两套完全不同的指标集，' +
    '这一项填错，后面的分数解释就没有意义。',
  HARD_RULES:
    '至少一条硬约束。硬约束是**淘汰线**：判不过的候选直接不进候选池，' +
    '所以它也是评估阶段「为什么这个分子没进来」的唯一依据。',
  SOFT_RULES: '软约束只影响排序与提示，不淘汰候选。可以留空。',
  METRICS:
    '优化指标与权重。综合分按权重归一化加权；没有设阈值的指标只参与排序、不参与达标判定，' +
    '这类指标会在指标表里标「未设阈值」。',
  QUOTA:
    '最少候选数决定生成要跑出多少条（少于它会判定基线不可冻结），GPU 与湿实验配额封顶后续开销。',
  RISKS: '风险关注点。标了「必须验证」的风险在决策阶段强制要求实测证据，不能靠预测分数放过。'
}

function asItems(value: unknown): Array<Record<string, unknown>> {
  const box = value as { items?: unknown[] } | null | undefined
  return Array.isArray(box?.items) ? (box.items as Array<Record<string, unknown>>) : []
}

function num(value: unknown, decimals = 2): string {
  if (value === null || value === undefined || value === '') return '—'
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(decimals).replace(/\.0+$/, '') : String(value)
}

function detailOf(code: BaselineBlockCode): Detail {
  const raw = props.snapshot?.[SNAPSHOT_FIELD[code]]

  // 没有冻结快照：只解释这一块要求什么，不展示任何报文
  if (!props.snapshot) {
    return { kind: 'text', text: BLOCK_REQUIREMENT[code] }
  }

  if (code === 'MODALITY') {
    const code0 = String(raw ?? '')
    const label = MODALITY_LABEL[code0] ?? code0
    return { kind: 'text', text: `${label}（${code0 || '未填'}）` }
  }

  if (code === 'TARGETS') {
    const items = asItems(raw)
    if (!items.length) return { kind: 'text', text: '这一块没有内容。' }
    const moa = (raw as { moaCode?: string } | null)?.moaCode
    return {
      kind: 'list',
      items: items.map((t) => ({
        title: String(t.name ?? '—'),
        tags: t.isPrimary
          ? [{ text: '主靶点', tone: 'tag--success' }]
          : [{ text: pick(TARGET_ROLE_LABEL, String(t.role ?? '')), tone: 'tag--neutral' }],
        meta: [
          pick(TARGET_ROLE_LABEL, String(t.role ?? '')),
          String(t.targetType ?? '—'),
          t.uniprotId ? `UniProt ${String(t.uniprotId)}` : '无 UniProt',
          moa ? `作用机制 ${pick(MOA_LABEL, moa)}` : '未指定作用机制'
        ]
      }))
    }
  }

  if (code === 'STRUCTURE') {
    const box = raw as
      | {
          structures?: Array<Record<string, unknown>>
          pockets?: Array<Record<string, unknown>>
        }
      | null
    const structures = box?.structures ?? []
    const pockets = box?.pockets ?? []
    if (!structures.length) return { kind: 'text', text: '这一块没有可用结构。' }

    return {
      kind: 'table',
      cols: ['来源', 'PDB', '链', '分辨率', '方法'],
      rows: structures.map((s) => [
        String(s.source ?? '—'),
        String(s.pdbId ?? '—'),
        String(s.chainId ?? '—'),
        s.resolution === null || s.resolution === undefined ? '—' : `${num(s.resolution, 2)} Å`,
        String(s.experimentMethod ?? '—')
      ]),
      second: pockets.length
        ? {
            title: `结合口袋（${pockets.length} 个）`,
            cols: ['编号', '识别方法', '评分', '可成药性', '选用'],
            rows: pockets.map((p) => [
              String(p.pocketNo ?? '—'),
              String(p.method ?? '—'),
              num(p.score, 2),
              num(p.druggability, 3),
              p.isSelected ? '是' : '否'
            ])
          }
        : undefined
    }
  }

  if (code === 'HARD_RULES' || code === 'SOFT_RULES') {
    const items = asItems(raw)
    if (!items.length) {
      return { kind: 'text', text: code === 'SOFT_RULES' ? '没有软约束（可以留空）。' : '这一块没有硬约束。' }
    }
    return {
      kind: 'table',
      cols: ['判定式', '类型', '越界处理', '权重'],
      rows: items.map((r) => [
        String(r.humanReadable ?? '（未写可读描述）'),
        pick(RULE_TYPE_LABEL, String(r.ruleType ?? '')),
        pick(BOUNDARY_POLICY_LABEL, String(r.boundaryPolicy ?? '')),
        num(r.weight, 0)
      ]),
      numCols: [3]
    }
  }

  if (code === 'METRICS') {
    const items = asItems(raw)
    if (!items.length) return { kind: 'text', text: '这一块没有指标。' }

    const totalWeight = items.reduce((sum, m) => sum + (Number(m.weight) || 0), 0)
    const noThreshold = items.filter(
      (m) => m.threshold === null || m.threshold === undefined
    ).length

    return {
      kind: 'table',
      cols: ['指标', '方向', '权重', '判定线'],
      rows: items.map((m) => {
        const unit = String(m.unit ?? '')
        const op = pick(OPERATOR_LABEL, String(m.operator ?? ''))
        const hasThreshold = m.threshold !== null && m.threshold !== undefined
        const boundary = hasThreshold
          ? `${op} ${num(m.threshold, 2)}${unit ? ` ${unit}` : ''}`
          : m.targetValue !== null && m.targetValue !== undefined
            ? `目标 ${num(m.targetValue, 2)}${unit ? ` ${unit}` : ''}`
            : '未设阈值'
        return [
          String(m.metricName ?? m.metricCode ?? '—'),
          pick(BETTER_LABEL, String(m.better ?? '')),
          num(m.weight, 2),
          boundary
        ]
      }),
      numCols: [2],
      note:
        `共 ${items.length} 项，权重合计 ${num(totalWeight, 2)}` +
        (noThreshold ? `；其中 ${noThreshold} 项未设阈值，只参与排序、不参与达标判定` : '')
    }
  }

  if (code === 'QUOTA') {
    const q = raw as Record<string, unknown> | null
    if (!q) return { kind: 'text', text: '这一块没有内容。' }
    const mode = String(q.capacityMode ?? '')
    const capacityValue = q.capacityValue
    const capacity =
      mode === 'RATIO'
        ? `${CAPACITY_MODE_LABEL.RATIO} ${num(Number(capacityValue) * 100, 0)}%`
        : mode === 'ABSOLUTE'
          ? `${CAPACITY_MODE_LABEL.ABSOLUTE} ${num(capacityValue, 0)} 个`
          : '—'
    return {
      kind: 'kv',
      rows: [
        { label: '最少候选数', value: num(q.minCandidates, 0) },
        { label: 'GPU 配额', value: `${num(q.gpuHours, 1)} 小时` },
        { label: '湿实验名额', value: `${num(q.wetLab, 0)} 个` },
        { label: '决策名额', value: capacity }
      ]
    }
  }

  if (code === 'RISKS') {
    const items = asItems(raw)
    if (!items.length) return { kind: 'text', text: '没有风险关注点（可以留空）。' }
    return {
      kind: 'list',
      items: items.map((r) => ({
        title: String(r.description ?? r.riskCode ?? '—'),
        tags: [
          {
            text: String(r.severity ?? '—'),
            tone:
              String(r.severity) === 'HIGH' || String(r.severity) === 'CRITICAL'
                ? 'tag--danger'
                : String(r.severity) === 'MEDIUM'
                  ? 'tag--warning'
                  : 'tag--neutral'
          },
          ...(r.mustVerify ? [{ text: '必须实测', tone: 'tag--info' }] : [])
        ],
        meta: [String(r.dimension ?? '—'), String(r.riskCode ?? '')].filter(Boolean)
      }))
    }
  }

  return { kind: 'text', text: '这一块没有内容。' }
}
</script>

<style lang="scss" scoped>
.board {
  &__head {
    display: flex;
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
    max-width: 720px;
    margin: 0;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__count {
    padding: 4px 12px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    white-space: nowrap;

    @each $tone, $color in (success: $success-color, warning: $warning-color, danger: $error-color, neutral: $text-muted) {
      &--#{$tone} {
        color: $color;
        background: rgba($color, 0.12);
      }
    }
  }

  &__empty {
    padding: $spacing-xl 0;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    gap: $spacing-md;
  }
}

.block {
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;
  overflow: hidden;
  transition: box-shadow $transition-fast, border-color $transition-fast;

  &:hover {
    box-shadow: $shadow-card;
  }

  &--missing {
    border-color: rgba($error-color, 0.45);
  }

  &--warn {
    border-color: rgba($warning-color, 0.45);
  }

  /** 展开时占满整行：表格有 4~5 列，挤在 320px 的格子里读不了 */
  &--open {
    grid-column: 1 / -1;
  }

  &__head {
    display: flex;
    gap: 10px;
    align-items: center;
    width: 100%;
    padding: 12px 14px;
    border: 0;
    background: transparent;
    cursor: pointer;
    text-align: left;
  }

  &__no {
    flex-shrink: 0;
    width: 22px;
    height: 22px;
    border-radius: 6px;
    background: $color-surface-sunken;
    color: $color-text-faint;
    font-size: $font-size-xs;
    font-weight: $font-weight-semibold;
    line-height: 22px;
    text-align: center;
  }

  &__label {
    flex: 1;
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $color-text;
  }

  &__optional {
    margin-left: 6px;
    font-size: 10px;
    font-style: normal;
    color: $color-text-faint;
  }

  &__state {
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;

    @each $tone, $color in (success: $success-color, warning: $warning-color, danger: $error-color, neutral: $text-muted) {
      &--#{$tone} {
        color: $color;
      }
    }
  }

  &__caret {
    color: $color-text-faint;
    font-size: 10px;
  }

  &__message {
    margin: 0;
    padding: 0 14px 12px 46px;
    font-size: $font-size-sm;
    line-height: 1.55;
    color: $color-text-soft;

    &--error {
      color: $error-color;
    }
  }

  &__gaps {
    margin: 0;
    padding: 0 14px 12px 46px;
    list-style: none;
  }

  &__detail {
    border-top: 1px dashed $color-border-soft;
    background: $color-surface-sunken;
    padding: 10px 14px;
    overflow-x: auto;
  }

  &__jump {
    margin: 8px 0 0;
  }
}

/* 展开后的内容：键值对 / 小表格 / 卡片列表 */

.detail {
  &__text {
    margin: 0;
    font-size: $font-size-xs;
    line-height: 1.7;
    color: $color-text-soft;
  }

  &__note {
    margin: 8px 0 0;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;
  }
}

.kv {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px 14px;
  margin: 0;

  &__row {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__value {
    margin: 0;
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $color-text;
  }
}

.mini {
  width: 100%;
  border-collapse: collapse;
  font-size: $font-size-xs;

  &--second {
    margin-top: 10px;
  }

  &__caption {
    padding-bottom: 4px;
    font-size: $font-size-xs;
    color: $color-text-faint;
    text-align: left;
  }

  th {
    padding: 4px 6px;
    border-bottom: 1px solid $color-border;
    font-weight: $font-weight-medium;
    color: $color-text-faint;
    text-align: left;
    white-space: nowrap;
  }

  td {
    padding: 5px 6px;
    border-bottom: 1px dashed $color-border-soft;
    color: $color-text;
    line-height: 1.5;
  }

  tr:last-child td {
    border-bottom: 0;
  }

  &__num {
    font-family: monospace;
    text-align: right;
    white-space: nowrap;
  }
}

.cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: 8px 10px;
    border: 1px solid $color-border-soft;
    border-radius: $radius-control;
    background: $color-surface;
  }

  &__top {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
  }

  &__title {
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $color-text;
  }

  &__meta {
    margin: 4px 0 0;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;
  }
}

.gap {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 4px 0;
  font-size: $font-size-xs;
  line-height: 1.5;

  &__code {
    flex-shrink: 0;
    padding: 1px 6px;
    border-radius: 4px;
    background: $color-surface-sunken;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 10px;
  }

  &__text {
    color: $color-text-soft;
  }

  &--error .gap__code {
    color: $error-color;
    background: rgba($error-color, 0.1);
  }

  &--warning .gap__code {
    color: $warning-color;
    background: rgba($warning-color, 0.12);
  }
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: $color-brand;
  font-size: $font-size-xs;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}
</style>
