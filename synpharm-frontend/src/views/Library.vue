<template>
  <div class="lb">
    <Sidebar />
    <main class="lb__main">
      <div class="page">
        <PageHeader :title="activeDataset?.name || '库'" :subtitle="subtitle">
          <template #actions>
            <button type="button" class="btn" :disabled="loading" @click="reload">
              {{ loading ? '加载中…' : '刷新' }}
            </button>
          </template>
        </PageHeader>

        <p v-if="error" class="banner banner--danger">{{ error }}</p>

        <div class="lb__cols">
          <!-- ======================= 左：数据集 ======================= -->
          <aside class="lb__side">
            <div v-for="group in datasetGroups" :key="group.key" class="lb__group">
              <p v-if="group.items.length || group.key !== 'shared'" class="lb__group-label">
                {{ group.label }}
                <em v-if="group.hint">{{ group.hint }}</em>
              </p>

              <button
                v-for="ds in group.items"
                :key="ds.id"
                type="button"
                class="lb__ds"
                :class="{ 'lb__ds--active': ds.id === activeDatasetId }"
                @click="selectDataset(ds)"
              >
                <span class="lb__ds-icon" :class="`lb__ds-icon--${ds.itemType.toLowerCase()}`">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"
                       stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path v-for="(d, i) in DS_ICON[ds.itemType] || DS_ICON.TARGET" :key="i" :d="d" />
                  </svg>
                </span>
                <span class="lb__ds-text">
                  <span class="lb__ds-name">{{ ds.name }}</span>
                  <span class="lb__ds-meta">
                    {{ ITEM_TYPE_LABEL[ds.itemType] || ds.itemType }} · {{ ds.itemCount }} 条
                  </span>
                </span>
                <span v-if="ds.isOwner" class="lb__ds-badge">我的</span>
                <span v-else-if="!ds.itemCount" class="lb__ds-badge lb__ds-badge--soon"
                      title="公共库为空通常是初始化种子数据未导入，管理员可执行 synpharm-deploy/scripts/apply-seeds.sh">暂无数据</span>
              </button>

              <p v-if="group.key !== 'shared' && !group.items.length" class="lb__group-empty">
                {{ group.empty }}
              </p>
            </div>

            <div class="lb__new">
              <button type="button" class="lb__new-btn" disabled title="私有库写入在下一阶段开放">
                <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
                     stroke-width="2" stroke-linecap="round" aria-hidden="true">
                  <path d="M12 5v14M5 12h14" />
                </svg>
                新建私有库
              </button>
              <p class="lb__new-hint">下一阶段：建库 → 导入 → 自定义标签</p>
            </div>
          </aside>

          <!-- ======================= 右：列表 ======================= -->
          <section class="lb__body">
            <!-- 工具栏：搜索 + 筛选开关，只占一行 -->
            <div class="lb__bar">
              <div class="lb__search">
                <svg class="lb__search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                  <circle cx="11" cy="11" r="7" />
                  <path d="M16.5 16.5L21 21" />
                </svg>
                <input
                  v-model.trim="keyword"
                  type="search"
                  class="lb__search-input"
                  :placeholder="searchPlaceholder"
                  @keyup.enter="applySearch"
                />
              </div>

              <button
                type="button"
                class="lb__filter-btn"
                :class="{ 'lb__filter-btn--on': filterOpen || selectedTags.length > 0 }"
                :disabled="!availableTags.length"
                @click="filterOpen = !filterOpen"
              >
                <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M4 6h16M7 12h10M10 18h4" />
                </svg>
                筛选
                <em v-if="selectedTags.length" class="lb__filter-count">{{ selectedTags.length }}</em>
              </button>

              <span class="lb__count">{{ total }} 条</span>
            </div>

            <!-- 已选条件：始终可见，可单个移除 -->
            <div v-if="selectedTags.length" class="lb__chosen">
              <span class="lb__chosen-label">已选</span>
              <button
                v-for="code in selectedTags"
                :key="code"
                type="button"
                class="lb__chosen-chip"
                @click="toggleTag(code)"
              >
                {{ tagLabel(code) }}
                <svg viewBox="0 0 12 12" width="8" height="8" fill="none" stroke="currentColor"
                     stroke-width="2" stroke-linecap="round" aria-hidden="true">
                  <path d="M2 2l8 8M10 2l-8 8" />
                </svg>
              </button>
              <button type="button" class="lb__chosen-clear" @click="clearFilters">全部清除</button>
            </div>

            <!-- 折叠筛选面板：只显示有货的标签，按组收起 -->
            <div v-if="filterOpen" class="lb__panel">
              <div class="lb__panel-head">
                <span class="lb__panel-title">按标签筛选</span>
                <label class="lb__panel-mode">
                  <input v-model="tagMode" type="checkbox" :checked="tagMode === 'AND'" @change="onModeChange" />
                  同时满足全部标签
                </label>
              </div>
              <div v-for="group in tagGroupsInPanel" :key="group.key" class="lb__panel-group">
                <p class="lb__panel-group-label">
                  {{ group.label }}
                  <em v-if="group.readonly" title="按实际数据推导，不允许手改">只读</em>
                </p>
                <div class="lb__panel-tags">
                  <button
                    v-for="tag in group.tags"
                    :key="tag.code"
                    type="button"
                    class="lb__tag"
                    :class="{ 'lb__tag--on': selectedTags.includes(tag.code) }"
                    @click="toggleTag(tag.code)"
                  >
                    {{ tag.label }}
                    <em class="lb__tag-count">{{ tag.usageCount }}</em>
                  </button>
                </div>
              </div>
              <p v-if="!tagGroupsInPanel.length" class="lb__panel-empty">
                这个数据集还没有任何标签。导入数据后系统会自动打上来源与完备度标签。
              </p>
            </div>

            <!-- 条目列表：主区域；宽屏双列，数据少时也不会留一大片空白 -->
            <div v-if="items.length" class="lb__list">
              <button
                v-for="item in items"
                :key="item.id"
                type="button"
                class="lb__item"
                :class="[
                  { 'lb__item--active': item.id === selectedItemId },
                  `lb__item--${qualityMeta(item.quality.level).tone}`
                ]"
                @click="selectItem(item)"
              >
                <span class="lb__item-rail" aria-hidden="true" />
                <span class="lb__item-body">
                  <span class="lb__item-head">
                    <span class="lb__item-title">{{ item.title }}</span>
                    <span
                      class="lb__item-level"
                      :class="`lb__item-level--${qualityMeta(item.quality.level).tone}`"
                    >
                      {{ qualityMeta(item.quality.level).label }}
                    </span>
                  </span>
                  <span v-if="item.subtitle" class="lb__item-sub">{{ item.subtitle }}</span>
                  <span class="lb__item-facts">
                    <span v-for="fact in itemFacts(item)" :key="fact" class="lb__fact">{{ fact }}</span>
                  </span>
                  <span class="lb__item-tags">
                    <span v-for="chip in visibleChips(item)" :key="chip.code" class="lb__chip">
                      {{ chip.label }}
                    </span>
                    <span v-if="hiddenChipCount(item) > 0" class="lb__chip lb__chip--more">
                      +{{ hiddenChipCount(item) }}
                    </span>
                  </span>
                </span>
                <svg class="lb__item-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M9 6l6 6-6 6" />
                </svg>
              </button>
            </div>

            <EmptyState
              v-else
              :title="loading ? '加载中…' : emptyTitle"
              :description="emptyDescription"
            />

            <!-- 库概览：数据几条时首屏下半部分不至于纯白，也顺手回答「这个库能干什么」 -->
            <section v-if="items.length && !hasFilters" class="lb__overview">
              <div class="lb__ov-block">
                <p class="lb__ov-label">{{ overviewLabel }}</p>
                <div class="lb__ov-rows">
                  <div v-for="row in overviewBuckets" :key="row.key" class="lb__ov-row">
                    <span class="lb__ov-dot" :class="`lb__ov-dot--${row.tone}`" />
                    <span class="lb__ov-name">{{ row.label }}</span>
                    <span class="lb__ov-bar">
                      <i :style="{ width: `${row.pct}%` }" :class="`lb__ov-fill--${row.tone}`" />
                    </span>
                    <span class="lb__ov-num">{{ row.count }}</span>
                  </div>
                </div>
              </div>
              <div class="lb__ov-block">
                <p class="lb__ov-label">可以做什么</p>
                <div class="lb__ov-capabilities">
                  <div v-for="cap in capabilitySummary" :key="cap.label" class="lb__ov-cap">
                    <span class="lb__ov-cap-num">{{ cap.count }}</span>
                    <span class="lb__ov-cap-label">{{ cap.label }}</span>
                  </div>
                </div>
              </div>
            </section>

            <div v-if="total > pageSize" class="lb__pager">
              <button type="button" class="btn btn--sm" :disabled="page <= 1" @click="goPage(page - 1)">
                上一页
              </button>
              <span class="lb__pager-info">{{ page }} / {{ totalPages }}</span>
              <button
                type="button"
                class="btn btn--sm"
                :disabled="page >= totalPages"
                @click="goPage(page + 1)"
              >
                下一页
              </button>
            </div>
          </section>
        </div>
      </div>
    </main>

    <!-- ======================= 详情抽屉 ======================= -->
    <Transition name="lb-drawer">
      <div v-if="selectedItem" class="lb__drawer" role="dialog" aria-modal="true">
        <div class="lb__drawer-mask" @click="selectedItemId = 0" />
        <aside class="lb__drawer-panel">
          <header class="lb__drawer-head">
            <div class="lb__drawer-titles">
              <h2 class="lb__drawer-title">{{ selectedItem.title }}</h2>
              <p v-if="selectedItem.subtitle" class="lb__drawer-sub">{{ selectedItem.subtitle }}</p>
            </div>
            <button type="button" class="lb__drawer-close" aria-label="关闭" @click="selectedItemId = 0">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
                   stroke-width="2" stroke-linecap="round" aria-hidden="true">
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </header>

          <div class="lb__drawer-scroll">
            <div class="lb__verdict" :class="`lb__verdict--${qualityMeta(selectedItem.quality.level).tone}`">
              <span class="lb__verdict-level">{{ qualityMeta(selectedItem.quality.level).label }}</span>
              <span class="lb__verdict-detail">
                {{
                  selectedItem.quality.missing.length
                    ? selectedItem.quality.missing.join(' · ')
                    : verdictOkText
                }}
              </span>
            </div>

            <div v-if="selectedItem.readiness.length" class="lb__ready">
              <span class="lb__ready-label">可以做</span>
              <span v-for="r in selectedItem.readiness" :key="r" class="lb__ready-chip">{{ r }}</span>
            </div>

            <section class="lb__section">
              <h3 class="lb__section-title">基本信息</h3>
              <dl class="lb__dl">
                <template v-for="row in payloadRows" :key="row.label">
                  <dt>{{ row.label }}</dt>
                  <dd>{{ row.value }}</dd>
                </template>
              </dl>
            </section>

            <section v-if="allChips.length" class="lb__section">
              <h3 class="lb__section-title">标签</h3>
              <div class="lb__chip-group" v-for="group in chipsByGroup" :key="group.key">
                <p class="lb__chip-group-label">{{ group.label }}</p>
                <div class="lb__chip-row">
                  <span v-for="chip in group.chips" :key="chip.code" class="lb__chip">{{ chip.label }}</span>
                </div>
              </div>
            </section>

            <p v-if="selectedItem.derivedFrom" class="lb__derived">
              复制自 <b>{{ selectedItem.derivedFrom.datasetName || '其他库' }}</b>
            </p>
          </div>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import {
  ITEM_TYPE_LABEL,
  TAG_GROUP_LABEL,
  TAG_GROUP_READONLY_HINT,
  libraryApi,
  qualityMeta,
  type LibraryDataset,
  type LibraryItem,
  type LibraryTag,
  type LibraryTagChip
} from '@/api/library'
import type { ApiError } from '@/utils/request'

