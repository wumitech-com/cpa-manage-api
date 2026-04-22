<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import PageBlock from '../components/PageBlock.vue'
import PageHeader from '../shared/ui/PageHeader.vue'
import {
  exportAccountList,
  getAccountDateSummary,
  getAccountFilterStats,
  getAccountList,
  importAccountCsv,
  startNurtureAccounts,
  type AccountFilterStatsData,
  type AccountItem
} from '../api/registerApi'

const rows = ref<AccountItem[]>([])
const selectedRows = ref<AccountItem[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const importing = ref(false)
const nurturing = ref(false)
const totalAccurate = ref(true)
const summary = reactive({
  registerSuccessCount: 0,
  twofaSuccessCount: 0,
  twofaRate: 0
})
const filterStats = ref<AccountFilterStatsData | null>(null)
const router = useRouter()

const query = reactive({
  startDate: '',
  endDate: '',
  sortOrder: 'desc' as 'asc' | 'desc',
  registerMethod: '云手机',
  username: '',
  country: '',
  // 复选框筛选维度（后端支持：mode=SUCCESS|FAIL|ALL）
  registerStatusMode: 'ALL',
  keyStatusMode: 'ALL',
  matureStatusMode: 'ALL',
  emailBindStatusMode: 'ALL',
  blockStatusMode: 'ALL',
  sellStatusMode: 'ALL',
  shopStatusMode: 'ALL'
})

const registerStatus = ref<string[]>([])
const keyStatus = ref<string[]>([])
const matureStatus = ref<string[]>([])
const emailBindStatus = ref<string[]>([])
const blockStatus = ref<string[]>([])
const sellStatus = ref<string[]>([])
const shopStatus = ref<string[]>([])

function twoWayMode(selected: string[], a: string, b: string) {
  const set = new Set(selected || [])
  const hasA = set.has(a)
  const hasB = set.has(b)
  if ((hasA && hasB) || (!hasA && !hasB)) return 'ALL'
  if (hasA) return a
  return b
}

watch([registerStatus, keyStatus, matureStatus, emailBindStatus, blockStatus, sellStatus, shopStatus], () => {
  query.registerStatusMode = twoWayMode(registerStatus.value, 'SUCCESS', 'FAIL')
  query.keyStatusMode = twoWayMode(keyStatus.value, 'SUCCESS', 'FAIL')
  query.matureStatusMode = twoWayMode(matureStatus.value, 'MATURE', 'UNMATURE')
  query.emailBindStatusMode = twoWayMode(emailBindStatus.value, 'SUCCESS', 'FAIL')
  query.blockStatusMode = twoWayMode(blockStatus.value, 'UNBLOCKED', 'BLOCKED')
  query.sellStatusMode = twoWayMode(sellStatus.value, 'SALEABLE', 'SOLD')
  query.shopStatusMode = twoWayMode(shopStatus.value, 'SHOP', 'MATRIX')
}, { immediate: true })

function effectiveDateRange() {
  if (query.startDate && query.endDate) {
    return { startDate: query.startDate, endDate: query.endDate }
  }
  const today = new Date().toISOString().slice(0, 10)
  return { startDate: today, endDate: today }
}

function registerStatusParam() {
  // 默认不勾选时沿用后端默认口径（仅注册成功）
  if (registerStatus.value.length === 0) return undefined
  if (registerStatus.value.length >= 2) return 'ALL'
  return registerStatus.value[0]
}

function listFilterParams() {
  const { startDate, endDate } = effectiveDateRange()
  return {
    startDate,
    endDate,
    username: query.username.trim() || undefined,
    country: query.country.trim() || undefined,
    registerStatus: registerStatusParam(),
    keyStatus: query.keyStatusMode === 'ALL' ? undefined : query.keyStatusMode,
    matureStatus: query.matureStatusMode === 'ALL' ? undefined : query.matureStatusMode,
    emailBindStatus: query.emailBindStatusMode === 'ALL' ? undefined : query.emailBindStatusMode,
    blockStatus: query.blockStatusMode === 'ALL' ? undefined : query.blockStatusMode,
    sellStatus: query.sellStatusMode === 'ALL' ? undefined : query.sellStatusMode,
    shopStatus: query.shopStatusMode === 'ALL' ? undefined : query.shopStatusMode
  }
}

function fmtPct(part: number) {
  const t = filterStats.value?.total ?? 0
  if (!t || part === undefined || part === null) return '0.00%'
  return `${((Number(part) / t) * 100).toFixed(2)}%`
}

/** 某一维未在筛选里锁死（mode 为 ALL）时才展示该维占比 */
const showStatRegister = computed(() => query.registerStatusMode === 'ALL')
const showStatKey = computed(() => query.keyStatusMode === 'ALL')
const showStatMature = computed(() => query.matureStatusMode === 'ALL')
const showStatEmailBind = computed(() => query.emailBindStatusMode === 'ALL')
const showStatBlock = computed(() => query.blockStatusMode === 'ALL')
const showStatSell = computed(() => query.sellStatusMode === 'ALL')
const showStatWindow = computed(() => query.shopStatusMode === 'ALL')

async function loadFilterStats() {
  const res = await getAccountFilterStats(listFilterParams())
  if (res?.success && res.data) {
    filterStats.value = res.data
  } else {
    filterStats.value = null
  }
}

async function loadData() {
  if (query.registerMethod !== '云手机') {
    rows.value = []
    total.value = 0
    totalAccurate.value = true
    filterStats.value = null
    return
  }
  loading.value = true
  try {
    const res = await getAccountList({
      page: page.value,
      size: size.value,
      sortOrder: query.sortOrder,
      ...listFilterParams()
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      filterStats.value = null
      ElMessage.error(res?.message || '加载账号失败')
      await Promise.all([loadDateSummary(), loadFilterStats()])
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
    totalAccurate.value = data.totalAccurate !== false
    await Promise.all([loadDateSummary(), loadFilterStats()])
  } finally {
    loading.value = false
  }
}

async function loadDateSummary() {
  const { startDate, endDate } = effectiveDateRange()
  const res = await getAccountDateSummary(startDate, endDate)
  if (!res?.success || !res.data) return
  summary.registerSuccessCount = Number(res.data.registerSuccessCount || 0)
  summary.twofaSuccessCount = Number(res.data.twofaSuccessCount || 0)
  summary.twofaRate = Number(res.data.twofaRate || 0)
}

function reset() {
  query.startDate = ''
  query.endDate = ''
  query.sortOrder = 'desc'
  query.registerMethod = '云手机'
  query.username = ''
  query.country = ''
  registerStatus.value = []
  keyStatus.value = []
  matureStatus.value = []
  emailBindStatus.value = []
  blockStatus.value = []
  sellStatus.value = []
  shopStatus.value = []
  page.value = 1
  size.value = 10
  loadData()
}

function toCsvCell(v: unknown) {
  const text = String(v ?? '')
  if (text.includes(',') || text.includes('"') || text.includes('\n')) return `"${text.replaceAll('"', '""')}"`
  return text
}

function downloadCsv(data: AccountItem[]) {
  const headers = ['注册日期', '状态', '账号', '账号类别', '州', '城市', '换绑状态']
  const lines = [headers.join(',')]
  data.forEach((r) => {
    lines.push([
      toCsvCell(formatCreatedAt(r.createdAt)),
      toCsvCell(r.status),
      toCsvCell(r.username),
      toCsvCell(r.accountType),
      toCsvCell(r.state),
      toCsvCell(r.city),
      toCsvCell(r.newEmailBindStatus)
    ].join(','))
  })
  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `账号列表_${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function onSelectionChange(selection: AccountItem[]) {
  selectedRows.value = selection
}

function formatCreatedAt(v?: string) {
  if (!v) return ''
  const d = dayjs(v)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : v
}

function goDetail(row: AccountItem) {
  if (!row.id) {
    ElMessage.warning('该记录缺少ID，无法进入详情')
    return
  }
  router.push(`/account/${row.id}`)
}

async function handleExport() {
  if (selectedRows.value.length > 0) {
    downloadCsv(selectedRows.value)
    ElMessage.success(`已导出勾选账号 ${selectedRows.value.length} 条`)
    return
  }
  exporting.value = true
  try {
    const res = await exportAccountList({
      page: 1,
      size: 200000,
      sortOrder: query.sortOrder,
      ...listFilterParams()
    })
    if (!res?.success || !res.data) {
      ElMessage.error(res?.message || '导出失败')
      return
    }
    downloadCsv(res.data)
    ElMessage.success('导出成功')
  } finally {
    exporting.value = false
  }
}

async function handleStartNurture() {
  const ids = selectedRows.value
    .map((r) => Number(r.id || 0))
    .filter((id) => Number.isFinite(id) && id > 0)
  if (ids.length === 0) {
    ElMessage.warning('请先勾选账号')
    return
  }
  nurturing.value = true
  try {
    const res = await startNurtureAccounts(ids)
    if (!res?.success) {
      ElMessage.error(res?.message || '开始养号失败')
      return
    }
    ElMessage.success(res.message || `开始养号完成：${ids.length} 条`)
    selectedRows.value = []
    await loadData()
  } finally {
    nurturing.value = false
  }
}

async function handleImportCsv(file: File) {
  importing.value = true
  try {
    const res = await importAccountCsv(file)
    if (!res?.success) {
      ElMessage.error(res?.message || '导入失败')
      return false
    }
    const errList = Array.isArray(res?.errors) ? res.errors : []
    const baseMsg = `导入完成：新增${res.insertCount || 0}，更新${res.updateCount || 0}，跳过${res.skipCount || 0}`
    ElMessage.success(baseMsg)
    if (errList.length > 0) {
      ElMessage.warning(`${baseMsg}。其中有 ${errList.length} 条错误，示例：${String(errList[0]).slice(0, 120)}`)
    }
    page.value = 1
    await loadData()
    return true
  } finally {
    importing.value = false
  }
}

function beforeImport(file: File) {
  void handleImportCsv(file)
  return false
}

onMounted(() => {
  loadData()
})
</script>

<template>
  <section>
    <PageHeader title="账号管理" description="筛选、分页、导入导出与账号明细" />
    <PageBlock title="账号筛选">
      <div class="toolbar wrap">
        <el-date-picker v-model="query.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
        <el-date-picker v-model="query.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
        <div class="filter-row">
          <div class="filter-pair">
            <span class="filter-pair-label">注册</span>
            <el-checkbox-group v-model="registerStatus" size="small">
              <el-checkbox label="FAIL">注册失败</el-checkbox>
              <el-checkbox label="SUCCESS">注册成功</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="filter-pair">
            <span class="filter-pair-label">密钥</span>
            <el-checkbox-group v-model="keyStatus" size="small">
              <el-checkbox label="SUCCESS">密钥成功</el-checkbox>
              <el-checkbox label="FAIL">密钥失败</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="filter-pair">
            <span class="filter-pair-label">满月</span>
            <el-checkbox-group v-model="matureStatus" size="small">
              <el-checkbox label="MATURE">满月</el-checkbox>
              <el-checkbox label="UNMATURE">未满</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="filter-pair">
            <span class="filter-pair-label">邮绑</span>
            <el-checkbox-group v-model="emailBindStatus" size="small">
              <el-checkbox label="SUCCESS">邮绑成功</el-checkbox>
              <el-checkbox label="FAIL">邮绑失败</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="filter-pair">
            <span class="filter-pair-label">封号</span>
            <el-checkbox-group v-model="blockStatus" size="small">
              <el-checkbox label="BLOCKED">封号</el-checkbox>
              <el-checkbox label="UNBLOCKED">未封</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="filter-pair">
            <span class="filter-pair-label">售出</span>
            <el-checkbox-group v-model="sellStatus" size="small">
              <el-checkbox label="SOLD">已售</el-checkbox>
              <el-checkbox label="SALEABLE">可售</el-checkbox>
            </el-checkbox-group>
          </div>
          <div class="filter-pair">
            <span class="filter-pair-label">窗口</span>
            <el-checkbox-group v-model="shopStatus" size="small">
              <el-checkbox label="SHOP">橱窗号</el-checkbox>
              <el-checkbox label="MATRIX">矩阵号</el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
        <el-select v-model="query.sortOrder" style="width: 130px">
          <el-option label="注册日期倒序" value="desc" />
          <el-option label="注册日期正序" value="asc" />
        </el-select>
        <el-select v-model="query.registerMethod" style="width: 130px">
          <el-option label="云手机" value="云手机" />
          <el-option label="主板机" value="主板机" />
          <el-option label="魔云腾" value="魔云腾" />
          <el-option label="外采" value="外采" />
        </el-select>
        <el-input v-model="query.username" placeholder="账号" style="width: 180px" />
        <el-select v-model="query.country" style="width: 120px">
          <el-option label="全部国家" value="" />
          <el-option label="US" value="US" />
          <el-option label="MX" value="MX" />
          <el-option label="BR" value="BR" />
          <el-option label="JP" value="JP" />
        </el-select>
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button type="success" :loading="nurturing" @click="handleStartNurture">开始养号</el-button>
        <el-button :loading="exporting" @click="handleExport">导出CSV</el-button>
        <el-upload :show-file-list="false" :before-upload="beforeImport" accept=".csv">
          <el-button :loading="importing">导入CSV</el-button>
        </el-upload>
      </div>
      <div class="toolbar" style="margin-top: 10px;">
        <el-tag type="info">注册成功数：{{ summary.registerSuccessCount }}</el-tag>
        <el-tag type="success">2FA成功数：{{ summary.twofaSuccessCount }}</el-tag>
        <el-tag type="warning">2FA成功率：{{ summary.twofaRate.toFixed(2) }}%</el-tag>
      </div>
      <div v-if="filterStats" class="toolbar wrap filter-stats-bar" style="margin-top: 10px;">
        <span class="filter-stats-lead"
          >筛选结果 <b>{{ filterStats.total }}</b> 条 · 各维占比（未勾选的维度会显示；已锁定的维度隐藏）</span
        >
        <template v-if="showStatRegister">
          <el-tag size="small" effect="plain" type="info"
            >注册 成功{{ fmtPct(filterStats.register.success) }} 失败{{ fmtPct(filterStats.register.fail) }}</el-tag
          >
        </template>
        <template v-if="showStatKey">
          <el-tag size="small" effect="plain"
            >密钥 成功{{ fmtPct(filterStats.key.success) }} 失败{{ fmtPct(filterStats.key.fail) }}</el-tag
          >
        </template>
        <template v-if="showStatMature">
          <el-tag size="small" effect="plain"
            >满月{{ fmtPct(filterStats.mature.mature) }} 未满{{ fmtPct(filterStats.mature.unmature) }}</el-tag
          >
        </template>
        <template v-if="showStatEmailBind">
          <el-tag size="small" effect="plain"
            >邮绑 成功{{ fmtPct(filterStats.emailBind.success) }} 失败{{ fmtPct(filterStats.emailBind.fail) }} 未执行{{
              fmtPct(filterStats.emailBind.none)
            }}</el-tag
          >
        </template>
        <template v-if="showStatBlock">
          <el-tag size="small" effect="plain"
            >封号{{ fmtPct(filterStats.block.blocked) }} 未封{{ fmtPct(filterStats.block.unblocked) }}</el-tag
          >
        </template>
        <template v-if="showStatSell">
          <el-tag size="small" effect="plain"
            >售出 已售{{ fmtPct(filterStats.sell.sold) }} 可售{{ fmtPct(filterStats.sell.saleable) }} 其他{{
              fmtPct(filterStats.sell.other)
            }}</el-tag
          >
        </template>
        <template v-if="showStatWindow">
          <el-tag size="small" effect="plain"
            >窗口 橱窗{{ fmtPct(filterStats.window.shop) }} 矩阵{{ fmtPct(filterStats.window.matrix) }} 其他{{
              fmtPct(filterStats.window.other)
            }}</el-tag
          >
        </template>
      </div>
    </PageBlock>
    <PageBlock title="账号列表">
      <el-table :data="rows" v-loading="loading" style="width: 100%" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="50" />
        <el-table-column label="注册日期" width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ formatCreatedAt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80" />
        <el-table-column prop="username" label="账号" min-width="120" show-overflow-tooltip />
        <el-table-column prop="accountType" label="账号类别" width="100" show-overflow-tooltip />
        <el-table-column prop="state" label="州" min-width="90" show-overflow-tooltip />
        <el-table-column prop="city" label="城市" min-width="90" show-overflow-tooltip />
        <el-table-column prop="newEmailBindStatus" label="换绑状态" width="100" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <div class="total-hint">{{ totalAccurate ? `共 ${total} 条` : `约 ${total} 条` }}</div>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          background
          layout="sizes, prev, pager, next"
          :total="total"
          :page-sizes="[10, 50, 100]"
          @current-change="loadData"
          @size-change="page = 1; loadData()"
        />
      </div>
    </PageBlock>
  </section>
</template>

<style scoped>
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
}

.filter-pair {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 320px;
  flex: 0 0 320px;
}

.filter-pair-label {
  font-weight: 600;
  font-size: 12px;
  color: var(--el-text-color-regular);
  min-width: 44px;
}

.filter-pair :deep(.el-checkbox-group) {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

.filter-pair :deep(.el-checkbox) {
  margin-right: 0;
}

.filter-stats-bar {
  align-items: flex-start;
}

.filter-stats-lead {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

</style>
