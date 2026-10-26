<template>
  <div class="gn">
  <Sidebar />
  <main class="gn__main">
    <div class="page">
    <PageHeader :title="projectName" subtitle="需求分析 → 生成 → 编辑：本页负责把冻结基线转成可筛选、可追溯的候选池">
      <template #actions>
        <button type="button" class="btn" :disabled="loading" @click="loadAll">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
      </template>
    </PageHeader>

    <FlowSteps
      :project-id="projectId"
      current="generation"
      :candidate-id="firstCandidateId"
    />

    <p v-if="error" class="banner banner--danger">{{ error }}</p>
    <p v-if="notice" class="banner banner--ok">{{ notice }}</p>

    <!-- 状态条：能不能生成、为什么不能 -->
    <section class="status" :class="`status--${gateTone}`">
      <div class="status__main">
        <span class="status__badge">{{ gateLabel }}</span>
        <span class="status__text">{{ gateText }}</span>
      </div>
      <div class="status__meta">
        <span>{{ runs.length }} 个批次</span>
        <span v-if="activeRun">当前：{{ activeRun.runNo }}</span>
        <button
          v-if="hasBaseline && !hasRound"
          type="button"
          class="btn btn--sm"
          :disabled="openingRound"
          @click="openRound"
        >
          {{ openingRound ? '开启中…' : '开启第 1 轮' }}
        </button>
      </div>
    </section>

    <nav class="tabs">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        type="button"
        class="tabs__btn"
        :class="{ 'tabs__btn--active': activeTab === tab.key }"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </nav>

    <!-- 发起 -->
    <section v-if="activeTab === 'new'" class="panel">
      <GenerationConfig
        :has-baseline="hasBaseline"
        :has-round="hasRound"
        :submitting="submitting"
        @submit="submit"
      />
    </section>

    <!-- 批次列表 -->
    <section v-else-if="activeTab === 'runs'" class="panel">
      <div v-if="!runs.length" class="panel__empty">
        <EmptyState title="还没有生成批次" description="到「发起生成」提交第一批候选。" />
      </div>
      <table v-else class="runs">
        <thead>
          <tr>
            <th>批次</th>
            <th>来源</th>
            <th>状态</th>
            <th class="num">生成</th>
            <th class="num">最终</th>
            <th>结论</th>
            <th>时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id">
            <td>
              <code>{{ run.runNo }}</code>
              <span v-if="run.degraded" class="badge badge--warn" :title="run.degradedReason || ''">降级</span>
            </td>
            <td>
              <span class="strategy" :title="strategyMeta(run.strategy).hint">
                {{ strategyMeta(run.strategy).label }}
              </span>
            </td>
            <td>
              <span class="tag" :class="`tag--${statusMeta(run.status).tone}`">
                {{ statusMeta(run.status).label }}
              </span>
            </td>
            <td class="num">{{ run.generatedCount ?? '—' }}</td>
            <td class="num">{{ run.finalCount ?? '—' }}</td>
            <td>
              <span
                v-if="run.diagnosis?.verdict"
                class="tag"
                :class="`tag--${verdictMeta(run.diagnosis.verdict).tone}`"
              >
                {{ verdictMeta(run.diagnosis.verdict).label }}
              </span>
              <span v-else class="muted">—</span>
            </td>
            <td class="muted">{{ fmtTime(run.createdAt) }}</td>
            <td>
              <button type="button" class="btn btn--sm" @click="openRun(run.id)">查看</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 详情 -->
    <template v-else-if="activeRun">
      <section class="panel">
        <header class="panel__head">
          <div>
            <h3 class="panel__title">
              {{ activeRun.runNo }}
              <span v-if="activeRun.degraded" class="badge badge--warn">{{ activeRun.degradedReason || '降级产出' }}</span>
            </h3>
            <p class="panel__meta">
              {{ strategyMeta(activeRun.strategy).label }} ·
              采样 {{ activeRun.nSamples ?? '—' }} / 批 {{ activeRun.batchSize ?? '—' }} ·
              种子 {{ activeRun.seed ?? '—' }} ·
              plan <code>{{ (activeRun.planHash || '').slice(0, 10) || '—' }}</code>
            </p>
          </div>
          <div class="panel__actions">
            <button
              v-if="canCancel"
              type="button"
              class="btn btn--sm"
              :disabled="cancelling"
              @click="cancel"
            >
              {{ cancelling ? '取消中…' : '取消批次' }}
            </button>
          </div>
        </header>

        <p v-if="activeRun.errorMsg" class="banner banner--warn">{{ activeRun.errorMsg }}</p>
        <p v-if="activeRun.rationale" class="panel__rationale">{{ activeRun.rationale }}</p>

        <GenerationDiagnosis :diagnosis="activeRun.diagnosis" :fallback-verdict="fallbackVerdict" />
        <GenerationFunnel :funnel="activeRun.funnel" :cluster-count="activeRun.validClusterCount" />

        <details v-if="activeRun.plan" class="plan">
          <summary class="plan__summary">查看 S3 生成计划（采样量与工具链）</summary>
          <!-- 计划原来是以原始 JSON 贴出来的，除了工程师没人能读。
               这里改成它实际回答的几个问题：用什么策略、算多少、为什么 -->
          <dl class="plan__kv">
            <div class="plan__row">
              <dt>生成策略</dt>
              <dd>{{ activeRun.plan.strategy }}<span v-if="activeRun.plan.degraded" class="tag tag--warning">降级</span></dd>
            </div>
            <div class="plan__row">
              <dt>计划采样</dt>
              <dd>{{ activeRun.plan.nSamples }} 条（每批 {{ activeRun.plan.batchSize }} 条）</dd>
            </div>
            <div class="plan__row">
              <dt>随机种子</dt>
              <dd><code>{{ activeRun.plan.seed }}</code></dd>
            </div>
            <div v-if="activeRun.plan.estimatedGpuHours !== undefined" class="plan__row">
              <dt>预估 GPU</dt>
              <dd>{{ activeRun.plan.estimatedGpuHours }} 小时</dd>
            </div>
            <div v-if="activeRun.plan.fallbackStrategy" class="plan__row">
              <dt>兜底策略</dt>
              <dd>{{ activeRun.plan.fallbackStrategy }}</dd>
            </div>
            <div v-if="activeRun.plan.tools?.length" class="plan__row">
              <dt>工具链</dt>
              <dd>{{ activeRun.plan.tools.map((t) => t.toolCode).join(' → ') }}</dd>
            </div>
          </dl>
          <p v-if="activeRun.plan.degradedReason" class="plan__note">
            {{ activeRun.plan.degradedReason }}
          </p>
          <p v-if="activeRun.plan.rationale" class="plan__note">{{ activeRun.plan.rationale }}</p>
        </details>

        <details v-if="activeRun.slices?.length" class="plan">
          <summary class="plan__summary">分片执行情况（{{ activeRun.slices.length }} 片）</summary>
          <table class="slices">
            <thead>
              <tr>
                <th>#</th>
                <th>状态</th>
                <th class="num">产出</th>
                <th class="num">重试</th>
                <th>种子</th>
                <th>错误</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="slice in activeRun.slices" :key="slice.sliceNo">
                <td>{{ slice.sliceNo }}</td>
                <td><code>{{ slice.status }}</code></td>
                <td class="num">{{ slice.producedCount ?? '—' }}</td>
                <td class="num">{{ slice.retryCount ?? 0 }}</td>
                <td><code>{{ slice.seed ?? '—' }}</code></td>
                <td class="muted">{{ slice.errorMsg || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <section class="panel">
        <GenerationCandidates :key="activeRun.id" :run-id="activeRun.id" :project-id="projectId" />
      </section>
    </template>

    <div v-else-if="activeTab === 'detail'" class="panel panel__empty">
      <EmptyState title="未选择批次" description="到「批次列表」点一个批次的「查看」。" />
    </div>
  </div>
  </main>
</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import GenerationConfig from '@/components/design/GenerationConfig.vue'
import GenerationDiagnosis from '@/components/design/GenerationDiagnosis.vue'
import GenerationFunnel from '@/components/design/GenerationFunnel.vue'
import GenerationCandidates from '@/components/design/GenerationCandidates.vue'
import FlowSteps from '@/components/design/FlowSteps.vue'
import { generationApi, statusMeta, strategyMeta, verdictMeta } from '@/api/generation'
import type { GenerationCreateRequest, GenerationRun, GenerationVerdict } from '@/api/generation'
import { requirementApi } from '@/api/requirement'
import type { ApiError } from '@/utils/request'

/**
 * 生成页（阶段 C）。
 *
 * 与需求分析页同一口径：只拉状态、派写操作、把后端原因原样展示。
 * 不做本地乐观更新 —— 生成的结论是「能不能进入编辑」的闸门，
 * 前端猜一个 PASS 会让空集流进下游，这是设计里明确禁止的。
 */
const route = useRoute()
const projectId = Number(route.params.projectId)

const TABS = [
  { key: 'new', label: '发起生成' },
  { key: 'runs', label: '批次列表' },
  { key: 'detail', label: '批次详情' }
] as const

const loading = ref(false)
const submitting = ref(false)
const cancelling = ref(false)
const error = ref('')
const notice = ref('')
const activeTab = ref<'new' | 'runs' | 'detail'>('new')

const projectName = ref('生成')
const hasBaseline = ref(false)
const hasRound = ref(false)
const runs = ref<GenerationRun[]>([])
const activeRun = ref<GenerationRun | null>(null)

/**
 * 流程条第 3 步需要 candidateId 才能跳评估页。
 * 取当前批次里第一个「保留」的候选：它已经过 S7 配额，是唯一可评估的那批。
 */
const firstCandidateId = ref<number | null>(null)

/** 开轮是生成阶段的前置动作；缺轮次时用户不该只看到一句「请先开启一轮」 */
const openingRound = ref(false)

async function openRound() {
  if (openingRound.value) return
  openingRound.value = true
  error.value = ''
  notice.value = ''
  try {
    await requirementApi.openRound(projectId, '在生成页开启')
    notice.value = '已开启新一轮，可以发起生成了'
    await loadAll()
  } catch (e) {
    error.value = (e as ApiError)?.message || '开启轮次失败'
  } finally {
    openingRound.value = false
  }
}

const canCancel = computed(
  () => activeRun.value?.status === 'PENDING' || activeRun.value?.status === 'RUNNING'
)

/** 后端没给 diagnosis 时用批次状态兜底，避免面板空白 */
const fallbackVerdict = computed<GenerationVerdict | null>(() => {
  const status = activeRun.value?.status
  if (status === 'SUCCEEDED' || status === 'PARTIAL') return 'PASS_WITH_WARNING'
  if (status === 'FAILED') return 'FAIL'
  return null
})

const gateLabel = computed(() => {
  if (!hasBaseline.value) return '缺基线'
  if (!hasRound.value) return '缺轮次'
  return '可生成'
})

const gateTone = computed(() => {
  if (!hasBaseline.value || !hasRound.value) return 'danger'
  return 'success'
})

const gateText = computed(() => {
  if (!hasBaseline.value) return '项目还没有冻结基线。生成只读快照，请先在需求分析里冻结。'
  if (!hasRound.value) return '项目还没有开启任何轮次。生成必须挂在某一轮上，请先开启一轮。'
  return '基线已冻结、轮次已开启，可以发起生成。'
})

// ---------------------------------------------------------------- 加载

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [project, snapshot, list] = await Promise.all([
      requirementApi.getProject(projectId).catch(() => null),
      requirementApi.current(projectId).catch(() => null),
      generationApi.list(projectId).catch(() => ({ list: [] as GenerationRun[], total: 0, page: 1, pageSize: 20, hasNext: false }))
    ])
    projectName.value = project?.name || '生成'
    hasBaseline.value = Boolean(snapshot)
    hasRound.value = project?.currentRoundId != null
    runs.value = list.list ?? []
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

// ---------------------------------------------------------------- 动作

function switchTab(key: 'new' | 'runs' | 'detail') {
  activeTab.value = key
}

async function submit(body: GenerationCreateRequest) {
  submitting.value = true
  error.value = ''
  notice.value = ''
  try {
    const run = await generationApi.submit(projectId, body)
    notice.value =
      run.status === 'SUCCEEDED' || run.status === 'PARTIAL'
        ? `已生成 ${run.finalCount ?? 0} 个候选（${run.runNo}）`
        : `批次 ${run.runNo} 结束于 ${statusMeta(run.status).label}：${run.errorMsg || '见详情'}`
    activeRun.value = run
    activeTab.value = 'detail'
    await loadFirstCandidate(run.id)
    await loadAll()
  } catch (e) {
    // 后端在 400/404 里给的是可操作的中文原因（缺基线 / 搜索空间为空），原样展示
    error.value = (e as ApiError)?.message || '提交生成失败'
  } finally {
    submitting.value = false
  }
}

async function openRun(id: number) {
  error.value = ''
  try {
    activeRun.value = await generationApi.detail(id)
    activeTab.value = 'detail'
    await loadFirstCandidate(id)
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载批次详情失败'
  }
}

/**
 * 取该批次第一个「保留」候选，供流程条第 3 步（评估与编辑）跳转。
 *
 * 只查 S7_QUOTA / KEPT：这批候选已过配额，是唯一能进评估的；
 * 被粗筛或配额裁掉的没有落库 id，点了也只会 404。
 */
async function loadFirstCandidate(runId: number) {
  firstCandidateId.value = null
  try {
    const logs = await generationApi.candidateLogs(runId, 'S7_QUOTA', 'KEPT')
    firstCandidateId.value = logs.find((l) => l.candidateId)?.candidateId ?? null
  } catch {
    // 拿不到就禁用第 3 步，不阻断主流程
  }
}

async function cancel() {
  if (!activeRun.value) return
  cancelling.value = true
  try {
    activeRun.value = await generationApi.cancel(activeRun.value.id)
    notice.value = '批次已取消'
    await loadAll()
  } catch (e) {
    error.value = (e as ApiError)?.message || '取消失败'
  } finally {
    cancelling.value = false
  }
}

function fmtTime(value?: string): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}
</script>

<style lang="scss" scoped>
.gn {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.gn__main {
  flex: 1;
  padding: $spacing-lg $spacing-xl $spacing-2xl;
}

.page {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}
.status {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-md;
  align-items: center;
  justify-content: space-between;
  padding: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;

  @each $tone, $color in (success: $success-color, danger: $error-color, warning: $warning-color) {
    &--#{$tone} {
      border-left: 4px solid $color;

      .status__badge {
        color: $color;
        background: rgba($color, 0.12);
      }
    }
  }

  &__main {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
  }

  &__badge {
    padding: 3px 12px;
    border-radius: $radius-pill;
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
  }

  &__text {
    font-size: $font-size-sm;
    color: $color-text-soft;
  }

  &__meta {
    display: flex;
    gap: $spacing-md;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.tabs {
  display: flex;
  gap: $spacing-xs;

  &__btn {
    padding: 7px 14px;
    border: 1px solid transparent;
    border-radius: $radius-control;
    font-size: $font-size-sm;
    color: $color-text-soft;
    background: transparent;
    cursor: pointer;

    &:hover {
      background: $color-surface-alt;
    }

    &--active {
      color: $color-brand-strong;
      background: $color-brand-softer;
      border-color: $color-brand-soft;
      font-weight: $font-weight-medium;
    }
  }
}

.panel {
  padding: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

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

  &__meta {
    margin: 0;
    font-size: $font-size-xs;
    color: $color-text-faint;

    code {
      font-family: monospace;
    }
  }

  &__rationale {
    margin: 0 0 $spacing-md;
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-soft;
  }

  &__empty {
    padding: $spacing-xl 0;
  }
}

.runs {
  width: 100%;
  border-collapse: collapse;
  font-size: $font-size-sm;

  th,
  td {
    padding: 8px 10px;
    text-align: left;
    border-bottom: 1px solid $color-border-soft;
  }

  th {
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
    color: $color-text-faint;
    white-space: nowrap;
  }

  tbody tr:last-child td {
    border-bottom: none;
  }

  .num {
    text-align: right;
  }

  code {
    font-family: monospace;
    font-size: $font-size-xs;
  }
}

.strategy {
  font-size: $font-size-sm;
  color: $color-text;
}

.badge {
  margin-left: 6px;
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;

  &--warn {
    color: $warning-color;
    background: rgba($warning-color, 0.14);
  }
}

.tag {
  padding: 2px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  white-space: nowrap;

  @each $tone, $color in (success: $success-color, warning: $warning-color, danger: $error-color, info: $info-color, neutral: $text-muted) {
    &--#{$tone} {
      color: $color;
      background: rgba($color, 0.12);
    }
  }
}

.plan {
  margin-top: $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-control;

  &__summary {
    padding: 8px 12px;
    font-size: $font-size-sm;
    color: $color-text-soft;
    cursor: pointer;
  }

  &__kv {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 0;
    padding: $spacing-md;
    background: $color-surface-sunken;
    border-top: 1px solid $color-border;
  }

  &__row {
    display: flex;
    gap: $spacing-md;
    align-items: baseline;
    font-size: $font-size-xs;

    dt {
      flex-shrink: 0;
      width: 76px;
      color: $color-text-faint;
    }

    dd {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      align-items: center;
      margin: 0;
      color: $color-text;
    }
  }

  &__note {
    margin: 8px 0 0;
    padding: 0 $spacing-md $spacing-md;
    font-size: $font-size-xs;
    line-height: 1.7;
    color: $color-text-faint;
    background: $color-surface-sunken;
  }
}

.slices {
  width: 100%;
  border-collapse: collapse;
  font-size: $font-size-xs;

  th,
  td {
    padding: 6px 10px;
    text-align: left;
    border-top: 1px solid $color-border-soft;
  }

  th {
    color: $color-text-faint;
    font-weight: $font-weight-medium;
  }

  .num {
    text-align: right;
  }

  code {
    font-family: monospace;
  }
}

.muted {
  color: $color-text-faint;
}

.btn--sm {
  padding: 4px 10px;
  font-size: $font-size-xs;
}
</style>
