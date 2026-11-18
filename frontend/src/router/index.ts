import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { tokenStorage } from '@/api/http'

/**
 * 路由表。
 *
 * meta.public = true 的页面不需要登录：
 *  - /login    登录页
 *  - /trace/** 公开溯源页（二维码扫出来就是它，要求登录等于把公众挡在门外）
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    // 公开溯源独立布局：消费者扫码进来不该看到后台侧边栏
    path: '/trace/:batchNo?',
    name: 'trace',
    component: () => import('@/views/PublicTraceView.vue'),
    meta: { public: true, title: '溯源查询' },
  },
  {
    path: '/',
    component: () => import('@/layout/AppLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '概览', icon: 'DataBoard' },
      },
      {
        path: 'suppliers',
        name: 'suppliers',
        component: () => import('@/views/SupplierView.vue'),
        meta: { title: '供应商备案', icon: 'OfficeBuilding' },
      },
      {
        path: 'purchases',
        name: 'purchases',
        component: () => import('@/views/PurchaseView.vue'),
        meta: { title: '采购批次', icon: 'ShoppingCart' },
      },
      {
        path: 'acceptances',
        name: 'acceptances',
        component: () => import('@/views/AcceptanceView.vue'),
        meta: { title: '入库验收', icon: 'Stamp' },
      },
      {
        path: 'process',
        name: 'process',
        component: () => import('@/views/ProcessView.vue'),
        meta: { title: '加工投料', icon: 'Cpu' },
      },
      {
        path: 'sales',
        name: 'sales',
        component: () => import('@/views/SaleView.vue'),
        meta: { title: '销售登记', icon: 'Sell' },
      },
      {
        path: 'cold-chain',
        name: 'cold-chain',
        component: () => import('@/views/ColdChainView.vue'),
        meta: { title: '冷链温控', icon: 'Sunny' },
      },
      {
        path: 'abnormal',
        name: 'abnormal',
        component: () => import('@/views/AbnormalView.vue'),
        meta: { title: '异常管理', icon: 'WarningFilled' },
      },
      {
        path: 'recalls',
        name: 'recalls',
        component: () => import('@/views/RecallView.vue'),
        meta: { title: '批次召回', icon: 'RefreshLeft' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const isPublic = to.meta.public === true
  const loggedIn = Boolean(tokenStorage.get())

  if (!isPublic && !loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && loggedIn) {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 食堂肉类溯源系统` : '高校食堂肉类供应链溯源系统'
})

export default router
