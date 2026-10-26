<template>
  <section class="wizard">
    <ol class="wizard__steps">
      <li
        v-for="(step, index) in STEPS"
        :key="step.key"
        class="wizard__step"
        :class="{
          'wizard__step--active': index === current,
          'wizard__step--done': index < current,
          'wizard__step--bad': stepState(step.key) === 'bad'
        }"
      >
        <button type="button" class="wizard__step-btn" @click="current = index">
          <span class="wizard__step-no">{{ index + 1 }}</span>
          <span class="wizard__step-label">{{ step.label }}</span>
          <i
            v-if="stepState(step.key)"
            class="wizard__step-dot"
            :class="`wizard__step-dot--${stepState(step.key)}`"
          />
        </button>
      </li>
    </ol>

    <div class="wizard__panel">
      <!-- ============================ 1 目标与形式 ============================ -->
      <template v-if="STEPS[current].key === 'goal'">
        <h3 class="wizard__title">目标与分子形式</h3>
        <p class="wizard__desc">
          分子形式决定默认评估维度与可用生成策略；作用机制决定策略模板怎么选。
          机制留空只会告警，但生成阶段只能按形式兜底，策略容易不匹配。
        </p>

        <div class="field">
          <label class="field__label" for="moa">作用机制（MOA）</label>
          <select id="moa" v-model="form.moaCode" class="field__control">
            <option value="">— 未指定 —</option>
            <option v-for="item in moaOptions" :key="item.id" :value="item.code">
              {{ item.name }}（{{ item.code }}）
            </option>
          </select>
          <p class="field__hint">留空时基线块1 会给 NO_MOA 告警，但不阻断冻结。</p>
        </div>

        <div class="field">
          <span class="field__label">分子形式</span>
          <p class="field__static">
            {{ modalityLabel }}
            <em v-if="!project?.modalityId" class="field__warn">未选择 —— 基线块3 会缺失</em>
          </p>
          <p class="field__hint">分子形式不在本页修改：它同时决定了指标补齐的候选集与编辑策略默认值。</p>
        </div>

        <div v-if="project?.goalText" class="field">
          <span class="field__label">项目目标</span>
          <p class="field__static field__static--long">{{ project.goalText }}</p>
        </div>
      </template>

      <!-- ============================ 2 靶点与结构 ============================ -->
      <template v-else-if="STEPS[current].key === 'target'">
        <h3 class="wizard__title">靶点与结构</h3>
        <p class="wizard__desc">
          基线要求「恰好一个主靶点」—— 结构准备只认一个对象，多主靶点会让后续步骤无法确定目标。
        </p>

        <table v-if="targets.length" class="table">
          <thead>
            <tr>
              <th>靶点</th>
              <th>角色</th>
              <th>主靶点</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="target in targets" :key="target.targetId">
              <td>{{ target.name || `#${target.targetId}` }}</td>
              <td>{{ roleLabel(target.role) }}</td>
              <td>
                <span v-if="target.isPrimary" class="tag tag--brand">主靶点</span>
                <span v-else class="tag">—</span>
              </td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-else title="还没有绑定靶点" description="基线块1 会因此缺失，无法冻结。" />

        <div v-if="blockMessage('STRUCTURE')" class="notice" :class="noticeTone('STRUCTURE')">
          <strong>结构</strong>
          <span>{{ blockMessage('STRUCTURE') }}</span>
        </div>

        <p class="wizard__tip">
          靶点的增删与结构导入在「靶点库」与项目设置里完成，本向导只做校验与提示 ——
          在这里提供快捷编辑，容易绕过重复绑定与主靶点唯一性的校验。
        </p>
      </template>

      <!-- ============================ 3 约束 ============================ -->
      <template v-else-if="STEPS[current].key === 'constrain'">
        <h3 class="wizard__title">约束</h3>
        <p class="wizard__desc">
          硬约束是生成的淘汰线，软约束是扣分线。基线要求至少一条启用的硬约束 ——
          没有硬约束的生成任务无法过滤越界候选，等于没有闸门。
        </p>

        <div class="notice" :class="noticeTone('HARD_RULES')">
          <strong>硬约束</strong>
          <span>{{ blockMessage('HARD_RULES') }}</span>
        </div>
        <div class="notice" :class="noticeTone('SOFT_RULES')">
          <strong>软约束</strong>
          <span>{{ blockMessage('SOFT_RULES') || '无软约束' }}</span>
        </div>

        <p class="wizard__tip">
          约束的逐条编辑界面还没做：现在只能在建项目时内联一条硬约束，之后要改得走
          <code>POST /projects/{id}/constraints</code> 重建约束集。
          不在本页开快捷开关的原因是约束规则带 params 结构校验，
          绕过校验器写入会让规则在生成阶段被静默忽略 —— 那比不能改更糟。
        </p>
      </template>

      <!-- ============================ 4 指标与风险 ============================ -->
      <template v-else-if="STEPS[current].key === 'metric'">
        <h3 class="wizard__title">评估指标与风险关注点</h3>
        <p class="wizard__desc">
          指标决定评估环节算什么；风险关注点决定决策环节要额外确认什么。
          标「必须验证」的风险点，决策阶段会强制要求实测证据。
        </p>

        <div class="toolbar">
          <button type="button" class="btn" :disabled="busy" @click="emit('init-metrics')">
            按分子形式补齐缺失指标
          </button>
          <span class="toolbar__hint">只补缺失的，不覆盖已手调的权重</span>
        </div>

        <table v-if="metrics.length" class="table table--compact">
          <thead>
            <tr>
              <th>指标</th>
              <th>维度</th>
              <th class="num">权重</th>
              <th>判定式</th>
              <th>越界策略</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="metric in metrics" :key="metric.id">
              <td>
                <span class="cell-main">{{ metric.metricName || metric.metricCode }}</span>
                <code class="cell-code">{{ metric.metricCode }}</code>
              </td>
              <td>{{ metric.dimension || '—' }}</td>
              <td class="num">
                <input
                  v-model.number="metricDraft[metric.id].weight"
                  type="number"
                  step="0.01"
                  min="0"
                  max="1"
                  class="mini-input"
                  @change="emit('update-metric', metric.id, metricDraft[metric.id])"
                />
              </td>
              <td>
                <input
                  v-model="metricDraft[metric.id].boundaryText"
                  type="text"
                  class="mini-input mini-input--wide"
                  placeholder="如 ≤ 10 nM"
                  @change="onBoundaryTextChange(metric.id)"
                />
              </td>
              <td>
                <!-- 三选一必须显式选择：没有默认值，未选就提交 -->
                <select
                  v-model="metricDraft[metric.id].boundaryPolicy"
                  class="mini-input"
                  @change="emit('update-metric', metric.id, metricDraft[metric.id])"
                >
                  <option value="">— 未选择 —</option>
                  <option v-for="opt in BOUNDARY_POLICY_OPTIONS" :key="opt.value" :value="opt.value">
                    {{ opt.label }}
                  </option>
                </select>
              </td>
              <td class="num">
                <button type="button" class="link link--danger" @click="emit('delete-metric', metric.id)">
                  移除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-else title="还没有启用任何指标" description="基线块6 会缺失，无法冻结。" />

        <h4 class="wizard__subtitle">风险关注点</h4>
        <table v-if="risks.length" class="table table--compact">
          <thead>
            <tr>
              <th>风险码</th>
              <th>维度</th>
              <th>说明</th>
              <th class="num">必须验证</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="risk in risks" :key="risk.id">
              <td><code class="cell-code">{{ risk.riskCode }}</code></td>
              <td>{{ risk.dimension || '—' }}</td>
              <td class="cell-desc">{{ risk.description || '—' }}</td>
              <td class="num">
                <input
                  type="checkbox"
                  :checked="risk.mustVerify"
                  @change="emit('toggle-risk', risk.id, !risk.mustVerify)"
                />
              </td>
              <td class="num">
                <button type="button" class="link link--danger" @click="emit('delete-risk', risk.id)">
                  移除
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-else title="没有风险关注点" description="块8 可选，但空着等于决策阶段没有强制验证项。" />

        <form class="inline-form" @submit.prevent="addRisk">
          <input v-model.trim="newRisk.riskCode" class="mini-input" placeholder="风险码，如 PTM_HOTSPOT" required />
          <input v-model.trim="newRisk.description" class="mini-input mini-input--wide" placeholder="说明" />
          <select v-model="newRisk.severity" class="mini-input">
            <option value="LOW">低</option>
            <option value="MEDIUM" selected>中</option>
            <option value="HIGH">高</option>
          </select>
          <label class="check">
            <input v-model="newRisk.mustVerify" type="checkbox" />
            必须验证
          </label>
          <button type="submit" class="btn btn--primary" :disabled="busy || !newRisk.riskCode">
            添加风险点
          </button>
        </form>
      </template>

      <!-- ============================ 5 配额 ============================ -->
      <template v-else>
        <h3 class="wizard__title">生成配额与最小候选数</h3>
        <p class="wizard__desc">
          最少候选数是生成后置闸门的判据：候选数不足时不允许进入编辑。
          GPU 小时是熔断依据，湿实验名额是决策阶段裁剪预算的依据。两者留空会告警但不阻断。
        </p>

        <h4 class="wizard__subtitle">决策推荐名额（档位可调）</h4>
        <p class="wizard__desc wizard__desc--tight">
          决策阶段最终推荐几个候选去测。档位只决定「想推多少」，湿实验名额决定「能推多少」——
          两者取小。档位随基线冻结，中途改动不影响已提交的决策。
        </p>
        <div class="field-row">
          <div class="field">
            <label class="field__label" for="capMode">档位模式</label>
            <select id="capMode" v-model="form.decisionCapacityMode" class="field__control">
              <option value="RATIO">按候选数比例</option>
              <option value="ABSOLUTE">固定个数</option>
            </select>
          </div>
          <div class="field">
            <label class="field__label" for="capValue">
              {{ form.decisionCapacityMode === 'ABSOLUTE' ? '推荐个数' : '比例（0~1）' }}
            </label>
            <input
              id="capValue"
              v-model.number="form.decisionCapacityValue"
              type="number"
              :step="form.decisionCapacityMode === 'ABSOLUTE' ? 1 : 0.05"
              :min="form.decisionCapacityMode === 'ABSOLUTE' ? 1 : 0.05"
              :max="form.decisionCapacityMode === 'ABSOLUTE' ? 1000 : 1"
              class="field__control"
            />
            <p class="field__hint">
              {{
                form.decisionCapacityMode === 'ABSOLUTE'
                  ? '整数，如 8。仍受湿实验名额封顶。'
                  : '小数，0.2 = 20%。写成 20 会被判超范围（它不是百分数）。'
              }}
            </p>
          </div>
          <div class="field">
            <span class="field__label">按当前设置</span>
            <p class="field__static">{{ capacityPreview }}</p>
          </div>
        </div>

        <div class="field-row">
          <div class="field">
            <label class="field__label" for="minCand">最少候选数 <em class="req">必填</em></label>
            <input
              id="minCand"
              v-model.number="form.minCandidates"
              type="number"
              min="1"
              class="field__control"
              placeholder="如 20"
            />
            <p class="field__hint">必须 ≥ 1；留空时基线块7 判缺失。</p>
          </div>
          <div class="field">
            <label class="field__label" for="gpu">GPU 小时配额</label>
            <input
              id="gpu"
              v-model.number="form.quotaGpuHours"
              type="number"
              min="0"
              step="0.5"
              class="field__control"
              placeholder="留空 = 不限（会告警）"
            />
          </div>
          <div class="field">
            <label class="field__label" for="wet">湿实验名额</label>
            <input
              id="wet"
              v-model.number="form.quotaWetLab"
              type="number"
              min="0"
              class="field__control"
              placeholder="留空 = 不限（会告警）"
            />
          </div>
        </div>

        <p v-if="quotaHint" class="field__hint field__hint--block">{{ quotaHint }}</p>
        <button type="button" class="btn" :disabled="busy" @click="emit('save-quota', { ...form })">
          保存配额与机制
        </button>
      </template>
    </div>

    <footer class="wizard__foot">
      <button type="button" class="btn" :disabled="current === 0" @click="current--">上一步</button>
      <span class="wizard__foot-hint">{{ footerHint }}</span>
      <button
        type="button"
        class="btn btn--primary"
        :disabled="current === STEPS.length - 1"
        @click="current++"
      >
        下一步
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import {
  BOUNDARY_POLICY_OPTIONS,
  type BaselineBlockCode,
  type BaselinePreview,
  type LibraryItem,
  type ProjectBrief,
  type ProjectMetric,
  type ProjectMetricUpsert,
  type ProjectQuotaPatch,
  type ProjectRisk,
  type ProjectRiskUpsert
} from '@/api/requirement'

