<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import PageBlock from '../components/PageBlock.vue'
import { getWindowList, type WindowItem } from '../api/registerApi'

const rows = ref<WindowItem[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const loading = ref(false)
const totalAccurate = ref(true)
const pageAdjusting = ref(false)

const query = reactive({
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

async function loadData() {
  loading.value = true
  try {
    const res = await getWindowList({
      page: page.value,
      size: size.value,
      fanStartDate: query.fanStartDate || undefined,
      fanEndDate: query.fanEndDate || undefined,
      nurtureStartDate: query.nurtureStartDate || undefined,
      nurtureEndDate: query.nurtureEndDate || undefined,
      nurtureStrategy: query.nurtureStrategy === 'ALL' ? undefined : query.nurtureStrategy,
      shopStatus: query.shopStatus === 'ALL' ? undefined : query.shopStatus,
      nurtureDevice: query.nurtureDevice === 'ALL' ? undefined : query.nurtureDevice,
      country: query.country === 'ALL' ? undefined : query.country,
      account: query.account.trim() || undefined,
      note: query.note.trim() || undefined
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载开窗失败')
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

    // 防止当前页超出总页数导致的空页体验
    const totalPages = Math.max(1, Math.ceil((Number(total.value) || 0) / (Number(size.value) || 10)))
    if (page.value > totalPages && !pageAdjusting.value) {
      pageAdjusting.value = true
      page.value = totalPages
      await loadData()
      return
    }
    pageAdjusting.value = false
  } finally {
    loading.value = false
  }
}

function reset() {
  query.fanStartDate = ''
  query.fanEndDate = ''
  query.nurtureStartDate = ''
  query.nurtureEndDate = ''
  query.nurtureStrategy = 'ALL'
  query.shopStatus = 'ALL'
  query.nurtureDevice = 'ALL'
  query.country = 'ALL'
  query.account = ''
  query.note = ''
  page.value = 1
  size.value = 10
  loadData()
}

onMounted(loadData)
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>开窗管理</h2>
        <p>开窗筛选、分页与字段明细</p>
      </div>
    </div>
    <PageBlock title="开窗筛选">
      <div class="toolbar wrap">
        <el-date-picker v-model="query.fanStartDate" type="date" value-format="YYYY-MM-DD" placeholder="刷粉开始" />
        <el-date-picker v-model="query.fanEndDate" type="date" value-format="YYYY-MM-DD" placeholder="刷粉结束" />
        <el-date-picker v-model="query.nurtureStartDate" type="date" value-format="YYYY-MM-DD" placeholder="养号开始" />
        <el-date-picker v-model="query.nurtureEndDate" type="date" value-format="YYYY-MM-DD" placeholder="养号结束" />
        <el-select v-model="query.nurtureStrategy" style="width: 130px">
          <el-option label="全部策略" value="ALL" />
          <el-option label="发布+浏览" value="发布+浏览" />
          <el-option label="浏览" value="浏览" />
        </el-select>
        <el-select v-model="query.shopStatus" style="width: 160px">
          <el-option label="全部状态" value="ALL" />
          <el-option label="成功" value="成功" />
          <el-option label="失败" value="失败" />
          <el-option label="等待" value="等待" />
        </el-select>
        <el-select v-model="query.nurtureDevice" style="width: 140px">
          <el-option label="全部设备" value="ALL" />
          <el-option label="ARM架构" value="ARM架构" />
          <el-option label="云手机" value="云手机" />
          <el-option label="魔云腾" value="魔云腾" />
          <el-option label="其他" value="其他" />
        </el-select>
        <el-select v-model="query.country" style="width: 120px">
          <el-option label="全部国家" value="ALL" />
          <el-option label="US" value="US" />
          <el-option label="MX" value="MX" />
          <el-option label="BR" value="BR" />
          <el-option label="JP" value="JP" />
        </el-select>
        <el-input v-model="query.account" placeholder="账号" style="width: 180px" />
        <el-input v-model="query.note" placeholder="备注" style="width: 160px" />
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </PageBlock>
    <PageBlock title="开窗列表">
      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <el-table-column prop="username" label="账号" min-width="120" show-overflow-tooltip />
        <el-table-column prop="fanDate" label="刷粉日期" width="135" show-overflow-tooltip />
        <el-table-column prop="nurtureDate" label="养号日期" width="135" show-overflow-tooltip />
        <el-table-column prop="nurtureStrategy" label="养号策略" width="100" show-overflow-tooltip />
        <el-table-column prop="shopStatus" label="开窗状态" width="100" show-overflow-tooltip />
        <el-table-column prop="registerIp" label="注册IP归属" min-width="140" show-overflow-tooltip />
        <el-table-column prop="registerEnv" label="注册环境" min-width="140" show-overflow-tooltip />
        <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip />
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
