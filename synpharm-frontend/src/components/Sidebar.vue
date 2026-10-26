<template>
  <aside class="nav" :class="{ 'nav--collapsed': collapsed }" aria-label="主导航">
    <router-link to="/projects" class="nav__brand" title="SynPharm · 设计项目">
      <span class="nav__mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor"
             stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 3.2 19 7.1v8.5L12 20.8 5 15.6V7.1l7-3.9Z" />
          <path d="M12 10.7a1.35 1.35 0 1 0 0 2.7 1.35 1.35 0 0 0 0-2.7Z" fill="currentColor" stroke="none" />
        </svg>
      </span>
      <span class="nav__brand-text">
        <span class="nav__name">SynPharm</span>
        <span class="nav__tagline">AI 药物研发平台</span>
      </span>
    </router-link>

    <nav class="nav__body">
      <div v-for="group in navGroups" :key="group.key" class="nav__group">
        <p class="nav__group-label">
          <span>{{ group.label }}</span>
          <i class="nav__group-rule" aria-hidden="true" />
        </p>
        <router-link
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav__link"
          :class="{ 'nav__link--active': isActive(item) }"
          :title="collapsed ? `${group.label} · ${item.label}` : item.label"
        >
          <svg class="nav__icon" viewBox="0 0 24 24" aria-hidden="true"
               fill="none" stroke="currentColor" stroke-width="1.6"
               stroke-linecap="round" stroke-linejoin="round">
            <path v-for="(d, i) in ICONS[item.icon]" :key="i" :d="d" />
          </svg>
          <span class="nav__label">{{ item.label }}</span>
        </router-link>
      </div>
    </nav>

    <div class="nav__user">
      <router-link
        to="/profile"
        class="nav__user-link"
        :class="{ 'nav__user-link--active': route.path === '/profile' }"
        :title="`${authStore.userNickname}${authStore.isGuest ? '（游客）' : ''} · 个人中心`"
      >
        <span class="nav__avatar">{{ avatarText }}</span>
        <span class="nav__user-info">
          <span class="nav__user-name">{{ authStore.userNickname }}</span>
          <span class="nav__user-role">{{ authStore.isGuest ? '游客身份' : '已登录' }}</span>
        </span>
      </router-link>

      <button type="button" class="nav__logout" title="退出登录" aria-label="退出登录" @click="handleLogout">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
             stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 3v7" />
          <path d="M7.6 6.3a7.2 7.2 0 1 0 8.8 0" />
        </svg>
      </button>
    </div>

    <button
      type="button"
      class="nav__toggle"
      :title="collapsed ? '展开导航' : '收起导航'"
      :aria-label="collapsed ? '展开导航' : '收起导航'"
      @click="toggle"
    >
      <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
           stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path :d="collapsed ? 'M9 6l6 6-6 6' : 'M15 6l-6 6 6 6'" />
      </svg>
    </button>
  </aside>
</template>

<script setup lang="ts">
/**
 * 左侧竖向导航。
 *
 * **图标用内联 SVG 而不是 emoji**：🧬🧪📊 这类字符在不同系统上字形、大小、基线
 * 全不一样（Windows 是彩色 Segoe UI Emoji，macOS 是 Apple Color Emoji，缺字体的
 * 环境里会退化成黑白方块），一个科研工具拿它们当图标看起来像临时页面。
 * 内联 SVG 走 `currentColor`，能与文字同色、随主题变、尺寸精确可控。
 *
 * **结构**：两个产品线（药物设计 / 分子预测）各自成组，组标题带一道渐隐细线把
 * 分组边界交代清楚。原来的「账号」组只有「个人中心」一项 —— 单项成组是噪音，
 * 已并入底部用户区：点头像进个人中心，退出是独立图标按钮。
 *
 * **定位**：`position: fixed`，不占文档流。正文左偏移由 `App.vue` 的
 * `.app-container` 统一给（一次，而不是 14 个页面各写一遍），宽度靠
 * `--sp-nav-width` 跟随收起/展开。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

interface NavItem {
  path: string
  label: string
  /** 图标键，见 ICONS */
  icon: string
  /** 用于高亮的路径前缀，可多个（详情页需点亮其所属一级栏目） */
  match: string[]
  /**
   * 只按完整路径匹配。
   * 用于 `/design` 这种「既是自己、又是其它路由前缀」的页面 ——
   * 否则基线 / 生成 / 决策页会同时点亮两个入口。
   */
  exact?: boolean
}

