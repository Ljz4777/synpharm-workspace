<template>
  <div class="ov">
    <p v-if="loadError" class="ov__error">{{ loadError }}</p>

    <!-- 零数据时先给「三步上手」卡片，而不是四个 0 -->
    <section v-if="showOnboarding" class="ov__onboarding">
      <div class="ov__onboarding-head">🚀 三步开始评价</div>
      <div class="ov__steps">
        <div class="ov__step">
          <span class="ov__step-num">1</span>
          <div class="ov__step-body">
            <div class="ov__step-title">选类型</div>
            <div class="ov__step-desc">DTI、PPI、DDI 三选一，先看「能力边界」确认引擎在线。</div>
          </div>
        </div>
        <div class="ov__step">
          <span class="ov__step-num">2</span>
          <div class="ov__step-body">
            <div class="ov__step-title">填输入</div>
            <div class="ov__step-desc">DTI 传 SMILES + 靶点，PPI 传两条序列，DDI 传两个 SMILES。</div>
          </div>
        </div>
        <div class="ov__step">
          <span class="ov__step-num">3</span>
          <div class="ov__step-body">
            <div class="ov__step-title">看结果</div>
            <div class="ov__step-desc">提交后到「单次/批量」或「评价记录」里查看，可跳转 3D 可视化。</div>
          </div>
        </div>
      </div>
      <div class="ov__onboarding-actions">
        <router-link :to="{ path: '/evaluation', query: { tab: 'single' } }" class="ov__btn ov__btn--primary">
          发起单次评价
        </router-link>
        <router-link :to="{ path: '/evaluation', query: { tab: 'boundary' } }" class="ov__btn">
          查看能力边界
        </router-link>
      </div>
    </section>

    <!-- 四个统计口径：任务量、完成度、在跑、平均置信度 -->
    <section v-else class="ov__stats">
      <template v-if="loading">
        <span v-for="n in 4" :key="n" class="u-skeleton ov__skeleton" />
      </template>
      <template v-else>
        <StatCard icon="📋" :value="stats.totalTasks" label="评价任务" tone="brand" />
        <StatCard icon="✅" :value="stats.completedTasks" label="已完成" tone="success" />
        <StatCard icon="⏳" :value="stats.runningTasks" label="运行中" tone="warning" />
        <StatCard icon="🎯" :value="`${stats.averageConfidence}%`" label="平均置信度" tone="info" />
      </template>
    </section>

    <div class="ov__cols">
      <AppCard padding="none">
        <template #header>
          <div class="ov__head">
            <h3 class="ov__title">最近评价任务</h3>
            <span class="ov__sub">按创建时间倒序，最多 5 条</span>
          </div>
        </template>
        <template #actions>
          <router-link :to="recordsLink('tasks')" class="ov__link">查看全部 →</router-link>
        </template>

        <div v-if="loading" class="ov__rows">
          <span v-for="n in 3" :key="n" class="u-skeleton ov__row-skeleton" />
        </div>
        <div v-else-if="recentTasks.length" class="ov__rows">
          <div v-for="task in recentTasks" :key="task.id" class="ov__row">
            <div class="ov__row-main">
              <span class="ov__row-name">{{ task.name || task.taskNo || task.id }}</span>
              <span class="ov__row-type">{{ typeLabel(task.predictType || task.type) }}</span>
            </div>
            <StatusTag kind="status" :value="task.status" />
          </div>
        </div>
        <EmptyState
          v-else
          icon="📋"
          title="还没有评价任务"
          description="到「单次评价」发起一次，任务会出现在这里。"
        />
      </AppCard>

      <AppCard padding="none">
        <template #header>
          <div class="ov__head">
            <h3 class="ov__title">最近评价结果</h3>
            <span class="ov__sub">亲和力与置信度均来自预测服务原值</span>
          </div>
        </template>
        <template #actions>
          <router-link :to="recordsLink('records')" class="ov__link">查看全部 →</router-link>
        </template>

        <div v-if="loading" class="ov__rows">
          <span v-for="n in 3" :key="n" class="u-skeleton ov__row-skeleton" />
        </div>
        <div v-else-if="recentResults.length" class="ov__rows">
          <div v-for="r in recentResults" :key="String(r.id)" class="ov__row">
            <div class="ov__row-main">
              <span class="ov__row-name">{{ r.targetName || r.targetId || '—' }}</span>
              <span class="ov__row-type">{{ r.ligandSmiles || '—' }}</span>
            </div>
            <div class="ov__row-metrics">
              <span class="ov__metric">{{ affinityText(r) }}</span>
              <span class="ov__metric ov__metric--conf" :class="`ov__metric--${r.confidenceLevel || 'low'}`">
                {{ Math.round((r.confidenceScore ?? 0) * 100) }}%
              </span>
            </div>
          </div>
        </div>
        <EmptyState
          v-else
          icon="📈"
          title="还没有评价结果"
          description="完成一次评价后，结果会展示在这里，并可进入 3D 可视化查看结构。"
        />
      </AppCard>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 评价体系 · 概览。
 *
 * 四个统计数全部由任务/结果列表现算，不另开统计接口 —— 两个口径的「总任务数」
 * 迟早会不一致，界面上出现两个不同数字比没有数字更糟。
 */
