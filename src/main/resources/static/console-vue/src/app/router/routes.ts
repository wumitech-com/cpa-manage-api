import type { RouteRecordRaw } from 'vue-router'

const LoginPage = () => import('../../pages/LoginPage.vue')
const AppShell = () => import('../../layouts/AppShell.vue')
const PlaceholderPage = () => import('../../pages/PlaceholderPage.vue')
const PhoneServerAssetPage = () => import('../../features/asset/pages/PhoneServerAssetPage.vue')
const CloudPhoneManagementPage = () => import('../../features/task/pages/RegisterDispatchPage.vue')
const RegisterServerPage = () => import('../../features/register/pages/RegisterServerPage.vue')
const RegisterPhonePage = () => import('../../features/register/pages/RegisterPhonePage.vue')
const RegisterEmailPage = () => import('../../features/register/pages/RegisterEmailPage.vue')
const TaskDispatchPage = () => import('../../features/task/pages/TaskDispatchPage.vue')
const OverviewDashboardPage = () => import('../../features/overview/pages/OverviewDashboardPage.vue')
const BlockRateAnalysisPage = () => import('../../features/block-rate/pages/BlockRateAnalysisPage.vue')
const TaskManagementPage = () => import('../../features/task/pages/TaskManagementPage.vue')
const AccountManagementPage = () => import('../../features/account/pages/AccountManagementPage.vue')
const WindowManagementPage = () => import('../../features/window/pages/WindowManagementPage.vue')
const RetentionInfoPage = () => import('../../features/retention/pages/RetentionInfoPage.vue')
const DeviceInspectionPage = () => import('../../features/device/pages/DeviceInspectionPage.vue')
const AccountDetailPage = () => import('../../pages/AccountDetailPage.vue')

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
      { path: 'account', component: AccountManagementPage, meta: { title: '账号管理' } },
      { path: 'account/:id', component: AccountDetailPage, meta: { title: '账号详情' } },
      { path: 'window', component: WindowManagementPage, meta: { title: '开窗管理' } },
      { path: 'retention', component: RetentionInfoPage, meta: { title: '留存信息' } },
      { path: 'device', component: DeviceInspectionPage, meta: { title: '设备巡检' } },
      {
        path: 'resource/server',
        component: PhoneServerAssetPage,
        meta: { title: '服务器管理' }
      },
      {
        path: 'resource/phone',
        component: CloudPhoneManagementPage,
        meta: { title: '云手机管理' }
      },
      {
        path: 'register/server',
        component: RegisterServerPage,
        meta: { title: '注册服务器' }
      },
      {
        path: 'register/phone',
        component: RegisterPhonePage,
        meta: { title: '注册云手机' }
      },
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
      {
        path: 'design-lab',
        component: PlaceholderPage,
        meta: {
          title: '重构设计台',
          tip: '所有业务页面已临时隐藏。接下来我们将逐页设计并逐步开放。'
        }
      },
      { path: ':pathMatch(.*)*', redirect: '/design-lab' }
    ]
  }
]
