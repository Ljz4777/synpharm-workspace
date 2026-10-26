<template>
  <div class="tg">
    <Sidebar />
    <main class="tg__main">
      <div class="page">
        <PageHeader
          icon="🧪"
          title="靶点库"
          subtitle="靶点与结构是需求基线块1、块2 的唯一来源：这里能不能拿到结构，决定这个靶点能不能立项"
        >
          <template #actions>
            <button type="button" class="btn" :disabled="loading" @click="load">
              {{ loading ? '加载中…' : '刷新' }}
            </button>
          </template>
        </PageHeader>

        <p v-if="error" class="banner banner--danger">{{ error }}</p>

        <!-- 筛选 -->
        <section class="tg__filter">
          <input
            v-model="searchQuery"
            type="text"
            class="tg__search-input"
            placeholder="搜索名称、UniProt、基因名、中文名…"
          />
          <select v-model="filterType" class="tg__select">
            <option value="all">全部类型</option>
            <option v-for="t in targetTypes" :key="t" :value="t">{{ t }}</option>
          </select>
          <select v-model="filterArea" class="tg__select">
            <option value="all">全部领域</option>
            <option v-for="a in diseaseAreas" :key="a" :value="a">{{ a }}</option>
          </select>
          <span class="tg__count">{{ filtered.length }} / {{ targets.length }} 个靶点</span>
        </section>

        <section class="tg__body">
          <!-- 靶点列表 -->
          <div class="tg__list">
            <div
              v-for="target in filtered"
              :key="target.id"
              class="tg__item"
              :class="{ 'tg__item--selected': selectedId === target.id }"
              @click="selectTarget(target)"
            >
              <div class="tg__item-head">
                <span class="tg__item-name">{{ target.name }}</span>
                <span class="tg__item-status">{{ statusText(target.status) }}</span>
              </div>
              <span v-if="target.chineseName" class="tg__item-cn">{{ target.chineseName }}</span>
              <div class="tg__item-tags">
                <span v-if="target.targetType" class="tg__tag tg__tag--type">{{ target.targetType }}</span>
                <span v-if="target.diseaseArea" class="tg__tag tg__tag--area">{{ target.diseaseArea }}</span>
              </div>
              <div class="tg__item-foot">
                <span class="tg__item-id">{{ target.uniprotId || '—' }}</span>
                <span class="tag" :class="readinessOf(target).tone">{{ readinessOf(target).label }}</span>
              </div>
            </div>

            <div v-if="!filtered.length && !loading" class="tg__empty">
              <span class="tg__empty-icon">🧪</span>
              <span class="tg__empty-text">{{ targets.length ? '没有匹配的靶点' : '靶点库还是空的' }}</span>
            </div>
          </div>

          <!-- 靶点详情 -->
          <div v-if="selected" class="tg__detail">
            <div class="tg__detail-head">
              <div>
                <h3 class="tg__detail-name">{{ selected.name }}</h3>
                <span v-if="selected.chineseName" class="tg__detail-cn">{{ selected.chineseName }}</span>
              </div>
              <button type="button" class="btn btn--primary btn--sm" @click="useTarget(selected)">
                用此靶点建项目 →
              </button>
            </div>

            <p v-if="selected.description" class="tg__desc">{{ selected.description }}</p>

            <!-- 立项前最该看的一条：结构够不够冻结基线块2 -->
            <p class="banner" :class="`banner--${readinessOf(selected).banner}`">
              {{ readinessOf(selected).hint }}
            </p>

            <div class="tg__block">
              <h4 class="tg__block-title">基本信息</h4>
              <div class="tg__kv"><span>UniProt ID</span><b>{{ selected.uniprotId || '—' }}</b></div>
              <div class="tg__kv"><span>基因名称</span><b>{{ selected.geneName || '—' }}</b></div>
              <div class="tg__kv"><span>物种</span><b>{{ organismText(selected.organism) }}</b></div>
              <div class="tg__kv"><span>序列长度</span><b>{{ selected.sequenceLength ?? '—' }} aa</b></div>
              <div class="tg__kv"><span>支持状态</span><b>{{ statusText(selected.status) }}</b></div>
            </div>

            <div class="tg__block">
              <h4 class="tg__block-title">分子特征</h4>
              <div class="tg__kv"><span>靶点类型</span><b>{{ selected.targetType || '—' }}</b></div>
              <div class="tg__kv"><span>蛋白家族</span><b>{{ selected.family || '—' }}</b></div>
              <div class="tg__kv"><span>主要通路</span><b>{{ selected.pathway || '—' }}</b></div>
              <div class="tg__kv"><span>疾病领域</span><b>{{ selected.diseaseArea || '—' }}</b></div>
            </div>

            <div v-if="selected.relatedDiseases || selected.knownDrugs" class="tg__block">
              <h4 class="tg__block-title">临床相关</h4>
              <div v-if="selected.relatedDiseases" class="tg__kv">
                <span>相关疾病</span><b>{{ selected.relatedDiseases }}</b>
              </div>
              <div v-if="selected.knownDrugs" class="tg__kv">
                <span>相关药物</span><b>{{ selected.knownDrugs }}</b>
              </div>
            </div>

            <!-- 结构与口袋：基线块2 就取这一块 -->
            <div class="tg__block">
              <h4 class="tg__block-title">
                结构与口袋
                <span v-if="structures.length" class="tg__block-count">{{ structures.length }} 个结构</span>
              </h4>

              <p v-if="!structures.length" class="tg__block-empty">
                这个靶点还没有任何可用结构。基线块2 会缺失，项目冻结不了 ——
                没有结构就没有口袋，对接类指标也无从计算。
              </p>

              <ul v-else class="structs">
                <li v-for="s in structures" :key="s.id" class="structs__item">
                  <div class="structs__head">
                    <span class="structs__pdb">{{ s.pdbId || '无 PDB' }}</span>
                    <span class="tag tag--neutral">{{ structureSourceText(s.source) }}</span>
                    <span v-if="s.isPreferred" class="tag tag--success">首选</span>
                    <span class="structs__meta">
                      {{ methodText(s.experimentMethod) }}
                      <template v-if="s.resolution"> · {{ s.resolution.toFixed(2) }} Å</template>
                      <template v-if="s.chainId"> · 链 {{ s.chainId }}</template>
                    </span>
                  </div>

                  <p v-if="!s.pockets?.length" class="structs__empty">这个结构没有识别到口袋。</p>
                  <table v-else class="structs__pockets">
                    <thead>
                      <tr>
                        <th>口袋</th>
                        <th>方法</th>
                        <th class="num">评分</th>
                        <th class="num">可成药性</th>
                        <th class="num">体积</th>
                        <th>选用</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="p in s.pockets" :key="p.id">
                        <td>#{{ p.pocketNo }}</td>
                        <td>{{ pocketMethodText(p.method) }}</td>
                        <td class="num">{{ p.score ?? '—' }}</td>
                        <td class="num">{{ p.druggability ?? '—' }}</td>
                        <td class="num">{{ p.volume ?? '—' }}</td>
                        <td>{{ p.isSelected ? '是' : '否' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </li>
              </ul>
            </div>

            <p class="tg__note">
              靶点与结构目前是**只读**的：后端只提供查询接口，补充靶点或导入结构需要走数据侧脚本。
              这里不做「新建靶点」按钮，因为点下去也没有接口能接住。
            </p>
          </div>

          <div v-else class="tg__placeholder">
            <span class="tg__placeholder-icon">👈</span>
            <p>{{ loading ? '加载中…' : '从左侧选择一个靶点，查看它的结构与口袋' }}</p>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 靶点库（/targets）。
 *
 * 与设计流程的关系：本页是需求基线**块1（靶点）**与**块2（结构 / 口袋）**的数据源。
 * 之前这页读的是 `@/data/mockResults` 里的假靶点 —— 于是「靶点库」看着像个功能，
 * 内容却与基线、生成、评估毫无关系，用户自然觉得它和主线没关系。
 *
 * 现在全部走 `TargetController`：
 *   GET /api/design/targets            列表（含每个靶点的结构与口袋）
 *   GET /api/design/targets/{id}       详情
 *
 * 靶点规模很小（当前 3 条，接口单页上限 200），所以一次拉全再本地筛选：
 * 既省掉「每敲一个字打一次后端」，也让类型/领域下拉的候选值来自同一份数据。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import {
  EXPERIMENT_METHOD_LABEL,
  POCKET_METHOD_LABEL,
  STRUCTURE_SOURCE_LABEL,
  targetApi,
  type TargetDetail,
  type TargetStructure
} from '@/api/target'
import type { ApiError } from '@/utils/request'

const route = useRoute()
const router = useRouter()

const targets = ref<TargetDetail[]>([])
const selectedId = ref<number | null>(null)
const loading = ref(false)
const error = ref('')

const searchQuery = ref('')
const filterType = ref('all')
const filterArea = ref('all')

const selected = computed(() => targets.value.find((t) => t.id === selectedId.value) ?? null)

/** 详情里的结构可能比列表更新，单独取一次；取不到就退回列表里的那份 */
const detailStructures = ref<TargetStructure[] | null>(null)
const structures = computed<TargetStructure[]>(
  () => detailStructures.value ?? selected.value?.structures ?? []
)

const targetTypes = computed(() =>
  Array.from(new Set(targets.value.map((t) => t.targetType).filter(Boolean) as string[])).sort()
)
const diseaseAreas = computed(() =>
  Array.from(new Set(targets.value.map((t) => t.diseaseArea).filter(Boolean) as string[])).sort()
)

const filtered = computed(() => {
  const kw = searchQuery.value.trim().toLowerCase()
  return targets.value.filter((t) => {
    if (filterType.value !== 'all' && t.targetType !== filterType.value) return false
    if (filterArea.value !== 'all' && t.diseaseArea !== filterArea.value) return false
    if (!kw) return true
    return [t.name, t.uniprotId, t.geneName, t.chineseName, t.targetType]
      .filter(Boolean)
      .some((field) => String(field).toLowerCase().includes(kw))
  })
})

/* ---------------------------------------------------------------- 可立项性 */

/**
 * 「这个靶点能不能立项」—— 只看一件事：有没有可用结构。
 * 基线块2 没有结构就是 MISSING，而 MISSING 直接阻断冻结，所以这条判断不是提示而是闸门。
 */
function readinessOf(target: TargetDetail): {
  label: string
  tone: string
  banner: string
  hint: string
} {
  const structs = target.structures ?? []
  const pockets = structs.reduce((n, s) => n + (s.pockets?.length ?? 0), 0)

  if (!structs.length) {
    return {
      label: '不可立项',
      tone: 'tag--danger',
      banner: 'danger',
      hint: '这个靶点没有可用结构：需求基线块2 会缺失，项目无法冻结，也就无法发起生成。'
    }
  }
  if (!pockets) {
    return {
      label: '缺口袋',
      tone: 'tag--warning',
      banner: 'warn',
      hint: '有结构但没识别到口袋：块2 可以过，但对接类指标会缺席，评估分数会少一截。'
    }
  }
  return {
    label: '可立项',
    tone: 'tag--success',
    banner: 'ok',
    hint: `有 ${structs.length} 个结构、${pockets} 个口袋，需求基线块2 可以满足。`
  }
}

/* ---------------------------------------------------------------- 文案 */

const ORGANISM_LABEL: Record<string, string> = {
  human: '人类',
  mouse: '小鼠',
  rat: '大鼠'
}

const STATUS_LABEL: Record<string, string> = {
  supported: '已支持',
  beta: '测试中',
  planned: '规划中'
}

const organismText = (v?: string | null): string => (v ? ORGANISM_LABEL[v] ?? v : '—')
const statusText = (v?: string | null): string => (v ? STATUS_LABEL[v] ?? v : '—')
const structureSourceText = (v?: string | null): string => (v ? STRUCTURE_SOURCE_LABEL[v] ?? v : '—')
const methodText = (v?: string | null): string => (v ? EXPERIMENT_METHOD_LABEL[v] ?? v : '—')
const pocketMethodText = (v?: string | null): string => (v ? POCKET_METHOD_LABEL[v] ?? v : '—')

/* ---------------------------------------------------------------- 加载 */

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await targetApi.list({ pageSize: 200 })
    targets.value = res.list ?? []
    pickInitial()
  } catch (e) {
    error.value = (e as ApiError)?.message || '加载靶点库失败'
  } finally {
    loading.value = false
  }
}

