<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import PageBlock from '../../../components/PageBlock.vue'
import {
  batchCreatePhoneServer,
  disablePhoneServer,
  enablePhoneServer,
  getPhoneServerList,
  updatePhoneServer,
  type PhoneServerItem
} from '../../../api/phoneServerApi'
import { countPhoneDeviceByServer } from '../../../api/phoneDeviceApi'

const loading = ref(false)
const rows = ref<Array<PhoneServerItem & { deviceCount?: number }>>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const router = useRouter()

const filters = reactive({
  serverIp: '',
  status: '' as '' | '0' | '1',
  usageScope: '' as '' | 'NONE' | 'REGISTER' | 'RETENTION' | 'MIXED'
})

const statusOptions = [
  { label: '全部', value: '' },
  { label: '启用', value: '0' },
  { label: '禁用', value: '1' }
]
const usageOptions = [
  { label: '全部用途', value: '' },
  { label: '未分配(NONE)', value: 'NONE' },
  { label: '注册(REGISTER)', value: 'REGISTER' },
  { label: '留存(RETENTION)', value: 'RETENTION' },
  { label: '混用(MIXED)', value: 'MIXED' }
]

const batchRows = ref<Array<{
  serverIp: string
  xrayServerIp: string
  appiumServer: string
  maxConcurrency: number
  usageScope: 'NONE' | 'REGISTER' | 'RETENTION' | 'MIXED'
  note: string
}>>([
  { serverIp: '', xrayServerIp: '192.168.40.249', appiumServer: '10.13.58.129', maxConcurrency: 8, usageScope: 'NONE', note: '' }
])

const editVisible = ref(false)
const editForm = reactive({
  id: 0,
  xrayServerIp: '192.168.40.249',
  appiumServer: '10.13.58.129',
  maxConcurrency: 8,
  usageScope: 'NONE' as 'NONE' | 'REGISTER' | 'RETENTION' | 'MIXED',
  note: ''
})

const IPV4_REGEX =
  /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/

