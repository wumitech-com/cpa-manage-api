<script setup lang="ts">
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { onMounted, reactive, ref } from 'vue'
import PageBlock from '../components/PageBlock.vue'
import { getRetentionRecords, inspectDevice, type RetentionRecord } from '../api/deviceRetentionApi'

const inspectLoading = ref(false)
const inspectForm = reactive({
  phoneId: '',
  gaid: ''
})
const inspectResult = ref('')
const tunnelCommand = ref('')
const adbCommand = ref('')

const retentionLoading = ref(false)
const retentionDate = ref(dayjs().format('YYYY-MM-DD'))
const retentionRows = ref<RetentionRecord[]>([])
const retentionPage = ref(1)
const retentionSize = ref(20)
const retentionTotal = ref(0)
const retentionRate = ref(0)
const scriptOk = ref(0)
const backupOk = ref(0)
const twofaSuccess = ref(0)
const retentionLogout = ref(0)

async function runInspect() {
  if (!inspectForm.phoneId.trim() || !inspectForm.gaid.trim()) {
    ElMessage.warning('phone_id 和 gaid 不能为空')
    return
  }
  inspectLoading.value = true
  try {
    const res = await inspectDevice(inspectForm.phoneId.trim(), inspectForm.gaid.trim())
    if (!res?.success || !res.data) {
      inspectResult.value = res?.message || '执行失败'
      ElMessage.error(inspectResult.value)
      return
    }
    inspectResult.value = [
      `恢复结果: ${res.message || '成功'}`,
      `端口转发: ${res.data.tunnelCommand || '-'}`,
      `ADB连接: ${res.data.adbConnectCommand || '-'}`,
      `服务IP: ${res.data.serverIp || '-'}`
    ].join('\n')
    tunnelCommand.value = res.data.tunnelCommand || ''
    adbCommand.value = res.data.adbConnectCommand || ''
  } catch (err: any) {
    const isTimeout = err?.code === 'ECONNABORTED' || String(err?.message || '').toLowerCase().includes('timeout')
    inspectResult.value = isTimeout
      ? '执行超时（已等待 180 秒）。后端任务可能仍在执行，请稍后重试或查看后端日志确认最终结果。'
      : `执行失败：${err?.response?.data?.message || err?.message || '未知错误'}`
    ElMessage.error(isTimeout ? '设备巡检超时，请稍后重试' : '设备巡检失败')
  } finally {
    inspectLoading.value = false
  }
}

async function copyText(text: string, okMsg: string) {
  if (!text) {
    ElMessage.warning('命令为空，无法复制')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(okMsg)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function loadRetention() {
  retentionLoading.value = true
  try {
    const res = await getRetentionRecords(retentionDate.value, retentionPage.value, retentionSize.value)
    if (!res?.success || !res.data) {
      retentionRows.value = []
      retentionTotal.value = 0
      ElMessage.error(res?.message || '加载留存失败')
      return
    }
    retentionRows.value = res.data.records || []
    retentionTotal.value = res.data.total || 0
    retentionRate.value = res.data.successRate || 0
    scriptOk.value = res.data.scriptSuccessCount || 0
    backupOk.value = res.data.backupSuccessCount || 0
    twofaSuccess.value = res.data.retention2faSuccess || 0
    retentionLogout.value = res.data.retentionLogout || 0
  } finally {
    retentionLoading.value = false
  }
}

onMounted(loadRetention)
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>留存与设备</h2>
        <p>设备恢复执行与留存统计统一视图</p>
      </div>
    </div>

    <PageBlock title="设备巡检">
      <div class="toolbar wrap">
        <el-input v-model="inspectForm.phoneId" placeholder="phone_id" style="width: 260px" />
        <el-input v-model="inspectForm.gaid" placeholder="gaid" style="width: 260px" />
        <el-button type="primary" :loading="inspectLoading" @click="runInspect">恢复并生成命令</el-button>
      </div>
      <el-input v-model="inspectResult" type="textarea" :rows="5" readonly style="margin-top: 12px" />
      <div class="toolbar" style="margin-top: 10px">
        <el-button plain @click="copyText(tunnelCommand, '已复制端口转发命令')">复制端口转发命令</el-button>
        <el-button plain @click="copyText(adbCommand, '已复制ADB连接命令')">复制ADB连接命令</el-button>
      </div>
    </PageBlock>

    <PageBlock title="留存记录" :desc="`留存成功率：${retentionRate}%`">
      <div class="grid-4" style="margin-bottom: 12px">
        <div class="card mini-stat"><div class="stat-label">总记录</div><div class="stat-value">{{ retentionTotal }}</div></div>
        <div class="card mini-stat"><div class="stat-label">脚本成功</div><div class="stat-value">{{ scriptOk }}</div></div>
        <div class="card mini-stat"><div class="stat-label">备份成功</div><div class="stat-value">{{ backupOk }}</div></div>
        <div class="card mini-stat"><div class="stat-label">2FA成功/注销</div><div class="stat-value">{{ twofaSuccess }} / {{ retentionLogout }}</div></div>
      </div>
      <div class="toolbar">
        <el-date-picker v-model="retentionDate" type="date" value-format="YYYY-MM-DD" />
        <el-button type="primary" @click="retentionPage = 1; loadRetention()">查询</el-button>
      </div>
      <el-table :data="retentionRows" v-loading="retentionLoading" style="width: 100%; margin-top: 12px">
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
          v-model:current-page="retentionPage"
          v-model:page-size="retentionSize"
          background
          layout="total, sizes, prev, pager, next"
          :total="retentionTotal"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="loadRetention"
          @size-change="retentionPage = 1; loadRetention()"
        />
      </div>
    </PageBlock>
  </section>
</template>