async function selectTarget(target: TargetDetail) {
  selectedId.value = target.id
  detailStructures.value = null
  try {
    const detail = await targetApi.detail(target.id)
    // 用详情覆盖列表里的那份（结构/口袋可能有更新）
    const idx = targets.value.findIndex((t) => t.id === target.id)
    if (idx >= 0) targets.value[idx] = detail
    detailStructures.value = detail.structures ?? []
  } catch {
    // 详情失败不阻断：列表里已经带了结构与口袋
    detailStructures.value = target.structures ?? []
  }
}

/** 支持 `?targetId=` 深链：基线页块1/块2 会带着当前靶点跳进来 */
function pickInitial() {
  const wanted = Number(route.query.targetId ?? 0)
  const hit = targets.value.find((t) => t.id === wanted) ?? targets.value[0]
  if (hit) void selectTarget(hit)
}

/**
 * 用此靶点建项目：跳项目列表并预填靶点。
 * 不让这里直接建项目 —— 建项目还要选分子形式、配额、约束，缺一个就冻结不了。
 */
function useTarget(target: TargetDetail) {
  router.push({ path: '/projects', query: { targetId: String(target.id), create: '1' } })
}

onMounted(load)
</script>

<style lang="scss" scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}.targets {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

.targets__content {
  flex: 1;
  margin-left: $sidebar-width;
  padding: $spacing-lg;
}

.targets__header {
  margin-bottom: $spacing-xl;
}

.targets__title {
  font-size: 24px;
  font-weight: 600;
  color: $text-primary;
  margin-bottom: $spacing-xs;
}

.targets__subtitle {
  font-size: $font-size-sm;
  color: $text-muted;
}

.targets__search {
  display: flex;
  gap: $spacing-md;
  margin-bottom: $spacing-xl;
}

.targets__search-input {
  flex: 1;
  padding: $spacing-sm $spacing-md;
  border: 1px solid $border-color;
  border-radius: $border-radius-md;
  font-size: $font-size-base;
  background: $bg-primary;
  
  &:focus {
    outline: none;
    border-color: $primary-color;
  }
}

.targets__organism-select {
  padding: $spacing-sm $spacing-md;
  border: 1px solid $border-color;
  border-radius: $border-radius-md;
  font-size: $font-size-sm;
  background: $bg-primary;
  cursor: pointer;
  
  &:focus {
    outline: none;
    border-color: $primary-color;
  }
}

.targets__main {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $spacing-xl;
}

.targets__list {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}

.targets__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: $spacing-md;
  background: $bg-primary;
  border-radius: $border-radius-md;
  border: 1px solid $border-light;
  cursor: pointer;
  transition: all $transition-fast;
  
  &:hover {
    border-color: $border-color;
  }
  
  &--selected {
    border-color: $primary-color;
    background: rgba($primary-color, 0.02);
  }
}

