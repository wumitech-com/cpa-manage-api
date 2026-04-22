<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import type { EChartsOption } from 'echarts'
import StatCard from '../components/StatCard.vue'
import EChartPanel from '../components/EChartPanel.vue'
import { getDailyDetail, getDailyOverview, getDailyTrend, type DailyTrendItem, type DistItem } from '../api/statsApi'
import PageHeader from '../shared/ui/PageHeader.vue'

const loading = ref(false)
const date = ref(dayjs().format('YYYY-MM-DD'))
const overview = ref({
  todayRegister: 0,
  todayRegisterSuccess: 0,
  today2faSuccess: 0,
  todayNeedRetention: 0,
  todayRegisterSuccessRate: 0,
  today2faSetupSuccessRate: 0
})
const trend = ref<DailyTrendItem[]>([])
const serverKeyword = ref('')
const hourColumns = Array.from({ length: 24 }, (_, i) => `h${String(i).padStart(2, '0')}`)
const detail = ref<{
  androidVersionDist?: DistItem[]
  behaviorDist?: DistItem[]
  tiktokVersionDist?: DistItem[]
  countryDist?: DistItem[]
  phoneServerIpDist?: DistItem[]
  serverHourly2fa?: Array<{ serverIp?: string; total?: number; hourly?: Array<{ hour?: string; count?: number }> }>
  serverHourlyRegister?: Array<{ serverIp?: string; total?: number; [key: string]: string | number | undefined }>
  trafficTotal?: number
  trafficAvgPerSuccess?: number
  trafficTotalAll?: number
}>({})

/** 全量总流量(bytes) ÷ 今日注册成功数，展示 MB */
const registerSuccessAvgTrafficMb = computed(() => {
  const totalBytes = Number(detail.value.trafficTotalAll || 0)
  const success = Number(overview.value.todayRegisterSuccess || 0)
  if (success <= 0) return '—'
  const mb = totalBytes / success / 1024 / 1024
  return `${mb.toFixed(2)} MB`
})

/** 全量总流量(bytes) ÷ 今日注册数，展示 MB */
const singleAvgTrafficMb = computed(() => {
  const totalBytes = Number(detail.value.trafficTotalAll || 0)
  const reg = Number(overview.value.todayRegister || 0)
  if (reg <= 0) return '—'
  const mb = totalBytes / reg / 1024 / 1024
  return `${mb.toFixed(2)} MB`
})

const trafficOption = computed<EChartsOption>(() => {
  const labels = trend.value.map((i) => i.label || i.date)
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['总流量(GB)', '单个平均(GB)'] },
    xAxis: { type: 'category' as const, data: labels },
    yAxis: { type: 'value' as const },
    series: [
      {
        name: '总流量(GB)',
        type: 'bar' as const,
        data: trend.value.map((i) => Number((Number(i.trafficTotal || 0) / 1024 / 1024 / 1024).toFixed(2)))
      },
      {
        name: '单个平均(GB)',
        type: 'line' as const,
        smooth: true,
        data: trend.value.map((i) => Number((Number(i.trafficAvg || 0) / 1024 / 1024 / 1024).toFixed(2)))
      }
    ]
  }
})

const topAndroid = computed(() => (detail.value.androidVersionDist || []).slice(0, 8))
const topCountry = computed(() => (detail.value.countryDist || []).slice(0, 8))
const topBehavior = computed(() => (detail.value.behaviorDist || []).slice(0, 8))
const topServer = computed(() => (detail.value.phoneServerIpDist || []).slice(0, 8))
const topTiktok = computed(() => (detail.value.tiktokVersionDist || []).slice(0, 8))
const serverHourly = computed(() => detail.value.serverHourly2fa || [])
const serverHourlyRegister = computed(() => detail.value.serverHourlyRegister || [])

const serverRegisterRateMap = computed(() => {
  const m: Record<string, number> = {}
  ;(serverHourlyRegister.value || []).forEach((row) => {
    const ip = String(row.serverIp || '').trim()
    if (!ip) return
    m[ip] = Number((row as any).registerSuccessRate ?? 0) || 0
  })
  return m
})

