<template>
  <div class="ev">
  <Sidebar />
  <main class="ev__main">
    <div class="page">
    <PageHeader
      :title="`候选 #${candidateId} · 评估与编辑`"
      subtitle="冻结评估 → 诊断缺口 → 匹配动作 → 影响面守卫 → 版本树"
    >
      <template #actions>
        <button type="button" class="btn" :disabled="loading" @click="loadAll">
          {{ loading ? '加载中…' : '刷新' }}
        </button>
        <button type="button" class="btn btn--primary" :disabled="freezing" @click="doFreeze">
          {{ freezing ? '评估中…' : freeze ? '重新评估并冻结' : '评估并冻结' }}
        </button>
      </template>
    </PageHeader>

    <FlowSteps
      :project-id="projectId"
      current="evaluation"
      :candidate-id="candidateId"
    />

    <p v-if="error" class="banner banner--danger">{{ error }}</p>
    <p v-if="notice" class="banner banner--ok">{{ notice }}</p>

    <!-- 状态条：下一步该做什么 -->
    <section class="status" :class="`status--${gateTone}`">
      <div class="status__main">
        <span class="status__badge">{{ gateLabel }}</span>
        <span class="status__text">{{ gateText }}</span>
      </div>
      <div class="status__meta">
        <span v-if="freeze">冻结 {{ freeze.freezeNo }}</span>
        <span v-if="gaps">诊断 {{ gaps.reportNo }}</span>
      </div>
    </section>

    <nav class="tabs">
      <button
        v-for="tab in TABS"
        :key="tab.key"
        type="button"
        class="tabs__btn"
        :class="{ 'tabs__btn--active': activeTab === tab.key }"
        :disabled="!tabReady(tab.key)"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </nav>

    <section v-if="activeTab === 'freeze'" class="panel">
      <div v-if="!freeze" class="panel__empty">
        <EmptyState
          title="还没有评估冻结快照"
          description="编辑与决策都以冻结快照为输入。先点上方「评估并冻结」。"
        />
      </div>
      <EvaluationFreezePanel v-else :freeze="freeze" />
    </section>

    <section v-else-if="activeTab === 'gaps'" class="panel">
      <div v-if="!gaps" class="panel__empty">
        <EmptyState title="还没有缺口诊断" description="点下方按钮生成诊断报告。" />
        <button type="button" class="btn btn--primary" :disabled="diagnosing" @click="diagnose">
          {{ diagnosing ? '诊断中…' : '生成缺口诊断' }}
        </button>
      </div>
      <GapPanel v-else :report="gaps" :selected="pendingAction" @preview="previewImpact" />
    </section>

    <section v-else-if="activeTab === 'impact'" class="panel">
      <div v-if="!impact" class="panel__empty">
        <EmptyState
          title="还没有影响面评估"
          description="到「缺口诊断」里点一个候选动作，或再点一次该动作重新评估。"
        />
      </div>
      <template v-else>
        <header class="impact__head">
          <h3 class="impact__title">
            影响面：{{ actionLabel(impact.actionCode) }}
            <code class="impact__code">{{ impact.actionCode }}</code>
          </h3>
          <span class="tag" :class="`tag--${guardMeta(impact.guardResult).tone}`">
            {{ guardMeta(impact.guardResult).label }}
          </span>
        </header>

        <p class="impact__hint">{{ guardMeta(impact.guardResult).hint }}</p>
        <p v-if="impact.blockReason" class="banner banner--danger">{{ impact.blockReason }}</p>

        <table class="impact__table">
          <thead>
            <tr>
              <th>维度</th>
              <th>风险</th>
              <th>当前是否达标</th>
              <th>预测变化</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, i) in impact.protectedOnes" :key="i">
              <td>{{ p.dimension === '*' ? '全部已达标维度' : dimensionLabel(p.dimension) }}</td>
              <td>
                <span class="tag" :class="p.risk === 'MUST_DROP' ? 'tag--danger' : 'tag--warning'">
                  {{ p.risk === 'MUST_DROP' ? '会下降' : '需观察' }}
                </span>
              </td>
              <td>{{ p.currentPassed ? '已达标' : '未达标' }}</td>
              <td class="muted">{{ p.predictedDelta ?? '—' }}</td>
              <td class="note">{{ p.note }}</td>
            </tr>
          </tbody>
        </table>

        <p class="impact__foot">
          预测变化恒为 <code>—</code>：动作→指标的量化模型未接入，只给定性风险。
          编一个「-0.3」会让用户以为系统量过了。
        </p>
      </template>
    </section>

    <section v-else class="panel">
      <div v-if="!tree" class="panel__empty">
        <EmptyState title="暂无版本树" description="点刷新加载。" />
      </div>
      <CandidateTree v-else :tree="tree" :busy="loading" @focus="switchCandidate" />
      <div class="tree-actions">
        <button type="button" class="btn" :disabled="loading" @click="revert">切回父版本</button>
        <button type="button" class="btn btn--danger" :disabled="loading" @click="abandon">放弃此分支</button>
        <span class="tree-actions__note">
          切回父版本 = 新建一个与父同内容的新版本，不是回滚 —— 历史只增不删。
        </span>
      </div>
    </section>
  </div>
  </main>
