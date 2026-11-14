<template>
  <div class="ev">
    <Sidebar />
    <main class="ev__main">
      <div class="page">
        <PageHeader
          icon="🧪"
          title="评价工作台"
          subtitle="指标口径 → 发起评价 → 能力边界：先知道「评什么、用什么评」，再决定要不要跑"
        />

        <EngineStatusBar show-probe-at />

        <TabBar v-model="currentTab" :tabs="TABS" />

        <OverviewPanel v-if="activeTab === 'overview'" />

        <!-- 两个预测面板常驻挂载、只切换可见性：切到「批量」再切回来不该丢掉刚算出的结果 -->
        <div v-show="activeTab === 'single'" class="ev__panel">
          <PredictPanel mode="single" />
        </div>
        <div v-show="activeTab === 'batch'" class="ev__panel">
          <PredictPanel mode="batch" />
        </div>

        <MetricsPanel v-if="activeTab === 'metrics'" />
        <BoundaryPanel v-if="activeTab === 'boundary'" />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 评价体系 · 评价工作台。
 *
 * 「分子预测」只是这个体系里的一个执行入口，所以这一页有五块：
 * 概览、单次评价、批量评价、指标（评什么）、能力边界（用什么评、边界在哪）。
 *
 * 页签与 `?tab=` 双向同步：从候选评估页「送进评价」深链进来要能直接落到单次评价，
 * 刷新后也不该跳回概览。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import TabBar from '@/components/ui/TabBar.vue'
import OverviewPanel from '@/components/evaluation/OverviewPanel.vue'
import PredictPanel from '@/components/evaluation/PredictPanel.vue'
import MetricsPanel from '@/components/evaluation/MetricsPanel.vue'
import BoundaryPanel from '@/components/evaluation/BoundaryPanel.vue'
import EngineStatusBar from '@/components/evaluation/EngineStatusBar.vue'

const TABS = [
  { key: 'overview', label: '概览', icon: '📊' },
  { key: 'single', label: '单次评价', icon: '🎯' },
  { key: 'batch', label: '批量评价', icon: '📄' },
  { key: 'metrics', label: '指标', icon: '📐' },
  { key: 'boundary', label: '能力边界', icon: '🧭' }
]

const TAB_KEYS = TABS.map((t) => t.key)

const route = useRoute()
const router = useRouter()

function resolveTab(): string {
  const raw = route.query.tab
  const key = typeof raw === 'string' ? raw : ''
  return TAB_KEYS.includes(key) ? key : 'overview'
}

const activeTab = ref(resolveTab())

// 外部（侧栏、深链）改 query 时同步页签
watch(
  () => route.query.tab,
  () => {
    const next = resolveTab()
    if (next !== activeTab.value) activeTab.value = next
  }
)

// 页签变化写回 query；replace 而不是 push，避免「后退」在页签之间反复横跳
const currentTab = computed({
  get: () => activeTab.value,
  set: (value: string) => {
    activeTab.value = value
    if (resolveTab() !== value) {
      router.replace({ path: route.path, query: { ...route.query, tab: value } })
    }
  }
})
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
  min-width: 0;
}

.page {
  display: flex;
  flex-direction: column;
  max-width: $content-max-width;
  margin: 0 auto;
}

.ev__panel {
  min-width: 0;
}
</style>
