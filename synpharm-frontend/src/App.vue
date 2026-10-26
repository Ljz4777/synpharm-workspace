<template>
  <div class="app-container" :class="{ 'app-container--with-nav': hasNav }">
    <router-view v-slot="{ Component }">
      <transition name="fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<script setup lang="ts">
// authStore.init() 已在 main.ts 中（router 安装前）完成，避免重复初始化
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 落地页与登录页没有左侧导航，正文不能跟着给左偏移 */
const hasNav = computed(() => route.meta.requiresAuth === true)
</script>

<style lang="scss">
/* Token 已由 vite.config.ts 的 additionalData 自动注入，无需再 import */

:root {
  font-family: $font-family-base;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  // 主题色也作为 CSS 变量暴露一份，方便内联 SVG / 第三方组件读取
  --sp-font-sans: #{$font-family-base};
  --sp-font-mono: #{$font-family-mono};
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  height: 100%;
  font-size: $font-size-base;
  font-family: $font-family-base;
  color: $color-text;
  background: $color-canvas;
}

#app {
  height: 100%;
}

.app-container {
  height: 100%;
  min-height: 100vh;
  transition: padding-left $transition-normal;

  // 左侧导航是 fixed 的，正文区的左偏移在这里统一给一次 ——
  // 这样 14 个页面不需要各自写 padding-left，换布局也只改这一处。
  // 只有需要登录的页面才有导航；落地页与登录页不能跟着偏移。
  // 宽度由 Sidebar 写入 CSS 变量（收起/展开时同步变化）。
  &--with-nav {
    padding-left: var(--sp-nav-width, #{$sidebar-width});
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: $border-color;
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: $text-muted;
}
</style>