<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import type { EChartsOption } from 'echarts'
import StatCard from '../components/StatCard.vue'
import EChartPanel from '../components/EChartPanel.vue'
import {
  getBlockRate,
  getBlockRateMatrix,
  type BlockRateMatrixRow,
  type DistItem
} from '../api/statsApi'

const loading = ref(false)
const selectedCountry = ref('ALL')
const countryOptions = [
  { label: '全部', value: 'ALL' },
  { label: 'US', value: 'US' },
  { label: 'MX', value: 'MX' },
  { label: 'BR', value: 'BR' }
]
const matrixOffsets = [1, 3, 7, 14, 21, 30]
const todayIso = dayjs().format('YYYY-MM-DD')
const last5StartIso = dayjs().subtract(4, 'day').format('YYYY-MM-DD')
// 允许用户选择封号率分析的日期范围；默认最近 5 天（start=end-4）
const dateRange = ref<[string, string]>([last5StartIso, todayIso])
const queryEndDate = computed(() => dateRange.value?.[1] || todayIso)
const queryStartDate = computed(() => dateRange.value?.[0] || todayIso)
const data = ref({
  nextDay: { label: '次日封号率', blockRate: 0, total: 0, blocked: 0, baseDate: '-' },
  threeDay: { label: '3天封号率', blockRate: 0, total: 0, blocked: 0, baseDate: '-' },
  sevenDay: { label: '7天封号率', blockRate: 0, total: 0, blocked: 0, baseDate: '-' }
})
const period = ref<'nextDay' | 'threeDay' | 'sevenDay'>('nextDay')
const matrixRows = ref<BlockRateMatrixRow[]>([])
type BlockDetail = {
  androidVersionDist?: DistItem[]
  behaviorDist?: DistItem[]
  tiktokVersionDist?: DistItem[]
  countryDist?: DistItem[]
  phoneServerIpDist?: DistItem[]
  androidVersionRateDist?: DistItem[]
  behaviorRateDist?: DistItem[]
  tiktokVersionRateDist?: DistItem[]
  countryRateDist?: DistItem[]
  phoneServerIpRateDist?: DistItem[]
}

const detailByPeriod = ref<Record<string, BlockDetail>>({})
const detailFallback = ref<BlockDetail>({})

const currentDetail = computed(() => detailByPeriod.value[period.value] || detailFallback.value || {})
const topAndroid = computed(() => (currentDetail.value.androidVersionDist || []).slice(0, 8))
const topBehavior = computed(() => (currentDetail.value.behaviorDist || []).slice(0, 8))
const topCountry = computed(() => (currentDetail.value.countryDist || []).slice(0, 8))
const topServer = computed(() => (currentDetail.value.phoneServerIpDist || []).slice(0, 8))
const topTiktok = computed(() => (currentDetail.value.tiktokVersionDist || []).slice(0, 8))
const rateAndroid = computed(() => (currentDetail.value.androidVersionRateDist || []).slice(0, 8))
const rateBehavior = computed(() => (currentDetail.value.behaviorRateDist || []).slice(0, 8))
const rateTiktok = computed(() => (currentDetail.value.tiktokVersionRateDist || []).slice(0, 8))
const rateCountry = computed(() => (currentDetail.value.countryRateDist || []).slice(0, 8))
const rateServer = computed(() => (currentDetail.value.phoneServerIpRateDist || []).slice(0, 8))

const selectedItem = computed(() => data.value[period.value])

const matrixOffsetTrendOption = computed<EChartsOption>(() => {
  const xData = matrixRows.value.map((row) => toMMDD(row.rowDate))
  const offsets = matrixOffsets
  const series = offsets.map((off) => {
    const data = matrixRows.value.map((row) => {
      const cell = (row.cells || []).find((c) => Number(c.offset) === off)
      return Number(cell?.blockRateAsOfRow || 0)
    })
    return {
      name: `前${off}天`,
      type: 'line' as const,
      smooth: true,
      data
    }
  })
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: offsets.map((off) => `前${off}天`) },
    xAxis: { type: 'category' as const, data: xData },
    yAxis: { type: 'value' as const, axisLabel: { formatter: '{value}%' } },
    series
  }
})


async function loadData() {
  loading.value = true
  try {
    const start = queryStartDate.value
    const end = queryEndDate.value
    const ctry = selectedCountry.value

    const [res, mx] = await Promise.all([
      getBlockRate(queryEndDate.value, ctry),
      start && end && !dayjs(start).isAfter(dayjs(end))
        ? getBlockRateMatrix(start, end, ctry)
        : Promise.resolve({ success: false as const })
    ])
    if (res?.success && res.data) {
      data.value = {
        nextDay: { ...data.value.nextDay, ...(res.data.nextDay || {}) },
        threeDay: { ...data.value.threeDay, ...(res.data.threeDay || {}) },
        sevenDay: { ...data.value.sevenDay, ...(res.data.sevenDay || {}) }
      }
      detailByPeriod.value = res.data.blockedDetailByPeriod || {}
      detailFallback.value = res.data.blockedDetail || {}
    }
    matrixRows.value = mx?.success && mx.data?.rows ? mx.data.rows : []
  } finally {
    loading.value = false
  }
}

