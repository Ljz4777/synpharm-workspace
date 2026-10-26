<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 全站统一的下拉选择器。
 *
 * 为什么不用原生 `<select>`：箭头与展开面板都是操作系统画的，无法贴合主题 ——
 * 深色模式下是白底列表，Windows 上是灰凸小按钮，和这套扁平青绿主题完全不搭。
 * 原生控件的 `option` 只能改配色，改不了行高、圆角、hover、勾选标记。
 *
 * 两个有意的实现选择：
 *
 * 1. **面板 teleport 到 body 并用 fixed 定位**。下拉常出现在表格单元格、
 *    可滚动容器、模态框里，`overflow: hidden` 会把绝对定位的面板裁掉。
 * 2. **触发按钮就是组件根节点**。这样调用方传进来的 `class`（宽度 / 外边距）
 *    与改造前的 `<select>` 完全一致，不需要为 37 处调用点重排版面。
 *
 * 无障碍：根节点是 `role="combobox"`，面板是 `listbox`，
 * 键盘支持 ↑↓ 选择、Home/End、Enter/Space 展开或确认、Esc 关闭、Tab 离开即关闭。
 */
interface OptionLike {
  [key: string]: unknown
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | null
    /** 选项数组：可以是原始值，也可以是对象（用 valueKey / labelKey 取字段） */
    options?: readonly (OptionLike | string | number)[]
    valueKey?: string
    labelKey?: string
    /** 副标题字段（如维度、严重度），显示在名称下方 */
    hintKey?: string
    placeholder?: string
    disabled?: boolean
    size?: 'sm' | 'md'
    /** 撑满父容器（对应原来 select 的 width:100%） */
    block?: boolean
    /** block 时的最大宽度，默认 420px —— 与各页 .field__control 的原上限一致 */
    maxWidth?: number
    /** 固定宽度（px） */
    width?: number
    /** 最小宽度（px）：选项文字较长时用 */
    minWidth?: number
  }>(),
  {
    modelValue: null,
    options: () => [],
    valueKey: 'value',
    labelKey: 'label',
    hintKey: '',
    placeholder: '请选择',
    disabled: false,
    size: 'md',
    block: false,
    maxWidth: 420,
    width: 0,
    minWidth: 0
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number | null): void
  (e: 'change', value: string | number | null): void
}>()

// 模板有两个根节点（按钮 + Teleport），Vue 不会自动把 attrs 落到任何一个上。
// 不显式绑定的话，调用方传的 class / id 会连同 `<label for>` 一起被丢掉 ——
// 表现就是「明明写了 width 类，下拉还是缩成内容宽」。
defineOptions({ inheritAttrs: false })

interface Item {
  value: string | number
  label: string
  hint: string
  disabled: boolean
}

function normalise(raw: readonly (OptionLike | string | number)[]): Item[] {
  return raw.map((o) => {
    if (o === null || o === undefined) {
      return { value: '', label: '', hint: '', disabled: true }
    }
    if (typeof o === 'object') {
      const rec = o as Record<string, unknown>
      return {
        value: (rec[props.valueKey] ?? '') as string | number,
        label: String(rec[props.labelKey] ?? rec[props.valueKey] ?? ''),
        hint: props.hintKey ? String(rec[props.hintKey] ?? '') : '',
        disabled: rec.disabled === true
      }
    }
    return { value: o as string | number, label: String(o), hint: '', disabled: false }
  })
}

const items = computed(() => normalise(props.options ?? []))

const selectedIndex = computed(() =>
  items.value.findIndex((i) => sameValue(i.value, props.modelValue))
)

const selected = computed(() => (selectedIndex.value >= 0 ? items.value[selectedIndex.value] : null))

const displayLabel = computed(() => selected.value?.label ?? props.placeholder)

const isPlaceholder = computed(() => !selected.value)