async function loadData() {
  loading.value = true
  try {
    const res = await getPhoneServerList({
      serverIp: filters.serverIp.trim() || undefined,
      status: filters.status === '' ? undefined : Number(filters.status),
      usageScope: filters.usageScope || undefined,
      page: page.value,
      size: size.value
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载资产列表失败')
      return
    }
    const serverRows = (res.data.list || []) as Array<PhoneServerItem & { deviceCount?: number }>
    const serverIps = serverRows.map((item) => item.serverIp).filter((v) => Boolean(v))
    if (serverIps.length > 0) {
      const countResp = await countPhoneDeviceByServer(serverIps)
      const countMap = countResp?.success && countResp.data ? countResp.data : {}
      serverRows.forEach((row) => {
        row.deviceCount = Number(countMap[row.serverIp] ?? 0)
      })
    }
    rows.value = serverRows
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

function addBatchRow() {
  batchRows.value.push({ serverIp: '', xrayServerIp: '192.168.40.249', appiumServer: '10.13.58.129', maxConcurrency: 8, usageScope: 'NONE', note: '' })
}

function removeBatchRow(index: number) {
  if (batchRows.value.length === 1) return
  batchRows.value.splice(index, 1)
}

async function submitBatchCreate() {
  const payload = batchRows.value
    .map((row) => ({
      serverIp: row.serverIp.trim(),
      xrayServerIp: row.xrayServerIp.trim() || undefined,
      appiumServer: row.appiumServer.trim() || undefined,
      maxConcurrency: Number(row.maxConcurrency || 8),
      status: 0,
      usageScope: row.usageScope || 'NONE',
      note: row.note.trim() || undefined
    }))
    .filter((row) => row.serverIp)

  if (payload.length === 0) {
    ElMessage.warning('请至少填写一条 server_ip')
    return
  }
  const invalid = payload.find((item) => !IPV4_REGEX.test(item.serverIp))
  if (invalid) {
    ElMessage.warning(`IP 格式不正确: ${invalid.serverIp}`)
    return
  }
  if (payload.some((item) => item.maxConcurrency <= 0)) {
    ElMessage.warning('最大并发数必须大于0')
    return
  }

  const res = await batchCreatePhoneServer(payload)
  if (!res?.success || !res.data) {
    ElMessage.error(res?.message || '批量入库失败')
    return
  }
  const insertCount = Number(res.data.insertCount || 0)
  const skipCount = Number(res.data.skipCount || 0)
  const skipped = (res.data.skippedServerIps || []).slice(0, 5).join(', ')
  if (insertCount > 0) {
    ElMessage.success(`入库成功 ${insertCount} 条，跳过 ${skipCount} 条`)
  } else {
    ElMessage.warning(`本次无新增，跳过 ${skipCount} 条${skipped ? `（${skipped}）` : ''}`)
  }

  batchRows.value = [{ serverIp: '', xrayServerIp: '192.168.40.249', appiumServer: '10.13.58.129', maxConcurrency: 8, usageScope: 'NONE', note: '' }]
  page.value = 1
  await loadData()
}

function resetFilters() {
  filters.serverIp = ''
  filters.status = ''
  filters.usageScope = ''
  page.value = 1
  loadData()
}

function openEdit(row: PhoneServerItem) {
  editForm.id = row.id
  editForm.xrayServerIp = row.xrayServerIp || '192.168.40.249'
  editForm.appiumServer = row.appiumServer || '10.13.58.129'
  editForm.maxConcurrency = Number(row.maxConcurrency || 8)
  editForm.usageScope = (row.usageScope as 'NONE' | 'REGISTER' | 'RETENTION' | 'MIXED') || 'NONE'
  editForm.note = row.note || ''
  editVisible.value = true
}

async function saveEdit() {
  if (editForm.maxConcurrency <= 0) {
    ElMessage.warning('最大并发数必须大于0')
    return
  }
  const res = await updatePhoneServer(editForm.id, {
    xrayServerIp: editForm.xrayServerIp.trim() || undefined,
    appiumServer: editForm.appiumServer.trim() || undefined,
    maxConcurrency: Number(editForm.maxConcurrency || 8),
    usageScope: editForm.usageScope || 'NONE',
    note: editForm.note.trim() || undefined
  })
  if (!res?.success) {
    ElMessage.error(res?.message || '保存失败')
    return
  }
  ElMessage.success('保存成功')
  editVisible.value = false
  await loadData()
}

async function toggleStatus(row: PhoneServerItem) {
  const req = row.status === 0 ? disablePhoneServer : enablePhoneServer
  const res = await req(row.id)
  if (!res?.success) {
    ElMessage.error(res?.message || '状态更新失败')
    return
  }
  ElMessage.success(row.status === 0 ? '已禁用' : '已启用')
  await loadData()
}

function goCreatePhone(row: PhoneServerItem) {
  router.push({
    path: '/resource/phone',
    query: {
      serverIp: row.serverIp,
      action: 'batchCreate'
    }
  })
}

onMounted(async () => {
  await loadData()
})
</script>

<template>
  <section>
    <PageHeader title="服务器管理" description="手工/批量入库、编辑与启用禁用" />

    <PageBlock title="筛选条件">
      <div class="toolbar wrap">
        <el-input v-model="filters.serverIp" style="width: 220px" placeholder="server_ip" />
        <el-select v-model="filters.status" style="width: 160px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.usageScope" style="width: 180px">
          <el-option v-for="item in usageOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </PageBlock>

    <PageBlock title="批量录入">
      <el-table :data="batchRows" size="small" border>
        <el-table-column label="server_ip" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.serverIp" placeholder="必填，IPv4" />
          </template>
        </el-table-column>
        <el-table-column label="xray_server_ip" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.xrayServerIp" placeholder="可空" />
          </template>
        </el-table-column>
        <el-table-column label="appium_server" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.appiumServer" placeholder="可空，如 10.0.0.1:4723" />
          </template>
        </el-table-column>
        <el-table-column label="max_concurrency" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.maxConcurrency" :min="1" :max="200" />
          </template>
        </el-table-column>
        <el-table-column label="usage_scope" width="170">
          <template #default="{ row }">
            <el-select v-model="row.usageScope" style="width: 150px">
              <el-option v-for="item in usageOptions.filter((i) => i.value)" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="note" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.note" placeholder="备注" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ $index }">
            <el-button type="danger" link @click="removeBatchRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="toolbar" style="margin-top: 10px">
        <el-button @click="addBatchRow">新增一行</el-button>
        <el-button type="primary" @click="submitBatchCreate">批量入库</el-button>
      </div>
    </PageBlock>

    <PageBlock title="资产列表">
      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="serverIp" label="server_ip" min-width="170" />
        <el-table-column prop="xrayServerIp" label="xray_server_ip" min-width="150" />
        <el-table-column prop="appiumServer" label="appium_server" min-width="170" />
        <el-table-column prop="maxConcurrency" label="max_concurrency" width="150" />
        <el-table-column prop="usageScope" label="usage_scope" width="130" />
        <el-table-column prop="deviceCount" label="云手机数" width="110" />
        <el-table-column label="status" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'">{{ row.status === 0 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="note" min-width="180" />
        <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="success" link @click="goCreatePhone(row)">新增云手机</el-button>
            <el-button type="warning" link @click="toggleStatus(row)">{{ row.status === 0 ? '禁用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="loadData"
          @size-change="page = 1; loadData()"
        />
      </div>
    </PageBlock>

    <el-dialog v-model="editVisible" title="编辑服务器资产" width="620px">
      <el-form label-width="130px">
        <el-form-item label="xray_server_ip">
          <el-input v-model="editForm.xrayServerIp" />
        </el-form-item>
        <el-form-item label="appium_server">
          <el-input v-model="editForm.appiumServer" />
        </el-form-item>
        <el-form-item label="max_concurrency">
          <el-input-number v-model="editForm.maxConcurrency" :min="1" :max="200" />
        </el-form-item>
        <el-form-item label="usage_scope">
          <el-select v-model="editForm.usageScope" style="width: 220px">
            <el-option v-for="item in usageOptions.filter((i) => i.value)" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="note">
          <el-input v-model="editForm.note" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