</div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import EvaluationFreezePanel from '@/components/design/EvaluationFreezePanel.vue'
import GapPanel from '@/components/design/GapPanel.vue'
import CandidateTree from '@/components/design/CandidateTree.vue'
import FlowSteps from '@/components/design/FlowSteps.vue'
import { editApi, evaluationApi, actionLabel, dimensionLabel, guardMeta } from '@/api/evaluation'
import type { CandidateTree as TreeData, EditGapReport, EditImpact, EvaluationFreeze } from '@/api/evaluation'
import type { ApiError } from '@/utils/request'

/**
 * 评估与编辑页（阶段 D）。
 *
 * 页签按「必须按顺序完成」排列，未就绪的页签禁用 ——
 * 这不是体验优化，是把 E0 准入的硬约束（无冻结快照不许诊断、无诊断不许评估影响面）
 * 在界面上也表达出来，避免用户点了才看到报错。
 */
const route = useRoute()
const router = useRouter()
const candidateId = Number(route.params.candidateId)
/**
 * 评估页 URL 只带 candidateId（用户是从生成页的逐分子日志点进来的），
 * 但流程条与「返回批次」都需要 projectId —— 从冻结快照 / 缺口报告里反查，
 * 都不行时退回 URL 查询参数（项目列表页跳过来会带）。
 */
const projectId = ref<number | null>(
  route.query.projectId ? Number(route.query.projectId) : null
)

const TABS = [
  { key: 'freeze', label: '评估快照' },
  { key: 'gaps', label: '缺口与动作' },
  { key: 'impact', label: '影响面' },
  { key: 'tree', label: '版本树' }
] as const

const loading = ref(false)
const freezing = ref(false)
const diagnosing = ref(false)
const error = ref('')
const notice = ref('')
const activeTab = ref<'freeze' | 'gaps' | 'impact' | 'tree'>('freeze')

const freeze = ref<EvaluationFreeze | null>(null)
const gaps = ref<EditGapReport | null>(null)
const impact = ref<EditImpact | null>(null)
const tree = ref<TreeData | null>(null)
const pendingAction = ref('')

const gateLabel = computed(() => {
  if (!freeze.value) return '待评估'
  if (!gaps.value) return '待诊断'
  return '可编辑'
})

const gateTone = computed(() => {
  if (!freeze.value) return 'warning'
  if (!gaps.value) return 'warning'
  return 'success'
})

const gateText = computed(() => {
  if (!freeze.value) return '先做一次评估冻结：编辑的输入契约是冻结快照，不是「当前分数」。'
  if (!gaps.value) {
    const warn = freeze.value.weightCoverage !== null && freeze.value.weightCoverage !== undefined
      && freeze.value.weightCoverage < 0.999
    return warn
      ? `已冻结，但综合分只代表 ${Math.round((freeze.value.weightCoverage ?? 0) * 100)}% 的权重。生成诊断前请先看评估快照页的说明。`
      : '已冻结。下一步生成缺口诊断。'
  }
  return '缺口已诊断，可从「缺口与动作」里挑一个动作做影响面评估。'
})

function tabReady(key: string): boolean {
  if (key === 'freeze') return true
  if (key === 'gaps') return Boolean(freeze.value)
  if (key === 'impact') return Boolean(gaps.value)
  return Boolean(tree.value)
}

