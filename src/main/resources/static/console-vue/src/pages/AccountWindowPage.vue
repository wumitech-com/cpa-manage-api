<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import PageBlock from '../components/PageBlock.vue'
import { exportAccountList, getAccountList, getWindowList, importAccountCsv, type AccountItem, type WindowItem } from '../api/registerApi'

const activeTab = ref('account')

const accountRows = ref<AccountItem[]>([])
const accountPage = ref(1)
const accountSize = ref(10)
const accountTotal = ref(0)
const accountLoading = ref(false)
const accountExporting = ref(false)
const accountImporting = ref(false)
const accountTotalAccurate = ref(true)
const accountQuery = reactive({
  startDate: '',
  endDate: '',
  status: 'ALL',
  accountType: 'ALL',
  username: '',
  country: '',
  region: '',
  note: ''
})

const windowRows = ref<WindowItem[]>([])
const windowPage = ref(1)
const windowSize = ref(10)
const windowTotal = ref(0)
const windowLoading = ref(false)
const windowTotalAccurate = ref(true)
const windowQuery = reactive({
  fanStartDate: '',
  fanEndDate: '',
  nurtureStartDate: '',
  nurtureEndDate: '',
  nurtureStrategy: 'ALL',
  shopStatus: 'ALL',
  nurtureDevice: 'ALL',
  country: 'ALL',
  account: '',
  note: ''
})

async function loadAccount() {
  accountLoading.value = true
  try {
    const res = await getAccountList({
      page: accountPage.value,
      size: accountSize.value,
      startDate: accountQuery.startDate || undefined,
      endDate: accountQuery.endDate || undefined,
      status: accountQuery.status === 'ALL' ? undefined : accountQuery.status,
      accountType: accountQuery.accountType === 'ALL' ? undefined : accountQuery.accountType,
      username: accountQuery.username.trim() || undefined,
      country: accountQuery.country.trim() || undefined,
      region: accountQuery.region.trim() || undefined,
      note: accountQuery.note.trim() || undefined
    })
    if (!res?.success || !res.data) {
      accountRows.value = []
      accountTotal.value = 0
      ElMessage.error(res?.message || '加载账号失败')
      return
    }
    accountRows.value = res.data.list || []
    accountTotal.value = res.data.total || 0
    accountTotalAccurate.value = res.data.totalAccurate !== false
  } finally {
    accountLoading.value = false
  }
}

async function loadWindow() {
  windowLoading.value = true
  try {
    const res = await getWindowList({
      page: windowPage.value,
      size: windowSize.value,
      fanStartDate: windowQuery.fanStartDate || undefined,
      fanEndDate: windowQuery.fanEndDate || undefined,
      nurtureStartDate: windowQuery.nurtureStartDate || undefined,
      nurtureEndDate: windowQuery.nurtureEndDate || undefined,
      nurtureStrategy: windowQuery.nurtureStrategy === 'ALL' ? undefined : windowQuery.nurtureStrategy,
      shopStatus: windowQuery.shopStatus === 'ALL' ? undefined : windowQuery.shopStatus,
      nurtureDevice: windowQuery.nurtureDevice === 'ALL' ? undefined : windowQuery.nurtureDevice,
      country: windowQuery.country === 'ALL' ? undefined : windowQuery.country,
      account: windowQuery.account.trim() || undefined,
      note: windowQuery.note.trim() || undefined
    })
    if (!res?.success || !res.data) {
      windowRows.value = []
      windowTotal.value = 0
      ElMessage.error(res?.message || '加载开窗失败')
      return
    }
    windowRows.value = res.data.list || []
    windowTotal.value = res.data.total || 0
    windowTotalAccurate.value = res.data.totalAccurate !== false
  } finally {
    windowLoading.value = false
  }
}

function resetAccount() {
  accountQuery.startDate = ''
  accountQuery.endDate = ''
  accountQuery.status = 'ALL'
  accountQuery.accountType = 'ALL'
  accountQuery.username = ''
  accountQuery.country = ''
  accountQuery.region = ''
  accountQuery.note = ''
  accountPage.value = 1
  accountSize.value = 10
  loadAccount()
}