/**
 * 需求分析向导（5 步）。
 *
 * 两个刻意的取舍：
 *
 * 1. **只允许在「本职步骤」写数据**。靶点增删、约束规则、分子形式都有各自的校验器
 *    （主靶点唯一性、params 结构、category 归属），在这里再开一套快捷编辑，
 *    迟早会绕过校验写入一条「用户以为生效、生成时被静默忽略」的规则。
 * 2. **越界策略必须显式选择**。指标行上留「— 未选择 —」而不是默认 STRICT：
 *    默认值会让用户在不知情的情况下接受一条淘汰规则。
 *
 * 本组件只管展示与草稿，所有写操作通过事件交给页面执行（页面持有 API 与错误处理）。
 */
const props = defineProps<{
  project: ProjectBrief | null
  preview: BaselinePreview | null
  metrics: ProjectMetric[]
  risks: ProjectRisk[]
  moaOptions: LibraryItem[]
  modalityLabel: string
  busy?: boolean
}>()

const emit = defineEmits<{
  (e: 'save-quota', patch: ProjectQuotaPatch): void
  (e: 'init-metrics'): void
  (e: 'update-metric', metricId: number, patch: ProjectMetricUpsert): void
  (e: 'delete-metric', metricId: number): void
  (e: 'add-risk', body: ProjectRiskUpsert): void
  (e: 'toggle-risk', riskId: number, mustVerify: boolean): void
  (e: 'delete-risk', riskId: number): void
}>()

