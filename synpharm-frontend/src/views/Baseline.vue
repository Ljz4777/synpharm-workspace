<template>
  <div class="bl">
  <Sidebar />
  <main class="bl__main">
    <div class="page">
    <PageHeader :title="project?.name || '需求分析'" :subtitle="subtitle">
      <template #actions>
        <button type="button" class="btn" :disabled="loading" @click="loadAll">
          {{ loading ? '加载中…' : '重新预检' }}
        </button>
        <button
          type="button"
          class="btn btn--primary"
          :disabled="!preview?.ready || freezing"
          :title="freezeTitle"
          @click="freeze"
        >
          {{ freezing ? '冻结中…' : '冻结基线' }}
        </button>
      </template>
    </PageHeader>

    <FlowSteps :project-id="projectId" current="baseline" />

    <p v-if="error" class="banner banner--danger">{{ error }}</p>
    <p v-if="notice" class="banner banner--ok">{{ notice }}</p>

    <!-- 冻结成功后的下一步：不用再回项目列表找入口 -->
    <div v-if="currentSnapshot" class="next">
      <span class="next__text">
        基线已冻结（{{ currentSnapshot.snapshotNo }}）。生成只读这份快照，项目之后再改约束不影响已提交的任务。
      </span>
      <button type="button" class="btn btn--primary" @click="goGeneration">
        下一步：跑一批候选 →
      </button>
    </div>

    <!-- 预检总览：一句话说清「能不能冻结、为什么不能」 -->
    <section class="status" :class="`status--${statusTone}`">
      <div class="status__main">
        <span class="status__badge">{{ statusLabel }}</span>
        <span class="status__text">{{ statusText }}</span>
      </div>
      <div class="status__meta">
        <span v-if="currentSnapshot">当前生效：{{ currentSnapshot.snapshotNo }} · v{{ currentSnapshot.projectVersion }}</span>
        <span v-else>尚未冻结过基线</span>
        <span v-if="preview" class="status__hash" :title="preview.contentHash">
          hash {{ preview.contentHash.slice(0, 8) }}
        </span>
      </div>
    </section>

    <nav class="tabs">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        type="button"
        class="tabs__btn"
        :class="{ 'tabs__btn--active': activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
        <em v-if="tab.key === 'history' && history.length > 1" class="tabs__count">{{ history.length }}</em>
      </button>
    </nav>

    <BaselineBoard
      v-if="activeTab === 'board'"
      :preview="preview"
      :snapshot="currentSnapshot"
      :jump-map="JUMP_MAP"
      @jump="jumpToStep"
    />

    <BaselineWizard
      v-else-if="activeTab === 'wizard'"
      ref="wizardRef"
      :project="project"
      :preview="preview"
      :metrics="metrics"
      :risks="risks"
      :moa-options="moaOptions"
      :modality-label="modalityLabel"
      :busy="busy"
      @save-quota="saveQuota"
      @init-metrics="initMetrics"
      @update-metric="updateMetric"
      @delete-metric="deleteMetric"
      @add-risk="addRisk"
      @toggle-risk="toggleRisk"
      @delete-risk="deleteRisk"
    />

    <section v-else class="history">
      <p class="history__hint">
        每次内容变更都会产生新版本，旧版本自动置为 SUPERSEDED 并保留原样 ——
        历史基线是用来回答「这一轮为什么这么决策」的，不能被覆盖写。
      </p>
      <EmptyState v-if="!history.length" title="还没有基线版本" description="补齐八块后点「冻结基线」生成第一个版本。" />
      <ul v-else class="history__list">
        <li v-for="snap in history" :key="snap.id" class="version">
          <div class="version__head">
            <span class="version__no">v{{ snap.projectVersion }}</span>
            <span class="version__snap">{{ snap.snapshotNo }}</span>
            <span class="version__state" :class="`version__state--${snap.status.toLowerCase()}`">
              {{ snap.status === 'ACTIVE' ? '生效中' : '已取代' }}
            </span>
            <span class="version__time">{{ formatTime(snap.createdAt) }}</span>
          </div>
          <div class="version__body">
            <span class="version__modality">{{ labelOf(MODALITY_LABEL, snap.modalityCode) }}</span>
            <span class="version__hash">{{ snap.contentHash.slice(0, 12) }}</span>
            <span v-if="snap.completeness" class="version__blocks">
              {{ completenessText(snap.completeness) }}
            </span>
            <span v-if="snap.supersededBy" class="version__superseded">→ 被 v{{ snapshotVersionOf(snap.supersededBy) }} 取代</span>
          </div>
        </li>
      </ul>
    </section>
  </div>
  </main>