/**
 * 库页面（`/library`）。
 *
 * 上一版把 45 个标签全展开铺在条目上方，标签面板占了半屏、条目被挤到折叠线下，
 * 右侧还有一根常驻却空着的详情栏 —— 用户是来看条目的，却被筛选项堵住了门。
 *
 * 这一版的四条改动：
 *
 * 1. **标签面板折叠且只显示有货的**：45 个内置标签里，公共靶点库实际只用到 8 类。
 *    计数为 0 的标签不显示（`usageCount` 由后端按数据集算），需要时点「筛选」展开。
 * 2. **三栏改两栏 + 详情抽屉**：详情按需滑出，把 320px 常驻空栏还给列表。
 * 3. **条目卡片带完备度色轨**：一眼分辨「能立项」与「缺结构」，不用逐字读。
 * 4. **数据集卡片带类型图标与拥有标记**：公有/我的/共享 三区一眼可分。
 */
const route = useRoute()
const router = useRouter()

const loading = ref(false)
const error = ref('')

const datasets = ref<LibraryDataset[]>([])
/** 全量标签字典（含计数为 0 的）；面板只渲染 availableTags 里有的 */
const tags = ref<LibraryTag[]>([])
const items = ref<LibraryItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const activeDatasetId = ref(0)
const activeDataset = computed(() => datasets.value.find((d) => d.id === activeDatasetId.value) ?? null)