// 服务器分时表格需要 h00..h23 字段；后端的 serverHourly2fa 是 hourly 数组结构，这里做一次转换
const serverHourly2faTable = computed(() => {
  return (serverHourly.value || []).map((row) => {
    const out: Record<string, any> = {
      serverIp: row.serverIp,
      total: row.total || 0,
      // “注册成功率”口径：register_success / created_total（后端已在 serverHourlyRegister 返回）
      registerSuccessRate: serverRegisterRateMap.value[String(row.serverIp || '').trim()] ?? null
    }
    for (let h = 0; h < 24; h++) out[`h${String(h).padStart(2, '0')}`] = 0
    ;(row.hourly || []).forEach((it) => {
      const hh = Number(it.hour || 0)
      if (!Number.isFinite(hh) || hh < 0 || hh > 23) return
      out[`h${String(hh).padStart(2, '0')}`] = Number(it.count || 0)
    })
    return out
  })
})

const filteredServerRows = computed(() => {
  const kw = serverKeyword.value.trim().toLowerCase()
  if (!kw) return serverHourly2faTable.value
  return serverHourly2faTable.value.filter((row) => String(row.serverIp || '').toLowerCase().includes(kw))
})
const filteredServerRegisterRows = computed(() => {
  const kw = serverKeyword.value.trim().toLowerCase()
  const rows = (serverHourlyRegister.value || []).map((row) => {
    const r: Record<string, any> = {
      serverIp: String((row as any).serverIp || ''),
      registerSuccess: Number((row as any).total || 0) || 0,
      createdTotal: Number((row as any).createdTotal || 0) || 0,
      registerSuccessRate: Number((row as any).registerSuccessRate || 0) || 0
    }
    for (let h = 0; h < 24; h++) {
      const key = `h${String(h).padStart(2, '0')}`
      r[key] = Number((row as any)[key] || 0) || 0
    }
    return r
  })
  if (!kw) return rows
  return rows.filter((row) => row.serverIp.toLowerCase().includes(kw))
})
const serverSummary = computed(() => {
  const rows = filteredServerRows.value
  return {
    serverCount: rows.length,
    twofaTotal: rows.reduce((sum, row) => sum + Number(row.total || 0), 0)
  }
})
const registerSummary = computed(() => {
  const rows = filteredServerRegisterRows.value
  const registerSuccessTotal = rows.reduce((sum, row) => sum + row.registerSuccess, 0)
  const createdTotal = rows.reduce((sum, row) => sum + row.createdTotal, 0)
  const registerSuccessRate = createdTotal > 0 ? (registerSuccessTotal / createdTotal) * 100 : 0
  return {
    serverCount: rows.length,
    registerSuccessTotal,
    createdTotal,
    registerSuccessRate
  }
})

function fmtGb(num?: number) {
  const n = Number(num || 0)
  if (!n) return '0 GB'
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function compactHourlyText(list?: Array<{ hour?: string; count?: number }>) {
  const points = (list || []).filter((h) => Number(h.count || 0) > 0)
  if (!points.length) return '全天 0'
  const shown = points.slice(0, 6).map((h) => `${h.hour}: ${h.count}`).join(' | ')
  const remain = points.length - 6
  return remain > 0 ? `${shown} | +${remain}项` : shown
}

function hourLabel(col: string) {
  return col.slice(1)
}

const trendOption = computed<EChartsOption>(() => {
  const labels = trend.value.map((i) => i.label || i.date)
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['注册', '2FA', '留存'] },
    xAxis: { type: 'category' as const, data: labels },
    yAxis: { type: 'value' as const },
    series: [
      { name: '注册', type: 'line' as const, smooth: true, data: trend.value.map((i) => i.register || 0) },
      { name: '2FA', type: 'line' as const, smooth: true, data: trend.value.map((i) => i.twofa || 0) },
      { name: '留存', type: 'line' as const, smooth: true, data: trend.value.map((i) => i.retention || 0) }
    ]
  }
})

