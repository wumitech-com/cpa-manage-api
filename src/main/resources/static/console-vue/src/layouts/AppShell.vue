<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { AUTH_USER_KEY, clearAuth } from '../api/authApi'

const route = useRoute()
const router = useRouter()
const currentUser = localStorage.getItem(AUTH_USER_KEY) || '-'

const menus = [
  { label: '总览看板', path: '/overview' },
  { label: '封号率分析', path: '/block-rate' },
  { label: '任务管理', path: '/task' },
  { label: '账号管理', path: '/account' },
  { label: '开窗管理', path: '/window' },
  { label: '留存信息', path: '/retention' },
  { label: '设备巡检', path: '/device' }
]

function go(path: string) {
  if (route.path === path) return
  router.push(path)
}

function logout() {
  clearAuth()
  router.replace('/login')
}
</script>

<template>
  <div class="shell">
    <aside class="shell-sidebar">
      <div class="brand-title">TT Enterprise Console</div>
      <div class="brand-sub">Data Visualization Center</div>
      <div class="brand-sub" style="margin-top: 4px">当前用户：{{ currentUser }}</div>
      <el-button style="margin: 10px 0 6px; width: 100%" @click="logout">退出登录</el-button>
      <div class="menu-wrap">
        <button
          v-for="item in menus"
          :key="item.path"
          :class="['menu-item', route.path === item.path ? 'active' : '']"
          @click="go(item.path)"
        >
          {{ item.label }}
        </button>
      </div>
    </aside>
    <main class="shell-main">
      <router-view />
    </main>
  </div>
</template>
