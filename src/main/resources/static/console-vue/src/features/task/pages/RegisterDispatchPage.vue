<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import PageBlock from '../../../components/PageBlock.vue'
import {
  batchCreatePhoneDeviceByRule,
  deletePhoneDevice,
  getPhoneDeviceList,
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
  status: '',
  serverIp: '',
  phoneId: ''
})
const statusOptions = ['IDLE', 'BUSY', 'OFFLINE', 'DISABLED']

const createVisible = ref(false)
const editVisible = ref(false)
const editForm = reactive({
  id: 0,
  serverIp: '',
  phoneId: '',
  deviceStatus: 'IDLE',
  note: ''
})
const createForm = reactive({
  phonePrefix: 'tt_farm',
  serverIp: '',
  count: 10,
  note: ''
})

async function loadData() {
  loading.value = true
  try {
    const res = await getPhoneDeviceList({
      page: page.value,
      size: size.value,
      deviceStatus: filters.status || undefined,
      serverIp: filters.serverIp.trim(),
      phoneId: filters.phoneId.trim()
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载失败')
      return
    }
    rows.value = res.data.list || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  filters.status = ''
  filters.serverIp = ''
  filters.phoneId = ''
  page.value = 1
  loadData()
}

function openEdit(row: PhoneDeviceItem) {
  editForm.id = row.id
  editForm.serverIp = row.serverIp
  editForm.phoneId = row.phoneId
  editForm.deviceStatus = row.deviceStatus || 'IDLE'
  editForm.note = row.note || ''
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.id) return
  const res = await updatePhoneDevice(editForm.id, {
    serverIp: editForm.serverIp.trim(),
    phoneId: editForm.phoneId.trim(),
    deviceStatus: editForm.deviceStatus as 'IDLE' | 'BUSY' | 'OFFLINE' | 'DISABLED',
    note: editForm.note.trim() || undefined
  })
  if (!res?.success) return ElMessage.error(res?.message || '保存失败')
  ElMessage.success('更新成功')
  editVisible.value = false
  loadData()
}

async function submitCreate() {
  if (!createForm.phonePrefix.trim() || !createForm.serverIp.trim()) {
    ElMessage.warning('phonePrefix / serverIp 必填')
    return
  }
  const res = await batchCreatePhoneDeviceByRule({
    phonePrefix: createForm.phonePrefix.trim(),
    serverIp: createForm.serverIp.trim(),
    count: Number(createForm.count || 0),
    note: createForm.note.trim() || undefined
  })
  if (!res?.success) return ElMessage.error(res?.message || '新增失败')
  const inserted = Number(res.data?.insertCount || 0)
  const skip = Number(res.data?.skipCount || 0)
  ElMessage.success(`新增成功 ${inserted} 台，跳过 ${skip} 台`)
  createVisible.value = false
  loadData()
}

async function handleDelete(row: PhoneDeviceItem) {
  if (!row.id) return
  const res = await deletePhoneDevice(row.id)
  if (!res?.success) return ElMessage.error(res?.message || '删除失败')
  ElMessage.success('已删除')
  loadData()
}

onMounted(loadData)
onMounted(() => {
  const action = String(route.query.action || '')
  const serverIp = String(route.query.serverIp || '')
  if (action === 'batchCreate' && serverIp) {
    createForm.serverIp = serverIp
    createVisible.value = true
  }
})
</script>

<template>
  <section>
    <PageHeader title="云手机管理" description="云手机资产管理（独立于任务表）">
      <el-button type="primary" @click="createVisible = true">批量新增云手机</el-button>
    </PageHeader>

    <PageBlock title="筛选">
      <div class="toolbar">
        <el-select v-model="filters.status" style="width: 150px">
          <el-option key="" label="全部" value="" />
          <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
        </el-select>
        <el-input v-model="filters.serverIp" placeholder="serverIp" style="width: 200px" />
        <el-input v-model="filters.phoneId" placeholder="phoneId" style="width: 240px" />
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="resetFilter">重置</el-button>
      </div>
    </PageBlock>

    <PageBlock title="云手机列表">
      <el-table :data="rows" v-loading="loading" border size="small" @row-dblclick="openEdit">
        <el-table-column prop="serverIp" label="serverIp" min-width="140" />
        <el-table-column prop="phoneId" label="phoneId" min-width="200" />
        <el-table-column prop="deviceStatus" label="deviceStatus" width="130" />
        <el-table-column prop="note" label="note" min-width="200" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          layout="total, sizes, prev, pager, next"
          :total="total"
          :page-sizes="[20, 50, 100]"
          @current-change="loadData"
          @size-change="page = 1; loadData()"
        />
      </div>
    </PageBlock>

    <el-dialog v-model="createVisible" title="批量新增云手机" width="640px">
      <el-form label-width="130px">
        <el-form-item label="云手机前缀"><el-input v-model="createForm.phonePrefix" placeholder="如 tt_farm" /></el-form-item>
        <el-form-item label="serverIp"><el-input v-model="createForm.serverIp" /></el-form-item>
        <el-form-item label="新增数量"><el-input-number v-model="createForm.count" :min="1" :max="500" /></el-form-item>
        <el-form-item label="默认状态"><el-input value="IDLE (空闲)" disabled /></el-form-item>
        <el-form-item label="note"><el-input v-model="createForm.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">确定新增</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑云手机" width="640px">
      <el-form label-width="130px">
        <el-form-item label="serverIp"><el-input v-model="editForm.serverIp" /></el-form-item>
        <el-form-item label="phoneId"><el-input v-model="editForm.phoneId" /></el-form-item>
        <el-form-item label="deviceStatus">
          <el-select v-model="editForm.deviceStatus">
            <el-option v-for="item in statusOptions" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="note"><el-input v-model="editForm.note" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; align-items: center; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
</style>