</div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import BaselineBoard from '@/components/design/BaselineBoard.vue'
import BaselineWizard from '@/components/design/BaselineWizard.vue'
import FlowSteps from '@/components/design/FlowSteps.vue'
import { requirementApi } from '@/api/requirement'
import { BLOCK_LABEL, BLOCK_STATE_LABEL, MODALITY_LABEL, labelOf } from '@/utils/designLabels'
import type { ApiError } from '@/utils/request'
import type {
  BaselineBlockCode,
  BaselinePreview,
  BaselineSnapshot,
  LibraryItem,
  ProjectBrief,
  ProjectMetric,
  ProjectMetricUpsert,
  ProjectQuotaPatch,
  ProjectRisk,
  ProjectRiskUpsert
} from '@/api/requirement'

/**
 * 需求分析页（阶段 B）。
 *
 * 页面只做三件事：**拉状态、派写操作、把后端的中文原因原样展示**。
 * 不做本地乐观更新 —— 冻结判定依赖 contentHash，本地猜一个状态会让「预检后别人改过」
 * 这类竞态完全暴露不出来。
 */
const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.projectId)

/** 冻结基线后的下一步：直接去生成页，不必回项目列表再找入口 */
function goGeneration() {
  router.push(`/design/projects/${projectId}/generation`)
}

const TABS = [
  { key: 'board', label: '八块看板' },
  { key: 'wizard', label: '补齐向导' },
  { key: 'history', label: '版本历史' }
] as const

/** 块缺项 → 跳向导哪一步 */
const JUMP_MAP: Partial<Record<BaselineBlockCode, string>> = {
  TARGETS: 'target',
  STRUCTURE: 'target',
  MODALITY: 'goal',
  HARD_RULES: 'constrain',
  SOFT_RULES: 'constrain',
  METRICS: 'metric',
  RISKS: 'metric',
  QUOTA: 'quota'
}

const STEP_INDEX: Record<string, number> = {
  goal: 0,
  target: 1,
  constrain: 2,
  metric: 3,
  quota: 4
}

const loading = ref(false)
const freezing = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const activeTab = ref<'board' | 'wizard' | 'history'>('board')
const wizardRef = ref<InstanceType<typeof BaselineWizard> | null>(null)

const project = ref<ProjectBrief | null>(null)
const preview = ref<BaselinePreview | null>(null)
const metrics = ref<ProjectMetric[]>([])
const risks = ref<ProjectRisk[]>([])
const history = ref<BaselineSnapshot[]>([])
const moaOptions = ref<LibraryItem[]>([])
const modalityOptions = ref<LibraryItem[]>([])

const currentSnapshot = computed(() => history.value.find((s) => s.status === 'ACTIVE') ?? null)

const modalityLabel = computed(() => {
  const id = project.value?.modalityId
  if (!id) return '未选择'
  const item = modalityOptions.value.find((m) => m.id === id)
  return item ? `${item.name}（${item.code}）` : `#${id}`
})

const subtitle = computed(() =>
  project.value
    ? `${project.value.projectNo} · 八块完整性预检与冻结 · 冻结后生成任务只读快照`
    : '加载中…'
)

const statusLabel = computed(() => {
  if (!preview.value) return '未预检'
  if (preview.value.ready) return '可以冻结'
  return '存在阻断项'
})

const statusTone = computed(() => {
  if (!preview.value) return 'neutral'
  if (preview.value.ready) return 'success'
  return preview.value.gaps.some((g) => g.severity === 'ERROR') ? 'danger' : 'warning'
})

const statusText = computed(() => {
  if (!preview.value) return '点右上角「重新预检」检查八块是否齐全。'
  if (preview.value.ready) {
    const warns = preview.value.gaps.length
    return warns === 0
      ? '八块全部就绪，可以冻结为 v' + nextVersion.value + '。'
      : `没有阻断项，但有 ${warns} 条告警；冻结后这些告警会随快照一起记录。`
  }
  const errors = preview.value.gaps.filter((g) => g.severity === 'ERROR')
  const first = errors[0]?.message ?? ''
  return `还有 ${errors.length} 项阻断：${first}`
})

const nextVersion = computed(() => {
  const active = currentSnapshot.value
  return active ? active.projectVersion + 1 : 1
})

const freezeTitle = computed(() => {
  if (!preview.value) return '请先预检'
  if (!preview.value.ready) return '八块不齐，无法冻结'
  return '冻结为不可变快照，生成任务将只读它'
})

