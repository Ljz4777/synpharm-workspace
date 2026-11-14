<template>
  <div class="esb" :class="{ 'esb--probing': loading }">
    <div class="esb__pills">
      <button
        v-for="item in items"
        :key="item.key"
        type="button"
        class="esb__pill"
        :class="`esb__pill--${item.status}`"
        :title="item.title"
        @click="probeOne()"
      >
        <span class="esb__dot" :class="`esb__dot--${item.status}`" />
        <span class="esb__label">{{ item.label }}</span>
      </button>
    </div>

    <div class="esb__meta">
      <template v-if="healthLoading">探测中…</template>
      <template v-else-if="health">
        <span :class="`esb__health esb__health--${health.status.toLowerCase()}`">
          算法引擎 {{ health.status === 'UP' ? '在线' : '离线' }}
        </span>
        <span v-if="health.latencyMs != null" class="esb__latency">{{ health.latencyMs }}ms</span>
      </template>
      <template v-else-if="healthError">{{ healthError }}</template>
    </div>

    <div v-if="error" class="esb__probe-at esb__probe-at--error">{{ error }}</div>
    <div v-else-if="showProbeAt" class="esb__probe-at">
      <template v-if="probedAt">
        探测于 {{ formatTime(probedAt) }}
      </template>
      <template v-else>
        尚未探测，点击右侧刷新可强制探测
      </template>
    </div>

    <button v-if="showRefresh" type="button" class="esb__refresh" :disabled="loading" @click="refresh">
      <span v-if="loading" class="esb__spin" />刷新
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 引擎能力状态条。
 *
 * 解决的问题：评价工作台不知道 DTI/PPI/DDI 各自通没通，只能盲提交。
 * 数据源：/api/platform/capabilities（models 字段） + /api/platform/algorithm-health。
 *
 * 三个模型胶囊：
 *   ready     绿 = 模型 ready
 *   unknown   灰 = 尚未探测或平台未返回该字段
 *   error     红 = 探测失败 / 明确不可用
 *
 * 点击单个胶囊触发一次 /api/platform/algorithm-health 探测并刷新矩阵。
 */
import { computed, onMounted, ref } from 'vue'
import { platformApi, type CapabilitiesResponse, type AlgorithmHealthResponse } from '@/api/platform'

defineProps<{
  /** 是否显示「探测于」时间 */
  showProbeAt?: boolean
  /** 是否显示刷新按钮 */
  showRefresh?: boolean
}>()

type ModelKey = 'dti' | 'ppi' | 'ddi'
type Status = 'ready' | 'unknown' | 'error'

const MODEL_META: { key: ModelKey; label: string }[] = [
  { key: 'dti', label: 'DTI 药物-靶点' },
  { key: 'ppi', label: 'PPI 蛋白-蛋白' },
  { key: 'ddi', label: 'DDI 药物-药物' }
]

const capabilities = ref<CapabilitiesResponse | null>(null)
const health = ref<AlgorithmHealthResponse | null>(null)
const loading = ref(false)
const healthLoading = ref(false)
const error = ref('')
const healthError = ref('')

const items = computed(() => {
  const models = capabilities.value?.models
  return MODEL_META.map((m) => {
    const ready = models?.[m.key]
    let status: Status = 'unknown'
    let title = '平台尚未返回该模型状态'
    if (ready === true) {
      status = 'ready'
      title = `${m.label} 就绪，可以提交预测`
    } else if (ready === false) {
      status = 'error'
      title = `${m.label} 当前不可用，建议检查 FastAPI 是否启动`
    }
    return {
      key: m.key,
      label: m.label,
      status,
      title
    }
  })
})

const probedAt = computed(() => capabilities.value?.probedAt)

function formatTime(iso: string): string {
  const d = new Date(iso)
  return isNaN(d.getTime()) ? iso : d.toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

async function loadCapabilities(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    capabilities.value = await platformApi.fetchCapabilities()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '加载能力矩阵失败'
    capabilities.value = null
  } finally {
    loading.value = false
  }
}

async function loadHealth(): Promise<void> {
  healthLoading.value = true
  healthError.value = ''
  try {
    health.value = await platformApi.fetchAlgorithmHealth()
  } catch (e: unknown) {
    healthError.value = e instanceof Error ? e.message : '探测算法引擎失败'
    health.value = null
  } finally {
    healthLoading.value = false
  }
}

async function probeOne(): Promise<void> {
  // 点击胶囊：先探测引擎健康，再刷新能力矩阵
  await loadHealth()
  await loadCapabilities()
}

async function refresh(): Promise<void> {
  await Promise.all([loadHealth(), loadCapabilities()])
}

onMounted(() => {
  loadCapabilities()
  loadHealth()
})

defineExpose({ refresh })
</script>

<style lang="scss" scoped>
.esb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: $spacing-md;
  padding: $spacing-md $spacing-lg;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;
}

.esb__pills {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
}

.esb__pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid $color-border;
  border-radius: $radius-pill;
  background: $color-surface;
  font-size: $font-size-sm;
  color: $color-text;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;

  &:hover:not(:disabled) {
    background: $color-surface-sunken;
  }

  &--ready { border-color: rgba(16, 185, 129, 0.4); background: rgba(16, 185, 129, 0.08); }
  &--unknown { border-color: $color-border; background: $color-surface-sunken; color: $color-text-soft; }
  &--error { border-color: rgba(239, 68, 68, 0.4); background: rgba(239, 68, 68, 0.08); color: $error-color; }
}

.esb__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: $color-text-faint;

  &--ready { background: $success-color; }
  &--unknown { background: $color-text-faint; }
  &--error { background: $error-color; }
}

.esb__meta {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  font-size: $font-size-sm;
  color: $color-text-soft;
}

.esb__health {
  &--up { color: $success-color; }
  &--down { color: $error-color; }
}

.esb__latency {
  padding: 1px 6px;
  border-radius: $radius-control;
  background: $color-surface-sunken;
  font-family: $font-family-mono;
  font-size: $font-size-xs;
}

.esb__probe-at {
  width: 100%;
  font-size: $font-size-xs;
  color: $color-text-faint;

  &--error {
    color: $error-color;
  }
}

.esb__refresh {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: transparent;
  font-size: $font-size-sm;
  color: $color-brand;
  cursor: pointer;

  &:disabled { opacity: 0.6; cursor: not-allowed; }
}

.esb__spin {
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: esb-spin 0.8s linear infinite;
}

@keyframes esb-spin {
  to { transform: rotate(360deg); }
}
</style>