const STEPS = [
  { key: 'goal', label: '目标与形式' },
  { key: 'target', label: '靶点与结构' },
  { key: 'constrain', label: '约束' },
  { key: 'metric', label: '指标与风险' },
  { key: 'quota', label: '配额与冻结' }
] as const

const current = ref(0)

/** 供页面在「块缺项 → 去补齐」时直达某一步 */
function setStep(index: number) {
  if (index >= 0 && index < STEPS.length) current.value = index
}

defineExpose({ setStep })

const ROLE_LABEL: Record<string, string> = {
  WILD_TYPE: '野生型',
  MUTANT: '突变体',
  ANTI_TARGET: '反靶点',
  OFF_TARGET: '脱靶'
}

const roleLabel = (role: string) => ROLE_LABEL[role] ?? role

const targets = computed(() => props.project?.targets ?? [])

/* ------------------------------ 表单草稿 ------------------------------ */

const form = reactive<ProjectQuotaPatch>({
  moaCode: '',
  minCandidates: undefined,
  quotaGpuHours: undefined,
  quotaWetLab: undefined,
  decisionCapacityMode: 'RATIO',
  decisionCapacityValue: 0.2
})

/** 项目数据变化时重置草稿；不带条件地 watch 会把用户正在输入的值冲掉 */
watch(
  () => props.project,
  (project) => {
    if (!project) return
    form.moaCode = project.moaCode ?? ''
    form.minCandidates = project.minCandidates ?? undefined
    form.quotaGpuHours = project.quotaGpuHours ?? undefined
    form.quotaWetLab = project.quotaWetLab ?? undefined
    form.decisionCapacityMode = project.decisionCapacityMode || 'RATIO'
    form.decisionCapacityValue = project.decisionCapacityValue ?? 0.2
  },
  { immediate: true }
)