.targets__item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.targets__item-name {
  font-size: $font-size-sm;
  font-weight: 500;
  color: $text-primary;
}

.targets__item-id {
  font-size: $font-size-xs;
  color: $text-muted;
  font-family: monospace;
}

.targets__item-organism {
  font-size: $font-size-xs;
  color: $text-muted;
  padding: 2px 8px;
  background: $bg-secondary;
  border-radius: $border-radius-sm;
}

.targets__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: $spacing-2xl;
}

.targets__empty-icon {
  font-size: 48px;
  margin-bottom: $spacing-md;
}

.targets__empty-text {
  font-size: $font-size-base;
  color: $text-muted;
}

.targets__detail {
  background: $bg-primary;
  border-radius: $border-radius-lg;
  padding: $spacing-lg;
  border: 1px solid $border-light;
  height: fit-content;
}

.targets__detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: $spacing-lg;
}

.targets__detail-title {
  font-size: $font-size-lg;
  font-weight: 600;
  color: $text-primary;
}

.targets__use-btn {
  padding: $spacing-sm $spacing-lg;
  background: $primary-color;
  color: #ffffff;
  border: none;
  border-radius: $border-radius-md;
  font-size: $font-size-sm;
  font-weight: 500;
  cursor: pointer;
  transition: all $transition-fast;
  
  &:hover {
    background: $primary-dark;
  }
}

