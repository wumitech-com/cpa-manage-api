<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import PageBlock from '../../../components/PageBlock.vue'
import {
  getRegisterPhoneDeviceList,
  updatePhoneDevice,
  type PhoneDeviceItem
} from '../../../api/phoneDeviceApi'

const loading = ref(false)
const rows = ref<PhoneDeviceItem[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const route = useRoute()

const filters = reactive({
  serverIp: '',
  phoneId: '',
  deviceStatus: ''
})
const statusOptions = ['IDLE', 'BUSY', 'OFFLINE', 'DISABLED']

async function loadData() {
  loading.value = true
  try {
    const res = await getRegisterPhoneDeviceList({
      serverIp: filters.serverIp.trim() || undefined,
      phoneId: filters.phoneId.trim() || undefined,
      deviceStatus: filters.deviceStatus || undefined,
      page: page.value,
      size: size.value
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载注册云手机失败')
      return
    }
    rows.value = res.data.list || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filters.serverIp = ''
  filters.phoneId = ''
  filters.deviceStatus = ''
  page.value = 1
  loadData()
}

async function setStatus(row: PhoneDeviceItem, nextStatus: 'IDLE' | 'DISABLED') {
  const res = await updatePhoneDevice(row.id, { deviceStatus: nextStatus })
  if (!res?.success) {
    ElMessage.error(res?.message || '状态更新失败')
    return
  }
  ElMessage.success(nextStatus === 'IDLE' ? '已启用云手机' : '已禁用云手机')
  await loadData()
}

onMounted(() => {
  const serverIp = String(route.query.serverIp || '')
  if (serverIp) {
    filters.serverIp = serverIp
  }
  loadData()
})
</script>

<template>
  <section>
    <PageHeader title="注册云手机" description="仅展示注册服务器池下的云手机设备">
      <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
    </PageHeader>
    <PageBlock title="筛选条件">
      <div class="toolbar">
        <el-input v-model="filters.serverIp" placeholder="server_ip" style="width: 200px" />
        <el-input v-model="filters.phoneId" placeholder="phone_id" style="width: 240px" />
        <el-select v-model="filters.deviceStatus" style="width: 150px">
          <el-option key="" label="全部状态" value="" />
          <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>
    </PageBlock>
    <PageBlock title="注册云手机列表">
      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="serverIp" label="server_ip" min-width="160" />
        <el-table-column prop="phoneId" label="phone_id" min-width="240" />
        <el-table-column prop="deviceStatus" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.deviceStatus === 'IDLE' ? 'success' : row.deviceStatus === 'DISABLED' ? 'info' : 'warning'">
              {{ row.deviceStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="note" min-width="180" />
        <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.deviceStatus !== 'IDLE'" type="success" link @click="setStatus(row, 'IDLE')">启用</el-button>
            <el-button v-if="row.deviceStatus !== 'DISABLED'" type="warning" link @click="setStatus(row, 'DISABLED')">禁用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[20, 50, 100]"
          @current-change="loadData"
          @size-change="page = 1; loadData()"
        />
      </div>
    </PageBlock>
  </section>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
