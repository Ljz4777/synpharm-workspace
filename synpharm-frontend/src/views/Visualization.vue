<template>
  <div class="visualization">

    <Sidebar />

    <main class="visualization__content">

      <!-- Header -->
      <header class="visualization__header">

        <div>

          <h1 class="visualization__title">
            3D可视化
          </h1>

          <p class="visualization__subtitle">
            交互式分子结构展示 · 可用 ←/→ 切换结果
          </p>

        </div>

        <!-- 结果选择器：这个页面要能换分子看，而不是只认 URL 里的一条 -->
        <div class="visualization__picker">
          <span class="visualization__picker-label">评价结果</span>
          <select
            class="visualization__select"
            :value="selectedId"
            :disabled="!resultOptions.length"
            @change="onPickChange"
          >
            <option v-if="!resultOptions.length" value="">
              {{ resultsLoading ? '加载中…' : '暂无评价结果' }}
            </option>
            <option v-for="opt in resultOptions" :key="opt.id" :value="opt.id">
              {{ opt.label }}
            </option>
          </select>
          <button
            type="button"
            class="visualization__step"
            :disabled="!resultOptions.length"
            title="上一条（←）"
            @click="stepResult(-1)"
          >
            ‹
          </button>
          <button
            type="button"
            class="visualization__step"
            :disabled="!resultOptions.length"
            title="下一条（→）"
            @click="stepResult(1)"
          >
            ›
          </button>
        </div>

      </header>

      <!-- 从侧栏直接进入时没有上下文，这里明确说明画的是示例结构，避免误认 -->
      <div v-if="isDemo" class="visualization__demo-note">
        当前为<strong>示例结构</strong>（{{ targetName }}）。从「评价记录」打开某条预测结果，
        可以看到该结果的真实靶点结构、亲和力与相互作用。
      </div>

      <!-- 极简上下文：明确当前展示的是哪条靶点的结构 -->
      <div class="visualization__context">
        <span class="visualization__context-item">
          <span class="visualization__context-label">靶点</span>
          <b class="visualization__context-value">{{ targetName }}</b>
        </span>

        <span class="visualization__context-item">
          <span class="visualization__context-label">ID</span>
          <b class="visualization__context-value visualization__context-value--mono">
            {{ targetId || '—' }}
          </b>
        </span>

        <span class="visualization__context-item">
          <span class="visualization__context-label">PDB</span>
          <b class="visualization__context-value visualization__context-value--mono">
            {{ resolvedPdbId || '未匹配到结构' }}
          </b>
        </span>

        <span class="visualization__context-item">
          <span class="visualization__context-label">置信度</span>
          <StatusTag
            kind="confidence"
            :value="confidenceLevel"
            :score="confidenceScore"
          />
        </span>

        <span
          v-if="bindingAffinity !== null"
          class="visualization__context-item"
        >
          <span class="visualization__context-label">亲和力</span>
          <b class="visualization__context-value">
            {{ bindingAffinity.toFixed(2) }} kcal/mol
          </b>
        </span>

        <span
          v-if="resultLoading"
          class="visualization__context-hint"
        >
          预测结果加载中…
        </span>
        <span
          v-else-if="resultError"
          class="visualization__context-hint visualization__context-hint--error"
          :title="resultError"
        >
          {{ resultError }}，当前展示的是示例结构
        </span>
      </div>

      <!-- Main -->
      <section
        class="visualization__main"
        :class="{ 'visualization__main--collapsed': !controlsOpen }"
      >

        <!-- =========================
             Viewer
        ========================== -->

        <div class="visualization__canvas">

          <!-- Resolving -->
          <div
            v-if="resolving"
            class="visualization__canvas-placeholder"
          >

            <span class="visualization__canvas-placeholder-icon">
              🔍
            </span>

            <span class="visualization__canvas-placeholder-text">
              正在查找蛋白质结构...
            </span>

            <span class="visualization__canvas-placeholder-hint">
              {{ uniprotId }}
            </span>

          </div>

          <!-- Mol* -->
          <MolstarViewer
            v-else-if="resolvedPdbId"

            ref="molstarRef"

            :pdb-id="resolvedPdbId"

            :auto-rotate="autoRotate"

            :show-grid="showGrid"

            @structure-loaded="onStructureLoaded"
          />

          <!-- Error -->
          <div
            v-else-if="resolveError"
            class="visualization__canvas-placeholder"
          >

            <span class="visualization__canvas-placeholder-icon">
              ⚠️
            </span>

            <span class="visualization__canvas-placeholder-text">
              结构数据库查询失败
            </span>

            <span class="visualization__canvas-placeholder-hint">
              已尝试使用本地结构映射
            </span>

          </div>

          <!-- No structure -->
          <div
            v-else
            class="visualization__canvas-placeholder"
          >

            <span class="visualization__canvas-placeholder-icon">
              🧬
            </span>

            <span class="visualization__canvas-placeholder-text">
              暂无对应 PDB 结构
            </span>

            <span class="visualization__canvas-placeholder-hint">
              该靶点暂无可用的蛋白质结构数据
            </span>

          </div>

        </div>

        <!-- =========================
             Controls
             默认收起；收起后的把手贴在画布右侧（即该栏原来的位置），点击展开
        ========================== -->

        <button
          v-show="!controlsOpen"
          class="visualization__side-handle"
          type="button"
          title="展开显示控制"
          @click="controlsOpen = true"
        >
          <span class="visualization__side-handle-icon">⚙</span>
          <span class="visualization__side-handle-text">显示控制</span>
        </button>

        <div v-show="controlsOpen" class="visualization__controls">

          <header class="visualization__controls-head">
            <h3 class="visualization__controls-heading">显示控制</h3>
            <button
              class="visualization__controls-collapse"
              type="button"
              @click="controlsOpen = false"
            >
              收起 ›
            </button>
          </header>

          <!-- Display -->
          <div class="visualization__control-section">

            <h3 class="visualization__control-title">
              显示模式
            </h3>

            <div class="visualization__mode-buttons">

              <button
                v-for="mode in displayModes"
                :key="mode.value"

                class="visualization__mode-btn"

                :class="{
                  'visualization__mode-btn--active':
                    displayMode === mode.value
                }"

                @click="changeDisplayMode(mode.value)"
              >

                {{ mode.label }}

              </button>

            </div>

          </div>

          <!-- Color -->
          <div class="visualization__control-section">

            <h3 class="visualization__control-title">
              颜色方案
            </h3>

            <div class="visualization__color-options">

              <button
                v-for="color in colorSchemes"
                :key="color.value"

                class="visualization__color-btn"

                :class="{
                  'visualization__color-btn--active':
                    colorScheme === color.value
                }"

                :style="{
                  background: color.preview
                }"

                :title="color.label"

                @click="changeColorScheme(color.value)"
              />

            </div>

          </div>

          <!-- Settings -->
          <div class="visualization__control-section">

            <h3 class="visualization__control-title">
              设置
            </h3>

            <!-- Grid -->
            <div class="visualization__setting-item">

              <label class="visualization__setting-label">
                显示网格
              </label>

              <input
                v-model="showGrid"

                type="checkbox"

                class="visualization__setting-checkbox"
              />

            </div>

            <!-- Rotate -->
            <div class="visualization__setting-item">

              <label class="visualization__setting-label">
                自动旋转
              </label>

              <input
                v-model="autoRotate"

                type="checkbox"

                class="visualization__setting-checkbox"
              />

            </div>

            <!-- Labels -->
            <div class="visualization__setting-item">

              <label class="visualization__setting-label">
                显示标签
              </label>

              <input
                v-model="showLabels"

                type="checkbox"

                class="visualization__setting-checkbox"
              />

            </div>

          </div>

          <!-- Actions -->
          <div class="visualization__control-section">

            <h3 class="visualization__control-title">
              操作
            </h3>

            <div class="visualization__action-buttons">

              <button
                class="visualization__action-btn"

                @click="resetView"
              >
                重置视角
              </button>

              <button
                class="visualization__action-btn"

                @click="exportImage"
              >
                导出图片
              </button>

            </div>

          </div>

        </div>

      </section>

      <!-- =========================
           Information
      ========================== -->

      <section class="visualization__info">

        <!-- Interactions -->
        <div
          v-if="interactions.length"
          class="visualization__info-card"
        >

          <h3 class="visualization__info-title">

            <span class="visualization__info-title-icon">
              🔗
            </span>

            相互作用详情

            <span class="visualization__info-count">
              {{ interactionCount }}
            </span>

          </h3>

          <div class="visualization__interaction-list">

            <div
              v-for="(inter, idx) in interactions"

              :key="idx"

              class="visualization__interaction-item"
            >

              <span
                class="visualization__interaction-badge"

                :style="{
                  background:
                    getInteractionColor(inter.type)
                }"
              >

                {{ getInteractionTypeName(inter.type) }}

              </span>

              <span class="visualization__interaction-detail">

                {{ inter.residueName }}{{ inter.residueNumber }}

              </span>

              <span class="visualization__interaction-distance">

                {{ inter.distance }}Å

              </span>

            </div>

          </div>

        </div>

      </section>

    </main>

  </div>
