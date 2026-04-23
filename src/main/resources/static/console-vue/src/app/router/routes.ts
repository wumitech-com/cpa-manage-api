import type { RouteRecordRaw } from 'vue-router'

const LoginPage = () => import('../../pages/LoginPage.vue')
const AppShell = () => import('../../layouts/AppShell.vue')
const RegisterEmailPage = () => import('../../features/register/pages/RegisterEmailPage.vue')
const TaskDispatchPage = () => import('../../features/task/pages/TaskDispatchPage.vue')
const OverviewDashboardPage = () => import('../../features/overview/pages/OverviewDashboardPage.vue')
const BlockRateAnalysisPage = () => import('../../features/block-rate/pages/BlockRateAnalysisPage.vue')
const TaskManagementPage = () => import('../../features/task/pages/TaskManagementPage.vue')
const DeviceInspectionPage = () => import('../../features/device/pages/DeviceInspectionPage.vue')

export const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginPage, meta: { title: '登录', guestOnly: true } },
  {
    path: '/',
    component: AppShell,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/overview' },
      { path: 'overview', component: OverviewDashboardPage, meta: { title: '总览看板' } },
      { path: 'block-rate', component: BlockRateAnalysisPage, meta: { title: '封号率分析' } },
      { path: 'task', component: TaskManagementPage, meta: { title: '任务管理' } },
      { path: 'device', component: DeviceInspectionPage, meta: { title: '设备巡检' } },
      {
        path: 'register/email',
        component: RegisterEmailPage,
        meta: { title: '邮箱管理' }
      },
      {
        path: 'task/dispatch',
        component: TaskDispatchPage,
        meta: { title: '任务下发' }
      },
      { path: ':pathMatch(.*)*', redirect: '/overview' }
    ]
  }
]
