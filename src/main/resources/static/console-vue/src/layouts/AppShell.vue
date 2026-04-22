<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AUTH_USER_KEY, clearAuth } from '../api/authApi'

const route = useRoute()
const router = useRouter()
const currentUser = computed(() => localStorage.getItem(AUTH_USER_KEY) || '-')
const groupOpen = reactive<Record<string, boolean>>({
  '数据统计': true
})

const menuGroups = [
  {
    title: '数据统计',
    children: [
      { label: '总览看板', path: '/overview' },
      { label: '封号率分析', path: '/block-rate' },
      { label: '云手机管理', path: '/task' },
      { label: '设备恢复', path: '/device' },
      { label: '邮箱管理', path: '/register/email' },
      { label: '注册任务调度', path: '/task/dispatch' }
    ]
  }
]

function go(path: string) {
  if (route.path === path) return
  router.push(path)
}

function logout() {
  clearAuth()
  router.replace('/login')
}

function toggleGroup(title: string) {
  groupOpen[title] = !groupOpen[title]
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
        <div v-for="group in menuGroups" :key="group.title">
          <button class="group-title-btn" @click="toggleGroup(group.title)">
            <span>{{ group.title }}</span>
            <span>{{ groupOpen[group.title] ? '▾' : '▸' }}</span>
          </button>
          <div v-show="groupOpen[group.title]">
            <button
              v-for="item in group.children"
              :key="item.path"
              :class="['menu-item', route.path === item.path ? 'active' : '']"
              @click="go(item.path)"
            >
              {{ item.label }}
            </button>
          </div>
        </div>
      </div>
    </aside>
    <main class="shell-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.group-title-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: none;
  background: transparent;
  color: #909399;
  font-size: 12px;
  margin: 8px 0 6px;
  padding: 0;
  cursor: pointer;
}

.menu-item {
  width: 100%;
  display: block;
  text-align: left;
  margin: 6px 0;
  padding: 10px 14px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: #f5f7fa;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.menu-item:hover {
  background: rgba(64, 158, 255, 0.18);
}

.menu-item.active {
  background: rgba(64, 158, 255, 0.28);
}
</style>
