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
          <SpSelect
            id="moa"
            v-model="form.moaCode"
            block
            :options="moaSelectOptions"
          />
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
              <th />
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
              <td class="num">
                <button
                  type="button"
                  class="link link--danger"
                  :disabled="busy"
                  @click="emit('unbind-target', target.targetId)"
                >
                  解绑
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-else title="还没有绑定靶点" description="基线块1 会因此缺失，无法冻结。" />

        <form class="inline-form" @submit.prevent="submitBindTarget">
          <SpSelect
            v-model.number="newTarget.targetId"
            size="sm"
            :width="180"
            :options="bindableTargetOptions"
            placeholder="选择靶点库里的靶点…"
          />
          <SpSelect
            v-model="newTarget.role"
            size="sm"
            :width="108"
            :options="ROLE_OPTIONS"
          />
          <label class="check">
            <input v-model="newTarget.isPrimary" type="checkbox" :disabled="hasPrimary" />
            设为主靶点
          </label>
          <button type="submit" class="btn" :disabled="busy || !newTarget.targetId">
            绑定
          </button>
          <span v-if="hasPrimary" class="toolbar__hint">
            已有主靶点；要换主靶点请先解绑原来那个
          </span>
          <span v-else class="toolbar__hint">第一个靶点建议勾「设为主靶点」</span>
        </form>

        <div v-if="blockMessage('STRUCTURE')" class="notice" :class="noticeTone('STRUCTURE')">
          <strong>结构</strong>
          <span>{{ blockMessage('STRUCTURE') }}</span>
        </div>

        <p class="wizard__tip">
          结构由绑定靶点自带的 PDB 结构提供，本页不导入结构 ——
          分子精修台（<code>/design</code>）里画的结构是候选，不是靶点结构。
          靶点库里还没有需要的靶点时，先去「靶点库」登记。
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

        <ConstraintEditor
          :current="constraints ?? null"
          :busy="busy"
          @save="(rules) => emit('save-constraints', rules)"
        />
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
                <SpSelect
                  v-model="metricDraft[metric.id].boundaryPolicy"
                  size="sm"
                  :width="118"
                  :options="boundaryPolicyOptions"
                  @change="emit('update-metric', metric.id, metricDraft[metric.id])"
                />
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
        <p class="wizard__desc wizard__desc--tight">
          标「必须验证」的风险点会在决策阶段变成硬性要求：该风险没有实测证据的候选不得入选。
          所以别一股脑全勾 —— 全勾上等于什么都选不出来。
        </p>
        <table v-if="risks.length" class="table table--compact">
          <thead>
            <tr>
              <th>风险点</th>
              <th>维度</th>
              <th>说明</th>
              <th class="num">必须验证</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="risk in risks" :key="risk.id">
              <td>
                <span class="cell-main">{{ riskPresetOf(risk.riskCode)?.label ?? risk.riskCode }}</span>
                <code class="cell-code">{{ risk.riskCode }}</code>
              </td>
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
        <EmptyState v-else title="还没有风险关注点" description="块8 可选，但空着等于决策阶段没有强制验证项。" />

        <!-- 预设优先：把「自己编机器码」换成「挑一条现成的」 -->
        <div class="riskpick">
          <div class="riskpick__row">
            <SpSelect
              v-model="selectedRiskKey"
              size="sm"
              :min-width="240"
              :options="addableRiskOptions"
              placeholder="从常用风险点里挑一条…"
            />
            <button
              type="button"
              class="btn"
              :disabled="busy || !selectedRiskKey"
              @click="addSelectedRisk"
            >
              ＋ 添加
            </button>
            <button
              type="button"
              class="btn"
              :disabled="busy || addableRisks.length === 0"
              @click="addStarterRisks"
            >
              添加 3 条常见风险
            </button>
          </div>
          <p v-if="riskPreview" class="riskpick__preview">
            <strong>{{ riskPreview.label }}</strong>
            <span>{{ riskPreview.description }}</span>
            <span class="riskpick__meta">
              维度 {{ riskPreview.dimension }} · 严重度 {{ SEVERITY_SHORT[riskPreview.severity] }} ·
              {{ riskPreview.mustVerify ? '必须实测验证' : '不强制实测' }}
            </span>
            <span class="riskpick__why">{{ riskPreview.why }}</span>
          </p>
        </div>

        <button type="button" class="link" @click="customRisk.open = !customRisk.open">
          {{ customRisk.open ? '收起自定义风险点' : '＋ 自定义风险点（少见场景）' }}
        </button>

        <form v-if="customRisk.open" class="inline-form" @submit.prevent="submitCustomRisk">
          <input
            v-model.trim="customRisk.riskCode"
            class="mini-input"
            placeholder="风险码，如 FTO_RISK"
            required
          />
          <input
            v-model.trim="customRisk.description"
            class="mini-input mini-input--wide"
            placeholder="说明"
          />
          <SpSelect
            v-model="customRisk.severity"
            size="sm"
            :width="108"
            :options="SEVERITY_OPTIONS"
          />
          <label class="check">
            <input v-model="customRisk.mustVerify" type="checkbox" />
            必须验证
          </label>
          <button type="submit" class="btn btn--primary" :disabled="busy || !customRisk.riskCode">
            添加风险点
          </button>
        </form>
      </template>

      <!-- ============================ 5 配额 ============================ -->
      <template v-else>
      <h3 class="wizard__title">生成配额与决策名额</h3>
        <p class="wizard__desc">
        这两组数决定「生成多少」和「最后推几个去测」。生成配额是决策名额的计算基数 ——
        最少候选数留空时，决策名额只能写死个数、算不出比例。
        </p>

      <h4 class="wizard__subtitle">生成配额</h4>
      <div class="field-row">
        <div class="field">
          <label class="field__label" for="minCand">最少候选数 <em class="req">必填</em></label>
          <input
            id="minCand"
            v-model.number="form.minCandidates"
            type="number"
            step="1"
            min="1"
            inputmode="numeric"
            class="field__control"
            placeholder="20"
            />
          <p class="field__hint">
            生成后置闸门：候选数不足时不允许进入编辑。默认 <strong>20</strong>，按项目规模改。
          </p>
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
            step="1"
            min="0"
            inputmode="numeric"
            class="field__control"
            placeholder="留空 = 不限（会告警）"
          />
          <p class="field__hint">决策名额的封顶值：推荐个数不会超过它。</p>
        </div>
      </div>

      <h4 class="wizard__subtitle">决策推荐名额（档位可调）</h4>
      <p class="wizard__desc wizard__desc--tight">
        决策阶段最终推荐几个候选去测。档位只决定「想推多少」，湿实验名额决定「能推多少」——
        两者取小。档位随基线冻结，中途改动不影响已提交的决策。
      </p>
      <div class="field-row">
        <div class="field">
          <label class="field__label" for="capMode">档位模式</label>
          <SpSelect
            id="capMode"
            v-model="form.decisionCapacityMode"
            block
            :options="CAPACITY_MODE_OPTIONS"
          />
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

      <p v-if="quotaHint" class="field__hint field__hint--block">{{ quotaHint }}</p>
      <button
        type="button"
        class="btn"
        :class="{ 'btn--primary': quotaDirty }"
        :disabled="busy"
        @click="saveQuota"
      >
        {{ quotaDirty ? '保存配额与机制（有未保存改动）' : '保存配额与机制' }}
      </button>

      <!-- 冻结前检查：把「还差什么」摆在最后一步里，而不是让人滚回页面顶端看清单 -->
      <div class="precheck" :class="{ 'precheck--ready': canFreeze && !quotaDirty }">
        <p class="precheck__title">
          {{
            quotaDirty
              ? '配额改动还没保存，后端仍按旧值预检'
              : canFreeze
                ? '八块已齐，可以直接冻结基线'
                : `冻结前还差 ${blockingGaps.length} 项阻断`
          }}
        </p>
        <div v-if="quotaDirty" class="precheck__actions">
          <button type="button" class="precheck__go" :disabled="busy" @click="saveQuota">
            保存配额 →
          </button>
          <span class="precheck__note">
            最少候选数等改动要先落库，预检才会把它们算进基线块 7。
          </span>
        </div>
        <ul v-else-if="!canFreeze" class="precheck__list">
          <li v-for="gap in presetGaps" :key="gap.code" class="precheck__item">
            <span
              class="precheck__badge"
              :class="gap.severity === 'ERROR' ? 'precheck__badge--err' : 'precheck__badge--warn'"
            >
              {{ gap.severity === 'ERROR' ? '阻断' : '告警' }}
            </span>
            <span class="precheck__block">{{ gapLabel(gap.block) }}</span>
            <span class="precheck__msg">{{ gap.message }}</span>
            <button type="button" class="precheck__go" @click="jumpToGap(gap.block)">
              去补 →
            </button>
          </li>
        </ul>
        <p v-else class="precheck__note">
          冻结后生成任务只读这份快照；之后改约束不会影响已提交的任务。
        </p>
      </div>
      </template>
    </div>

    <footer class="wizard__foot">
      <button type="button" class="btn" :disabled="current === 0" @click="current--">上一步</button>
      <span class="wizard__foot-hint">{{ footerHint }}</span>
      <!-- 最后一步不能只是把「下一步」灰掉：那样用户在页面上找不到任何可点的收尾动作 -->
      <button
        v-if="current < STEPS.length - 1"
        type="button"
        class="btn btn--primary"
        @click="current++"
      >
        下一步
      </button>
      <button
        v-else
        type="button"
        class="btn btn--primary"
        :disabled="busy || !canFreeze || quotaDirty"
        :title="quotaDirty ? '先保存配额，否则冻结的是旧值' : freezeTitle"
        @click="emit('freeze')"
      >
        冻结基线
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SpSelect from '@/components/ui/SpSelect.vue'
import ConstraintEditor from '@/components/design/ConstraintEditor.vue'
import { RISK_PRESETS, riskPresetOf } from '@/utils/baselinePresets'
import {
  BOUNDARY_POLICY_OPTIONS,
  type BaselineBlockCode,
  type BaselinePreview,
  type ConstraintRule,
  type ConstraintSet,
  type LibraryItem,
  type ProjectBrief,
  type ProjectMetric,
  type ProjectMetricUpsert,
  type ProjectQuotaPatch,
  type ProjectRisk,
  type ProjectRiskUpsert,
  type ProjectTargetBind,
  type TargetBrief
} from '@/api/requirement'