interface NavGroup {
  key: string
  label: string
  items: NavItem[]
}

/**
 * 线性图标（24×24 描边路径）。
 * 只存 path 的 d 值；形状规则统一（1.6 描边、圆头圆角、无填充），
 * 因此 8 个图标排成一列时视觉重量一致 —— emoji 做不到这一点。
 */
const ICONS: Record<string, string[]> = {
  // 叠层：项目 = 一摞设计对象
  projects: [
    'M12 3.2 3.6 8l8.4 4.8L20.4 8 12 3.2Z',
    'M3.6 12l8.4 4.8L20.4 12',
    'M3.6 16l8.4 4.8L20.4 16'
  ],
  // 笔：精修台 = 手动改结构
  workbench: [
    'M15.6 4.6a2.2 2.2 0 0 1 3.1 3.1L8.5 17.9 4 19.2l1.3-4.4L15.6 4.6Z',
    'M14.2 6l3.8 3.8'
  ],
  // 准星：靶点
  targets: [
    'M12 5.2a6.8 6.8 0 1 0 0 13.6 6.8 6.8 0 0 0 0-13.6Z',
    'M12 9.7a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6Z',
    'M12 2.4v2.6M12 19v2.6M2.4 12H5M19 12h2.6'
  ],
  // 四宫格：仪表盘
  dashboard: [
    'M5.5 4h3.6A1.4 1.4 0 0 1 10.5 5.4v3.7a1.4 1.4 0 0 1-1.4 1.4H5.5A1.4 1.4 0 0 1 4.1 9.1V5.4A1.4 1.4 0 0 1 5.5 4Z',
    'M14.9 4h3.6a1.4 1.4 0 0 1 1.4 1.4v3.7a1.4 1.4 0 0 1-1.4 1.4h-3.6a1.4 1.4 0 0 1-1.4-1.4V5.4A1.4 1.4 0 0 1 14.9 4Z',
    'M5.5 14.5h3.6a1.4 1.4 0 0 1 1.4 1.4v3.7a1.4 1.4 0 0 1-1.4 1.4H5.5a1.4 1.4 0 0 1-1.4-1.4v-3.7a1.4 1.4 0 0 1 1.4-1.4Z',
    'M14.9 14.5h3.6a1.4 1.4 0 0 1 1.4 1.4v3.7a1.4 1.4 0 0 1-1.4 1.4h-3.6a1.4 1.4 0 0 1-1.4-1.4v-3.7a1.4 1.4 0 0 1 1.4-1.4Z'
  ],
  // 折线：预测
  predict: ['M3 12.5h3.6l2.6-7.2 4 14 2.6-6.8H21'],
  // 柱状：结果
  results: ['M3.6 20.4h16.8', 'M8 20.4v-6.8', 'M12.9 20.4V8', 'M17.8 20.4v-4.2'],
  // 列表点：任务
  tasks: ['M9 6.2h11', 'M9 12h11', 'M9 17.8h11', 'M4.6 6.2h.01', 'M4.6 12h.01', 'M4.6 17.8h.01']
}

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const STORAGE_KEY = 'nav_collapsed'

const collapsed = ref(false)

/**
 * 导航宽度同步给 CSS 变量。
 * 正文区的左偏移在 App.vue 的 `.app-container` 上只有一处，靠这个变量跟随
 * 收起/展开，否则 14 个页面都得各自监听状态。
 */
function applyWidth() {
  document.documentElement.style.setProperty(
    '--sp-nav-width',
    collapsed.value ? 'var(--sp-nav-width-collapsed)' : 'var(--sp-nav-width-expanded)'
  )
}

function toggle() {
  collapsed.value = !collapsed.value
  localStorage.setItem(STORAGE_KEY, collapsed.value ? '1' : '0')
  applyWidth()
}

/**
 * 主导航。按**产品线**分组：
 *   药物设计（设计项目 / 分子精修台 / 靶点库）
 *   分子预测（仪表盘 / 预测中心 / 预测结果 / 任务管理），与设计流程暂无数据往来
 *
 * 顺序是「项目 → 工具 → 基础数据」：用户的第一动作是选项目，靶点库是前两者的
 * 数据底座，按查阅频率排比按概念层级排更顺手。
 *
 * ⚠️ `/visualization`（3D 可视化）**刻意不在这里** —— 它的能力已并入设计工作台的
 * 口袋页签。该页面本身仍然存在：预测结果页 / 任务详情等会带着预测上下文
 * （亲和力、置信度、相互作用）深链进来，那部分属于预测域数据，工作台的
 * 口袋页签替代不了，所以只从导航移除、不删页面。
 */
