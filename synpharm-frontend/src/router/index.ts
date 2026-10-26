import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'Home',
      component: () => import('@/views/Home.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/Login.vue'),
      meta: { requiresAuth: false }
    },
    
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: () => import('@/views/Dashboard.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/predict',
      name: 'Predict',
      component: () => import('@/views/Predict.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/results',
      name: 'Results',
      component: () => import('@/views/Results.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/tasks',
      name: 'Tasks',
      component: () => import('@/views/Tasks.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/tasks/:id',
      name: 'TaskDetail',
      component: () => import('@/views/TaskDetail.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/targets',
      name: 'Targets',
      component: () => import('@/views/Targets.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/result/:id?',
      name: 'ResultDetail',
      component: () => import('@/views/ResultDetail.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/visualization',
      name: 'Visualization',
      component: () => import('@/views/Visualization.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/profile',
      name: 'Profile',
      component: () => import('@/views/Profile.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/design',
      name: 'Design',
      component: () => import('@/views/Design.vue'),
      meta: { requiresAuth: true }
    },
    {
      // 需求分析（阶段 B）：八块完整性预检 + 冻结基线
      path: '/design/projects/:projectId/baseline',
      name: 'Baseline',
      component: () => import('@/views/Baseline.vue'),
      meta: { requiresAuth: true }
    },
    {
      // 生成（阶段 C）：发起批次、看漏斗、看诊断、查逐分子去向
      path: '/design/projects/:projectId/generation',
      name: 'Generation',
      component: () => import('@/views/Generation.vue'),
      meta: { requiresAuth: true }
    },
    {
      // 设计项目列表：阶段 A~D 各页的唯一入口（此前只能手敲 URL 进基线/生成/评估）
      path: '/projects',
      name: 'Projects',
      component: () => import('@/views/Projects.vue'),
      meta: { requiresAuth: true }
    },
    {
      // 评估与编辑（阶段 D）：冻结快照 → 缺口诊断 → 动作匹配 → 影响面 → 版本树
      path: '/design/candidates/:candidateId/evaluation',
      name: 'Evaluation',
      component: () => import('@/views/Evaluation.vue'),
      meta: { requiresAuth: true }
    },
    {
      // 决策（阶段 E）：证据装配 → 否决复核 → 分桶 → 名额 → 回放指纹
      path: '/design/projects/:projectId/decision',
      name: 'Decision',
      component: () => import('@/views/Decision.vue'),
      meta: { requiresAuth: true }
    }
  ]
})

/** 登录后默认跳转的首页路由。设计流程是当前主线，落地就见到项目列表 */
const DEFAULT_HOME_PATH = '/projects'

router.beforeEach((to, _from, next) => {
  const authStore = useAuthStore()

  // ---------- 分支 1：访问根路径 ----------
  if (to.path === '/') {
    // 已登录 → 直接去仪表盘；未登录 → 留在首页
    if (authStore.isLoggedIn) {
      next(DEFAULT_HOME_PATH)
      return
    }
    next()
    return
  }

  // ---------- 分支 2：访问登录页 ----------
  if (to.path === '/login') {
    if (authStore.isLoggedIn) {
      // 已登录还来登录页 → 去首页（优先使用 redirect 参数，否则用默认首页）
      const redirect = (to.query.redirect as string) || DEFAULT_HOME_PATH
      next(redirect)
      return
    }
    next()
    return
  }

  // ---------- 分支 3：访问需要认证的页面 ----------
  if (to.meta.requiresAuth && !authStore.isLoggedIn) {
    // 未登录 → 跳登录页，并带上 redirect 参数以便登录后跳回
    next({
      path: '/login',
      query: { redirect: to.fullPath }
    })
    return
  }

  // ---------- 其他情况：放行 ----------
  next()
})

export default router