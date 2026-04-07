<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageBlock from '../components/PageBlock.vue'
import { getAccountDetail, updateAccount } from '../api/registerApi'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  id: 0,
  phoneId: '',
  username: '',
  createdAt: '',
  ip: '',
  androidVersion: '',
  status: '',
  newEmailBindSuccess: -1,
  newEmail: '',
  email: '',
  password: '',
  authenticatorKey: '',
  note: '',
  country: ''
})

async function loadDetail() {
  const id = Number(route.params.id || 0)
  if (!id) {
    ElMessage.error('账号ID无效')
    router.push('/account')
    return
  }
  loading.value = true
  try {
    const res = await getAccountDetail(id)
    if (!res?.success || !res.data) {
      ElMessage.error(res?.message || '加载详情失败')
      return
    }
    const d = res.data
    form.id = Number(d.id || id)
    form.phoneId = d.phoneId || ''
    form.username = d.username || ''
    form.createdAt = d.createdAt || ''
    form.ip = d.ip || ''
    form.androidVersion = d.androidVersion || ''
    form.status = d.status || ''
    form.newEmailBindSuccess = d.newEmailBindSuccess ?? -1
    form.newEmail = d.newEmail || ''
    form.email = d.email || ''
    form.password = d.password || ''
    form.authenticatorKey = d.authenticatorKey || ''
    form.note = d.note || ''
    form.country = d.country || ''
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (!form.id) return
  saving.value = true
  try {
    const res = await updateAccount({
      id: form.id,
      status: form.status || undefined,
      newEmailBindSuccess: form.newEmailBindSuccess === -1 ? undefined : form.newEmailBindSuccess,
      newEmail: form.newEmail,
      email: form.email,
      password: form.password,
      authenticatorKey: form.authenticatorKey,
      note: form.note,
      country: form.country
    })
    if (!res?.success) {
      ElMessage.error(res?.message || '保存失败')
      return
    }
    ElMessage.success('保存成功')
    await loadDetail()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadDetail()
})
</script>

<template>
  <section>
    <div class="page-head">
      <div>
        <h2>账号详情</h2>
        <p>查看并编辑账号信息（含 authenticator_key）</p>
      </div>
      <el-button @click="router.push('/account')">返回列表</el-button>
    </div>

    <PageBlock title="详情信息">
      <el-form label-width="130px" v-loading="loading">
        <el-form-item label="数据库ID">
          <el-input :model-value="String(form.id)" disabled />
        </el-form-item>
        <el-form-item label="设备ID(phone_id)">
          <el-input v-model="form.phoneId" disabled />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.username" disabled />
        </el-form-item>
        <el-form-item label="注册时间">
          <el-input v-model="form.createdAt" disabled />
        </el-form-item>
        <el-form-item label="IP">
          <el-input v-model="form.ip" disabled />
        </el-form-item>
        <el-form-item label="安卓版本">
          <el-input v-model="form.androidVersion" disabled />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 260px">
            <el-option label="2FA失败" value="2FA失败" />
            <el-option label="2FA成功-封号" value="2FA成功-封号" />
            <el-option label="2FA成功-正常" value="2FA成功-正常" />
            <el-option label="可售" value="可售" />
            <el-option label="换绑成功" value="换绑成功" />
            <el-option label="换绑失败" value="换绑失败" />
          </el-select>
        </el-form-item>
        <el-form-item label="换绑状态">
          <el-select v-model="form.newEmailBindSuccess" style="width: 260px">
            <el-option label="未换绑" :value="-1" />
            <el-option label="换绑成功" :value="1" />
            <el-option label="换绑失败" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="换绑邮箱">
          <el-input v-model="form.newEmail" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" />
        </el-form-item>
        <el-form-item label="authenticator_key">
          <el-input v-model="form.authenticatorKey" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="国家">
          <el-input v-model="form.country" style="width: 260px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存更新</el-button>
          <el-button @click="loadDetail">刷新</el-button>
        </el-form-item>
      </el-form>
    </PageBlock>
  </section>
</template>