const navGroups: NavGroup[] = [
  {
    key: 'design',
    label: '药物设计',
    items: [
      {
        path: '/projects',
        label: '设计项目',
        icon: 'projects',
        match: ['/projects', '/design/projects', '/design/candidates']
      },
      {
        path: '/design',
        label: '分子精修台',
        icon: 'workbench',
        match: ['/design'],
        exact: true
      },
      // 靶点库是设计流程的基础数据：基线块1（靶点）与块2（结构/口袋）读的就是它
      { path: '/targets', label: '靶点库', icon: 'targets', match: ['/targets'] }
    ]
  },
  {
    key: 'predict',
    label: '分子预测',
    items: [
      { path: '/dashboard', label: '仪表盘', icon: 'dashboard', match: ['/dashboard'] },
      { path: '/predict', label: '预测中心', icon: 'predict', match: ['/predict'] },
      { path: '/results', label: '预测结果', icon: 'results', match: ['/results', '/result'] },
      { path: '/tasks', label: '任务管理', icon: 'tasks', match: ['/tasks'] }
    ]
  }
]

/**
 * 判断某项是否为当前栏目。
 * 注意：结果列表是 /results，而结果详情是 /result/:id（单数），需分别匹配才会正确点亮。
 */
const isActive = (item: NavItem): boolean => {
  if (item.exact) return route.path === item.path
  return item.match.some((prefix) => route.path === prefix || route.path.startsWith(prefix + '/'))
}

const avatarText = computed(() => {
  if (!authStore.userNickname) return '游'
  return authStore.userNickname.charAt(0).toUpperCase()
})

const handleLogout = () => {
  authStore.logout()
  router.push('/login')
}

onMounted(() => {
  collapsed.value = localStorage.getItem(STORAGE_KEY) === '1'
  applyWidth()
})
</script>

<style lang="scss" scoped>
.nav {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: $z-sticky;
  display: flex;
  flex-direction: column;
  width: $sidebar-width;
  background: $bg-sidebar;
  color: $color-on-dark;
  // 与浅色正文区交界：一道内高光 + 一层极淡外阴影，避免生硬贴边
  box-shadow: inset -1px 0 0 rgba(255, 255, 255, 0.07), 6px 0 24px rgba(9, 32, 31, 0.18);
  transition: width $transition-normal;

  &--collapsed {
    width: $nav-width-collapsed;

    .nav__brand-text,
    .nav__label,
    .nav__user-info,
    .nav__group-rule {
      display: none;
    }

    .nav__brand {
      justify-content: center;
      padding: 0;
    }

    .nav__body {
      padding-left: 8px;
      padding-right: 8px;
    }

    .nav__link {
      gap: 0;
      justify-content: center;
      padding: 0;

      // 收起后 body 的内边距从 10px 变 8px，竖条要跟着贴回导轨边缘
      &--active::before {
        left: -8px;
      }
    }

    // 收起后组标题只剩一条居中细分隔线：比隐藏整组更能交代「这里换组了」
    .nav__group-label {
      justify-content: center;
      margin-bottom: 8px;
      padding: 0;

      span {
        display: none;
      }

      &::after {
        content: '';
        width: 20px;
        height: 1px;
        background: rgba(255, 255, 255, 0.14);
      }
    }

    .nav__group:first-child .nav__group-label {
      display: none;
    }

    .nav__user {
      flex-direction: column;
      gap: 8px;
      align-items: center;
      padding: $spacing-sm 0;
    }

    .nav__user-link {
      flex: 0 0 auto;
      justify-content: center;
      width: 100%;
    }

    .nav__toggle {
      right: -10px;
    }
  }
}

/* ---------------- 品牌 ---------------- */

.nav__brand {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
  height: 64px;
  padding: 0 $spacing-md;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  color: inherit;
  text-decoration: none;

  &:hover .nav__mark {
    background: rgba(20, 184, 166, 0.26);
    border-color: rgba(20, 184, 166, 0.5);
  }
}

.nav__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border: 1px solid rgba(20, 184, 166, 0.35);
  border-radius: 9px;
  background: rgba(20, 184, 166, 0.16);
  color: $accent-light;
  transition: background $transition-fast, border-color $transition-fast;
}

