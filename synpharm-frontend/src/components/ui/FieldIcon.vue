<template>
  <svg
    class="field-icon"
    viewBox="0 0 24 24"
    :width="size"
    :height="size"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<script setup lang="ts">
/**
 * 表单字段图标。
 *
 * 为什么不用 📧 🔑 👤 🔒：这些是 emoji，字形由操作系统字体决定 ——
 * Windows 上是彩色 Segoe UI Emoji（在输入框里显得突兀且偏大），
 * Linux 容器 / 缺字体环境里会退化成黑白方块或豆腐块。同一个表单
 * 在三种环境下三种样子，是「看起来不专业」的主要来源之一。
 *
 * 内联 SVG 走 `currentColor`，颜色跟随输入框（聚焦时随主色变深），
 * 尺寸精确可控，也不受字体影响。
 */
const props = withDefaults(
  defineProps<{
    /** mail / key / user / lock */
    name: 'mail' | 'key' | 'user' | 'lock'
    size?: number
    strokeWidth?: number
  }>(),
  { size: 15, strokeWidth: 1.6 }
)

const ICONS: Record<string, string[]> = {
  // 信封
  mail: [
    'M4 6.4h16a1.4 1.4 0 0 1 1.4 1.4v8.4A1.4 1.4 0 0 1 20 17.6H4a1.4 1.4 0 0 1-1.4-1.4V7.8A1.4 1.4 0 0 1 4 6.4Z',
    'M2.9 7.6 12 13.2l9.1-5.6'
  ],
  // 钥匙
  key: [
    'M14.6 4.4a5.2 5.2 0 1 1-4.4 7.9L4 18.5v2.1h2.4v-1.8h1.9v-1.9h1.9l1.4-1.4',
    'M17.2 8.4h.01'
  ],
  // 人
  user: [
    'M12 4.6a3.7 3.7 0 1 0 0 7.4 3.7 3.7 0 0 0 0-7.4Z',
    'M4.9 20a7.1 7.1 0 0 1 14.2 0'
  ],
  // 锁
  lock: [
    'M6.4 10.4h11.2a1.4 1.4 0 0 1 1.4 1.4v7.2a1.4 1.4 0 0 1-1.4 1.4H6.4A1.4 1.4 0 0 1 5 19v-7.2a1.4 1.4 0 0 1 1.4-1.4Z',
    'M8.2 10.4V7.6a3.8 3.8 0 0 1 7.6 0v2.8'
  ]
}

const paths = ICONS[props.name] ?? ICONS.mail
</script>

<style lang="scss" scoped>
.field-icon {
  flex-shrink: 0;
  display: block;
}
</style>