</template>

<script setup lang="ts">

import {
  ref,
  computed,
  watch,
  nextTick,
  onMounted,
  onUnmounted,
} from 'vue'

import { useRoute, useRouter } from 'vue-router'

import {
  mockTargets,
} from '@/data/mockResults'

import {
  resolveStructure,
  getPdbIdFromUniProt,
} from '@/utils/protein/structureResolver'

import { resultApi } from '@/api/predict'
import type { PredictionResult } from '@/types'

import Sidebar from '@/components/Sidebar.vue'

import MolstarViewer from '@/components/protein/MolstarViewer.vue'

import StatusTag from '@/components/ui/StatusTag.vue'

import {
  VIEWER_COLOR_SCHEMES,
  VIEWER_DISPLAY_MODES,
  useMolstarControls,
  type MolstarHandle,
} from '@/components/protein/viewerControls'


/* =========================================================
 * Route
 * ========================================================= */

const route = useRoute()
const router = useRouter()

/**
 * 显示控制接口与工作台口袋页签**共用一份实现**
 * （`components/protein/viewerControls.ts`）：
 * 显示模式 / 颜色方案 / Mol* 映射 / 组件句柄 / 加载后重新同步。
 */
const molstarRef =
  ref<MolstarHandle | null>(null)

const {
  displayMode,
  colorScheme,
  changeDisplayMode,
  changeColorScheme,
  resyncAfterLoad,
} = useMolstarControls(molstarRef)