/** 指标行草稿：权重、判定式文本、越界策略 */
interface MetricDraft {
  weight: number
  boundaryText: string
  boundaryPolicy: string
  operator: string
  threshold: number | null
}

const metricDraft = reactive<Record<number, MetricDraft>>({})

watch(
  () => props.metrics,
  (list) => {
    for (const metric of list) {
      if (!metricDraft[metric.id]) {
        metricDraft[metric.id] = {
          weight: Number(metric.weight ?? 0),
          boundaryText: metric.boundaryText ?? '',
          boundaryPolicy: metric.boundaryPolicy ?? '',
          operator: metric.operator ?? '',
          threshold: metric.threshold ?? null
        }
      }
    }
  },
  { immediate: true, deep: true }
)

/**
 * 判定式文本 → operator + threshold。
 * 只解析最朴素的比较式（≤/>=/</>/= 加数字）；解析不出就原样留着不发，
 * 免得把一个用户能看懂、但系统读不懂的文本静默丢掉。
 */
function onBoundaryTextChange(metricId: number) {
  const draft = metricDraft[metricId]
  const text = (draft.boundaryText || '').trim()
  const match = /^(<=|>=|<|>|=|≤|≥)\s*(-?\d+(?:\.\d+)?)/.exec(text)
  if (!match) return
  const symbol = match[1]
  const opMap: Record<string, string> = {
    '<=': 'LTE',
    '≤': 'LTE',
    '>=': 'GTE',
    '≥': 'GTE',
    '<': 'LT',
    '>': 'GT',
    '=': 'EQ'
  }
  draft.operator = opMap[symbol] ?? ''
  draft.threshold = Number(match[2])
  emit('update-metric', metricId, draftToPatch(draft))
}