const keyword = ref('')
const selectedTags = ref<string[]>([])
const tagMode = ref<'AND' | 'OR'>('AND')
const filterOpen = ref(false)

const selectedItemId = ref(0)
const selectedItem = computed(() => items.value.find((i) => i.id === selectedItemId.value) ?? null)

const hasFilters = computed(() => !!keyword.value || selectedTags.value.length > 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

/** 数据集已接入但一条都没有 → 说清「为什么空」，别让人以为是自己搜错了 */
const emptyTitle = computed(() => (hasFilters.value ? '没有匹配的条目' : '这个数据集还是空的'))

const emptyDescription = computed(() => {
  if (hasFilters.value) return '换个关键词，或减少标签条件（多标签默认要求同时命中）。'
  const type = activeDataset.value?.itemType
  if (activeDataset.value?.visibility === 'PUBLIC') {
    return '该数据集暂无条目。若为平台公共库，通常是初始化种子数据未导入，请联系管理员。'
  }
  if (type === 'MOLECULE' || type === 'SEQUENCE') {
    return '分子与抗体条目的写入接口在下一阶段开放，届时可在此检索结构、分子量与序列。'
  }
  return '数据集已建好但还没有接入条目。'
})

const subtitle = computed(() => {
  const ds = activeDataset.value
  if (!ds) return '公有数据集由平台维护，私有库归你所有'
  if (ds.visibility === 'PUBLIC') {
    return `${ITEM_TYPE_LABEL[ds.itemType] || ds.itemType}参考数据 · 平台维护 · 全体可读`
  }
  return `${ITEM_TYPE_LABEL[ds.itemType] || ds.itemType} · ${ds.isOwner ? '归你所有' : '他人共享'}`
})

/** 占位文字按数据集类型换：分子库里提示「搜基因名」是错的 */
const searchPlaceholder = computed(() => {
  const t = activeDataset.value?.itemType
  if (t === 'MOLECULE') return '搜索通用名、分子式、InChIKey…'
  if (t === 'SEQUENCE') return '搜索抗体名、靶点基因、PDB 编号…'
  return '搜索名称、基因名、UniProt、中文名…'
})

/** 数据集类型图标（内联 SVG，不用 emoji —— 跨平台字形不一致） */
const DS_ICON: Record<string, string[]> = {
  TARGET: ['M12 3v4M12 17v4M3 12h4M17 12h4', 'M12 8a4 4 0 100 8 4 4 0 000-8z'],
  MOLECULE: ['M12 3l7 4v10l-7 4-7-4V7z', 'M12 3v18M5 7l14 10M19 7L5 17'],
  SEQUENCE: ['M4 7h16M4 12h16M4 17h16', 'M8 4v16M16 4v16']
}

/* ------------------------------ 数据集分区 ------------------------------ */

const datasetGroups = computed(() => [
  {
    key: 'public',
    label: '公有数据集',
    hint: '平台维护',
    empty: '还没有公共数据集',
    items: datasets.value.filter((d) => d.visibility === 'PUBLIC')
  },
  {
    key: 'private',
    label: '我的数据集',
    hint: '归我所有',
    empty: '还没有自己的库',
    items: datasets.value.filter((d) => d.visibility === 'PRIVATE' && d.isOwner)
  },
  {
    key: 'shared',
    label: '共享给我',
    hint: '他人授权',
    empty: '',
    items: datasets.value.filter((d) => !d.isOwner && d.visibility === 'PRIVATE')
  }
])

/* ------------------------------ 标签面板 ------------------------------ */

/** 有货的标签（usageCount > 0）；展开面板只用这一份 */
const availableTags = computed(() => tags.value.filter((t) => (t.usageCount ?? 0) > 0))

const tagGroupsInPanel = computed(() => {
  const byGroup = new Map<string, LibraryTag[]>()
  for (const t of availableTags.value) {
    if (activeDataset.value && !tagSupports(t, activeDataset.value.itemType)) continue
    const list = byGroup.get(t.tagGroup) ?? []
    list.push(t)
    byGroup.set(t.tagGroup, list)
  }
  const ORDER = Object.keys(TAG_GROUP_LABEL)
  return [...byGroup.entries()]
    .map(([key, list]) => ({
      key,
      label: TAG_GROUP_LABEL[key] ?? key,
      readonly: !!TAG_GROUP_READONLY_HINT[key],
      tags: list.sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0))
    }))
    .sort((a, b) => ORDER.indexOf(a.key) - ORDER.indexOf(b.key))
})

