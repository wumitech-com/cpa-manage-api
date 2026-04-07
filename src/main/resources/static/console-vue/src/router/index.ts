import { createRouter, createWebHistory } from 'vue-router'
import { getAuthToken } from '../api/authApi'

// Use route-level lazy loading to reduce first-screen bundle size.
const LoginPage = () => import('../pages/LoginPage.vue')
const AppShell = () => import('../layouts/AppShell.vue')
const OverviewPage = () => import('../pages/OverviewPage.vue')
const BlockRatePage = () => import('../pages/BlockRatePage.vue')
const TaskPage = () => import('../pages/TaskPage.vue')
const AccountPage = () => import('../pages/AccountPage.vue')
const AccountDetailPage = () => import('../pages/AccountDetailPage.vue')
const WindowPage = () => import('../pages/WindowPage.vue')
const RetentionPage = () => import('../pages/RetentionPage.vue')
const DevicePage = () => import('../pages/DevicePage.vue')

const router = createRouter({
  history: createWebHistory('/console-vue/'),
  routes: [
    { path: '/login', component: LoginPage, meta: { title: '登录', guestOnly: true } },
    {
      path: '/',
      component: AppShell,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/overview' },
        { path: 'overview', component: OverviewPage, meta: { title: '总览看板' } },
        { path: 'block-rate', component: BlockRatePage, meta: { title: '封号率分析' } },
        { path: 'task', component: TaskPage, meta: { title: '任务管理' } },
        { path: 'account', component: AccountPage, meta: { title: '账号管理' } },
        { path: 'account/:id', component: AccountDetailPage, meta: { title: '账号详情' } },
        { path: 'window', component: WindowPage, meta: { title: '开窗管理' } },
        { path: 'retention', component: RetentionPage, meta: { title: '留存信息' } },
        { path: 'device', component: DevicePage, meta: { title: '设备巡检' } }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const token = getAuthToken()
  const needAuth = Boolean(to.matched.some((m) => m.meta?.requiresAuth))
  const guestOnly = Boolean(to.meta?.guestOnly)
  if (needAuth && !token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (guestOnly && token) {
    return { path: '/overview' }
  }
  return true
})

export default router
