import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getConsoleBootstrap } from './api'
import type { ConsoleMenuItem } from './types'
import { AUTH_USER_KEY } from '../../api/authApi'

const fallbackMenus: ConsoleMenuItem[] = [
  { label: '总览看板', path: '/overview', permission: 'view:overview' },
  { label: '封号率分析', path: '/block-rate', permission: 'view:block-rate' },
  { label: '任务管理', path: '/task', permission: 'view:task' },
  { label: '账号管理', path: '/account', permission: 'view:account' },
  { label: '开窗管理', path: '/window', permission: 'view:window' },
  { label: '留存信息', path: '/retention', permission: 'view:retention' },
  { label: '设备巡检', path: '/device', permission: 'view:device' }
]

export const useConsoleMetaStore = defineStore('console-meta', () => {
  const ready = ref(false)
  const loading = ref(false)
  const username = ref(localStorage.getItem(AUTH_USER_KEY) || '-')
  const permissions = ref<string[]>([])
  const menus = ref<ConsoleMenuItem[]>(fallbackMenus)
  const featureFlags = ref<Record<string, boolean>>({})

  function hasPermission(permission?: string) {
    if (!permission) return true
    if (!permissions.value.length) return true
    return permissions.value.includes(permission)
  }

  async function bootstrap() {
    if (ready.value || loading.value) return
    loading.value = true
    try {
      const res = await getConsoleBootstrap()
      if (res?.success && res.data) {
        username.value = res.data.username || username.value
        permissions.value = res.data.permissions || []
        menus.value = (res.data.menus || []).filter((menu) => hasPermission(menu.permission))
        featureFlags.value = res.data.featureFlags || {}
      }
    } finally {
      ready.value = true
      loading.value = false
    }
  }

  return {
    ready,
    loading,
    username,
    permissions,
    menus,
    featureFlags,
    hasPermission,
    bootstrap
  }
})