/**
 * 需求分析向导（5 步）。
 *
 * 设计口径：
 *
 * 1. **每一步都用该领域自己的接口写数据**，不在前端拼一套「差不多的」请求。
 *    靶点绑定走 `POST /projects/{id}/targets`（校验主靶点唯一性），约束走
 *    `POST /constraints` + 绑定激活（校验 `params` 结构与 `humanReadable`）。
 *    这些校验器是「规则真的会生效」的唯一保证 —— 绕过它们写入，用户会得到
 *    一条看起来生效、生成时被静默忽略的规则，那比不能改更糟。
 * 2. **越界策略必须显式选择**。指标行上留「— 未选择 —」而不是默认 STRICT：
 *    默认值会让用户在不知情的情况下接受一条淘汰规则。
 * 3. **可编辑项都给推荐值**。基线是校验式的（「至少一条硬约束」），
 *    但没有现成可选项时用户根本填不出来；推荐值集中在 `utils/baselinePresets`，
 *    每条都带「为什么推荐」，而不是一个不可质疑的默认。
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
  /** 靶点库可选靶点（绑定用） */
  targetOptions?: TargetBrief[]
  /** 当前生效约束集；null 表示还没绑定 */
  constraints?: ConstraintSet | null
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
  (e: 'bind-target', body: ProjectTargetBind): void
  (e: 'unbind-target', targetId: number): void
  (e: 'save-constraints', rules: ConstraintRule[]): void
  (e: 'freeze'): void
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