/* =========================================================
 * Result (真实 API：GET /api/results/{id})
 * ========================================================= */

const apiResult =
  ref<PredictionResult | null>(null)

const resultLoading =
  ref(false)

const resultError =
  ref('')

async function loadResult(): Promise<void> {

  const id =
    route.query.id as string

  if (!id) {

    apiResult.value = null
    resultError.value = ''
    resultLoading.value = false
    return
  }

  resultLoading.value = true
  resultError.value = ''

  try {

    apiResult.value =
      await resultApi.getResultDetail(id)

  } catch (err) {

    apiResult.value = null
    // 归属校验失败后端一律返回 404（不返回 403，避免暴露资源是否存在）。
    // 这里必须说清楚「不是你的数据」，否则兜底结构会让人误以为看到了自己的结果。
    const status = (err as { status?: number }).status
    resultError.value =
      status === 404
        ? '该预测结果不存在或不属于当前账号'
        : status === 401 || status === 403
          ? '没有权限查看该预测结果'
          : err instanceof Error
            ? err.message
            : '加载预测结果失败'

  } finally {

    resultLoading.value = false
  }

}

watch(
  () => route.query.id,

  () => {
    loadResult()
  },

  {
    immediate: true,
  }
)


/* =========================================================
 * 结果选择器：在同页切换任意一条历史评价结果
 * ========================================================= */

const recentResults =
  ref<PredictionResult[]>([])

const resultsLoading =
  ref(false)