function draftToPatch(draft: MetricDraft): ProjectMetricUpsert {
  const patch: ProjectMetricUpsert = { weight: draft.weight }
  if (draft.operator) patch.operator = draft.operator
  if (draft.threshold !== null) patch.threshold = draft.threshold
  if (draft.boundaryPolicy) patch.boundaryPolicy = draft.boundaryPolicy
  return patch
}

/* ------------------------------ 新增风险点 ------------------------------ */

const newRisk = reactive<ProjectRiskUpsert>({
  riskCode: '',
  description: '',
  severity: 'MEDIUM',
  mustVerify: true,
  source: 'MANUAL'
})

function addRisk() {
  if (!newRisk.riskCode) return
  emit('add-risk', { ...newRisk })
  newRisk.riskCode = ''
  newRisk.description = ''
}

/* ------------------------------ 块状态辅助 ------------------------------ */

function blockOf(code: BaselineBlockCode) {
  return props.preview?.blocks.find((b) => b.code === code)
}

function blockMessage(code: BaselineBlockCode): string {
  return blockOf(code)?.message ?? ''
}

function noticeTone(code: BaselineBlockCode): string {
  const state = blockOf(code)?.state
  if (state === 'MISSING') return 'notice--danger'
  if (state === 'WARN') return 'notice--warning'
  return 'notice--ok'
}

/** 步骤 → 该步负责的块；用于在步骤条上打「这一步还缺东西」的点 */
const STEP_BLOCKS: Record<string, BaselineBlockCode[]> = {
  goal: ['MODALITY', 'TARGETS'],
  target: ['TARGETS', 'STRUCTURE'],
  constrain: ['HARD_RULES', 'SOFT_RULES'],
  metric: ['METRICS', 'RISKS'],
  quota: ['QUOTA']
}

function stepState(stepKey: string): '' | 'bad' | 'warn' {
  if (!props.preview) return ''
  const codes = STEP_BLOCKS[stepKey] ?? []
  const states = codes.map((c) => blockOf(c)?.state)
  if (states.includes('MISSING')) return 'bad'
  if (states.includes('WARN')) return 'warn'
  return ''
}

const footerHint = computed(() => {
  if (!props.preview) return '尚未预检'
  const errors = props.preview.gaps.filter((g) => g.severity === 'ERROR').length
  const warns = props.preview.gaps.filter((g) => g.severity === 'WARN').length
  if (errors === 0 && warns === 0) return '八块齐全，可以冻结'
  return `${errors} 项阻断 · ${warns} 项告警`
})