/**
 * 标签是否适用于该条目类型。
 *
 * 分子/序列没有「靶点家族」「疾病领域」的概念，显示出来只会让人误以为
 * 「这个库缺这些维度」。后端已按 itemType 过滤过一轮，这里兜住切数据集后的残留。
 */
function tagSupports(tag: LibraryTag, itemType: string): boolean {
  if (itemType === 'TARGET') return true
  return !['FAMILY', 'DISEASE'].includes(tag.tagGroup)
}

function tagLabel(code: string): string {
  return tags.value.find((t) => t.code === code)?.label ?? code
}

function toggleTag(code: string) {
  const i = selectedTags.value.indexOf(code)
  if (i >= 0) selectedTags.value.splice(i, 1)
  else selectedTags.value.push(code)
  page.value = 1
  void loadItems()
}

function onModeChange() {
  tagMode.value = tagMode.value === 'AND' ? 'OR' : 'AND'
  if (selectedTags.value.length) {
    page.value = 1
    void loadItems()
  }
}

/* ------------------------------ 数据加载 ------------------------------ */

function toMessage(e: unknown): string {
  const apiError = e as ApiError
  return apiError?.message || '请求失败'
}

async function loadDatasets() {
  datasets.value = (await libraryApi.listDatasets()) ?? []
}

/** 带 datasetId 拿计数：面板只显示有货的标签 */
async function loadTags() {
  if (!activeDatasetId.value) {
    tags.value = []
    return
  }
  tags.value =
    (await libraryApi.listTags(activeDataset.value?.itemType, activeDatasetId.value)) ?? []
}

async function loadItems() {
  if (!activeDatasetId.value) {
    items.value = []
    total.value = 0
    return
  }
  loading.value = true
  error.value = ''
  try {
    const res = await libraryApi.queryItems({
      datasetId: activeDatasetId.value,
      itemType: activeDataset.value?.itemType,
      q: keyword.value || undefined,
      tags: selectedTags.value.length ? selectedTags.value : undefined,
      tagMode: tagMode.value,
      page: page.value,
      pageSize: pageSize.value
    })
    items.value = res?.list ?? []
    total.value = res?.total ?? 0
    if (selectedItemId.value && !items.value.some((i) => i.id === selectedItemId.value)) {
      selectedItemId.value = 0
    }
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    loading.value = false
  }
}

async function reload() {
  try {
    loading.value = true
    error.value = ''
    await loadDatasets()
    await loadTags()
    await loadItems()
  } catch (e) {
    error.value = toMessage(e)
  } finally {
    loading.value = false
  }
}

async function selectDataset(ds: LibraryDataset) {
  if (activeDatasetId.value === ds.id) return
  activeDatasetId.value = ds.id
  selectedTags.value = []
  filterOpen.value = false
  page.value = 1
  selectedItemId.value = 0
  void router.replace({ query: { ...route.query, dataset: String(ds.id) } })
  await loadTags()
  await loadItems()
}

function applySearch() {
  page.value = 1
  void loadItems()
}

function clearFilters() {
  keyword.value = ''
  selectedTags.value = []
  page.value = 1
  void loadItems()
}

function goPage(target: number) {
  page.value = target
  void loadItems()
}

function selectItem(item: LibraryItem) {
  selectedItemId.value = item.id
}

/* ------------------------------ 展示辅助 ------------------------------ */

function allChipsOf(item: LibraryItem): LibraryTagChip[] {
  return Object.values(item.tags ?? {}).flat()
}

/** 卡片上最多显示 3 个标签，其余折叠成 +N —— 一排 8 个标签会让卡片失去可扫视性 */
const CHIP_LIMIT = 3

/** 卡片右上角已经写了完备度状态，标签里同名的那个是重复信息，不占卡片位置 */
function cardChips(item: LibraryItem): LibraryTagChip[] {
  const badge = qualityMeta(item.quality?.level).label
  return allChipsOf(item).filter((c) => !(c.tagGroup === 'QUALITY' && c.label === badge))
}
function visibleChips(item: LibraryItem): LibraryTagChip[] {
  return cardChips(item).slice(0, CHIP_LIMIT)
}
function hiddenChipCount(item: LibraryItem): number {
  return Math.max(0, cardChips(item).length - CHIP_LIMIT)
}

const MOLECULE_KIND_LABEL: Record<string, string> = {
  SMALL_MOLECULE: '小分子',
  ANTIBODY: '抗体',
  PEPTIDE: '多肽',
  OLIGO: '寡核苷酸'
}

/** 卡片上的关键事实：只挑能决定「能不能用」的，不放描述性字段 */
function itemFacts(item: LibraryItem): string[] {
  const p = item.payload ?? {}
  const facts: string[] = []
  // UniProt 号已经在副标题里出现过，卡片上不再重复
  if (p.variantCode) facts.push(String(p.variantCode))
  if (p.sequenceLength) facts.push(`${p.sequenceLength} aa`)
  // 「1 个结构」在只有 AlphaFold 模型的靶点上会被误读成有实验结构，分开说
  if (p.structureCount !== undefined) {
    const exp = Number(p.experimentalStructureCount ?? 0)
    facts.push(exp > 0 ? `${exp} 个实验结构` : p.structureCount ? '仅 AlphaFold 模型' : '无结构')
  }
  // 分子量取 1 位、LogP 取 2 位：卡片上「MW 393.443」的精度没有意义，反而挤掉别的信息
  if (p.mw) facts.push(`MW ${Number(p.mw).toFixed(1)}`)
  if (p.logp) facts.push(`LogP ${Number(p.logp).toFixed(2)}`)
  if (p.moleculeKind && item.itemType !== 'SEQUENCE') {
    facts.push(MOLECULE_KIND_LABEL[String(p.moleculeKind)] ?? String(p.moleculeKind))
  }
  return facts
}