.targets__detail-content {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}

.targets__detail-row {
  display: flex;
  gap: $spacing-md;
}

.targets__detail-label {
  font-size: $font-size-xs;
  color: $text-muted;
  width: 100px;
  flex-shrink: 0;
}

.targets__detail-value {
  font-size: $font-size-sm;
  color: $text-primary;
  flex: 1;
}
</style>

<style lang="scss" scoped>
/* ===================== 靶点库（专业版） ===================== */
.tg {
  display: flex;
  min-height: 100vh;
  background: $bg-secondary;
}

// 宽度与居中由 styles/base.scss 的全局规则统一提供
.tg__main {
  flex: 1;
  padding: $spacing-lg $spacing-xl;
}

.tg__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: $spacing-lg;
}

.tg__title {
  font-size: $font-size-2xl;
  font-weight: 700;
  color: $text-primary;
}

.tg__subtitle {
  margin-top: $spacing-xs;
  font-size: $font-size-sm;
  color: $text-muted;
}

.tg__count {
  font-size: $font-size-sm;
  color: $text-secondary;
  background: $bg-tertiary;
  padding: $spacing-xs $spacing-md;
  border-radius: 999px;
}

/* ---------- 筛选 ---------- */
.tg__filter {
  display: flex;
  gap: $spacing-md;
  margin-bottom: $spacing-lg;
}

