<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import PageBlock from '../components/PageBlock.vue'
import { inspectDevice } from '../api/deviceRetentionApi'

const loading = ref(false)
const form = reactive({
  phoneId: '',
  gaid: ''
})
const result = ref('请先输入 phone_id 和 gaid，再点击“恢复并生成命令”。')
const tunnelCommand = ref('')
const adbCommand = ref('')

function resetForm() {
  form.phoneId = ''
  form.gaid = ''
  result.value = '请先输入 phone_id 和 gaid，再点击“恢复并生成命令”。'
  tunnelCommand.value = ''
  adbCommand.value = ''
}

async function runInspect() {
  if (!form.phoneId.trim() || !form.gaid.trim()) {
    ElMessage.warning('phone_id 和 gaid 不能为空')
    return
  }
  loading.value = true
  try {
    const res = await inspectDevice(form.phoneId.trim(), form.gaid.trim())
    if (!res?.success || !res.data) {
      result.value = res?.message || '执行失败'
      ElMessage.error(result.value)
      return
    }
    tunnelCommand.value = res.data.tunnelCommand || ''
    adbCommand.value = res.data.adbConnectCommand || ''
    result.value = [
      `恢复结果: ${res.message || '成功'}`,
      `端口转发: ${tunnelCommand.value || '-'}`,
      `ADB连接: ${adbCommand.value || '-'}`,
      `服务IP: ${res.data.serverIp || '-'}`
    ].join('\n')
  } finally {
    loading.value = false
  }
}

async function copyText(text: string, okMsg: string) {
  if (!text) return ElMessage.warning('命令为空，无法复制')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(okMsg)
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>设备巡检</h2>
        <p>恢复环境并生成命令，支持一键复制</p>
      </div>
    </div>
    <PageBlock title="设备恢复">
      <div class="toolbar wrap">
        <el-input v-model="form.phoneId" placeholder="phone_id" style="width: 260px" />
        <el-input v-model="form.gaid" placeholder="gaid" style="width: 260px" />
        <el-button type="primary" :loading="loading" @click="runInspect">恢复并生成命令</el-button>
        <el-button @click="resetForm">重置</el-button>
      </div>
      <el-input v-model="result" type="textarea" :rows="6" readonly style="margin-top: 12px" />
      <div class="toolbar" style="margin-top: 10px">
        <el-button plain @click="copyText(tunnelCommand, '已复制端口转发命令')">复制端口转发命令</el-button>
        <el-button plain @click="copyText(adbCommand, '已复制ADB连接命令')">复制ADB连接命令</el-button>
      </div>
    </PageBlock>
  </section>
</template>