/**
 * 宽度只能由组件自己给。
 *
 * 父页面写在 scoped style 里的宽度类**不会生效**：组件是多根节点（按钮 + Teleport），
 * Vue 只给根节点打自己的 scopeId，父组件的 scopeId 不会落到这个 button 上，
 * `.field__control[data-v-parent]` 因此永远匹配不到。所以用 props 表达布局意图。
 */
const rootStyle = computed(() => {
  const style: Record<string, string> = {}
  if (props.block) {
    style.width = '100%'
    if (props.maxWidth > 0) style.maxWidth = `${props.maxWidth}px`
  }
  if (props.width > 0) style.width = `${props.width}px`
  if (props.minWidth > 0) style.minWidth = `${props.minWidth}px`
  return style
})

/** 数字与字符串的 v-model 混用很常见（:value="1" 与 "1"），比较时要宽容 */
function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === null || a === undefined || b === null || b === undefined) return false
  return String(a) === String(b)
}

/* ------------------------------ 展开状态 ------------------------------ */

const root = ref<HTMLButtonElement | null>(null)
const panel = ref<HTMLElement | null>(null)
const open = ref(false)
/** 键盘高亮项（与已选中项分开：悬停时不该改变选中） */
const activeIndex = ref(-1)
const panelStyle = ref<Record<string, string>>({})

const PANEL_MAX_HEIGHT = 280
const PANEL_MIN_WIDTH = 160

function measure() {
  const el = root.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const width = Math.max(rect.width, PANEL_MIN_WIDTH)
  const spaceBelow = window.innerHeight - rect.bottom
  const spaceAbove = rect.top
  const flipUp = spaceBelow < PANEL_MAX_HEIGHT + 12 && spaceAbove > spaceBelow
  const maxHeight = Math.max(
    120,
    Math.min(PANEL_MAX_HEIGHT, (flipUp ? spaceAbove : spaceBelow) - 12)
  )
  // 触发器本身可能贴边或已被滚出视口，面板必须夹在可见区内
  const panelHeight = Math.min(maxHeight, PANEL_MAX_HEIGHT)
  const rawTop = flipUp ? rect.top - 6 - panelHeight : rect.bottom + 6
  const top = Math.max(8, Math.min(rawTop, Math.max(8, window.innerHeight - 8 - panelHeight)))
  panelStyle.value = {
    position: 'fixed',
    left: `${Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))}px`,
    top: `${top}px`,
    width: `${width}px`,
    maxHeight: `${maxHeight}px`
  }
}

async function openPanel() {
  if (props.disabled || open.value) return
  measure()
  open.value = true
  activeIndex.value = selectedIndex.value >= 0 ? selectedIndex.value : firstEnabled()
  await nextTick()
  scrollActiveIntoView()
  window.addEventListener('scroll', onViewportChange, true)
  window.addEventListener('resize', onViewportChange)
  document.addEventListener('mousedown', onDocMouseDown, true)
}

function closePanel() {
  if (!open.value) return
  open.value = false
  activeIndex.value = -1
  window.removeEventListener('scroll', onViewportChange, true)
  window.removeEventListener('resize', onViewportChange)
  document.removeEventListener('mousedown', onDocMouseDown, true)
}

function toggle() {
  if (open.value) closePanel()
  else void openPanel()
}

function firstEnabled(): number {
  return items.value.findIndex((i) => !i.disabled)
}

function onViewportChange() {
  // 滚动/缩放后按钮位置变了；面板跟着走，比直接关闭更符合预期
  if (open.value) measure()
}

function onDocMouseDown(e: MouseEvent) {
  const target = e.target as Node | null
  if (!target) return
  if (root.value?.contains(target)) return
  if (panel.value?.contains(target)) return
  closePanel()
}

function scrollActiveIntoView() {
  const container = panel.value
  if (!container) return
  const el = container.querySelector<HTMLElement>('.sp-select__option.is-active')
  el?.scrollIntoView({ block: 'nearest' })
}