/* ------------------------------ 读取 ------------------------------ */

function toMessage(e: unknown): string {
  const apiError = e as ApiError
  return apiError?.message || '请求失败'
}

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [projectData, previewData, metricList, riskList, historyList] = await Promise.all([
      requirementApi.getProject(projectId),
      requirementApi.preview(projectId),
      requirementApi.listMetrics(projectId),
      requirementApi.listRisks(projectId),
      requirementApi.history(projectId)
    ])
    project.value = projectData
    preview.value = previewData
    metrics.value = metricList ?? []
    risks.value = riskList ?? []
    history.value = historyList ?? []
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    loading.value = false
  }
}

async function loadLibraries() {
  try {
    const [moa, modality] = await Promise.all([
      requirementApi.listLibrary('MOA'),
      requirementApi.listLibrary('MODALITY')
    ])
    moaOptions.value = moa?.list ?? []
    modalityOptions.value = modality?.list ?? []
  } catch {
    // 字典拿不到不影响主流程：下拉会退化成只有「未指定」
    moaOptions.value = []
    modalityOptions.value = []
  }
}

/** 任何写操作之后都重新预检：判定权在后端，前端不猜 */
async function refreshAfterWrite(message: string) {
  notice.value = message
  error.value = ''
  preview.value = await requirementApi.preview(projectId)
  metrics.value = (await requirementApi.listMetrics(projectId)) ?? []
  risks.value = (await requirementApi.listRisks(projectId)) ?? []
}

/* ------------------------------ 写操作 ------------------------------ */