const quotaHint = computed(() => {
  const min = form.minCandidates
  if (min === undefined || min === null || Number.isNaN(min)) {
    return '最少候选数留空时，基线块7 会判「缺失」并阻断冻结。'
  }
  if (min < 1) return '最少候选数必须 ≥ 1，否则生成后置闸门无从判定。'
  if (form.quotaGpuHours !== undefined && form.quotaGpuHours !== null && form.quotaGpuHours <= 0) {
    return 'GPU 小时必须 > 0；不限额请留空。'
  }
  const mode = form.decisionCapacityMode
  const value = form.decisionCapacityValue
  if (value === undefined || value === null || Number.isNaN(value) || value <= 0) {
    return '决策名额参数必须 > 0，否则冻结会被拒。'
  }
  if (mode === 'RATIO' && value > 1) {
    return '按比例模式的参数是 0~1 的小数（0.2 = 20%）；想固定个数请把档位改成「固定个数」。'
  }
  if (mode === 'ABSOLUTE' && !Number.isInteger(value)) {
    return '固定个数模式必须是整数。'
  }
  return ''
})

/** 把档位换算成人能读的一句话，顺便暴露「比例写成 20」这类典型错误 */
const capacityPreview = computed(() => {
  const value = form.decisionCapacityValue
  if (value === undefined || value === null || Number.isNaN(value)) return '—'
  const base = form.minCandidates ?? 0
  let wanted: number
  if (form.decisionCapacityMode === 'ABSOLUTE') {
    wanted = Math.floor(value)
  } else {
    if (value > 1) return '比例须在 0~1 之间，当前写法会被判超范围'
    if (base < 1) return `候选数 ${value * 100}%，但还没填最少候选数`
    wanted = Math.max(1, Math.ceil(base * value))
  }
  const cap = form.quotaWetLab
  if (cap === undefined || cap === null) {
    return `想推 ${wanted} 个（湿实验名额未设，无法封顶）`
  }
  return `想推 ${wanted} 个，湿实验名额 ${cap} → 实际推荐 ${Math.min(wanted, cap)} 个`
})
</script>