.tg__search-input {
  flex: 1;
  padding: $spacing-sm $spacing-md;
  border: 1px solid $border-color;
  border-radius: $border-radius-md;
  font-size: $font-size-base;
  background: $bg-primary;
  &:focus { outline: none; border-color: $accent-color; box-shadow: 0 0 0 3px rgba(13, 148, 136, 0.12); }
}

.tg__select {
  padding: $spacing-sm $spacing-md;
  border: 1px solid $border-color;
  border-radius: $border-radius-md;
  font-size: $font-size-sm;
  background: $bg-primary;
  color: $text-primary;
  cursor: pointer;
  max-width: 240px;
  &:focus { outline: none; border-color: $accent-color; }
}

/* ---------- 布局 ---------- */
.tg__body {
  display: grid;
  grid-template-columns: 1fr 1.3fr;
  gap: $spacing-xl;
  align-items: start;
}

.tg__list {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
  padding-right: $spacing-xs;
}

.tg__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: $spacing-md;
  background: $bg-primary;
  border-radius: $border-radius-md;
  border: 1px solid $border-light;
  cursor: pointer;
  transition: $transition-fast;
  &:hover { border-color: $border-color; box-shadow: $shadow-sm; }
  &--selected { border-color: $accent-color; background: rgba(13, 148, 136, 0.04); }
}

.tg__item-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: $spacing-sm;
}

.tg__item-name {
  font-size: $font-size-base;
  font-weight: 700;
  color: $text-primary;
}

.tg__item-status {
  font-size: $font-size-xs;
  color: $text-muted;
  white-space: nowrap;
}

.tg__item-cn {
  font-size: $font-size-xs;
  color: $text-muted;
}

