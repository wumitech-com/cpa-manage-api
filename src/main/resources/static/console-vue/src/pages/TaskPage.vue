<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import PageBlock from '../components/PageBlock.vue'
import { getTaskList, resumeTask, stopTask, updateTask, type TaskItem } from '../api/taskApi'

const loading = ref(false)
const rows = ref<TaskItem[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)

const filters = reactive({
  status: 'ALL',
  serverIp: '',
  phoneId: ''
})

const statusOptions = ['ALL', 'PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'STOPPED']
const activeRow = ref<TaskItem | null>(null)
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({
  taskId: '',
  country: '',
  sdk: '',
  imagePath: '',
  gaidTag: '',
  dynamicIpChannel: '',
  staticIpChannel: '',
  biz: '',
  targetCount: ''
})

async function loadData() {
  loading.value = true
  try {
    const res = await getTaskList({
      page: page.value,
      size: size.value,
      status: filters.status,
      serverIp: filters.serverIp.trim(),
      phoneId: filters.phoneId.trim()
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载任务失败')
      return
    }
    const data = res.data
    if (typeof data.size === 'number' && [10, 50, 100].includes(Number(data.size))) {
      size.value = Number(data.size)
    }
    if (typeof data.page === 'number' && data.page > 0) {
      page.value = Number(data.page)
    }
    const rawList = data.list || []
    const serverTotal = Number(data.total || 0)
    if (serverTotal > 0) {
      rows.value = rawList
      total.value = serverTotal
    } else {
      total.value = rawList.length
      const from = Math.max(0, (page.value - 1) * size.value)
      rows.value = rawList.slice(from, from + size.value)
    }
  } finally {
    loading.value = false
  }
}

function reset() {
  filters.status = 'ALL'
  filters.serverIp = ''
  filters.phoneId = ''
  page.value = 1
  loadData()
}

async function handleStop(row: TaskItem) {
  if (!row.taskId) return
  const res = await stopTask(row.taskId)
  if (!res?.success) {
    ElMessage.error(res?.message || '停止失败')
    return
  }
  ElMessage.success(res.message || '任务已停止')
  loadData()
}

async function handleResumeRow(row: TaskItem) {
  if (!row.taskId) return
  const res = await resumeTask(row.taskId)
  if (!res?.success) {
    ElMessage.error(res?.message || '恢复失败')
    return
  }
  ElMessage.success(res.message || '任务已恢复')
  loadData()
}

function statusType(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'RUNNING') return 'warning'
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED' || status === 'STOPPED') return 'danger'
  return 'info'
}

function openEdit(row: TaskItem) {
  activeRow.value = row
  editForm.taskId = row.taskId || ''
  editForm.country = row.country || ''
  editForm.sdk = row.sdk || ''
  editForm.imagePath = row.imagePath || ''
  editForm.gaidTag = row.gaidTag || ''
  editForm.dynamicIpChannel = row.dynamicIpChannel || ''
  editForm.staticIpChannel = row.staticIpChannel || ''
  editForm.biz = row.biz || ''
  editForm.targetCount = row.targetCount == null ? '' : String(row.targetCount)
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.taskId) {
    ElMessage.warning('任务ID为空，无法保存')
    return
  }
  editSaving.value = true
  try {
    const res = await updateTask({
      taskId: editForm.taskId,
      country: editForm.country || undefined,
      sdk: editForm.sdk || undefined,
      imagePath: editForm.imagePath || undefined,
      gaidTag: editForm.gaidTag || undefined,
      dynamicIpChannel: editForm.dynamicIpChannel || undefined,
      staticIpChannel: editForm.staticIpChannel || undefined,
      biz: editForm.biz || undefined,
      targetCount: editForm.targetCount || undefined
    })
    if (!res?.success) {
      ElMessage.error(res?.message || '保存失败')
      return
    }
    ElMessage.success(res.message || '保存成功')
    editVisible.value = false
    loadData()
  } finally {
    editSaving.value = false
  }
}

async function handleResume() {
  if (!activeRow.value?.taskId) return
  const res = await resumeTask(activeRow.value.taskId)
  if (!res?.success) {
    ElMessage.error(res?.message || '恢复失败')
    return
  }
  ElMessage.success(res.message || '任务已恢复')
  editVisible.value = false
  loadData()
}

onMounted(loadData)
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>任务管理</h2>
        <p>任务查询、分页与运行状态控制</p>
      </div>
      <div class="toolbar">
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </div>

    <PageBlock title="筛选条件">
      <div class="toolbar">
        <el-select v-model="filters.status" style="width: 160px">
          <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-input v-model="filters.serverIp" placeholder="serverIp" style="width: 180px" />
        <el-input v-model="filters.phoneId" placeholder="phoneId" style="width: 220px" />
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </PageBlock>

    <PageBlock title="任务列表">
      <el-table :data="rows" v-loading="loading" style="width: 100%" @row-click="openEdit">
        <el-table-column prop="status" label="状态" min-width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="serverIp" label="服务器" min-width="160" />
        <el-table-column prop="phoneId" label="设备ID" min-width="220" />
        <el-table-column label="开始时间" min-width="180">
          <template #default="{ row }">{{ row.startTime || row.createdAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="180">
          <template #default="{ row }">{{ row.endTime || row.updatedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'STOPPED' || row.status === 'FAILED'"
              size="small"
              type="warning"
              plain
              @click.stop="handleResumeRow(row)"
            >
              恢复
            </el-button>
            <el-button size="small" type="danger" plain @click.stop="handleStop(row)">停止</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :page-sizes="[10, 50, 100]"
          @current-change="loadData"
          @size-change="page = 1; loadData()"
        />
      </div>
    </PageBlock>

    <el-dialog v-model="editVisible" title="任务参数编辑" width="780px">
      <el-form label-width="120px">
        <el-form-item label="任务ID">
          <el-input v-model="editForm.taskId" disabled />
        </el-form-item>
        <el-form-item label="国家">
          <el-input v-model="editForm.country" />
        </el-form-item>
        <el-form-item label="SDK">
          <el-input v-model="editForm.sdk" />
        </el-form-item>
        <el-form-item label="镜像路径">
          <el-input v-model="editForm.imagePath" />
        </el-form-item>
        <el-form-item label="GAID标签">
          <el-input v-model="editForm.gaidTag" />
        </el-form-item>
        <el-form-item label="动态IP渠道">
          <el-input v-model="editForm.dynamicIpChannel" />
        </el-form-item>
        <el-form-item label="静态IP渠道">
          <el-input v-model="editForm.staticIpChannel" />
        </el-form-item>
        <el-form-item label="业务标识">
          <el-input v-model="editForm.biz" />
        </el-form-item>
        <el-form-item label="目标数量">
          <el-input v-model="editForm.targetCount" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">关闭</el-button>
        <el-button type="warning" plain @click="handleResume">恢复任务</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>