/** 当前下拉选中的结果 id（与 URL 的 ?id= 保持同步） */
const selectedId =
  ref(String(route.query.id ?? ''))

const resultOptions =
  computed(() =>
    recentResults.value.map((r) => ({
      id: String(r.id),
      label: `${r.targetName || r.targetId || '评价结果'} · ${formatResultTime(r.createdAt)}`,
    }))
  )

function formatResultTime(iso?: string): string {

  if (!iso) return '—'

  const d = new Date(iso)

  return isNaN(d.getTime())
    ? iso
    : d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function loadRecentResults(): Promise<void> {

  resultsLoading.value = true

  try {

    const page = await resultApi.getResultList(1, 100)

    recentResults.value = page.list ?? []

  } catch {

    // 预测服务不可用时保持空列表：页面仍有示例结构与明确说明，不额外报警
    recentResults.value = []

  } finally {

    resultsLoading.value = false
  }
}

function pickResult(id: string): void {

  if (!id) return

  selectedId.value = id

  // 复用现有 watch(route.query.id) 重新取数，不改数据层
  router.replace({ path: route.path, query: { ...route.query, id } })
}

function onPickChange(event: Event): void {

  pickResult((event.target as HTMLSelectElement).value)
}

function stepResult(delta: number): void {

  if (!recentResults.value.length) return

  const ids = recentResults.value.map((r) => String(r.id))
  const current = ids.indexOf(selectedId.value)
  const next = current < 0 ? 0 : Math.min(Math.max(current + delta, 0), ids.length - 1)

  if (next !== current) pickResult(ids[next])
}

function onVisualizationKeydown(event: KeyboardEvent): void {

  const tag = (event.target as HTMLElement | null)?.tagName

  // 输入框/下拉里按方向键是在移动光标，不能抢
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return

  if (event.key === 'ArrowLeft') {

    event.preventDefault()
    stepResult(-1)

  } else if (event.key === 'ArrowRight') {

    event.preventDefault()
    stepResult(1)
  }
}

watch(
  () => route.query.id,

  (id) => {
    selectedId.value = String(id ?? '')
  }
)

onMounted(async () => {

  await loadRecentResults()

  // 从侧栏裸进：默认打开最近一条结果，而不是停在示例 EGFR 上
  const noContext =
    !route.query.id && !route.query.targetId && !route.query.targetName

  if (noContext && recentResults.value.length) {
    pickResult(String(recentResults.value[0].id))
  }

  window.addEventListener('keydown', onVisualizationKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onVisualizationKeydown)
})


/* =========================================================
 * Basic Info
 * ========================================================= */

/** 从侧栏裸进（无预测结果、也没带靶点参数）时的示例靶点 */
const DEMO_TARGET_NAME = 'EGFR'

const targetName = computed(() => {

  return (
    apiResult.value?.targetName ||
    (route.query.targetName as string) ||
    // 没有上下文时落到内置示例，否则页面只剩「未匹配到结构」的空状态，
    // 用户看不出这个页面能做什么。
    DEMO_TARGET_NAME

  )

})

/**
 * 是否在展示示例结构。
 * 只有「既没有预测结果、也没显式带靶点参数」时才算 —— 那种情况下
 * 页面上的结构不是用户的数据。
 */
const isDemo = computed(() => {
  return !apiResult.value && !route.query.id && !route.query.targetName
})


const targetId = computed(() => {

  return (
    apiResult.value?.targetId ||
    (route.query.targetId as string) ||
    '-'
  )

})


/* =========================================================
 * UniProt
 * ========================================================= */

const uniprotId = computed(() => {

  const name =
    targetName.value

  if (!name) {
    return null
  }

  const target =
    mockTargets.find(
      item =>
        item.name.toLowerCase() ===
        name.toLowerCase()
    )

  const uid =
    target?.uniprotId

  return (
    uid &&
    uid !== '-'
  )
    ? uid.toUpperCase()
    : null

})


/* =========================================================
 * Structure Resolve
 * ========================================================= */

const resolving =
  ref(false)

const resolveError =
  ref(false)

const resolvedPdbId =
  ref<string | null>(null)

const resolvedSource =
  ref('')


async function doResolve(): Promise<void> {

  const uid =
    uniprotId.value

  if (!uid) {

    resolvedPdbId.value =
      null

    resolvedSource.value =
      'none'

    return
  }

  resolving.value =
    true

  resolveError.value =
    false

  try {

    const resolved =
      await resolveStructure(uid)

    console.log(
      '[Structure Resolver]',
      {
        targetName:
          targetName.value,

        uniprotId:
          uid,

        source:
          resolved.source,

        structureId:
          resolved.structureId,
      }
    )

    if (resolved.structureId) {

      resolvedPdbId.value =
        resolved.structureId

      resolvedSource.value =
        resolved.source

    } else {

      resolvedPdbId.value =
        null

      resolvedSource.value =
        'none'
    }

  } catch (err) {

    console.error(
      '[Structure Resolver]',
      err
    )

    const fallback =
      getPdbIdFromUniProt(uid)

    if (fallback) {

      resolvedPdbId.value =
        fallback

      resolvedSource.value =
        'fallback'

      resolveError.value =
        true

    } else {

      resolvedPdbId.value =
        null

      resolvedSource.value =
        'none'

      resolveError.value =
        true
    }

  } finally {

    resolving.value =
      false
  }

}


/* =========================================================
 * Resolve Watch
 * ========================================================= */

watch(
  uniprotId,

  () => {
    doResolve()
  },

  {
    immediate: true,
  }
)


/* =========================================================
 * Info
 * ========================================================= */

const bindingAffinity = computed(() => {

  const apiValue =
    apiResult.value?.bindingAffinity

  if (apiValue !== null && apiValue !== undefined) {
    return apiValue
  }

  const value =
    route.query.bindingAffinity as string

  return value
    ? parseFloat(value)
    : null

})


const confidenceScore = computed(() => {

  const apiValue =
    apiResult.value?.confidenceScore

  if (apiValue !== null && apiValue !== undefined) {
    return apiValue
  }

  const value =
    route.query.confidenceScore as string

  return value
    ? parseFloat(value)
    : 0

})


const confidenceLevel = computed(() => {

  return (
    apiResult.value?.confidenceLevel ||
    (route.query.confidenceLevel as string) ||
    'medium'
  )

})


const interactions = computed(() => {

  return apiResult.value?.interactions || []

})


const interactionCount = computed(() => {

  return interactions.value.length

})


/* =========================================================
 * UI State
 * ========================================================= */

/** 右侧显示控制栏是否展开；默认收起，把宽度让给 3D 画布 */
const controlsOpen =
  ref(false)

const showGrid =
  ref(false)

const autoRotate =
  ref(false)

const showLabels =
  ref(false)


/* =========================================================
 * Display Modes / Colors / Mol* Mapping
 *
 * 均由共享模块提供，与工作台口袋页签是同一份，避免两边漂移。
 * ========================================================= */

const displayModes = VIEWER_DISPLAY_MODES
const colorSchemes = VIEWER_COLOR_SCHEMES


/* =========================================================
 * Structure Loaded
 * ========================================================= */

function onStructureLoaded(): void {

  console.log(
    '[Visualization] structure loaded'
  )

  /*
   * Mol* 加载完成后会重建场景并回到默认显示，
   * 因此要把当前 UI 状态重新同步一次。
   * 模式与配色在共享逻辑里；标签是本页的补充项。
   */

  resyncAfterLoad(() => {

    if (showLabels.value) {

      molstarRef.value
        ?.setLabelsVisible(true)
    }

  })

}


/* =========================================================
 * Labels
 * ========================================================= */

watch(
  showLabels,
  value => {

    nextTick(() => {

      molstarRef.value
        ?.setLabelsVisible(value)

    })

  }
)


/* =========================================================
 * Reset
 * ========================================================= */

function resetView(): void {

  molstarRef.value
    ?.resetView()

}


/* =========================================================
 * Export
 * ========================================================= */

async function exportImage(): Promise<void> {

  await molstarRef.value
    ?.exportImage()

}


/* =========================================================
 * Interaction
 * ========================================================= */

const getInteractionColor =
  (type: string): string => {

    const colors: Record<string, string> = {

      hydrogen_bond:
        'rgba(16, 185, 129, 0.15)',

      hydrophobic:
        'rgba(245, 158, 11, 0.15)',

      ionic:
        'rgba(239, 68, 68, 0.15)',

      pi_pi:
        'rgba(139, 92, 246, 0.15)',

      metal:
        'rgba(13, 148, 136, 0.15)',

    }

    return (
      colors[type] ||
      'rgba(148, 163, 184, 0.15)'
    )
  }


const getInteractionTypeName =
  (type: string): string => {

    const types: Record<string, string> = {

      hydrogen_bond:
        '氢键',

      hydrophobic:
        '疏水',

      ionic:
        '离子',

      pi_pi:
        'π-π',

      metal:
        '金属',

    }

    return types[type] || type
  }

</script>

<style lang="scss" scoped>

.visualization {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.visualization__content {
  flex: 1;
  padding: $spacing-lg;
}

.visualization__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: $spacing-md;
  margin-bottom: $spacing-lg;
}

.visualization__picker {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.visualization__picker-label {
  font-size: $font-size-sm;
  color: $text-muted;
  white-space: nowrap;
}

.visualization__select {
  min-width: 240px;
  max-width: 360px;
  padding: 7px 10px;
  border: 1px solid $border-color;
  border-radius: $border-radius-md;
  background: $bg-primary;
  color: $text-primary;
  font-size: $font-size-sm;
  font-family: inherit;
  cursor: pointer;

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.visualization__step {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid $border-color;
  border-radius: $border-radius-md;
  background: $bg-primary;
  color: $text-secondary;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;

  &:hover:not(:disabled) {
    background: $bg-tertiary;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.visualization__title {
  font-size: $font-size-3xl;
  font-weight: 600;
  color: $text-primary;
  margin-bottom: 2px;
}

.visualization__subtitle {
  font-size: $font-size-xs;
  color: $text-muted;
}


/* =========================================================
 * Main
 * ========================================================= */

.visualization__main {
  display: grid;

  grid-template-columns:
    minmax(0, 3fr) minmax(0, 1fr);

  gap: $spacing-md;

  margin-bottom: $spacing-lg;
}

// 控制栏收起时，右侧只保留一条窄把手，宽度让给 3D 画布
.visualization__main--collapsed {
  grid-template-columns:
    minmax(0, 1fr) auto;
}

/* =========================================================
 * Context（画布上方的极简上下文：当前展示的是谁的结构）
 * ========================================================= */

.visualization__demo-note {
  margin-bottom: $spacing-md;
  padding: $spacing-sm $spacing-md;

  font-size: $font-size-sm;
  line-height: 1.6;
  color: $color-text-soft;

  background: $color-brand-softer;
  border: 1px solid $color-border-soft;
  border-radius: $radius-control;

  strong {
    color: $color-text;
    font-weight: 600;
  }
}

.visualization__context {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: $spacing-sm $spacing-lg;

  margin-bottom: $spacing-md;
  padding: $spacing-sm $spacing-md;

  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius-control;
}

.visualization__context-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.visualization__context-label {
  font-size: $font-size-xs;
  color: $color-text-faint;
}

.visualization__context-value {
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  color: $color-text;

  &--mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: $font-size-xs;
  }
}

.visualization__context-hint {
  font-size: $font-size-xs;
  color: $color-text-faint;

  &--error {
    color: $error-color;
  }
}

/* =========================================================
 * Side handle（控制栏收起后的展开把手）
 * ========================================================= */

.visualization__side-handle {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $spacing-md;

  width: 44px;
  padding: $spacing-lg 0;

  background: $color-surface;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  cursor: pointer;

  transition:
    border-color 0.18s $ease-out,
    background 0.18s $ease-out;

  &:hover {
    border-color: rgba(13, 148, 136, 0.4);
    background: $color-brand-softer;
  }
}

.visualization__side-handle-icon {
  font-size: 16px;
  line-height: 1;
}

.visualization__side-handle-text {
  font-size: $font-size-xs;
  color: $color-text-soft;
  letter-spacing: 2px;
  // 竖排文字：窄条里比横排易读
  writing-mode: vertical-rl;
}

/* =========================================================
 * Controls head（控制栏标题 + 收起按钮）
 * ========================================================= */

.visualization__controls-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $spacing-sm;

  padding-bottom: $spacing-sm;
  margin-bottom: $spacing-md;
  border-bottom: 1px solid $color-border-soft;
}

.visualization__controls-heading {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text;
}

.visualization__controls-collapse {
  padding: 2px 8px;

  background: transparent;
  border: none;
  border-radius: $border-radius-sm;
  cursor: pointer;

  font-size: $font-size-xs;
  font-family: inherit;
  color: $color-text-faint;

  transition:
    color 0.18s $ease-out,
    background 0.18s $ease-out;

  &:hover {
    color: $color-brand;
    background: $color-brand-soft;
  }
}

.visualization__canvas {
  height: 480px;

  border-radius:
    $border-radius-lg;

  border:
    1px solid $border-light;

  overflow: hidden;

  background: #000;
}


/* =========================================================
 * Placeholder
 * ========================================================= */

.visualization__canvas-placeholder {

  width: 100%;
  height: 100%;

  display: flex;

  flex-direction: column;

  align-items: center;

  justify-content: center;

  gap: $spacing-sm;

  background: #f5f8f7;
}

.visualization__canvas-placeholder-icon {
  font-size: 48px;
  opacity: 0.4;
}

.visualization__canvas-placeholder-text {
  font-size: $font-size-base;
  color: $text-secondary;
}

.visualization__canvas-placeholder-hint {
  font-size: $font-size-xs;
  color: $text-muted;
}


/* =========================================================
 * Controls
 * ========================================================= */

.visualization__controls {

  display: flex;

  flex-direction: column;

  gap: $spacing-sm;
}

.visualization__control-section {

  background: $bg-primary;
  border-radius: $border-radius-lg;
  border: 1px solid $border-light;

  padding: $spacing-md;
}

.visualization__control-title {

  font-size: 10px;

  font-weight: 600;

  color: $text-muted;

  text-transform: uppercase;

  letter-spacing: 0.4px;

  margin-bottom: $spacing-sm;
}


/* =========================================================
 * Display Buttons
 * ========================================================= */

.visualization__mode-buttons {

  display: grid;

  grid-template-columns:
    1fr 1fr;

  gap: $spacing-xs;
}

.visualization__mode-btn {

  display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; background: transparent; border: 1px solid transparent; border-radius: $border-radius-md; font-size: $font-size-sm; color: $text-secondary; cursor: pointer; transition: all $transition-fast; &:hover { background: $bg-tertiary; color: $text-primary; }

  font-size: 11px;

  padding:
    5px 6px;

  &--active {
    background: rgba($primary-color, 0.08); color: $primary-color; border-color: rgba($primary-color, 0.12);
  }
}

// 相互作用详情标题右侧的条数徽标
.visualization__info-count {
  display: inline-block;

  min-width: 18px;
  padding: 0 6px;
  margin-left: 6px;

  background: $color-brand-soft;
  border-radius: $radius-pill;

  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  line-height: 18px;
  text-align: center;
  color: $color-brand;
}


/* =========================================================
 * Color
 * ========================================================= */

.visualization__color-options {

  display: flex;

  gap: $spacing-xs;
}

.visualization__color-btn {

  width: 28px;

  height: 28px;

  border-radius: 50%;

  border: 2px solid transparent;

  cursor: pointer;

  transition:
    all $transition-fast;

  &:hover {
    transform: scale(1.1);
  }

  &--active {

    border-color:
      $primary-color;

    box-shadow:
      0 0 0 2px
      rgba(
        $primary-color,
        0.2
      );
  }
}


/* =========================================================
 * Settings
 * ========================================================= */

.visualization__setting-item {

  display: flex;

  justify-content:
    space-between;

  align-items: center;

  margin-bottom:
    $spacing-sm;

  padding-bottom:
    $spacing-sm;

  border-bottom:
    1px solid $border-light;

  &:last-child {

    margin-bottom: 0;

    padding-bottom: 0;

    border-bottom: none;
  }
}

.visualization__setting-label {

  font-size:
    $font-size-xs;

  color:
    $text-secondary;
}

.visualization__setting-checkbox {

  width: 16px;

  height: 16px;

  cursor: pointer;

  accent-color:
    $primary-color;
}


/* =========================================================
 * Action
 * ========================================================= */

.visualization__action-buttons {

  display: flex;

  flex-direction: column;

  gap: $spacing-xs;
}

.visualization__action-btn {

  display: inline-flex; align-items: center; gap: 6px; padding: 10px 16px; background: transparent; color: $text-secondary; border: 1px solid $border-color; border-radius: $border-radius-md; font-size: $font-size-sm; cursor: pointer; transition: all $transition-fast; &:hover { background: $bg-tertiary; }
}


/* =========================================================
 * Info
 * ========================================================= */

.visualization__info {

  display: grid;

  grid-template-columns:
    minmax(0, 1fr);

  gap: $spacing-md;
}

.visualization__info-card {

  background: $bg-primary; border-radius: $border-radius-lg; border: 1px solid $border-light; box-shadow: $shadow-md; border-color: transparent;

  padding: $spacing-lg;
}

.visualization__info-title {

  font-size:
    $font-size-sm;

  font-weight: 600;

  color:
    $text-primary;

  margin-bottom:
    $spacing-md;

  display: flex;

  align-items: center;

  gap: $spacing-xs;
}

.visualization__info-title-icon {
  font-size: 16px;
}

.visualization__info-hint {
  margin-bottom: $spacing-md;
  font-size: 10px;
  color: $text-muted;
  text-align: center;
  padding: $spacing-xs;
  border-radius: $border-radius-sm;
  background: $bg-secondary;

  &--error {
    color: $error-color;
    background: rgba(239, 68, 68, 0.08);
  }
}

.visualization__info-content {

  display: flex;

  flex-direction: column;

  gap: $spacing-sm;
}

.visualization__info-row {

  display: flex;

  justify-content:
    space-between;

  align-items: center;

  padding-bottom:
    $spacing-xs;

  border-bottom:
    1px dashed $border-light;

  &:last-child {

    padding-bottom: 0;

    border-bottom: none;
  }
}

.visualization__info-label {

  font-size: 10px;

  color:
    $text-muted;

  text-transform:
    uppercase;

  letter-spacing: 0.3px;
}

.visualization__info-value {

  font-size:
    $font-size-xs;

  font-weight: 600;

  color:
    $text-primary;

  &--mono {

    font-family:
      "Consolas", "Monaco", "Courier New", monospace;

    font-size: 10px;
  }

  &--highlight {

    font-weight: 600; letter-spacing: -0.3px;

    color:
      $accent-color;

    font-size:
      $font-size-sm;
  }
}


/* =========================================================
 * Interaction
 * ========================================================= */

.visualization__interaction-list {

  display: flex;

  flex-direction: column;

  gap: $spacing-xs;
}

.visualization__interaction-item {

  display: flex;

  align-items: center;

  gap: $spacing-xs;

  padding:
    $spacing-xs
    $spacing-sm;

  background:
    $bg-secondary;

  border-radius:
    $border-radius-sm;
}

.visualization__interaction-badge {

  padding:
    1px 8px;

  border-radius:
    9999px;

  font-size: 10px;

  font-weight: 500;

  color:
    $text-secondary;

  white-space: nowrap;
}

.visualization__interaction-detail {

  flex: 1;

  font-size: 10px;

  font-weight: 500;

  color:
    $text-primary;

  font-family:
    "Consolas", "Monaco", "Courier New", monospace;
}

.visualization__interaction-distance {

  font-size: 10px;

  color:
    $text-muted;

  font-family:
    "Consolas", "Monaco", "Courier New", monospace;
}

</style>