/* ------------------------------ 选择 ------------------------------ */

function pick(item: Item) {
  if (item.disabled) return
  const changed = !sameValue(item.value, props.modelValue)
  // 保留原始类型：调用方用 v-model.number 时拿到的应是数字
  emit('update:modelValue', item.value)
  if (changed) emit('change', item.value)
  closePanel()
  root.value?.focus()
}

function move(delta: number) {
  if (!open.value) {
    void openPanel()
    return
  }
  const count = items.value.length
  if (count === 0) return
  let next = activeIndex.value
  for (let step = 0; step < count; step++) {
    next = (next + delta + count) % count
    if (!items.value[next].disabled) {
      activeIndex.value = next
      void nextTick(scrollActiveIntoView)
      return
    }
  }
}

function moveTo(edge: 'first' | 'last') {
  if (!open.value) {
    void openPanel()
    return
  }
  const idx = edge === 'first' ? items.value.findIndex((i) => !i.disabled) : lastEnabled()
  if (idx >= 0) {
    activeIndex.value = idx
    void nextTick(scrollActiveIntoView)
  }
}

function lastEnabled(): number {
  for (let i = items.value.length - 1; i >= 0; i--) {
    if (!items.value[i].disabled) return i
  }
  return -1
}

function onKeydown(e: KeyboardEvent) {
  if (props.disabled) return
  switch (e.key) {
    case 'ArrowDown':
      e.preventDefault()
      move(1)
      break
    case 'ArrowUp':
      e.preventDefault()
      move(-1)
      break
    case 'Home':
      e.preventDefault()
      moveTo('first')
      break
    case 'End':
      e.preventDefault()
      moveTo('last')
      break
    case 'Enter':
    case ' ':
      e.preventDefault()
      if (open.value) {
        const item = items.value[activeIndex.value]
        if (item) pick(item)
        else closePanel()
      } else {
        void openPanel()
      }
      break
    case 'Escape':
      if (open.value) {
        e.preventDefault()
        closePanel()
      }
      break
    case 'Tab':
      closePanel()
      break
    default:
      break
  }
}

// 禁用状态下若面板正开着（父组件把它设成禁用），立即收起
watch(
  () => props.disabled,
  (v) => {
    if (v) closePanel()
  }
)

// 选项变化后已选中项可能不存在了，面板里没有高亮项可滚动，重置即可
watch(items, () => {
  if (open.value) activeIndex.value = selectedIndex.value
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onViewportChange, true)
  window.removeEventListener('resize', onViewportChange)
  document.removeEventListener('mousedown', onDocMouseDown, true)
})

defineExpose({ focus: () => root.value?.focus(), open: () => void openPanel(), close: closePanel })
</script>

<template>
  <button
    ref="root"
    v-bind="$attrs"
    type="button"
    class="sp-select"
    :class="[`sp-select--${size}`, { 'is-open': open, 'is-placeholder': isPlaceholder }]"
    :style="rootStyle"
    :disabled="disabled"
    role="combobox"
    aria-haspopup="listbox"
    :aria-expanded="open"
    :aria-label="displayLabel"
    @click="toggle"
    @keydown="onKeydown"
  >
    <span class="sp-select__value">
      <span class="sp-select__label">{{ displayLabel }}</span>
      <span v-if="selected?.hint" class="sp-select__hint">{{ selected.hint }}</span>
    </span>
    <svg class="sp-select__chevron" viewBox="0 0 10 6" aria-hidden="true">
      <path
        d="M1 1l4 4 4-4"
        fill="none"
        stroke="currentColor"
        stroke-width="1.6"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
  </button>

  <Teleport to="body">
    <ul
      v-if="open"
      ref="panel"
      class="sp-select-panel"
      :style="panelStyle"
      role="listbox"
      tabindex="-1"
    >
      <li v-if="items.length === 0" class="sp-select-panel__empty">暂无可选项</li>
      <li
        v-for="(item, i) in items"
        :key="`${item.value}-${i}`"
        class="sp-select__option"
        :class="{
          'is-active': i === activeIndex,
          'is-selected': sameValue(item.value, modelValue),
          'is-disabled': item.disabled
        }"
        role="option"
        :aria-selected="sameValue(item.value, modelValue)"
        :aria-disabled="item.disabled"
        @mouseenter="!item.disabled && (activeIndex = i)"
        @click="pick(item)"
      >
        <span class="sp-select__option-main">
          <span class="sp-select__option-label">{{ item.label }}</span>
          <span v-if="item.hint" class="sp-select__option-hint">{{ item.hint }}</span>
        </span>
        <svg
          v-if="sameValue(item.value, modelValue)"
          class="sp-select__check"
          viewBox="0 0 12 10"
          aria-hidden="true"
        >
          <path
            d="M1 5.2l3.2 3.2L11 1.6"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </li>
    </ul>
  </Teleport>
