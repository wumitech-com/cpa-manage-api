<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, setAuth } from '../api/authApi'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

async function handleLogin() {
  if (!form.username.trim() || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await login(form.username.trim(), form.password)
    if (!res?.success || !res.data?.token) {
      ElMessage.error(res?.message || '登录失败')
      return
    }
    setAuth(res.data.token, res.data.username || form.username.trim())
    ElMessage.success('登录成功')
    const redirect = String(route.query.redirect || '/overview')
    router.replace(redirect)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="login-page">
    <div class="login-card">
      <h1>TT Enterprise Console</h1>
      <p>Data Visualization Center</p>
      <el-form @submit.prevent="handleLogin">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" placeholder="密码" show-password size="large" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" size="large" @click="handleLogin">
          登录
        </el-button>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #0b1020 0%, #141e3a 60%, #0f172a 100%);
}

.login-card {
  width: 380px;
  max-width: calc(100vw - 24px);
  padding: 28px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.28);
}

.login-card h1 {
  margin: 0;
  font-size: 30px;
  line-height: 1.1;
}

.login-card p {
  margin: 8px 0 18px;
  color: #64748b;
}
</style>

