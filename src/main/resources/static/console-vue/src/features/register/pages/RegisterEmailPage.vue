<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import PageBlock from '../../../components/PageBlock.vue'
import PageHeader from '../../../shared/ui/PageHeader.vue'
import { getEmailPoolList, importEmailTxt, type EmailPoolItem } from '../../../api/emailPoolApi'

const loading = ref(false)
const importing = ref(false)
const rows = ref<EmailPoolItem[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)

const query = reactive({
  email: '',
  channel: '',
  usageStatus: ''
})

const usageStatusOptions = [
  { label: '全部', value: '' },
  { label: '未使用', value: 'UNUSED' },
  { label: '已使用', value: 'USED' },
]

function formatTime(v?: string) {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 19)
}

function extractErrMsg(err: any, fallback: string) {
  const status = Number(err?.response?.status || 0)
  if (status === 413) {
    return '上传文件过大，请拆分后重试（当前限制 30MB）'
  }
  return err?.response?.data?.message || err?.message || fallback
}

async function loadData() {
  loading.value = true
  try {
    const res = await getEmailPoolList({
      page: page.value,
      size: size.value,
      email: query.email.trim() || undefined,
      channel: query.channel.trim() || undefined,
      usageStatus: query.usageStatus || undefined
    })
    if (!res?.success || !res.data) {
      rows.value = []
      total.value = 0
      ElMessage.error(res?.message || '加载邮箱列表失败')
      return
    }
    rows.value = (res.data.list || []) as EmailPoolItem[]
    total.value = Number(res.data.total || 0)
  } catch (err: any) {
    rows.value = []
    total.value = 0
    ElMessage.error(extractErrMsg(err, '加载邮箱列表失败'))
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.email = ''
  query.channel = ''
  query.usageStatus = ''
  page.value = 1
  loadData()
}

async function handleImport(file: File) {
  importing.value = true
  try {
    if (!file.name.toLowerCase().endsWith('.txt')) {
      ElMessage.warning('只支持 .txt 文件')
      return false
    }
    const res = await importEmailTxt(file, query.channel)
    if (!res?.success) {
      ElMessage.error(res?.message || '上传失败')
      return false
    }
    const data = res.data
    const msg = `上传完成：新增${data?.insertCount || 0}，更新${data?.updateCount || 0}，跳过${data?.skipCount || 0}`
    ElMessage.success(msg)
    if (Array.isArray(data?.errors) && data!.errors!.length > 0) {
      ElMessage.warning(`有 ${data!.errors!.length} 行格式异常，示例：${String(data!.errors![0]).slice(0, 120)}`)
    }
    page.value = 1
    void loadData()
    return true
  } catch (err: any) {
    ElMessage.error(extractErrMsg(err, '上传失败'))
    return false
  } finally {
    importing.value = false
  }
}

function beforeUpload(file: File) {
  void handleImport(file)
  return false
}

onMounted(loadData)
</script>

<template>
  <section>
    <PageHeader title="邮箱管理" description="支持批量上传邮箱和查看邮箱列表" />

    <PageBlock title="批量上传邮箱">
      <div class="toolbar wrap">
        <el-input v-model="query.channel" placeholder="来源渠道（可选）" style="width: 220px" />
        <el-upload :show-file-list="true" :before-upload="beforeUpload" accept=".txt">
          <el-button type="primary" :loading="importing">上传邮箱</el-button>
        </el-upload>
      </div>
      <div class="hint">
        TXT 每行格式：<code>email----password----client_id----refresh_token</code>
      </div>
    </PageBlock>

    <PageBlock title="筛选条件">
      <div class="toolbar wrap">
        <el-input v-model="query.email" placeholder="输入邮箱地址" style="width: 260px" />
        <el-input v-model="query.channel" placeholder="渠道(可选)" style="width: 180px" />
        <el-select v-model="query.usageStatus" style="width: 160px">
          <el-option v-for="item in usageStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" @click="page = 1; loadData()">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>
    </PageBlock>

    <PageBlock :title="`邮箱列表（第${page}页，共${total}个）`">
      <el-table :data="rows" border v-loading="loading">
        <el-table-column prop="email" label="邮箱地址" min-width="240" />
        <el-table-column prop="channel" label="渠道" min-width="120" />
        <el-table-column prop="usageStatus" label="使用状态" width="120" />
        <el-table-column prop="clientId" label="客户端ID" min-width="190" show-overflow-tooltip />
        <el-table-column prop="refreshToken" label="refresh_token" min-width="220" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" min-width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[20, 50, 100, 200]"
          :total="total"
          @current-change="loadData"
          @size-change="page = 1; loadData()"
        />
      </div>
    </PageBlock>
  </section>
</template>

<style scoped>
.hint {
  margin-top: 10px;
  color: #909399;
  font-size: 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