/* ------------------------------ 概览统计 ------------------------------ */

/**
 * 概览分布：按当前数据集的类型换维度。
 *
 * 靶点看「结构完备度」，分子看「分子量落在哪个区间」，抗体看「序列长度」——
 * 三类数据各有各的瓶颈，用同一个维度硬套只会得到三行 0。
 * 只统计当前页：跨页精确统计要后端加聚合接口，而概览的用途是一眼看出这批数据的形状。
 */
const overviewLabel = computed(() => {
  const t = activeDataset.value?.itemType
  const base = t === 'MOLECULE' ? '分子量分布' : t === 'SEQUENCE' ? '序列长度分布' : '完备度分布'
  // 分页时概览只统计当前页，标签必须写明，否则「24 条」配三行加起来 20 会让人以为算错了
  return total.value > items.value.length ? `${base} · 本页 ${items.value.length} 条` : base
})

const overviewBuckets = computed(() => {
  const type = activeDataset.value?.itemType
  const all = items.value
  const totalCount = all.length || 1

  let buckets: { key: string; label: string; tone: 'success' | 'warning' | 'neutral'; hit: (i: LibraryItem) => boolean }[]

  if (type === 'MOLECULE') {
    buckets = [
      { key: 'lite', label: 'MW ≤ 200', tone: 'warning', hit: (i) => Number(i.payload?.mw ?? 0) <= 200 },
      {
        key: 'drug',
        label: 'MW 200–450',
        tone: 'success',
        hit: (i) => Number(i.payload?.mw ?? 0) > 200 && Number(i.payload?.mw ?? 0) <= 450
      },
      { key: 'heavy', label: 'MW > 450', tone: 'neutral', hit: (i) => Number(i.payload?.mw ?? 0) > 450 }
    ]
  } else if (type === 'SEQUENCE') {
    buckets = [
      { key: 'short', label: '≤ 250 aa', tone: 'warning', hit: (i) => Number(i.payload?.sequenceLength ?? 0) <= 250 },
      {
        key: 'fab',
        label: '250–450 aa',
        tone: 'success',
        hit: (i) => Number(i.payload?.sequenceLength ?? 0) > 250 && Number(i.payload?.sequenceLength ?? 0) <= 450
      },
      { key: 'long', label: '> 450 aa', tone: 'neutral', hit: (i) => Number(i.payload?.sequenceLength ?? 0) > 450 }
    ]
  } else {
    buckets = [
      { key: 'EXPERIMENTAL_STRUCTURE', label: '有实验结构', tone: 'success', hit: (i) => i.quality?.level === 'EXPERIMENTAL_STRUCTURE' },
      { key: 'PREDICTED_ONLY', label: '仅预测结构', tone: 'warning', hit: (i) => i.quality?.level === 'PREDICTED_ONLY' },
      { key: 'NO_STRUCTURE', label: '无结构', tone: 'neutral', hit: (i) => i.quality?.level === 'NO_STRUCTURE' }
    ]
  }

  return buckets.map((b) => {
    const count = all.filter(b.hit).length
    return { key: b.key, label: b.label, tone: b.tone, count, pct: Math.round((count / totalCount) * 100) }
  })
})

/** 可用能力汇总：直接回答「这个库能拿去做什么」 */
const capabilitySummary = computed(() => {
  const all = items.value.flatMap((i) => i.readiness ?? [])
  const counts = new Map<string, number>()
  for (const r of all) counts.set(r, (counts.get(r) ?? 0) + 1)
  return [...counts.entries()]
    .map(([label, count]) => ({ label, count }))
    .sort((a, b) => b.count - a.count)
})

const allChips = computed(() => (selectedItem.value ? allChipsOf(selectedItem.value) : []))

/** 「齐备」的说法随条目类型变：分子没有序列这回事，抗体不谈结构完备度 */
const verdictOkText = computed(() => {
  const t = selectedItem.value?.itemType
  if (t === 'MOLECULE') return '结构已确证，性质齐备'
  if (t === 'SEQUENCE') return '重链与轻链序列完整'
  return '结构与序列齐备'
})

const chipsByGroup = computed(() => {
  const ORDER = Object.keys(TAG_GROUP_LABEL)
  return [...(selectedItem.value ? Object.entries(selectedItem.value.tags ?? {}) : [])]
    .map(([key, chips]) => ({ key, label: TAG_GROUP_LABEL[key] ?? key, chips }))
    .sort((a, b) => ORDER.indexOf(a.key) - ORDER.indexOf(b.key))
})

/** payload 转「标签 - 值」行，跳过空值避免一堆「—」 */
const payloadRows = computed(() => {
  const p = selectedItem.value?.payload ?? {}
  const LABELS: Record<string, string> = {
    uniprotId: 'UniProt',
    geneName: '基因名',
    name: '英文名',
    chineseName: '中文名',
    organism: '物种',
    targetType: '靶点类型',
    family: '蛋白家族',
    sequenceLength: '序列长度',
    variantCode: '变体',
    structureCount: '结构数',
    experimentalStructureCount: '实验结构数',
    pdbIds: 'PDB ID',
    moleculeKind: '分子类型',
    smiles: 'SMILES',
    inchikey: 'InChIKey',
    mw: '分子量',
    logp: 'LogP',
    tpsa: 'TPSA',
    hbd: '氢键供体',
    hba: '氢键受体',
    rotatable: '可旋转键',
    qed: 'QED',
    painsHits: 'PAINS 命中',
    sequenceSource: '序列来源'
  }
  // 枚举值不直接怼给用户看：SMALL_MOLECULE / PDB_DERIVED 是内部词汇
  const ENUM_LABEL: Record<string, Record<string, string>> = {
    moleculeKind: MOLECULE_KIND_LABEL,
    sequenceSource: { PDB_DERIVED: 'PDB 晶体结构链', PUBLIC: '公开序列', ILLUSTRATIVE: '示意序列（非真实）', IMGT: 'IMGT 编号' }
  }
  return Object.entries(LABELS)
    .filter(([k]) => {
      const v = p[k]
      return v !== null && v !== undefined && v !== '' && !(Array.isArray(v) && v.length === 0)
    })
    .map(([k, label]) => ({
      label,
      value: Array.isArray(p[k])
        ? (p[k] as unknown[]).join(', ')
        : (ENUM_LABEL[k]?.[String(p[k])] ?? String(p[k]))
    }))
})

