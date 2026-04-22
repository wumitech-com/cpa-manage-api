<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import PageBlock from '../../../components/PageBlock.vue'
import { countPhoneDeviceByServer } from '../../../api/phoneDeviceApi'
import { disablePhoneServer, enablePhoneServer, getRegisterServerList, type PhoneServerItem } from '../../../api/phoneServerApi'

const loading = ref(false)
const rows = ref<Array<PhoneServerItem & { deviceCount?: number }>>([])
const router = useRouter()

async function loadData() {
  loading.value = true
  try {
    const res = await getRegisterServerList()
    if (!res?.success || !res.data) {
      rows.value = []
      ElMessage.error(res?.message || '加载注册服务器失败')
      return
    }
    const list = (res.data || []) as Array<PhoneServerItem & { deviceCount?: number }>
    const serverIps = list.map((item) => item.serverIp).filter(Boolean)
    if (serverIps.length > 0) {
      const countRes = await countPhoneDeviceByServer(serverIps)
      const countMap = countRes?.success && countRes.data ? countRes.data : {}
      list.forEach((row) => {
        row.deviceCount = Number(countMap[row.serverIp] ?? 0)
      })
    }
    rows.value = list
  } finally {
    loading.value = false
  }
}

async function toggleStatus(row: PhoneServerItem) {
  const req = row.status === 0 ? disablePhoneServer : enablePhoneServer
  const res = await req(row.id)
  if (!res?.success) {
    ElMessage.error(res?.message || '状态更新失败')
    return
  }
  ElMessage.success(row.status === 0 ? '已禁用注册服务器' : '已启用注册服务器')
  await loadData()
}

function goRegisterPhone(row: PhoneServerItem) {
  router.push({
    path: '/register/phone',
    query: {
      serverIp: row.serverIp
    }
  })
}

onMounted(loadData)
</script>

<template>
  <section>
    <PageHeader title="注册服务器" description="仅展示已分配到注册用途（REGISTER/MIXED）的服务器">
      <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
    </PageHeader>
    <PageBlock title="注册服务器池">
      <el-table :data="rows" border v-loading="loading">
        <el-table-column prop="serverIp" label="server_ip" min-width="170" />
        <el-table-column prop="usageScope" label="usage_scope" width="140" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'">{{ row.status === 0 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="maxConcurrency" label="max_concurrency" width="140" />
        <el-table-column prop="deviceCount" label="云手机数" width="110" />
        <el-table-column prop="appiumServer" label="appium_server" min-width="160" />
        <el-table-column prop="xrayServerIp" label="xray_server_ip" min-width="150" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goRegisterPhone(row)">查看云手机</el-button>
            <el-button type="warning" link @click="toggleStatus(row)">
              {{ row.status === 0 ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageBlock>
  </section>
</template>
