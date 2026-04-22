<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import PageBlock from '../../../components/PageBlock.vue'
import {
  batchUpdateDispatch,
  listDispatchCandidates,
  listDispatchLogs,
  type BatchUpdateDispatchPayload,
  type DispatchCandidate,
  type DispatchLogItem
} from '../../../api/registerDispatchApi'

const loading = ref(false)
const rows = ref<DispatchCandidate[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selectedRows = ref<DispatchCandidate[]>([])

const logLoading = ref(false)
const logs = ref<DispatchLogItem[]>([])
const logTotal = ref(0)
const logPage = ref(1)
const logSize = ref(20)

const filters = reactive({
  statuses: ['STOPPED', 'COMPLETED'] as Array<'STOPPED' | 'COMPLETED' | 'FAILED'>,
  serverIp: '',
  phoneId: ''
})

const form = reactive({
  taskType: 'FAKE_EMAIL',
  tiktokVersionDir: '',
  country: '',
  sdk: '',
  imagePath: '',
  dynamicIpChannel: '',
  staticIpChannel: '',
  continuous: true,
  targetCount: 1
})

const sdkOptions = ['33', '34']

const tiktokVersionByCountry: Record<string, string[]> = {
  US: ['44.3.3', '43.9.3'],
  MX: ['44.3.3', '43.9.3'],
  BR: ['44.5.3', '43.9.3']
}

const imageTagBySdk: Record<string, string> = {
  '33': 'android13_cpu:20260327',
  '34': 'android14_cpu:20260327'
}

const dynamicIpChannelOptions = ['lajiao', 'netnut_biu', 'kookeey', 'ipweb']
const staticIpChannelOptions = ['ipidea']

const tiktokVersionOptions = computed(() => {
  const country = (form.country || '').trim().toUpperCase()
  const versions = tiktokVersionByCountry[country] || []
  return versions
})

const imagePathOptions = computed(() => {
  const sdk = (form.sdk || '').trim()
  const base = 'uhub.service.ucloud.cn/phone/'
  if (sdk && imageTagBySdk[sdk]) {
    return [`${base}${imageTagBySdk[sdk]}`]
  }
  return Object.values(imageTagBySdk).map((tag) => `${base}${tag}`)
})

function toSuggestions(source: string[], queryString: string) {
  const q = (queryString || '').trim().toLowerCase()
  const list = !q ? source : source.filter((item) => item.toLowerCase().includes(q))
  return list.map((item) => ({ value: item }))
}

function queryCountrySuggestions(queryString: string, cb: (arg: Array<{ value: string }>) => void) {
  cb(toSuggestions(['US', 'MX', 'BR'], queryString))
}

function querySdkSuggestions(queryString: string, cb: (arg: Array<{ value: string }>) => void) {
  cb(toSuggestions(sdkOptions, queryString))
}

function queryTiktokSuggestions(queryString: string, cb: (arg: Array<{ value: string }>) => void) {
  cb(toSuggestions(tiktokVersionOptions.value, queryString))
}

function queryImageSuggestions(queryString: string, cb: (arg: Array<{ value: string }>) => void) {
  cb(toSuggestions(imagePathOptions.value, queryString))
}

function queryDynamicIpSuggestions(queryString: string, cb: (arg: Array<{ value: string }>) => void) {
  cb(toSuggestions(dynamicIpChannelOptions, queryString))
}

function queryStaticIpSuggestions(queryString: string, cb: (arg: Array<{ value: string }>) => void) {
  cb(toSuggestions(staticIpChannelOptions, queryString))
}

function normalizeTiktokVersionDir(raw: string, country: string) {
  const text = (raw || '').trim()
  const c = (country || '').trim().toLowerCase()
  if (!text) return undefined
  if (!c) return text
  const fullMatch = text.match(/^com\.zhiliaoapp\.musically_([a-z]{2})_(.+)$/i)
  if (fullMatch && fullMatch[2]) {
    return `com.zhiliaoapp.musically_${c}_${fullMatch[2]}`
  }
  const shortMatch = text.match(/^\d+\.\d+\.\d+$/)
  if (shortMatch) {
    return `com.zhiliaoapp.musically_${c}_${text}`
  }
  return text
}

function normalizeImagePath(raw: string, sdk: string) {
  const text = (raw || '').trim()
  if (!text) return undefined
  const androidBySdk: Record<string, string> = {
    '33': '13',
    '34': '14'
  }
  const androidVersion = androidBySdk[(sdk || '').trim()]
  if (!androidVersion) return text
  if (/android\d+_cpu/i.test(text)) {
    return text.replace(/android\d+_cpu/i, `android${androidVersion}_cpu`)
  }
  return text
}

const logFilters = reactive({
  serverIp: '',
  phoneId: ''
})

const selectedCount = computed(() => selectedRows.value.length)

function validateBeforeSubmit() {
  if (selectedRows.value.length === 0) {
    ElMessage.warning('请先勾选要更新的云手机任务')
    return false
  }
  if (!form.continuous && Number(form.targetCount || 0) <= 0) {
    ElMessage.warning('非持续注册时，注册轮次必须大于0')
    return false
  }
  return true
}

async function loadCandidates() {
  loading.value = true
  try {
    const res = await listDispatchCandidates({
      statuses: filters.statuses,
      serverIp: filters.serverIp.trim() || undefined,
      phoneId: filters.phoneId.trim() || undefined,
      page: page.value,
      size: size.value
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载候选任务失败')
      return
    }
    rows.value = res.data.list || []
    total.value = Number(res.data.total || 0)
  } finally {
    loading.value = false
  }
}

async function onBatchUpdate() {
  if (!validateBeforeSubmit()) return
  loading.value = true
  try {
    const payload: BatchUpdateDispatchPayload = {
      taskIds: selectedRows.value.map((v) => v.id),
      taskType: form.taskType as 'FAKE_EMAIL' | 'REAL_EMAIL',
      tiktokVersionDir: normalizeTiktokVersionDir(form.tiktokVersionDir, form.country),
      country: form.country.trim() || undefined,
      sdk: form.sdk.trim() || undefined,
      imagePath: normalizeImagePath(form.imagePath, form.sdk),
      dynamicIpChannel: form.dynamicIpChannel.trim() || undefined,
      staticIpChannel: form.staticIpChannel.trim() || undefined,
      continuous: form.continuous,
      targetCount: form.continuous ? undefined : Number(form.targetCount || 1)
    }
    const res = await batchUpdateDispatch(payload)
    if (!res?.success || !res.data) {
      ElMessage.error(res?.message || '批量更新失败')
      return
    }
    ElMessage.success(`已更新 ${res.data.successCount} 条，跳过 ${res.data.skipCount} 条`)
    await loadCandidates()
    await loadLogs()
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logLoading.value = true
  try {
    const res = await listDispatchLogs({
      serverIp: logFilters.serverIp.trim() || undefined,
      phoneId: logFilters.phoneId.trim() || undefined,
      page: logPage.value,
      size: logSize.value
    })
    if (!res?.success || !res.data) {
      logs.value = []
      logTotal.value = 0
      return
    }
    logs.value = res.data.list || []
    logTotal.value = Number(res.data.total || 0)
  } finally {
    logLoading.value = false
  }
}

onMounted(async () => {
  await loadCandidates()
  await loadLogs()
})
</script>

<template>
  <section>
    <PageHeader title="注册任务调度" description="筛选已停止/已完成云手机任务，勾选后批量更新配置并重置为 PENDING" />

    <div class="workspace">
      <div class="dispatch-layout">
        <PageBlock title="候选任务" class="left-panel">
          <div class="toolbar filter-bar">
            <el-select v-model="filters.statuses" multiple collapse-tags style="width: 260px">
              <el-option label="已停止 STOPPED" value="STOPPED" />
              <el-option label="已完成 COMPLETED" value="COMPLETED" />
              <el-option label="失败 FAILED" value="FAILED" />
            </el-select>
            <el-input v-model="filters.serverIp" placeholder="server_ip" style="width: 180px" />
            <el-input v-model="filters.phoneId" placeholder="phone_id" style="width: 220px" />
            <el-button type="primary" @click="page = 1; loadCandidates()">查询</el-button>
          </div>
          <div class="summary-row">
            <span class="summary-chip">已选 {{ selectedCount }} 条</span>
            <span class="summary-chip muted">共 {{ total }} 条候选任务</span>
          </div>
          <div class="left-table-wrap">
            <el-table
              v-loading="loading"
              :data="rows"
              size="small"
              border
              stripe
            height="100%"
              @selection-change="(val: DispatchCandidate[]) => (selectedRows = val)"
            >
              <el-table-column type="selection" width="48" />
              <el-table-column prop="phoneId" label="phone_id" min-width="180" />
              <el-table-column prop="serverIp" label="server_ip" min-width="140" />
              <el-table-column prop="status" label="status" width="120" />
              <el-table-column prop="targetCount" label="target_count" width="120" />
              <el-table-column prop="updatedAt" label="updated_at" min-width="170" />
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="size"
              layout="total, sizes, prev, pager, next"
              :total="total"
              :page-sizes="[20, 50, 100]"
              @current-change="loadCandidates"
              @size-change="page = 1; loadCandidates()"
            />
          </div>
        </PageBlock>

        <PageBlock title="配置面板" class="right-panel">
          <div class="right-config-scroll">
            <div class="group-title">任务策略</div>
            <el-form label-width="88px" class="config-form">
              <el-form-item label="任务类型">
                <el-radio-group v-model="form.taskType">
                <el-radio label="FAKE_EMAIL">随机邮箱</el-radio>
                <el-radio label="REAL_EMAIL">真实邮箱</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="持续注册">
                <el-radio-group v-model="form.continuous">
                  <el-radio :label="true">是</el-radio>
                  <el-radio :label="false">否</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="!form.continuous" label="注册轮次">
                <el-input-number v-model="form.targetCount" :min="1" :max="5000" />
              </el-form-item>
            </el-form>

            <div class="group-title">环境参数</div>
            <el-form label-width="88px" class="config-form">
              <el-form-item label="国家">
                <el-autocomplete
                  v-model="form.country"
                  :fetch-suggestions="queryCountrySuggestions"
                  style="width: 100%"
                  clearable
                  @blur="form.country = (form.country || '').trim().toUpperCase()"
                />
              </el-form-item>
              <el-form-item label="SDK">
                <el-autocomplete
                  v-model="form.sdk"
                  :fetch-suggestions="querySdkSuggestions"
                  style="width: 100%"
                  clearable
                  @blur="form.sdk = (form.sdk || '').trim()"
                />
              </el-form-item>
              <el-form-item label="TikTok版本">
                <el-autocomplete
                  v-model="form.tiktokVersionDir"
                  :fetch-suggestions="queryTiktokSuggestions"
                  style="width: 100%"
                  :disabled="!form.country"
                  clearable
                  @blur="form.tiktokVersionDir = (form.tiktokVersionDir || '').trim()"
                />
              </el-form-item>
              <el-form-item label="镜像路径">
                <el-autocomplete
                  v-model="form.imagePath"
                  :fetch-suggestions="queryImageSuggestions"
                  style="width: 100%"
                  clearable
                  @blur="form.imagePath = (form.imagePath || '').trim()"
                />
              </el-form-item>
            </el-form>

            <div class="group-title">网络参数</div>
            <el-form label-width="88px" class="config-form">
              <el-form-item label="动态IP渠道">
                <el-autocomplete
                  v-model="form.dynamicIpChannel"
                  :fetch-suggestions="queryDynamicIpSuggestions"
                  style="width: 100%"
                  clearable
                  @blur="form.dynamicIpChannel = (form.dynamicIpChannel || '').trim()"
                />
              </el-form-item>
              <el-form-item label="静态IP渠道">
                <el-autocomplete
                  v-model="form.staticIpChannel"
                  :fetch-suggestions="queryStaticIpSuggestions"
                  style="width: 100%"
                  clearable
                  @blur="form.staticIpChannel = (form.staticIpChannel || '').trim()"
                />
              </el-form-item>
            </el-form>
          </div>

          <div class="action-bar">
            <el-button type="success" :loading="loading" @click="onBatchUpdate">更新选中任务（{{ selectedCount }}）</el-button>
          </div>
        </PageBlock>
      </div>

      <PageBlock title="调度日志" class="log-panel">
        <div class="toolbar filter-bar">
          <el-input v-model="logFilters.serverIp" placeholder="server_ip" style="width: 160px" />
          <el-input v-model="logFilters.phoneId" placeholder="phone_id" style="width: 200px" />
          <el-button type="primary" @click="logPage = 1; loadLogs()">查询日志</el-button>
        </div>
        <el-table v-loading="logLoading" :data="logs" size="small" border stripe>
          <el-table-column prop="batchId" label="batch_id" min-width="190" />
          <el-table-column prop="serverIp" label="server_ip" min-width="140" />
          <el-table-column prop="phoneId" label="phone_id" min-width="170" />
          <el-table-column prop="oldStatus" label="old_status" width="110" />
          <el-table-column prop="newStatus" label="new_status" width="110" />
          <el-table-column prop="result" label="result" width="90" />
          <el-table-column prop="message" label="message" min-width="180" />
          <el-table-column prop="createdAt" label="created_at" min-width="170" />
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="logPage"
            v-model:page-size="logSize"
            layout="total, sizes, prev, pager, next"
            :total="logTotal"
            :page-sizes="[20, 50, 100]"
            @current-change="loadLogs"
            @size-change="logPage = 1; loadLogs()"
          />
        </div>
      </PageBlock>
    </div>
  </section>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 10px; align-items: center; }
.filter-bar {
  padding: 10px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #edf1f7;
}
.summary-row {
  display: flex;
  gap: 8px;
  margin: 8px 0 10px;
}
.summary-chip {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: #334155;
  background: #eef2ff;
}
.muted { color: #64748b; background: #f1f5f9; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.workspace { display: flex; flex-direction: column; gap: 12px; }
.dispatch-layout {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
  gap: 12px;
  align-items: stretch;
  --panel-height: clamp(560px, calc(100vh - 250px), 760px);
}
.left-panel,
.right-panel {
  height: var(--panel-height);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.left-table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.left-table-wrap :deep(.el-table) { height: 100%; }
.right-config-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}
.group-title {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  margin: 4px 0 10px;
}
.config-form :deep(.el-form-item) { margin-bottom: 14px; }
.action-bar {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  padding-top: 10px;
  border-top: 1px solid #edf1f7;
}
</style>