async function saveQuota(patch: ProjectQuotaPatch) {
  busy.value = true
  try {
    await requirementApi.patchProject(projectId, patch)
    project.value = await requirementApi.getProject(projectId)
    await refreshAfterWrite('配额与作用机制已保存')
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function initMetrics() {
  busy.value = true
  try {
    const added = await requirementApi.initMetricsFromModality(projectId)
    await refreshAfterWrite(`已按分子形式补入 ${added?.length ?? 0} 个指标`)
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function updateMetric(metricId: number, patch: ProjectMetricUpsert) {
  busy.value = true
  try {
    await requirementApi.updateMetric(projectId, metricId, patch)
    await refreshAfterWrite('指标已更新')
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function deleteMetric(metricId: number) {
  busy.value = true
  try {
    await requirementApi.deleteMetric(projectId, metricId)
    await refreshAfterWrite('指标已移除')
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function addRisk(body: ProjectRiskUpsert) {
  busy.value = true
  try {
    await requirementApi.addRisk(projectId, body)
    await refreshAfterWrite('风险关注点已添加')
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function toggleRisk(riskId: number, mustVerify: boolean) {
  busy.value = true
  try {
    await requirementApi.updateRisk(projectId, riskId, { mustVerify })
    await refreshAfterWrite(mustVerify ? '已标记为必须验证' : '已取消必须验证')
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function deleteRisk(riskId: number) {
  busy.value = true
  try {
    await requirementApi.deleteRisk(projectId, riskId)
    await refreshAfterWrite('风险关注点已移除')
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    busy.value = false
  }
}

async function freeze() {
  freezing.value = true
  error.value = ''
  notice.value = ''
  try {
    // 带上预检时的 hash：预检之后有人改过项目会被后端挡住（BASELINE_STALE）
    const snapshot = await requirementApi.freeze(projectId, preview.value?.contentHash)
    history.value = (await requirementApi.history(projectId)) ?? []
    project.value = await requirementApi.getProject(projectId)
    notice.value = `已冻结 ${snapshot.snapshotNo}（v${snapshot.projectVersion}）`
  } catch (e) {
    error.value = toMessage(e)
    // 撞到并发修改：重新预检让用户看到最新缺口，而不是停在旧画面
    const apiError = e as ApiError
    if (apiError?.errorCode === 'BASELINE_STALE') {
      preview.value = await requirementApi.preview(projectId)
    }
  } finally {
    freezing.value = false
  }
}

/* ------------------------------ 展示辅助 ------------------------------ */

function jumpToStep(stepKey: string) {
  activeTab.value = 'wizard'
  // 向导是 v-else-if 渲染的，切 tab 后同一 tick 内 ref 还没挂上，等 DOM 更新再设步骤
  const index = STEP_INDEX[stepKey] ?? 0
  void nextTick(() => wizardRef.value?.setStep(index))
}

function formatTime(value: string): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

/**
 * 块状态摘要：`HARD_RULES:MISSING · METRICS:WARN` 这种机器码对用户等于没说，
 * 改成「硬约束 缺失 · 指标 有告警」。
 */
function completenessText(completeness: Record<string, string>): string {
  const entries = Object.entries(completeness)
  const bad = entries.filter(([, state]) => state !== 'OK')
  if (bad.length === 0) return `${entries.length} 块全部齐全`
  return bad
    .map(([code, state]) => `${labelOf(BLOCK_LABEL, code)} ${labelOf(BLOCK_STATE_LABEL, state)}`)
    .join(' · ')
}

function snapshotVersionOf(id: number): string {
  const found = history.value.find((s) => s.id === id)
  return found ? String(found.projectVersion) : `#${id}`
}

onMounted(() => {
  void loadLibraries()
  void loadAll()
})

watch(
  () => route.params.projectId,
  (next, prev) => {
    if (next && next !== prev) void loadAll()
  }
)
</script>

<style lang="scss" scoped>
.next {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-md;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border: 1px solid rgba($success-color, 0.35);
  border-radius: $radius-card;
  background: rgba($success-color, 0.06);

  &__text {
    font-size: $font-size-sm;
    line-height: 1.6;
    color: $color-text-soft;
  }
}

.bl {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.bl__main {
  flex: 1;
  padding: $spacing-lg $spacing-xl $spacing-2xl;
}

.page {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}
.banner {
  margin: 0;
  padding: 10px 14px;
  border-radius: $radius-control;
  font-size: $font-size-sm;
  line-height: 1.6;

  &--danger {
    background: rgba($error-color, 0.09);
    color: #b91c1c;
  }

  &--ok {
    background: rgba($success-color, 0.1);
    color: #047857;
  }
}

.status {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;

  &--success {
    border-color: rgba($success-color, 0.4);
  }

  &--warning {
    border-color: rgba($warning-color, 0.45);
  }

  &--danger {
    border-color: rgba($error-color, 0.4);
  }

  &__main {
    display: flex;
    gap: 10px;
    align-items: center;
    min-width: 0;
  }

  &__badge {
    flex-shrink: 0;
    padding: 3px 10px;
    border-radius: $radius-pill;
    font-size: $font-size-xs;
    font-weight: $font-weight-medium;
  }

  &--success &__badge {
    color: $success-color;
    background: rgba($success-color, 0.12);
  }

  &--warning &__badge {
    color: #b45309;
    background: rgba($warning-color, 0.14);
  }

  &--danger &__badge {
    color: $error-color;
    background: rgba($error-color, 0.12);
  }

  &--neutral &__badge {
    color: $text-muted;
    background: $color-surface-sunken;
  }

  &__text {
    font-size: $font-size-sm;
    color: $color-text-soft;
  }

  &__meta {
    display: flex;
    gap: 12px;
    align-items: center;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__hash {
    padding: 1px 6px;
    border-radius: 4px;
    background: $color-surface-sunken;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
}

.tabs {
  display: flex;
  gap: 4px;
  padding-bottom: 2px;
  border-bottom: 1px solid $color-border;

  &__btn {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    padding: 8px 14px;
    border: 0;
    border-bottom: 2px solid transparent;
    background: none;
    font-size: $font-size-sm;
    color: $color-text-faint;
    cursor: pointer;

    &--active {
      border-bottom-color: $color-brand;
      color: $color-brand-strong;
      font-weight: $font-weight-medium;
    }
  }

  &__count {
    padding: 0 6px;
    border-radius: $radius-pill;
    background: $color-brand-soft;
    font-size: 10px;
    font-style: normal;
  }
}

.history {
  &__hint {
    margin: 0 0 $spacing-md;
    font-size: $font-size-sm;
    line-height: 1.65;
    color: $color-text-faint;
  }

  &__list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }
}

.version {
  padding: 12px 16px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;

  &__head {
    display: flex;
    gap: 12px;
    align-items: center;
    margin-bottom: 6px;
  }

  &__no {
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__snap {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__state {
    padding: 2px 8px;
    border-radius: $radius-pill;
    font-size: 10px;

    &--active {
      color: $success-color;
      background: rgba($success-color, 0.12);
    }

    &--superseded {
      color: $text-muted;
      background: $color-surface-sunken;
    }
  }

  &__time {
    margin-left: auto;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__body {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    font-size: $font-size-xs;
    color: $color-text-soft;
  }

  &__hash {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    color: $color-text-faint;
  }

  &__superseded {
    color: $text-muted;
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
</style>