/** 最少候选数的默认值。基线块7 判缺失时它是阻断项，所以给一个可见的默认值而不是留空 */
const DEFAULT_MIN_CANDIDATES = 20

/* ------------------------------ 表单草稿 ------------------------------ */

const form = reactive<ProjectQuotaPatch>({
  moaCode: '',
  minCandidates: DEFAULT_MIN_CANDIDATES,
  quotaGpuHours: undefined,
  quotaWetLab: undefined,
  decisionCapacityMode: 'RATIO',
  decisionCapacityValue: 0.2
})

/** 项目数据变化时重置草稿；不带条件地 watch 会把用户正在输入的值冲掉 */
let syncedQuotaSignature = ''
watch(
  () => props.project,
  (project) => {
    if (!project) return
    // 页面每次写操作后都会重拉项目，若服务端这些值没变就不该覆盖草稿
    const signature = [
      project.id,
      project.moaCode ?? '',
      project.minCandidates ?? '',
      project.quotaGpuHours ?? '',
      project.quotaWetLab ?? '',
      project.decisionCapacityMode ?? '',
      project.decisionCapacityValue ?? ''
    ].join('|')
    if (signature === syncedQuotaSignature) return
    syncedQuotaSignature = signature
    form.moaCode = project.moaCode ?? ''
    // 留空时回落到推荐默认值：空着会是阻断项，而空白输入框没有任何提示
    form.minCandidates = project.minCandidates ?? DEFAULT_MIN_CANDIDATES
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

/* ------------------------------ 靶点绑定 ------------------------------ */

const ROLE_OPTIONS = [
  { value: 'WILD_TYPE', label: '野生型' },
  { value: 'MUTANT', label: '突变体' },
  { value: 'ANTI_TARGET', label: '反靶点' },
  { value: 'OFF_TARGET', label: '脱靶' }
]

const SEVERITY_OPTIONS = [
  { value: 'LOW', label: '低' },
  { value: 'MEDIUM', label: '中' },
  { value: 'HIGH', label: '高' }
]

const CAPACITY_MODE_OPTIONS = [
  { value: 'RATIO', label: '按候选数比例' },
  { value: 'ABSOLUTE', label: '固定个数' }
]

/** 越界策略：首项是显式的「未选择」，不允许有默认值 */
const boundaryPolicyOptions = [
  { value: '', label: '— 未选择 —' },
  ...BOUNDARY_POLICY_OPTIONS.map((o) => ({ value: o.value, label: o.label }))
]

const hasPrimary = computed(() => targets.value.some((t) => t.isPrimary))

const newTarget = reactive<ProjectTargetBind>({
  targetId: 0,
  role: 'WILD_TYPE',
  isPrimary: false
})

/** 已经绑过的靶点不再出现在下拉里（后端会 409 重复绑定，不如直接不给选） */
const bindableTargets = computed(() => {
  const bound = new Set(targets.value.map((t) => t.targetId))
  return (props.targetOptions ?? []).filter((t) => !bound.has(t.id))
})

const bindableTargetOptions = computed(() =>
  bindableTargets.value.map((t) => ({
    value: t.id,
    label: t.name,
    hint: t.geneName ?? ''
  }))
)

/** MOA 下拉：第一项是显式的「未指定」，与原来的空 value 语义一致 */
const moaSelectOptions = computed(() => [
  { value: '', label: '— 未指定 —' },
  ...props.moaOptions.map((m) => ({ value: m.code, label: `${m.name}（${m.code}）` }))
])

function submitBindTarget() {
  if (!newTarget.targetId) return
  emit('bind-target', {
    targetId: newTarget.targetId,
    role: newTarget.role,
    isPrimary: newTarget.isPrimary || !hasPrimary.value
  })
  newTarget.targetId = 0
  newTarget.isPrimary = false
}

/* ------------------------------ 风险点（预设优先） ------------------------------ */

const SEVERITY_SHORT: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '严重'
}

const selectedRiskKey = ref('')

const addableRisks = computed(() => {
  const used = new Set(props.risks.map((r) => r.riskCode))
  return RISK_PRESETS.filter((p) => !used.has(p.riskCode))
})

const addableRiskOptions = computed(() =>
  addableRisks.value.map((p) => ({
    value: p.key,
    label: p.label,
    hint: `${p.dimension} · ${SEVERITY_SHORT[p.severity]}`
  }))
)

const riskPreview = computed(
  () => addableRisks.value.find((p) => p.key === selectedRiskKey.value) ?? null
)

function addSelectedRisk() {
  const preset = riskPreview.value
  if (!preset) return
  emit('add-risk', {
    riskCode: preset.riskCode,
    dimension: preset.dimension,
    description: preset.description,
    severity: preset.severity,
    mustVerify: preset.mustVerify,
    source: 'MANUAL'
  })
  selectedRiskKey.value = ''
}

/** 「先来三条最常见的」：安全 + 稳定性 + 可成药性各一条，覆盖面最广 */
const STARTER_RISK_KEYS = ['hepatotox', 'plasma', 'aggregation']

function addStarterRisks() {
  const picks = STARTER_RISK_KEYS.map((k) => addableRisks.value.find((p) => p.key === k)).filter(
    (p): p is (typeof RISK_PRESETS)[number] => !!p
  )
  if (picks.length === 0) return
  for (const preset of picks) {
    emit('add-risk', {
      riskCode: preset.riskCode,
      dimension: preset.dimension,
      description: preset.description,
      severity: preset.severity,
      mustVerify: preset.mustVerify,
      source: 'MANUAL'
    })
  }
}

const customRisk = reactive({
  open: false,
  riskCode: '',
  description: '',
  severity: 'MEDIUM',
  mustVerify: false
})

function submitCustomRisk() {
  if (!customRisk.riskCode) return
  emit('add-risk', {
    riskCode: customRisk.riskCode,
    description: customRisk.description,
    severity: customRisk.severity,
    mustVerify: customRisk.mustVerify,
    source: 'MANUAL'
  })
  customRisk.riskCode = ''
  customRisk.description = ''
  customRisk.open = false
}

/**
 * 保存配额。
 *
 * 两个必须归一化的点：
 *  - 数字框被清空时 `v-model.number` 会留下空串，直接提交会让后端反序列化 Integer 失败（400）
 *  - 空的对象键会被 JSON.stringify 丢掉，等于「保持原值」，这正是想要的语义
 */
function saveQuota() {
  const clean = (v: unknown): number | undefined => {
    if (v === '' || v === null || v === undefined) return undefined
    const n = Number(v)
    return Number.isFinite(n) ? n : undefined
  }
  const payload: ProjectQuotaPatch = {
    minCandidates: clean(form.minCandidates),
    quotaGpuHours: clean(form.quotaGpuHours),
    quotaWetLab: clean(form.quotaWetLab),
    decisionCapacityMode: form.decisionCapacityMode,
    decisionCapacityValue: clean(form.decisionCapacityValue)
  }
  // 作用机制留空表示「不改」，传空串会走一遍 MOA 校验，没必要
  if (form.moaCode) payload.moaCode = form.moaCode
  emit('save-quota', payload)
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

/** 能否冻结：判定权在后端（preview.ready），这里只做展示 */
const canFreeze = computed(() => props.preview?.ready === true)

/**
 * 配额是否有未保存的改动。
 *
 * 这条必须可见：默认的「最少候选数 20」只活在草稿里，没保存时后端仍判 QUOTA 缺失，
 * 于是预检一直说「最少候选数必须 ≥ 1」而冻结按钮一直是灰的 ——
 * 用户看着框里明明有 20，会觉得这个字段根本没用。
 */
const quotaDirty = computed(() => {
  const p = props.project
  if (!p) return false
  const num = (v: unknown): number | null => {
    if (v === '' || v === null || v === undefined) return null
    const n = Number(v)
    return Number.isFinite(n) ? n : null
  }
  return (
    num(form.minCandidates) !== num(p.minCandidates) ||
    num(form.quotaGpuHours) !== num(p.quotaGpuHours) ||
    num(form.quotaWetLab) !== num(p.quotaWetLab) ||
    num(form.decisionCapacityValue) !== num(p.decisionCapacityValue) ||
    (form.decisionCapacityMode ?? '') !== (p.decisionCapacityMode ?? '') ||
    (form.moaCode ?? '') !== (p.moaCode ?? '')
  )
})

const freezeTitle = computed(() =>
  canFreeze.value ? '冻结为不可变快照' : '还有阻断项，先把「去补」列出的块补齐'
)

/** 阻断项排前面，同一块只留第一条（后面是同一原因的重复） */
const blockingGaps = computed(() =>
  (props.preview?.gaps ?? []).filter((g) => g.severity === 'ERROR')
)

const presetGaps = computed(() => {
  const seen = new Set<string>()
  const ordered = [...(props.preview?.gaps ?? [])].sort((a, b) =>
    a.severity === b.severity ? 0 : a.severity === 'ERROR' ? -1 : 1
  )
  const out: typeof ordered = []
  for (const gap of ordered) {
    if (seen.has(gap.block)) continue
    seen.add(gap.block)
    out.push(gap)
  }
  return out
})

/** 块 → 负责它的步骤下标，用于「去补」直达 */
const BLOCK_STEP: Record<string, number> = {
  MODALITY: 0,
  TARGETS: 0,
  STRUCTURE: 1,
  HARD_RULES: 2,
  SOFT_RULES: 2,
  METRICS: 3,
  RISKS: 3,
  QUOTA: 4
}

function jumpToGap(block: string) {
  const index = BLOCK_STEP[block]
  if (index !== undefined) current.value = index
}

/** 块的中文名取后端给的那份，避免前端再维护一张表 */
function gapLabel(block: string): string {
  return props.preview?.blocks.find((b) => b.code === block)?.label ?? block
}

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

// 冻结前检查：最后一步自己回答「还差什么、点哪补」
.precheck {
  margin-top: $spacing-md;
  padding: 14px 16px;
  border: 1px solid rgba($warning-color, 0.3);
  border-radius: $radius-card;
  background: rgba($warning-color, 0.07);

  &--ready {
    border-color: rgba($success-color, 0.32);
    background: rgba($success-color, 0.07);
  }

  &__title {
    margin: 0 0 10px;
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    font-size: $font-size-sm;
  }

  &__badge {
    flex: 0 0 auto;
    padding: 1px 7px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;

    &--err {
      background: rgba($error-color, 0.12);
      color: $error-color;
    }

    &--warn {
      background: rgba($warning-color, 0.16);
      color: mix(#000, $warning-color, 18%);
    }
  }

  &__block {
    flex: 0 0 auto;
    min-width: 76px;
    font-weight: $font-weight-medium;
    color: $color-text;
  }

  &__msg {
    flex: 1 1 200px;
    color: $color-text-soft;
  }

  &__go {
    flex: 0 0 auto;
    padding: 3px 10px;
    border: 1px solid rgba($warning-color, 0.45);
    border-radius: $radius-control;
    background: $color-surface;
    color: mix(#000, $warning-color, 18%);
    font-family: inherit;
    font-size: $font-size-xs;
    cursor: pointer;

    &:hover {
      background: rgba($warning-color, 0.12);
    }
  }

  &__note {
    margin: 0;
    font-size: $font-size-sm;
    color: $color-text-soft;
  }
}

// 风险点预设选择：选中即预览「会写入什么」，避免盲选
.riskpick {
  margin-top: $spacing-sm;
  padding: 12px;
  border: 1px solid $color-border;
  border-radius: 10px;
  background: $color-surface-sunken;

  &__row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
  }

  &__preview {
    display: flex;
    flex-direction: column;
    gap: 3px;
    margin: 10px 0 0;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-soft;

    strong {
      font-size: $font-size-sm;
      color: $color-text;
    }
  }

  &__meta,
  &__why {
    color: $color-text-faint;
  }
}

.check {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  font-size: $font-size-xs;
  color: $color-text-soft;
}
</style>