function resetWindow() {
  windowQuery.fanStartDate = ''
  windowQuery.fanEndDate = ''
  windowQuery.nurtureStartDate = ''
  windowQuery.nurtureEndDate = ''
  windowQuery.nurtureStrategy = 'ALL'
  windowQuery.shopStatus = 'ALL'
  windowQuery.nurtureDevice = 'ALL'
  windowQuery.country = 'ALL'
  windowQuery.account = ''
  windowQuery.note = ''
  windowPage.value = 1
  windowSize.value = 10
  loadWindow()
}

function toCsvCell(v: unknown) {
  const text = String(v ?? '')
  if (text.includes(',') || text.includes('"') || text.includes('\n')) {
    return `"${text.replaceAll('"', '""')}"`
  }
  return text
}

function downloadCsv(rows: AccountItem[]) {
  const headers = ['注册日期', '状态', '账号', '密码', '邮箱', '账号类别', '备注', '详情', '国家']
  const lines = [headers.join(',')]
  rows.forEach((r) => {
    lines.push([
      toCsvCell(r.createdAt),
      toCsvCell(r.status),
      toCsvCell(r.username),
      toCsvCell(r.password),
      toCsvCell(r.email),
      toCsvCell(r.accountType),
      toCsvCell(r.note),
      toCsvCell(`${r.state || ''} ${r.city || ''}`.trim()),
      toCsvCell(r.ip)
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

async function handleExport() {
  accountExporting.value = true
  try {
    const res = await exportAccountList({
      page: 1,
      size: 200000,
      startDate: accountQuery.startDate || undefined,
      endDate: accountQuery.endDate || undefined,
      status: accountQuery.status === 'ALL' ? undefined : accountQuery.status,
      accountType: accountQuery.accountType === 'ALL' ? undefined : accountQuery.accountType,
      username: accountQuery.username.trim() || undefined,
      country: accountQuery.country.trim() || undefined,
      region: accountQuery.region.trim() || undefined,
      note: accountQuery.note.trim() || undefined
    })
    if (!res?.success || !res.data) {
      ElMessage.error(res?.message || '导出失败')
      return
    }
    downloadCsv(res.data)
    ElMessage.success('导出成功')
  } finally {
    accountExporting.value = false
  }
}

async function handleImportCsv(file: File) {
  accountImporting.value = true
  try {
    const res = await importAccountCsv(file)
    if (!res?.success) {
      ElMessage.error(res?.message || '导入失败')
      return false
    }
    ElMessage.success(`导入完成：新增${res.insertCount || 0}，更新${res.updateCount || 0}，跳过${res.skipCount || 0}`)
    accountPage.value = 1
    await loadAccount()
    return true
  } finally {
    accountImporting.value = false
  }
}

function beforeImport(file: File) {
  void handleImportCsv(file)
  return false
}

onMounted(() => {
  loadAccount()
  loadWindow()
})
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>账号与开窗管理</h2>
        <p>统一查看账号资产、开窗状态和关键筛选</p>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="账号管理" name="account">
        <PageBlock title="账号筛选">
          <div class="toolbar wrap">
            <el-date-picker v-model="accountQuery.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
            <el-date-picker v-model="accountQuery.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
            <el-select v-model="accountQuery.status" style="width: 120px">
              <el-option label="全部状态" value="ALL" />
              <el-option label="可售" value="可售" />
              <el-option label="已售" value="已售" />
              <el-option label="封号" value="封号" />
            </el-select>
            <el-input v-model="accountQuery.username" placeholder="账号" style="width: 180px" />
            <el-input v-model="accountQuery.country" placeholder="国家" style="width: 120px" />
            <el-input v-model="accountQuery.region" placeholder="州/地区" style="width: 160px" />
            <el-input v-model="accountQuery.note" placeholder="备注" style="width: 160px" />
            <el-button type="primary" @click="accountPage = 1; loadAccount()">查询</el-button>
            <el-button @click="resetAccount">重置</el-button>
            <el-button :loading="accountExporting" @click="handleExport">导出CSV</el-button>
            <el-upload :show-file-list="false" :before-upload="beforeImport" accept=".csv">
              <el-button :loading="accountImporting">导入CSV</el-button>
            </el-upload>
          </div>
        </PageBlock>
        <PageBlock title="账号列表">
          <el-table :data="accountRows" v-loading="accountLoading" style="width: 100%">
            <el-table-column prop="createdAt" label="注册日期" min-width="165" />
            <el-table-column prop="status" label="状态" width="90" />
            <el-table-column prop="username" label="账号" min-width="140" />
            <el-table-column prop="email" label="邮箱" min-width="180" />
            <el-table-column prop="accountType" label="账号类别" width="120" />
            <el-table-column prop="ip" label="IP" min-width="130" />
            <el-table-column prop="state" label="州" min-width="120" />
            <el-table-column prop="city" label="城市" min-width="120" />
          </el-table>
          <div class="pager">
            <div class="total-hint">{{ accountTotalAccurate ? `共 ${accountTotal} 条` : `约 ${accountTotal} 条` }}</div>
            <el-pagination
              v-model:current-page="accountPage"
              v-model:page-size="accountSize"
              background
              layout="sizes, prev, pager, next"
              :total="accountTotal"
              :page-sizes="[10, 20, 50, 100]"
              @current-change="loadAccount"
              @size-change="accountPage = 1; loadAccount()"
            />
          </div>
        </PageBlock>
      </el-tab-pane>

      <el-tab-pane label="开窗管理" name="window">
        <PageBlock title="开窗筛选">
          <div class="toolbar wrap">
            <el-date-picker v-model="windowQuery.fanStartDate" type="date" value-format="YYYY-MM-DD" placeholder="刷粉开始" />
            <el-date-picker v-model="windowQuery.fanEndDate" type="date" value-format="YYYY-MM-DD" placeholder="刷粉结束" />
            <el-date-picker v-model="windowQuery.nurtureStartDate" type="date" value-format="YYYY-MM-DD" placeholder="养号开始" />
            <el-date-picker v-model="windowQuery.nurtureEndDate" type="date" value-format="YYYY-MM-DD" placeholder="养号结束" />
            <el-select v-model="windowQuery.nurtureStrategy" style="width: 130px">
              <el-option label="全部策略" value="ALL" />
              <el-option label="不刷粉" value="不刷粉" />
              <el-option label="刷粉" value="刷粉" />
            </el-select>
            <el-select v-model="windowQuery.shopStatus" style="width: 160px">
              <el-option label="全部状态" value="ALL" />
              <el-option label="未开店" value="未开店" />
              <el-option label="不可开店" value="不可开店" />
              <el-option label="开店成功" value="开店成功" />
            </el-select>
            <el-select v-model="windowQuery.nurtureDevice" style="width: 130px">
              <el-option label="全部设备" value="ALL" />
              <el-option label="云手机" value="云手机" />
              <el-option label="主板机" value="主板机" />
            </el-select>
            <el-input v-model="windowQuery.country" placeholder="国家" style="width: 120px" />
            <el-input v-model="windowQuery.account" placeholder="账号" style="width: 180px" />
            <el-input v-model="windowQuery.note" placeholder="备注" style="width: 160px" />
            <el-button type="primary" @click="windowPage = 1; loadWindow()">查询</el-button>
            <el-button @click="resetWindow">重置</el-button>
          </div>
        </PageBlock>
        <PageBlock title="开窗列表">
          <el-table :data="windowRows" v-loading="windowLoading" style="width: 100%">
            <el-table-column prop="username" label="账号" min-width="140" />
            <el-table-column prop="fanDate" label="刷粉日期" min-width="150" />
            <el-table-column prop="nurtureDate" label="养号日期" min-width="150" />
            <el-table-column prop="nurtureStrategy" label="养号策略" min-width="120" />
            <el-table-column prop="shopStatus" label="开窗状态" min-width="120" />
            <el-table-column prop="registerIp" label="注册IP归属" min-width="160" />
            <el-table-column prop="registerEnv" label="注册环境" min-width="160" />
          </el-table>
          <div class="pager">
            <div class="total-hint">{{ windowTotalAccurate ? `共 ${windowTotal} 条` : `约 ${windowTotal} 条` }}</div>
            <el-pagination
              v-model:current-page="windowPage"
              v-model:page-size="windowSize"
              background
              layout="sizes, prev, pager, next"
              :total="windowTotal"
              :page-sizes="[10, 20, 50, 100]"
              @current-change="loadWindow"
              @size-change="windowPage = 1; loadWindow()"
            />
          </div>
        </PageBlock>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