.tg__item-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tg__tag {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: $font-size-xs;
  font-weight: 500;
  white-space: nowrap;
  &--type { background: rgba(13, 148, 136, 0.1); color: $accent-color; }
  &--area { background: rgba(16, 185, 129, 0.1); color: $success-color; }
}

.tg__item-id {
  font-size: $font-size-xs;
  color: $text-light;
  font-family: monospace;
}

.tg__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: $spacing-2xl;
  color: $text-muted;
}

.tg__empty-icon { font-size: 40px; margin-bottom: $spacing-sm; }
.tg__empty-text { font-size: $font-size-base; }

/* ---------- 详情 ---------- */
.tg__detail {
  background: $bg-primary;
  border: 1px solid $border-color;
  border-radius: $border-radius-lg;
  padding: $spacing-lg;
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}

.tg__detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: $spacing-md;
  padding-bottom: $spacing-md;
  border-bottom: 1px solid $border-light;
}

.tg__detail-name {
  font-size: $font-size-xl;
  font-weight: 700;
  color: $text-primary;
}

.tg__detail-cn {
  display: block;
  margin-top: 4px;
  font-size: $font-size-sm;
  color: $accent-color;
}

.tg__use-btn {
  padding: $spacing-sm $spacing-lg;
  background: $primary-color;
  color: #fff;
  border: none;
  border-radius: $border-radius-md;
  font-size: $font-size-sm;
  font-weight: 500;
  cursor: pointer;
  transition: $transition-fast;
  white-space: nowrap;
  &:hover { background: $primary-light; }
}

.tg__desc {
  padding: $spacing-md;
  background: $bg-secondary;
  border-left: 3px solid $accent-color;
  border-radius: $border-radius-md;
  font-size: $font-size-sm;
  line-height: 1.7;
  color: $text-secondary;
}

.tg__block {
  padding: $spacing-md;
  background: $bg-secondary;
  border-radius: $border-radius-md;
}

.tg__block-title {
  font-size: $font-size-sm;
  font-weight: 700;
  color: $text-primary;
  margin-bottom: $spacing-md;
}

.tg__kv {
  display: flex;
  gap: $spacing-md;
  padding: $spacing-xs 0;
  font-size: $font-size-sm;
  span { width: 84px; color: $text-muted; flex-shrink: 0; }
  b { color: $text-primary; font-weight: 500; line-height: 1.6; }
}

.tg__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: $spacing-3xl;
  background: $bg-primary;
  border: 1px dashed $border-color;
  border-radius: $border-radius-lg;
  color: $text-muted;
  .tg__placeholder-icon { font-size: 40px; margin-bottom: $spacing-md; }
  p { font-size: $font-size-base; }
}
/* ---------------- 结构与口袋（基线块2 的数据源） ---------------- */

.tg__item-foot {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.tg__block-count {
  margin-left: 6px;
  font-size: $font-size-xs;
  font-weight: normal;
  color: $color-text-faint;
}

.tg__block-empty {
  margin: 0;
  font-size: $font-size-xs;
  line-height: 1.7;
  color: $color-text-faint;
}

.tg__note {
  margin: $spacing-md 0 0;
  font-size: $font-size-xs;
  line-height: 1.7;
  color: $color-text-faint;
}

.structs {
  display: flex;
  flex-direction: column;
  gap: $spacing-sm;
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: $spacing-sm $spacing-md;
    border: 1px solid $color-border-soft;
    border-radius: $radius-control;
    background: $color-surface;
  }

  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    align-items: center;
  }

  &__pdb {
    font-family: monospace;
    font-size: $font-size-sm;
    font-weight: $font-weight-medium;
    color: $color-text;
  }

  &__meta {
    margin-left: auto;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__empty {
    margin: 6px 0 0;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__pockets {
    width: 100%;
    margin-top: 8px;
    border-collapse: collapse;
    font-size: $font-size-xs;

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
    }

    tr:last-child td {
      border-bottom: 0;
    }

    .num {
      font-family: monospace;
      text-align: right;
      white-space: nowrap;
    }
  }
}

</style>