</template>

<style scoped lang="scss">
.sp-select {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid $color-border;
  border-radius: $radius-control;
  background: $color-surface;
  font-family: inherit;
  font-size: $font-size-sm;
  color: $color-text;
  text-align: left;
  cursor: pointer;
  transition: border-color $transition-fast, box-shadow $transition-fast,
    background $transition-fast;

  &:hover:not(:disabled) {
    border-color: rgba($accent-color, 0.55);
  }

  &:focus-visible {
    outline: none;
    border-color: $color-brand;
    box-shadow: $shadow-focus;
  }

  &.is-open {
    border-color: $color-brand;
    box-shadow: $shadow-focus;
  }

  &:disabled {
    opacity: 0.55;
    background: $color-surface-sunken;
    cursor: not-allowed;
  }

  &--sm {
    height: 26px;
    padding: 0 8px;
    font-size: $font-size-xs;
    border-radius: $border-radius-sm;
  }

  &__value {
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 0;
    overflow: hidden;
  }

  &__label {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &.is-placeholder &__label {
    color: $color-text-faint;
  }

  &__hint {
    flex: 0 0 auto;
    font-size: $font-size-xs;
    color: $color-text-faint;
  }

  &__chevron {
    flex: 0 0 auto;
    width: 10px;
    height: 6px;
    color: $color-text-faint;
    transition: transform $transition-fast, color $transition-fast;
  }

  &.is-open &__chevron {
    transform: rotate(180deg);
    color: $color-brand;
  }
}

// 面板虽然 teleport 到 body，但仍是本组件模板的一部分，作用域属性会一并带过去
.sp-select-panel {
  z-index: 900;
  margin: 0;
  padding: 4px;
  list-style: none;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid $color-border;
  border-radius: $radius-card;
  background: $color-surface;
  box-shadow: $shadow-lg;
  animation: sp-fade-up 0.12s $ease-out both;
}

.sp-select-panel__empty {
  padding: 10px;
  font-size: $font-size-sm;
  color: $color-text-faint;
  text-align: center;
}

.sp-select__option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: $border-radius-sm;
  font-size: $font-size-sm;
  color: $color-text;
  cursor: pointer;

  &.is-active {
    background: $color-brand-softer;
  }

  &.is-selected {
    color: $color-brand-strong;
    font-weight: $font-weight-medium;

    &.is-active {
      background: $color-brand-soft;
    }
  }

  &.is-disabled {
    opacity: 0.45;
    cursor: not-allowed;

    &.is-active {
      background: transparent;
    }
  }
}

.sp-select__option-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.sp-select__option-label {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sp-select__option-hint {
  font-size: $font-size-xs;
  font-weight: $font-weight-normal;
  color: $color-text-faint;
}

.sp-select__check {
  flex: 0 0 auto;
  width: 12px;
  height: 10px;
  color: $color-brand;
}
</style>