<style lang="scss" scoped>
.wizard {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;

  &__steps {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__step {
    flex: 1 1 0;
    min-width: 130px;
    border-radius: $radius-control;
    background: $color-surface-sunken;

    &--active {
      background: $color-brand-soft;
    }

    &--active .wizard__step-label {
      color: $color-brand-strong;
      font-weight: $font-weight-semibold;
    }

    &--done .wizard__step-no {
      color: $success-color;
      background: rgba($success-color, 0.14);
    }

    &--bad .wizard__step-no {
      color: $error-color;
      background: rgba($error-color, 0.14);
    }
  }

  &__step-btn {
    display: flex;
    gap: 8px;
    align-items: center;
    width: 100%;
    padding: 9px 11px;
    border: 0;
    background: none;
    cursor: pointer;
    
  }

  &__step-no {
    flex-shrink: 0;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: $color-surface;
    color: $color-text-faint;
    font-size: 11px;
    font-weight: $font-weight-semibold;
    line-height: 20px;
    text-align: center;
  }

  &__step-label {
    flex: 1;
    font-size: $font-size-xs;
    color: $color-text-soft;
    text-align: left;
  }

  &__step-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;

    &--bad {
      background: $error-color;
    }

    &--warn {
      background: $warning-color;
    }
  }

  &__panel {
    padding: $spacing-lg;
    border: 1px solid $color-border;
    border-radius: $radius-card;
    background: $color-surface;
  }

  &__title {
    margin: 0 0 6px;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__subtitle {
    margin: $spacing-lg 0 8px;
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__desc {
    margin: 0 0 $spacing-lg;
    max-width: 760px;
    font-size: $font-size-sm;
    line-height: 1.65;
    color: $color-text-faint;

    &--tight {
      margin-bottom: $spacing-md;
    }
  }

  &__tip {
    margin: $spacing-md 0 0;
    padding: 10px 12px;
    border-left: 3px solid $color-border;
    border-radius: 0 $radius-control $radius-control 0;
    background: $color-surface-sunken;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__foot {
    display: flex;
    gap: $spacing-md;
    align-items: center;
    justify-content: space-between;
  }

  &__foot-hint {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.field {
  margin-bottom: $spacing-md;

  &__label {
    display: block;
    margin-bottom: 5px;
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    color: $color-text-soft;
  }

  &__control {
    width: 100%;
    max-width: 420px;
    height: 34px;
    padding: 0 10px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    background: $color-surface;
    font-size: $font-size-sm;
    color: $color-text;

    &:focus {
      outline: none;
      border-color: $color-brand;
      box-shadow: $shadow-focus;
    }
  }

  &__static {
    margin: 0;
    font-size: $font-size-sm;
    color: $color-text;

    &--long {
      padding: 8px 10px;
      border-radius: $radius-control;
      background: $color-surface-sunken;
      line-height: 1.6;
      color: $color-text-soft;
    }
  }

  &__warn {
    margin-left: 8px;
    font-size: $font-size-xs;
    font-style: normal;
    color: $error-color;
  }

  &__hint {
    margin: 5px 0 0;
    font-size: $font-size-xs;
    line-height: 1.5;
    color: $color-text-faint;

    &--block {
      margin-bottom: $spacing-md;
    }
  }

  &-row {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: $spacing-md;
    margin-bottom: $spacing-md;
  }
}

.req {
  margin-left: 4px;
  font-style: normal;
  color: $error-color;
}

.table {
  width: 100%;
  margin-bottom: $spacing-md;
  border-collapse: collapse;
  font-size: $font-size-sm;

  th,
  td {
    padding: 8px 10px;
    border-bottom: 1px solid $color-border-soft;
    text-align: left;
    vertical-align: middle;
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

  &--compact td {
    padding: 6px 10px;
  }
}

.cell-main {
  display: block;
  color: $color-text;
}

.cell-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10px;
  color: $color-text-faint;
}

.cell-desc {
  max-width: 320px;
  color: $color-text-soft;
}

.mini-input {
  height: 28px;
  padding: 0 8px;
  border: 1px solid $color-border;
  border-radius: 6px;
  background: $color-surface;
  font-size: $font-size-xs;
  color: $color-text;

  &--wide {
    width: 150px;
  }

  &:focus {
    outline: none;
    border-color: $color-brand;
  }
}

.btn {
  height: 32px;
  padding: 0 14px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;
  font-size: $font-size-sm;
  color: $color-text;
  cursor: pointer;
  transition: background $transition-fast, border-color $transition-fast;

  &:hover:not(:disabled) {
    background: $color-surface-alt;
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }

  &--primary {
    border-color: $color-brand;
    background: $color-brand;
    color: #fff;

    &:hover:not(:disabled) {
      background: $color-brand-strong;
    }
  }
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: $spacing-md;

  &__hint {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.notice {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 8px;
  padding: 10px 12px;
  border-radius: $radius-control;
  font-size: $font-size-xs;
  line-height: 1.6;

  strong {
    flex-shrink: 0;
    font-weight: $font-weight-semibold;
  }

  &--ok {
    background: rgba($success-color, 0.08);
    color: $text-secondary;
  }

  &--warning {
    background: rgba($warning-color, 0.1);
    color: #92400e;
  }

  &--danger {
    background: rgba($error-color, 0.09);
    color: #b91c1c;
  }
}

.tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: $radius-pill;
  background: $color-surface-sunken;
  font-size: $font-size-xs;
  color: $color-text-faint;

  &--brand {
    background: $color-brand-soft;
    color: $color-brand-strong;
  }
}

.link {
  padding: 0;
  border: 0;
  background: none;
  font-size: $font-size-xs;
  color: $color-brand;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }

  &--danger {
    color: $error-color;
  }
}

.inline-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: $spacing-sm;
}

.check {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  font-size: $font-size-xs;
  color: $color-text-soft;
}
</style>
