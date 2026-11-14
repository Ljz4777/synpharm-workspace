<template>
  <div class="rp">
    <div class="rp__filters">
      <input v-model="filters.search" type="text" class="rp__search" placeholder="搜索靶点名称、ID 或结果编号…" />
      <SpSelect v-model="filters.confidence" :min-width="150" :options="CONFIDENCE_OPTIONS" />
      <SpSelect v-model="filters.sortBy" :min-width="150" :options="SORT_OPTIONS" />
      <span class="rp__count">{{ filteredResults.length }} / {{ results.length }} 条</span>
    </div>

    <div v-if="loading" class="rp__state">加载中…</div>
    <div v-else-if="loadError" class="rp__state rp__state--error">
      <p>{{ loadError }}</p>
      <p v-if="loadHint" class="rp__hint">{{ loadHint }}</p>
      <button type="button" class="rp__retry" @click="loadResults">重试</button>
    </div>
    <template v-else>
      <div v-if="filteredResults.length" class="rp__grid">
        <ResultCard
          v-for="result in filteredResults"
          :key="String(result.id)"
          :result="result"
          @detail="handleResultDetail"
          @3d="handleResult3D"
          @delete="handleResultDelete"
        />
      </div>

      <EmptyState
        v-else
        icon="📭"
        title="没有符合条件的评价结果"
        :description="results.length ? '放宽筛选条件再试，或清空搜索关键词。' : '到「单次评价」或「批量评价」发起一次，结果会出现在这里。'"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 评价体系 · 评价结果列表。
 *
 * 「评价记录」页签之一。筛选、排序都在前端做 —— 结果总量受后端分页上限约束，
 * 但把这些条件翻译成后端参数会让「筛选」和「翻页」互相打架（翻页时筛选条件要重发）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resultApi } from '@/api/predict'
import type { ApiError } from '@/utils/request'
import type { PredictionResult } from '@/types'
import ResultCard from '@/components/ResultCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import SpSelect from '@/components/ui/SpSelect.vue'

const router = useRouter()

const filters = reactive({
  confidence: 'all',
  search: '',
  sortBy: 'date'
})

const CONFIDENCE_OPTIONS = [
  { value: 'all', label: '所有置信度' },
  { value: 'high', label: '高置信度' },
  { value: 'medium', label: '中置信度' },
  { value: 'low', label: '低置信度' }
]

const SORT_OPTIONS = [
  { value: 'date', label: '按日期' },
  { value: 'affinity', label: '按亲和力' },
  { value: 'confidence', label: '按置信度' }
]

const results = ref<PredictionResult[]>([])
const loading = ref(false)
const loadError = ref('')
const loadHint = ref('')

async function loadResults(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadHint.value = ''
  try {
    const page = await resultApi.getResultList(1, 100)
    results.value = page.list
  } catch (error: unknown) {
    const apiError = error as ApiError
    // 网关统一兜底文案「依赖的下游服务暂不可用」= predict 未部署 / 未注册，
    // 不是用户网络问题；说清楚找谁，并给一个重试入口。
    if (apiError?.status === 503 || apiError?.errorCode === 'DEPENDENCY_UNAVAILABLE') {
      loadError.value = '预测服务暂时不可用，无法读取评价结果。'
      loadHint.value = '通常表示 synpharm-predict 未启动或未注册到网关，请联系管理员检查后重试。'
    } else {
      loadError.value = error instanceof Error ? error.message : '加载评价结果失败'
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadResults)

const filteredResults = computed(() => {
  let list = [...results.value]

  if (filters.confidence !== 'all') {
    list = list.filter((r) => r.confidenceLevel === filters.confidence)
  }

  if (filters.search) {
    const search = filters.search.toLowerCase()
    list = list.filter(
      (r) =>
        (r.targetName || '').toLowerCase().includes(search) ||
        (r.targetId || '').toLowerCase().includes(search) ||
        String(r.id).toLowerCase().includes(search)
    )
  }

  switch (filters.sortBy) {
    case 'affinity':
      list.sort((a, b) => (a.bindingAffinity ?? 0) - (b.bindingAffinity ?? 0))
      break
    case 'confidence':
      list.sort((a, b) => (b.confidenceScore ?? 0) - (a.confidenceScore ?? 0))
      break
    default:
      list.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
  }

  return list
})

function handleResultDetail(result: PredictionResult): void {
  router.push({ path: '/result/' + String(result.id) })
}

function handleResult3D(result: PredictionResult): void {
  router.push({
    path: '/visualization',
    query: {
      id: String(result.id),
      targetName: result.targetName,
      targetId: result.targetId
    }
  })
}

async function handleResultDelete(result: PredictionResult): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确认删除这条评价结果？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await resultApi.deleteResult(result.id)
    ElMessage.success('已删除')
    await loadResults()
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}
</script>

<style lang="scss" scoped>
.rp {
  display: flex;
  flex-direction: column;
  gap: $spacing-md;
}

.rp__filters {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  flex-wrap: wrap;
}

.rp__search {
  flex: 1;
  min-width: 220px;
  padding: 9px $spacing-md;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;
  font-size: $font-size-sm;
  font-family: inherit;
  color: $color-text;

  &:focus {
    outline: none;
    border-color: $color-brand;
    box-shadow: $shadow-focus;
  }
}

.rp__count {
  margin-left: auto;
  font-size: $font-size-xs;
  color: $color-text-faint;
  font-variant-numeric: tabular-nums;
}

.rp__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: $spacing-md;
}

.rp__state {
  padding: $spacing-xl;
  text-align: center;
  color: $color-text-faint;
  font-size: $font-size-sm;
}

.rp__state--error {
  color: $error-color;
}

.rp__hint {
  margin-top: $spacing-xs;
  color: $color-text-faint;
}

.rp__retry {
  margin-top: $spacing-md;
  padding: 7px 18px;
  border: 1px solid currentColor;
  border-radius: $radius-control;
  background: transparent;
  color: inherit;
  font-size: $font-size-sm;
  font-family: inherit;
  cursor: pointer;
}
</style>