// ---------------------------------------------------------------- 加载

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    freeze.value = await evaluationApi.latest(candidateId).catch(() => null)
    if (!projectId.value && freeze.value?.projectId) {
      projectId.value = freeze.value.projectId
    }
    gaps.value = await editApi.gaps(candidateId).catch(() => null)
    tree.value = await editApi.tree(candidateId).catch(() => null)
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadAll)

// ---------------------------------------------------------------- 动作

async function doFreeze() {
  freezing.value = true
  error.value = ''
  notice.value = ''
  try {
    freeze.value = await evaluationApi.freeze(candidateId)
    notice.value = `已冻结 ${freeze.value.freezeNo}（覆盖率 ${Math.round((freeze.value.weightCoverage ?? 0) * 100)}%）`
    await loadAll()
  } catch (e) {
    // 计算层不可用等失败原因由后端给中文，原样展示
    error.value = (e as ApiError)?.message || '评估失败'
  } finally {
    freezing.value = false
  }
}


async function diagnose() {
  diagnosing.value = true
  error.value = ''
  notice.value = ''
  try {
    gaps.value = await editApi.diagnose(candidateId)
    notice.value = `诊断完成：${gaps.value.gaps.length} 项可修缺口、${gaps.value.unfixable.length} 项修不了`
    await loadAll()
  } catch (e) {
    error.value = (e as ApiError)?.message || '诊断失败'
  } finally {
    diagnosing.value = false
  }
}

async function previewImpact(actionCode: string) {
  pendingAction.value = actionCode
  error.value = ''
  try {
    impact.value = await editApi.previewImpact(candidateId, actionCode)
    activeTab.value = 'impact'
  } catch (e) {
    error.value = (e as ApiError)?.message || '影响面评估失败'
  }
}

async function revert() {
  if (!window.confirm('切回父版本会新建一个与父同内容的新版本（历史保留），继续？')) return
  loading.value = true
  error.value = ''
  try {
    const res = await editApi.revert(candidateId)
    notice.value = `已新建回退版本 #${res.candidateId}`
    router.push(`/design/candidates/${res.candidateId}/evaluation`)
  } catch (e) {
    error.value = (e as ApiError)?.message || '回退失败'
  } finally {
    loading.value = false
  }
}

async function abandon() {
  if (!window.confirm('放弃后该分支不再参与编辑与决策（保留在版本树里）。继续？')) return
  loading.value = true
  error.value = ''
  try {
    await editApi.abandon(candidateId)
    notice.value = '分支已放弃'
    await loadAll()
  } catch (e) {
    error.value = (e as ApiError)?.message || '放弃失败'
  } finally {
    loading.value = false
  }
}

function switchCandidate(id: number) {
  router.push(`/design/candidates/${id}/evaluation`)
}
</script>

<style lang="scss" scoped>
.ev {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.ev__main {
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

  @each $tone, $color in (success: $success-color, warning: $warning-color, danger: $error-color) {
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
    font-family: monospace;
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

    &:hover:not(:disabled) {
      background: $color-surface-alt;
    }

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
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

  &__empty {
    display: flex;
    flex-direction: column;
    gap: $spacing-md;
    align-items: center;
    padding: $spacing-xl 0;
  }
}

.impact {
  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: $spacing-sm;
    align-items: center;
    margin-bottom: $spacing-sm;
  }

  &__title {
    display: flex;
    gap: $spacing-sm;
    align-items: baseline;
    margin: 0;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__code {
    font-family: monospace;
    font-size: $font-size-xs;
    font-weight: $font-weight-normal;
    color: $color-text-faint;
  }

  &__hint {
    margin: 0 0 $spacing-md;
    font-size: $font-size-sm;
    color: $color-text-soft;
  }

  &__table {
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

.note {
  font-size: $font-size-xs;
  color: $color-text-faint;
}

.muted {
  color: $color-text-faint;
}

.tree-actions {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
  align-items: center;
  margin-top: $spacing-md;
  padding-top: $spacing-md;
  border-top: 1px solid $color-border;

  &__note {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

.tag {
  padding: 2px 8px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;

  @each $tone, $color in (success: $success-color, warning: $warning-color, danger: $error-color, neutral: $text-muted) {
    &--#{$tone} {
      color: $color;
      background: rgba($color, 0.12);
    }
  }
}

.btn--danger {
  color: $error-color;
  border-color: rgba($error-color, 0.4);

  &:hover:not(:disabled) {
    background: rgba($error-color, 0.08);
  }
}
</style>