import { computed, onMounted, ref } from 'vue'
import { resultApi, taskApi } from '@/api/predict'
import type { PredictionResult, Task } from '@/types'
import AppCard from '@/components/ui/AppCard.vue'
import StatCard from '@/components/ui/StatCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusTag from '@/components/ui/StatusTag.vue'

const tasks = ref<Task[]>([])
const results = ref<PredictionResult[]>([])
const loading = ref(false)
const loadError = ref('')

const TYPE_TEXT: Record<string, string> = {
  dti: '药物-靶点',
  ppi: '蛋白-蛋白',
  ddi: '药物-药物'
}

function typeLabel(type?: string): string {
  if (!type) return '—'
  return TYPE_TEXT[type.toLowerCase()] || type
}

function affinityText(r: PredictionResult): string {
  return r.bindingAffinity != null ? `${r.bindingAffinity.toFixed(2)} kcal/mol` : '—'
}

/** 结果页在另一个导航项下，链接要带页签参数，否则会落到默认页签 */
function recordsLink(tab: string): string {
  return `/evaluation/records?tab=${tab}`
}

const stats = computed(() => {
  const total = tasks.value.length
  const completed = tasks.value.filter((t) => t.status === 'completed').length
  const running = tasks.value.filter((t) => t.status === 'running').length
  const avg = results.value.length
    ? Math.round((results.value.reduce((s, r) => s + (r.confidenceScore ?? 0), 0) / results.value.length) * 100)
    : 0
  return { totalTasks: total, completedTasks: completed, runningTasks: running, averageConfidence: avg }
})

const recentTasks = computed(() => tasks.value.slice(0, 5))

const recentResults = computed(() =>
  [...results.value]
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .slice(0, 5)
)

/** 没有任何任务/结果时展示「三步上手」 */
const showOnboarding = computed(() => !loading.value && !loadError.value && tasks.value.length === 0 && results.value.length === 0)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const [taskList, resultPage] = await Promise.all([
      taskApi.getTaskList(),
      resultApi.getResultList(1, 100)
    ])
    tasks.value = taskList
    results.value = resultPage.list
  } catch (error: unknown) {
    loadError.value = error instanceof Error ? error.message : '加载概览数据失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
// 语义变量里没有现成的浅色背景 token，这里就近定义，避免在模板里散落 rgba
$ov-ok-bg: rgba(5, 150, 105, 0.12);
$ov-warn-bg: rgba(217, 119, 6, 0.12);
$ov-err-bg: rgba(220, 38, 38, 0.1);

.ov {
  display: flex;
  flex-direction: column;
  gap: $spacing-lg;
}

.ov__error {
  padding: $spacing-sm $spacing-md;
  border-radius: $radius-control;
  background: $ov-err-bg;
  color: $error-color;
  font-size: $font-size-sm;
}

.ov__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: $spacing-md;
}

.ov__skeleton {
  height: 88px;
  border-radius: $radius-card;
}

.ov__cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: $spacing-lg;
  align-items: start;
}

.ov__head {
  display: flex;
  align-items: baseline;
  gap: $spacing-sm;
  min-width: 0;
}

.ov__title {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text;
}

.ov__sub {
  font-size: $font-size-xs;
  color: $color-text-faint;
}

.ov__rows {
  display: flex;
  flex-direction: column;
}

.ov__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $spacing-md;
  padding: $spacing-sm $spacing-lg;
  border-bottom: 1px solid $color-border;

  &:last-child {
    border-bottom: none;
  }
}

.ov__row-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ov__row-name {
  font-size: $font-size-sm;
  color: $color-text;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ov__row-type {
  font-size: $font-size-xs;
  color: $color-text-faint;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

.ov__row-metrics {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  flex-shrink: 0;
}

.ov__metric {
  font-size: $font-size-xs;
  font-variant-numeric: tabular-nums;
  color: $color-text-soft;
}

.ov__metric--conf {
  padding: 2px 8px;
  border-radius: $radius-pill;
  background: $color-surface-sunken;
}

.ov__metric--high { background: $ov-ok-bg; color: $success-color; }
.ov__metric--medium { background: $ov-warn-bg; color: $warning-color; }
.ov__metric--low { background: $ov-err-bg; color: $error-color; }

.ov__row-skeleton {
  height: 40px;
  margin: $spacing-xs $spacing-lg;
  border-radius: $border-radius-sm;
}

.ov__link {
  font-size: $font-size-sm;
  color: $color-brand;
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

.ov__onboarding {
  padding: $spacing-lg;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;
}

.ov__onboarding-head {
  margin-bottom: $spacing-md;
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text;
}

.ov__steps {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: $spacing-md;
}

.ov__step {
  display: flex;
  gap: $spacing-sm;
}

.ov__step-num {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: $color-brand;
  color: $color-on-dark;
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  flex-shrink: 0;
}

.ov__step-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ov__step-title {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text;
}

.ov__step-desc {
  font-size: $font-size-xs;
  line-height: 1.6;
  color: $color-text-soft;
}

.ov__onboarding-actions {
  display: flex;
  gap: $spacing-md;
  margin-top: $spacing-lg;
}

.ov__btn {
  padding: 8px 16px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;
  font-size: $font-size-sm;
  color: $color-text;
  text-decoration: none;
  cursor: pointer;

  &--primary {
    border-color: $color-brand;
    background: $color-brand;
    color: $color-on-dark;
  }
}
</style>