async function loadData() {
  loading.value = true
  try {
    const [ov, tr, dt] = await Promise.all([getDailyOverview(date.value), getDailyTrend(date.value), getDailyDetail(date.value)])
    if (ov?.success && ov.data) overview.value = { ...overview.value, ...ov.data }
    trend.value = tr?.success && tr.data?.dailyTrend ? tr.data.dailyTrend : []
    detail.value = dt?.success && dt.data?.twofaDetail ? dt.data.twofaDetail : {}
  } finally {
    loading.value = false
  }
}

function setToday() {
  date.value = dayjs().format('YYYY-MM-DD')
  loadData()
}

onMounted(loadData)
</script>

<template>
  <section>
    <PageHeader title="总览看板" description="企业级注册与转化指标看板">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" />
        <el-button @click="setToday">今天</el-button>
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
    </PageHeader>

    <div class="grid-4">
      <StatCard label="今日注册" :value="overview.todayRegister" />
      <StatCard label="注册成功" :value="overview.todayRegisterSuccess" />
      <StatCard label="2FA 成功" :value="overview.today2faSuccess" />
      <StatCard label="需留存" :value="overview.todayNeedRetention" />
    </div>
    <div class="grid-3">
      <StatCard label="今日注册成功率" :value="`${overview.todayRegisterSuccessRate || 0}%`" />
      <StatCard label="今日2FA设置成功率" :value="`${overview.today2faSetupSuccessRate || 0}%`" />
    </div>
    <div class="grid-3">
      <StatCard
        label="注册成功平均流量"
        :value="registerSuccessAvgTrafficMb"
        hint="全量总流量 ÷ 今日注册成功数"
      />
      <StatCard
        label="单个平均流量"
        :value="singleAvgTrafficMb"
        hint="全量总流量 ÷ 今日注册数"
      />
      <StatCard label="全量总流量" :value="fmtGb(detail.trafficTotalAll)" />
    </div>

      <div class="card page-block">
        <div class="panel-title">服务器分时 2FA 成功列表</div>
      <div class="toolbar wrap">
        <el-input v-model="serverKeyword" placeholder="按服务器IP筛选" style="width: 220px" clearable />
        <el-tag type="info">服务器总数：{{ serverSummary.serverCount }}</el-tag>
          <el-tag type="success">2FA 总量：{{ serverSummary.twofaTotal }}</el-tag>
      </div>
      <el-table :data="filteredServerRows" style="width: 100%; margin-top: 10px;" max-height="320">
        <el-table-column prop="serverIp" label="服务器IP" width="160" fixed="left" />
        <el-table-column prop="total" label="总量" width="90" fixed="left" />
        <el-table-column label="注册成功率" width="140" fixed="left">
          <template #default="{ row }">
            {{
              row.registerSuccessRate == null
                ? '—'
                : `${Number(row.registerSuccessRate || 0).toFixed(2)}%`
            }}
          </template>
        </el-table-column>
        <el-table-column v-for="col in hourColumns" :key="col" :prop="col" :label="hourLabel(col)" width="68" />
      </el-table>
    </div>

    <div class="card page-block">
      <div class="panel-title">各服务器注册成功量与成功率</div>
      <div class="toolbar wrap">
        <el-tag type="info">服务器总数：{{ registerSummary.serverCount }}</el-tag>
        <el-tag type="success">注册成功总量：{{ registerSummary.registerSuccessTotal }}</el-tag>
        <el-tag>注册总量：{{ registerSummary.createdTotal }}</el-tag>
        <el-tag type="warning">整体成功率：{{ registerSummary.registerSuccessRate.toFixed(2) }}%</el-tag>
      </div>
      <el-table :data="filteredServerRegisterRows" style="width: 100%; margin-top: 10px;" max-height="320">
        <el-table-column prop="serverIp" label="服务器IP" min-width="180" fixed="left" />
        <el-table-column prop="registerSuccess" label="注册成功量" width="120" />
        <el-table-column prop="createdTotal" label="注册总量" width="120" />
        <el-table-column label="注册成功率" width="120">
          <template #default="{ row }">{{ row.registerSuccessRate.toFixed(2) }}%</template>
        </el-table-column>
        <el-table-column v-for="col in hourColumns" :key="`register-${col}`" :prop="col" :label="hourLabel(col)" width="68" />
      </el-table>
    </div>

    <EChartPanel title="7日趋势（注册/2FA/留存）" :option="trendOption" :height="360" />
    <EChartPanel title="7日流量趋势（总量/均值）" :option="trafficOption" :height="340" />

    <div class="grid-2">
      <div class="card page-block">
        <div class="panel-title">2FA Android 版本分布</div>
        <el-empty v-if="!topAndroid.length" description="暂无数据" :image-size="60" />
        <div v-else class="dist-list">
          <div v-for="item in topAndroid" :key="`a-${item.value}`" class="dist-row">
            <span class="name">{{ item.value || '-' }}</span>
            <span class="meta">{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span>
          </div>
        </div>
      </div>
      <div class="card page-block">
        <div class="panel-title">2FA 国家分布</div>
        <el-empty v-if="!topCountry.length" description="暂无数据" :image-size="60" />
        <div v-else class="dist-list">
          <div v-for="item in topCountry" :key="`c-${item.value}`" class="dist-row">
            <span class="name">{{ item.value || '-' }}</span>
            <span class="meta">{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span>
          </div>
        </div>
      </div>
      <div class="card page-block">
        <div class="panel-title">2FA 行为分布</div>
        <el-empty v-if="!topBehavior.length" description="暂无数据" :image-size="60" />
        <div v-else class="dist-list">
          <div v-for="item in topBehavior" :key="`b-${item.value}`" class="dist-row">
            <span class="name">{{ item.value || '-' }}</span>
            <span class="meta">{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span>
          </div>
        </div>
      </div>
      <div class="card page-block">
        <div class="panel-title">2FA 服务器分布</div>
        <el-empty v-if="!topServer.length" description="暂无数据" :image-size="60" />
        <div v-else class="dist-list">
          <div v-for="item in topServer" :key="`s-${item.value}`" class="dist-row">
            <span class="name">{{ item.value || '-' }}</span>
            <span class="meta">{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span>
          </div>
        </div>
      </div>
      <div class="card page-block">
        <div class="panel-title">2FA TikTok 版本分布</div>
        <el-empty v-if="!topTiktok.length" description="暂无数据" :image-size="60" />
        <div v-else class="dist-list">
          <div v-for="item in topTiktok" :key="`t-${item.value}`" class="dist-row">
            <span class="name">{{ item.value || '-' }}</span>
            <span class="meta">{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span>
          </div>
        </div>
      </div>
      <div class="card page-block">
        <div class="panel-title">2FA 按服务器分时段（摘要）</div>
        <el-empty v-if="!serverHourly.length" description="暂无数据" :image-size="60" />
        <div v-else class="hourly-list">
          <div v-for="item in serverHourly" :key="item.serverIp || 'unknown'" class="hourly-row">
            <div class="name">{{ item.serverIp || '-' }}（总计 {{ item.total || 0 }}）</div>
            <div class="meta">{{ compactHourlyText(item.hourly) }}</div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.dist-list {
  display: grid;
  gap: 8px;
  max-height: 240px;
  overflow: auto;
  padding-right: 4px;
}

.dist-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.dist-row .name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dist-row .meta {
  color: var(--text-secondary);
}

.hourly-list {
  display: grid;
  gap: 8px;
  max-height: 240px;
  overflow: auto;
  padding-right: 4px;
}

.hourly-row {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
}

.hourly-row .name {
  font-weight: 600;
  margin-bottom: 4px;
}

.hourly-row .meta {
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: auto;
  padding-bottom: 2px;
}

.dist-list::-webkit-scrollbar,
.hourly-list::-webkit-scrollbar {
  width: 8px;
}

.dist-list::-webkit-scrollbar-thumb,
.hourly-list::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.5);
  border-radius: 999px;
}

@media (max-width: 1200px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
