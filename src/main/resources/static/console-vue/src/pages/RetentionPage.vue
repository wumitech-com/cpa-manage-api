<script setup lang="ts">
import dayjs from 'dayjs'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageBlock from '../components/PageBlock.vue'
import { getRetentionRecords, type RetentionRecord } from '../api/deviceRetentionApi'

const loading = ref(false)
const date = ref(dayjs().format('YYYY-MM-DD'))
const rows = ref<RetentionRecord[]>([])
const page = ref(1)
const size = ref(50)
const total = ref(0)
const successRate = ref(0)
const scriptOk = ref(0)
const backupOk = ref(0)
const backupRate = ref(0)
const twofaSuccess = ref(0)
const logout = ref(0)
const cohortTotal = ref(0)
const cohortBlocked = ref(0)
const cohortLogout = ref(0)
const cohortBlockRate = ref(0)
const allCohortTotal = ref(0)
const allCohortBlocked = ref(0)
const allCohortLogout = ref(0)
const allCohortBlockRate = ref(0)

async function loadData() {
  loading.value = true
  try {
    const res = await getRetentionRecords(date.value, page.value, size.value)
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载留存失败')
      return
    }
    rows.value = res.data.records || []
    total.value = res.data.total || 0
    successRate.value = res.data.successRate || 0
    scriptOk.value = res.data.scriptSuccessCount || 0
    backupOk.value = res.data.backupSuccessCount || 0
    backupRate.value = res.data.backupRate || 0
    twofaSuccess.value = res.data.retention2faSuccess || 0
    logout.value = res.data.retentionLogout || 0
    cohortTotal.value = res.data.cohortTotal || 0
    cohortBlocked.value = res.data.cohortBlocked || 0
    cohortLogout.value = res.data.cohortLogout || 0
    cohortBlockRate.value = res.data.cohortBlockRate || 0
    allCohortTotal.value = res.data.allCohortTotal || 0
    allCohortBlocked.value = res.data.allCohortBlocked || 0
    allCohortLogout.value = res.data.allCohortLogout || 0
    allCohortBlockRate.value = res.data.allCohortBlockRate || 0
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

function setToday() {
  date.value = dayjs().format('YYYY-MM-DD')
  page.value = 1
  loadData()
}
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>留存信息</h2>
        <p>留存统计、成功率与明细分页查询</p>
      </div>
    </div>
    <PageBlock title="留存统计" :desc="`留存成功率：${successRate}%`">
      <div class="grid-4" style="margin-bottom: 12px">
        <div class="card mini-stat"><div class="stat-label">总记录</div><div class="stat-value">{{ total }}</div></div>
        <div class="card mini-stat"><div class="stat-label">脚本成功</div><div class="stat-value">{{ scriptOk }}</div></div>
        <div class="card mini-stat"><div class="stat-label">备份成功</div><div class="stat-value">{{ backupOk }}</div></div>
        <div class="card mini-stat"><div class="stat-label">备份成功率</div><div class="stat-value">{{ backupRate }}%</div></div>
      </div>
      <div class="grid-4" style="margin-bottom: 12px">
        <div class="card mini-stat"><div class="stat-label">2FA成功/注销</div><div class="stat-value">{{ twofaSuccess }} / {{ logout }}</div></div>
        <div class="card mini-stat"><div class="stat-label">当日队列总数</div><div class="stat-value">{{ cohortTotal }}</div></div>
        <div class="card mini-stat"><div class="stat-label">当日队列封禁/注销</div><div class="stat-value">{{ cohortBlocked }} / {{ cohortLogout }}</div></div>
        <div class="card mini-stat"><div class="stat-label">当日队列封禁率</div><div class="stat-value">{{ cohortBlockRate }}%</div></div>
        <div class="card mini-stat"><div class="stat-label">全量队列封禁率</div><div class="stat-value">{{ allCohortBlockRate }}%</div></div>
      </div>
      <div class="grid-3" style="margin-bottom: 12px">
        <div class="card mini-stat"><div class="stat-label">全量队列总数</div><div class="stat-value">{{ allCohortTotal }}</div></div>
        <div class="card mini-stat"><div class="stat-label">全量队列封禁</div><div class="stat-value">{{ allCohortBlocked }}</div></div>
        <div class="card mini-stat"><div class="stat-label">全量队列注销</div><div class="stat-value">{{ allCohortLogout }}</div></div>
      </div>
      <div class="toolbar">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" />
        <el-button @click="setToday">今天</el-button>
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" style="width: 100%; margin-top: 12px">
        <el-table-column prop="taskId" label="任务ID" min-width="130" />
        <el-table-column label="设备" min-width="220">
          <template #default="{ row }">{{ row.phoneServerIp || '-' }} / {{ row.phoneId || '-' }}</template>
        </el-table-column>
        <el-table-column prop="accountRegisterId" label="账号ID" min-width="100" />
        <el-table-column prop="gaid" label="GAID" min-width="130" />
        <el-table-column label="脚本" min-width="90">
          <template #default="{ row }">{{ row.scriptSuccess === true || row.scriptSuccess === 1 ? '成功' : '失败' }}</template>
        </el-table-column>
        <el-table-column label="备份" min-width="90">
          <template #default="{ row }">{{ row.backupSuccess === true || row.backupSuccess === 1 ? '成功' : '失败' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" min-width="170" />
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
  </section>
</template>