.nav__brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.nav__name {
  font-size: 15px;
  font-weight: $font-weight-semibold;
  line-height: 1.2;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.nav__tagline {
  font-size: 10px;
  line-height: 1.2;
  letter-spacing: 0.04em;
  color: rgba(255, 255, 255, 0.42);
  white-space: nowrap;
}

/* ---------------- 分组与链接 ---------------- */

.nav__body {
  flex: 1;
  min-height: 0;
  padding: $spacing-md 10px;
  overflow-y: auto;

  // 深色底上的滚动条要够淡，否则中间出现一条亮杠
  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-thumb {
    border-radius: 3px;
    background: rgba(255, 255, 255, 0.16);
  }
}

.nav__group + .nav__group {
  margin-top: $spacing-md;
}

.nav__group-label {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 8px;
  padding: 0 8px;
  font-size: 11px;
  font-weight: $font-weight-semibold;
  line-height: 1.4;
  letter-spacing: 0.06em;
  color: rgba(255, 255, 255, 0.45);
  white-space: nowrap;
}

/* 组标题右侧的渐隐细线：把「分组」这件事交代清楚，比单靠间距更明确 */
.nav__group-rule {
  flex: 1;
  min-width: 12px;
  height: 1px;
  background: linear-gradient(to right, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.02));
}

.nav__link {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  height: 38px;
  padding: 0 10px;
  margin-bottom: 2px;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.7);
  font-size: $font-size-sm;
  text-decoration: none;
  transition: background $transition-fast, color $transition-fast;

  &:hover {
    color: #fff;
    background: rgba(255, 255, 255, 0.06);
  }

  &:focus-visible {
    outline: 2px solid $accent-light;
    outline-offset: -2px;
  }

  // 当前栏目：淡主色底 + 左缘竖条。竖排导航里竖条比整块变色定位更快，
  // 也不会把深青底压得很闷
  &--active {
    color: #fff;
    font-weight: $font-weight-medium;
    background: rgba(20, 184, 166, 0.16);

    .nav__icon {
      color: $accent-light;
      opacity: 1;
    }

    &::before {
      content: '';
      position: absolute;
      left: -10px;
      top: 50%;
      width: 3px;
      height: 20px;
      border-radius: 0 3px 3px 0;
      background: $accent-light;
      transform: translateY(-50%);
    }
  }
}

.nav__icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  opacity: 0.8;
  transition: color $transition-fast, opacity $transition-fast;
}

.nav__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------------- 底部用户区 ---------------- */

.nav__user {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
  padding: 10px $spacing-md;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.nav__user-link {
  display: flex;
  gap: 9px;
  align-items: center;
  flex: 1;
  min-width: 0;
  padding: 4px;
  margin: -4px;
  border-radius: 8px;
  color: inherit;
  text-decoration: none;
  transition: background $transition-fast;

  &:hover {
    background: rgba(255, 255, 255, 0.06);
  }

  &--active {
    background: rgba(20, 184, 166, 0.16);
  }
}

.nav__avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(140deg, rgba(20, 184, 166, 0.55), rgba(13, 148, 136, 0.28));
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: #fff;
}

.nav__user-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.nav__user-name {
  overflow: hidden;
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav__user-role {
  font-size: 10px;
  line-height: 1.3;
  color: rgba(255, 255, 255, 0.4);
}

.nav__logout {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: background $transition-fast, color $transition-fast;

  &:hover {
    background: rgba($error-color, 0.22);
    color: #fff;
  }

  &:focus-visible {
    outline: 2px solid $accent-light;
    outline-offset: 1px;
  }
}

/* ---------------- 收起开关 ---------------- */

.nav__toggle {
  position: absolute;
  top: 50%;
  right: -10px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 40px;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-left: 0;
  border-radius: 0 8px 8px 0;
  background: $bg-sidebar-hover;
  color: rgba(255, 255, 255, 0.62);
  cursor: pointer;
  transform: translateY(-50%);
  transition: background $transition-fast, color $transition-fast;

  // 深色导轨上的开关也保持深色：原来白底 + 灰边落在浅色正文区里像一块贴上去的补丁
  &:hover {
    background: $accent-dark;
    color: #fff;
  }

  &:focus-visible {
    outline: 2px solid $accent-light;
    outline-offset: 1px;
  }
}
</style>