function fmtRate(v?: number) {
  const n = typeof v === 'number' && isFinite(v) ? v : 0
  // 保留 9 位小数，并尽量去掉尾部 0
  return n.toFixed(9).replace(/\.?0+$/, '')
}

function toMMDD(v?: string) {
  if (!v) return '-'
  return dayjs(v).format('MMDD')
}

function setToday() {
  const v = dayjs().format('YYYY-MM-DD')
  const start = dayjs().subtract(4, 'day').format('YYYY-MM-DD')
  dateRange.value = [start, v]
  loadData()
}

function riskClass(rate?: number) {
  return Number(rate || 0) >= 30 ? 'risk-alert' : ''
}

onMounted(loadData)
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>封号率分析</h2>
        <p>关键周期风险指标与趋势对比</p>
      </div>
      <div class="toolbar">
        <el-select v-model="selectedCountry" style="width: 110px" @change="loadData">
          <el-option v-for="o in countryOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </div>

    <div class="grid-3">
      <div :class="riskClass(data.nextDay.blockRate)">
        <StatCard :label="data.nextDay.label" :value="`${data.nextDay.blockRate || 0}%`" :hint="`${data.nextDay.baseDate} | ${data.nextDay.blocked}/${data.nextDay.total}`" />
      </div>
      <div :class="riskClass(data.threeDay.blockRate)">
        <StatCard :label="data.threeDay.label" :value="`${data.threeDay.blockRate || 0}%`" :hint="`${data.threeDay.baseDate} | ${data.threeDay.blocked}/${data.threeDay.total}`" />
      </div>
      <div :class="riskClass(data.sevenDay.blockRate)">
        <StatCard :label="data.sevenDay.label" :value="`${data.sevenDay.blockRate || 0}%`" :hint="`${data.sevenDay.baseDate} | ${data.sevenDay.blocked}/${data.sevenDay.total}`" />
      </div>
    </div>

    <div class="card page-block" v-if="matrixRows.length">
      <div class="panel-title">回溯周期封号率趋势（多线）</div>
      <div class="toolbar wrap" style="margin-bottom: 10px; margin-top: 8px">
        <el-select v-model="selectedCountry" style="width: 120px" @change="loadData">
          <el-option v-for="o in countryOptions" :key="`trend-${o.value}`" :label="`国家：${o.label}`" :value="o.value" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :clearable="false"
          @change="loadData"
        />
        <el-button @click="setToday">最近5天</el-button>
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
      </div>
      <EChartPanel title="多周期趋势线（D1/D3/D7/D14/D21/D30）" :option="matrixOffsetTrendOption" :height="320" />
    </div>

    <div class="card page-block cohort-matrix" v-if="matrixRows.length">
      <div class="panel-title">回溯周期封号率矩阵（注册前 1/3/7/14/21/30 天）</div>
      <div class="toolbar wrap" style="margin-bottom: 10px; margin-top: 8px">
        <el-select v-model="selectedCountry" style="width: 120px" @change="loadData">
          <el-option v-for="o in countryOptions" :key="`mx-${o.value}`" :label="`国家：${o.label}`" :value="o.value" />
        </el-select>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          :clearable="false"
          @change="loadData"
        />
        <el-button @click="setToday">最近5天</el-button>
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
      </div>
      <div class="matrix-wrap">
        <div class="matrix-header">
          <div class="matrix-head-title">注册日期</div>
          <div v-for="off in matrixOffsets" :key="`h-${off}`" class="matrix-head-cell">前{{ off }}天</div>
        </div>
        <div v-for="row in matrixRows" :key="`row-${row.rowDate}`" class="matrix-row">
          <div class="matrix-row-title">{{ toMMDD(row.rowDate) }}</div>
          <div v-for="cell in row.cells" :key="`off-${row.rowDate}-${cell.offset}`" class="matrix-cell">
            <div class="matrix-cell-head">{{ toMMDD(cell.cohortDate) }}（{{ cell.total }}）</div>
            <div class="matrix-cell-body">{{ fmtRate(cell.blockRateAsOfRow) }}%</div>
          </div>
        </div>
      </div>
    </div>

    <div class="card page-block">
      <div class="panel-title">封号分布（按周期）</div>
      <div class="toolbar wrap" style="margin-bottom: 10px">
        <el-radio-group v-model="period">
          <el-radio-button label="nextDay">次日</el-radio-button>
          <el-radio-button label="threeDay">3天</el-radio-button>
          <el-radio-button label="sevenDay">7天</el-radio-button>
        </el-radio-group>
      </div>
      <div class="block-desc">
        当前周期：{{ selectedItem.label }}（{{ selectedItem.baseDate }}） 封禁 {{ selectedItem.blocked }} / 总数 {{ selectedItem.total }}，封号率 {{ selectedItem.blockRate }}%
      </div>
      <div class="grid-2">
        <div class="dist-card">
          <div class="dist-title">Android 版本</div>
          <el-empty v-if="!topAndroid.length" description="暂无数据" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in topAndroid" :key="`a-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">行为分布</div>
          <el-empty v-if="!topBehavior.length" description="暂无数据" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in topBehavior" :key="`b-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">国家分布</div>
          <el-empty v-if="!topCountry.length" description="暂无数据" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in topCountry" :key="`c-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">TikTok 版本分布</div>
          <el-empty v-if="!topTiktok.length" description="暂无数据" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in topTiktok" :key="`t-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">服务器分布</div>
          <el-empty v-if="!topServer.length" description="暂无数据" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in topServer" :key="`s-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }} / {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
      </div>
    </div>

    <div class="card page-block">
      <div class="panel-title">封号率分布榜（按封号率）</div>
      <div class="grid-2">
        <div class="dist-card">
          <div class="dist-title">Android 封号率榜</div>
          <el-empty v-if="!rateAndroid.length" description="后端未返回该字段" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in rateAndroid" :key="`ra-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }}/{{ item.totalCount || 0 }} · {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">行为封号率榜</div>
          <el-empty v-if="!rateBehavior.length" description="后端未返回该字段" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in rateBehavior" :key="`rb-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }}/{{ item.totalCount || 0 }} · {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">TikTok 版本封号率榜</div>
          <el-empty v-if="!rateTiktok.length" description="后端未返回该字段" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in rateTiktok" :key="`rt-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }}/{{ item.totalCount || 0 }} · {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">国家封号率榜</div>
          <el-empty v-if="!rateCountry.length" description="后端未返回该字段" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in rateCountry" :key="`rc-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }}/{{ item.totalCount || 0 }} · {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
        <div class="dist-card">
          <div class="dist-title">服务器封号率榜</div>
          <el-empty v-if="!rateServer.length" description="后端未返回该字段" :image-size="50" />
          <div v-else class="dist-list">
            <div v-for="item in rateServer" :key="`rs-${item.value}`" class="dist-row"><span>{{ item.value || '-' }}</span><span>{{ item.count || 0 }}/{{ item.totalCount || 0 }} · {{ item.percent ?? 0 }}%</span></div>
          </div>
        </div>
      </div>
    </div>

    <div class="card page-block">
      <div class="panel-title">说明</div>
      <p class="block-desc">
        - 观察截止日为日期范围<strong>结束日</strong>当天 23:59:59；卡片与趋势分子均为「block_time 非空且不晚于该时刻」。<br>
        - 次日 / 3 天 / 7 天：cohort 分别为结束日前 1 / 3 / 7 个自然日完成 2FA 的账号。<br>
        - 矩阵：每行日期视为注册日；列分别回看注册前 1/3/7/14/21/30 天 cohort 的封号率。<br>
        - 国家选「全部」时不按国家过滤；选具体国家时仅统计该国记录。
      </p>
    </div>
  </section>
</template>

<style scoped>
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dist-card {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px;
}

.dist-title {
  margin-bottom: 8px;
  font-weight: 600;
}

.dist-list {
  display: grid;
  gap: 6px;
  max-height: 240px;
  overflow: auto;
  padding-right: 4px;
}

.dist-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(148, 163, 184, 0.08);
}

.dist-list::-webkit-scrollbar {
  width: 8px;
}

.dist-list::-webkit-scrollbar-thumb {
  background: rgba(148, 163, 184, 0.5);
  border-radius: 999px;
}

.risk-alert :deep(.card) {
  border-color: #ef4444;
  box-shadow: 0 0 0 1px rgba(239, 68, 68, 0.25);
}

.cohort-matrix {
  margin-bottom: 12px;
}

.matrix-wrap {
  display: grid;
  gap: 8px;
  max-height: 520px;
  overflow: auto;
  padding-right: 4px;
}

.matrix-header {
  display: grid;
  grid-template-columns: 96px repeat(6, minmax(0, 1fr));
  gap: 8px;
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--bg-card, #fff);
  padding-bottom: 2px;
}

.matrix-head-title,
.matrix-head-cell {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 6px 8px;
  font-size: 12px;
  font-weight: 700;
  color: var(--text-weak);
  text-align: center;
}

.matrix-row {
  display: grid;
  grid-template-columns: 96px repeat(6, minmax(0, 1fr));
  align-items: stretch;
  gap: 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px;
  background: rgba(148, 163, 184, 0.05);
}

.matrix-row-title {
  font-weight: 700;
  line-height: 1.2;
  display: flex;
  align-items: center;
  justify-content: center;
}

.matrix-cell {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
  background: rgba(148, 163, 184, 0.06);
}

.matrix-cell-head {
  font-size: 11px;
  color: var(--text-weak);
  line-height: 1.2;
  margin-bottom: 4px;
}

.matrix-cell-body {
  font-size: 18px;
  font-weight: 700;
  line-height: 1.1;
}

@media (max-width: 1200px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }

  .matrix-row {
    grid-template-columns: 1fr;
  }
}
</style>