onMounted(async () => {
  await loadDatasets()

  const datasetParam = Number(route.query.dataset)
  if (datasetParam && datasets.value.some((d) => d.id === datasetParam)) {
    activeDatasetId.value = datasetParam
  } else if (datasets.value.length) {
    // 默认打开第一个公共数据集，避免首屏空白
    activeDatasetId.value = datasets.value[0].id
  }

  await loadTags()
  await loadItems()

  const itemParam = Number(route.query.item)
  if (itemParam) {
    const found = items.value.find((i) => i.id === itemParam)
    if (found) selectedItemId.value = found.id
  }
})
</script>

<style lang="scss" scoped>
.lb {
  display: flex;
  min-height: 100vh;
  background: $color-canvas;

  &__main {
    flex: 1;
    min-width: 0;
    padding: $spacing-lg;
  }

  &__cols {
    display: grid;
    grid-template-columns: 236px minmax(0, 1fr);
    gap: $spacing-md;
    align-items: start;

    @media (max-width: 860px) {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  /* ============================ 数据集侧栏 ============================ */
  &__side {
    display: flex;
    flex-direction: column;
    gap: $spacing-lg;
    padding: $spacing-md $spacing-sm;
    border: 1px solid $color-border;
    border-radius: $radius-card;
    background: $color-surface;
  }

  &__group {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__group-label {
    display: flex;
    align-items: baseline;
    gap: 5px;
    margin: 0 0 6px;
    padding: 0 8px;
    font-size: $font-size-xs;
    font-weight: $font-weight-semibold;
    color: $color-text-soft;
  }

  &__group-label em {
    font-style: normal;
    font-weight: $font-weight-normal;
    color: $color-text-faint;
  }

  &__group-empty {
    margin: 0;
    padding: 2px 8px 4px;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__ds {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 8px;
    border: 1px solid transparent;
    border-radius: $radius-control;
    background: none;
    font-family: inherit;
    text-align: left;
    cursor: pointer;
    transition: background $transition-fast, border-color $transition-fast;

    &:hover {
      background: $color-surface-alt;
    }

    &--active {
      border-color: rgba($accent-color, 0.45);
      background: $color-brand-softer;
    }
  }

  &__ds-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 auto;
    width: 28px;
    height: 28px;
    border-radius: $border-radius-sm;
    background: $color-surface-sunken;
    color: $color-text-faint;

    svg {
      width: 15px;
      height: 15px;
    }

    &--target {
      background: rgba(13, 148, 136, 0.12);
      color: $accent-dark;
    }

    &--molecule {
      background: rgba(8, 145, 178, 0.12);
      color: $info-color;
    }

    &--sequence {
      background: rgba(217, 119, 6, 0.12);
      color: $warning-color;
    }
  }

  &__ds-text {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
    flex: 1;
  }

  &__ds-name {
    font-size: $font-size-sm;
    color: $color-text;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__ds-meta {
    font-size: 10px;
    color: $color-text-faint;
  }

  &__ds-badge {
    flex: 0 0 auto;
    padding: 0 5px;
    border-radius: $radius-pill;
    background: rgba($accent-color, 0.14);
    font-size: 10px;
    color: $accent-dark;

    &--soon {
      background: $color-surface-sunken;
      color: $color-text-faint;
    }
  }

  &__new {
    padding-top: $spacing-sm;
    border-top: 1px dashed $color-border;
  }

  &__new-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 6px 10px;
    border: 1px dashed $color-border;
    border-radius: $radius-control;
    background: none;
    font-family: inherit;
    font-size: $font-size-xs;
    color: $color-text-faint;
    cursor: not-allowed;
  }

  &__new-hint {
    margin: 6px 0 0;
    font-size: 10px;
    line-height: 1.5;
    color: $color-text-faint;
  }

  /* ============================ 工具条 ============================ */
  &__body {
    display: flex;
    flex-direction: column;
    gap: $spacing-sm;
    min-width: 0;
  }

  &__bar {
    display: flex;
    align-items: center;
    gap: $spacing-sm;
  }

  &__search {
    position: relative;
    flex: 1;
    min-width: 0;
  }

  &__search-icon {
    position: absolute;
    left: 10px;
    top: 50%;
    width: 14px;
    height: 14px;
    transform: translateY(-50%);
    color: $color-text-faint;
    pointer-events: none;
  }

  &__search-input {
    width: 100%;
    height: 34px;
    padding: 0 10px 0 30px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    background: $color-surface;
    font-family: inherit;
    font-size: $font-size-sm;
    color: $color-text;

    &::placeholder {
      color: $color-text-faint;
    }

    &:focus {
      outline: none;
      border-color: $color-brand;
      box-shadow: $shadow-focus;
    }
  }

  &__filter-btn {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    height: 34px;
    padding: 0 12px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    background: $color-surface;
    font-family: inherit;
    font-size: $font-size-sm;
    color: $color-text-soft;
    cursor: pointer;
    white-space: nowrap;

    &:hover:not(:disabled) {
      border-color: rgba($accent-color, 0.5);
    }

    &--on {
      border-color: $color-brand;
      color: $color-brand-strong;
      background: $color-brand-softer;
    }

    &:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
  }

  &__filter-count {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 15px;
    height: 15px;
    padding: 0 4px;
    border-radius: $radius-pill;
    background: $color-brand;
    font-style: normal;
    font-size: 10px;
    color: #fff;
  }

  &__count {
    flex: 0 0 auto;
    font-size: $font-size-xs;
    color: $color-text-faint;
    font-variant-numeric: tabular-nums;
  }

  /* ============================ 已选条件 ============================ */
  &__chosen {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px;
  }

  &__chosen-label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__chosen-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 7px;
    border: 1px solid rgba($accent-color, 0.4);
    border-radius: $radius-pill;
    background: $color-brand-softer;
    font-family: inherit;
    font-size: $font-size-xs;
    color: $color-brand-strong;
    cursor: pointer;

    &:hover {
      border-color: $error-color;
      color: $error-color;
    }
  }

  &__chosen-clear {
    border: 0;
    background: none;
    padding: 0 2px;
    font-family: inherit;
    font-size: $font-size-xs;
    color: $color-text-faint;
    text-decoration: underline;
    cursor: pointer;
  }

  /* ============================ 筛选面板 ============================ */
  &__panel {
    display: flex;
    flex-direction: column;
    gap: $spacing-sm;
    padding: $spacing-md;
    border: 1px solid $color-border;
    border-radius: $radius-card;
    background: $color-surface;
    animation: sp-fade-up 0.18s $ease-out both;
  }

  &__panel-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: $spacing-sm;
    padding-bottom: $spacing-sm;
    border-bottom: 1px solid $color-border-soft;
  }

  &__panel-title {
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__panel-mode {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: $font-size-xs;
    color: $color-text-soft;
    cursor: pointer;
  }

  &__panel-group {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__panel-group-label {
    display: flex;
    align-items: center;
    gap: 5px;
    margin: 0;
    font-size: 10px;
    color: $color-text-faint;
  }

  &__panel-group-label em {
    padding: 0 5px;
    border-radius: $radius-pill;
    background: $color-surface-sunken;
    font-style: normal;
    cursor: help;
  }

  &__panel-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  &__panel-empty {
    margin: 0;
    padding: $spacing-sm;
    font-size: $font-size-xs;
    line-height: 1.6;
    color: $color-text-faint;
  }

  &__tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 9px;
    border: 1px solid $color-border;
    border-radius: $radius-pill;
    background: $color-surface;
    font-family: inherit;
    font-size: $font-size-xs;
    color: $color-text-soft;
    cursor: pointer;
    transition: border-color $transition-fast, background $transition-fast, color $transition-fast;

    &:hover {
      border-color: rgba($accent-color, 0.5);
      color: $color-brand-strong;
    }

    &--on {
      border-color: $color-brand;
      background: $color-brand;
      color: #fff;

      .lb__tag-count {
        color: rgba(255, 255, 255, 0.75);
      }
    }
  }

  &__tag-count {
    font-size: 9px;
    font-style: normal;
    color: $color-text-faint;
    font-variant-numeric: tabular-nums;
  }

  /* ============================ 条目列表 ============================ */
  &__list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
    gap: 8px;

    @media (max-width: 1100px) {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  &__item {
    position: relative;
    display: flex;
    align-items: center;
    gap: $spacing-sm;
    padding: 12px 12px 12px 16px;
    border: 1px solid $color-border;
    border-radius: $radius-card;
    background: $color-surface;
    font-family: inherit;
    text-align: left;
    cursor: pointer;
    overflow: hidden;
    transition: border-color $transition-fast, box-shadow $transition-fast, transform $transition-fast;

    &:hover {
      border-color: rgba($accent-color, 0.45);
      box-shadow: $shadow-card;
    }

    &--active {
      border-color: $color-brand;
      box-shadow: $shadow-focus;
    }
  }

  // 左侧完备度色轨：一眼分辨「能立项」与「缺结构」
  &__item-rail {
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: $text-light;
  }

  &__item--success &__item-rail {
    background: $success-color;
  }

  &__item--warning &__item-rail {
    background: $warning-color;
  }

  &__item--danger &__item-rail {
    background: $error-color;
  }

  &__item-body {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
    flex: 1;
  }

  &__item-head {
    display: flex;
    align-items: center;
    gap: $spacing-sm;
  }

  &__item-title {
    font-size: $font-size-base;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__item-level {
    padding: 0 6px;
    border-radius: $radius-pill;
    font-size: 10px;

    &--success {
      background: rgba($success-color, 0.12);
      color: mix(#000, $success-color, 18%);
    }

    &--warning {
      background: rgba($warning-color, 0.14);
      color: mix(#000, $warning-color, 20%);
    }

    &--danger {
      background: rgba($error-color, 0.12);
      color: mix(#000, $error-color, 12%);
    }

    &--neutral {
      background: $color-surface-sunken;
      color: $color-text-soft;
    }
  }

  &__item-sub {
    font-size: $font-size-xs;
    color: $color-text-soft;
  }

  &__item-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
  }

  &__fact {
    font-size: 10px;
    color: $color-text-faint;
    font-variant-numeric: tabular-nums;

    & + &::before {
      content: '·';
      margin-right: 5px;
      color: $color-border;
    }
  }

  &__item-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 1px;
  }

  &__chip {
    padding: 1px 7px;
    border-radius: $radius-pill;
    background: $color-surface-sunken;
    font-size: 10px;
    color: $color-text-soft;

    &--more {
      background: none;
      color: $color-text-faint;
    }
  }

  &__item-arrow {
    flex: 0 0 auto;
    width: 14px;
    height: 14px;
    color: $text-light;
  }

  &__item:hover &__item-arrow {
    color: $accent-color;
  }

  &__pager {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: $spacing-md;
    padding-top: $spacing-sm;
  }

  &__pager-info {
    font-size: $font-size-xs;
    color: $color-text-faint;
    font-variant-numeric: tabular-nums;
  }

  /* ============================ 库概览 ============================ */
  &__overview {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: $spacing-md;
    margin-top: $spacing-sm;

    @media (max-width: 900px) {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  &__ov-block {
    padding: $spacing-md;
    border: 1px solid $color-border;
    border-radius: $radius-card;
    background: $color-surface;
  }

  &__ov-label {
    margin: 0 0 10px;
    font-size: 10px;
    font-weight: $font-weight-semibold;
    color: $color-text-faint;
    letter-spacing: 0.06em;
  }

  &__ov-rows {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__ov-row {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: $font-size-xs;
  }

  &__ov-dot {
    flex: 0 0 auto;
    width: 6px;
    height: 6px;
    border-radius: 50%;

    &--success {
      background: $success-color;
    }

    &--warning {
      background: $warning-color;
    }

    &--danger {
      background: $error-color;
    }

    &--neutral {
      background: $text-light;
    }
  }

  &__ov-name {
    flex: 0 0 auto;
    width: 76px;
    color: $color-text-soft;
  }

  &__ov-bar {
    flex: 1;
    height: 5px;
    border-radius: $radius-pill;
    background: $color-surface-sunken;
    overflow: hidden;

    i {
      display: block;
      height: 100%;
      border-radius: $radius-pill;
      transition: width 0.35s $ease-out;
    }
  }

  &__ov-fill--success {
    background: $success-color;
  }

  &__ov-fill--warning {
    background: $warning-color;
  }

  &__ov-fill--danger {
    background: $error-color;
  }

  &__ov-fill--neutral {
    background: $text-light;
  }

  &__ov-num {
    flex: 0 0 auto;
    min-width: 18px;
    text-align: right;
    color: $color-text;
    font-variant-numeric: tabular-nums;
  }

  &__ov-capabilities {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__ov-cap {
    display: flex;
    align-items: baseline;
    gap: 5px;
    padding: 6px 11px;
    border-radius: $radius-control;
    background: $color-surface-sunken;
  }

  &__ov-cap-num {
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $accent-dark;
    font-variant-numeric: tabular-nums;
  }

  &__ov-cap-label {
    font-size: $font-size-xs;
    color: $color-text-soft;
  }

  /* ============================ 详情抽屉 ============================ */
  &__drawer {
    position: fixed;
    inset: 0;
    z-index: $z-modal;
  }

  &__drawer-mask {
    position: absolute;
    inset: 0;
    background: rgba(15, 43, 44, 0.24);
    animation: lb-fade 0.18s $ease-out both;
  }

  &__drawer-panel {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    width: 420px;
    max-width: 92vw;
    display: flex;
    flex-direction: column;
    background: $color-surface;
    border-left: 1px solid $color-border;
    box-shadow: $shadow-xl;
  }

  &__drawer-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: $spacing-sm;
    padding: $spacing-md;
    border-bottom: 1px solid $color-border;
  }

  &__drawer-titles {
    min-width: 0;
  }

  &__drawer-title {
    margin: 0;
    font-size: $font-size-xl;
    font-weight: $font-weight-semibold;
    color: $color-text;
  }

  &__drawer-sub {
    margin: 3px 0 0;
    font-size: $font-size-xs;
    color: $color-text-soft;
  }

  &__drawer-close {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: 0 0 auto;
    width: 28px;
    height: 28px;
    border: 1px solid $color-border;
    border-radius: $radius-control;
    background: $color-surface;
    color: $color-text-faint;
    cursor: pointer;

    &:hover {
      color: $color-text;
      border-color: $text-light;
    }
  }

  &__drawer-scroll {
    flex: 1;
    overflow-y: auto;
    padding: $spacing-md;
    display: flex;
    flex-direction: column;
    gap: $spacing-md;
  }

  &__verdict {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 12px;
    border-radius: $radius-control;
    border-left: 3px solid currentColor;

    &--success {
      background: rgba($success-color, 0.08);
      color: mix(#000, $success-color, 18%);
    }

    &--warning {
      background: rgba($warning-color, 0.1);
      color: mix(#000, $warning-color, 20%);
    }

    &--danger {
      background: rgba($error-color, 0.08);
      color: mix(#000, $error-color, 12%);
    }

    &--neutral {
      background: $color-surface-sunken;
      color: $color-text-soft;
    }
  }

  &__verdict-level {
    font-size: $font-size-sm;
    font-weight: $font-weight-semibold;
  }

  &__verdict-detail {
    font-size: $font-size-xs;
    opacity: 0.9;
  }

  &__ready {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px;
  }

  &__ready-label {
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__ready-chip {
    padding: 2px 8px;
    border-radius: $radius-pill;
    background: rgba($success-color, 0.1);
    font-size: $font-size-xs;
    color: mix(#000, $success-color, 18%);
  }

  &__section {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: $spacing-sm;
    border-top: 1px solid $color-border-soft;
  }

  &__section-title {
    margin: 0;
    font-size: $font-size-xs;
    font-weight: $font-weight-semibold;
    color: $color-text-soft;
    letter-spacing: 0.04em;
  }

  &__dl {
    display: grid;
    grid-template-columns: 88px minmax(0, 1fr);
    gap: 6px $spacing-sm;
    margin: 0;
    font-size: $font-size-xs;

    dt {
      color: $color-text-faint;
    }

    dd {
      margin: 0;
      color: $color-text;
      word-break: break-word;
    }
  }

  &__chip-group {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__chip-group-label {
    margin: 0;
    font-size: 10px;
    color: $color-text-faint;
  }

  &__chip-row {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  &__derived {
    margin: 0;
    padding-top: $spacing-sm;
    border-top: 1px dashed $color-border;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }
}

@keyframes lb-fade {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.lb-drawer-enter-active,
.lb-drawer-leave-active {
  transition: opacity 0.2s $ease-out;

  .lb__drawer-panel {
    transition: transform 0.24s $ease-out;
  }
}

.lb-drawer-enter-from,
.lb-drawer-leave-to {
  opacity: 0;

  .lb__drawer-panel {
    transform: translateX(100%);
  }
}
</style>
