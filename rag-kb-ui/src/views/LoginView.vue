<template>
  <div class="login">
    <div class="login-card">
      <div class="brand">
        <h2 class="brand-title">登录</h2>
        <p class="brand-sub">站主登录后可以管理内容；访客也可以注册，注册后留言可以选公开或悄悄话</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="submit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            autocomplete="username"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            autocomplete="current-password"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button
          type="primary"
          class="submit"
          size="large"
          :loading="loading"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>

      <div class="foot">
        <router-link to="/" class="back">← 返回主页</router-link>
        <span class="dot">·</span>
        <router-link to="/register" class="back">注册一个账号</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  if (loading.value) return
  // 表单校验失败时 validate 会 reject，用 catch 吃掉避免控制台报错
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    ElMessage.success('登录成功')
    // 登录前想去哪就回哪；没指定就按身份分流：站主进后台，普通用户回主页
    const redirect = route.query.redirect
    if (redirect && typeof redirect === 'string') {
      router.replace(redirect)
    } else {
      router.replace(auth.isAdmin ? { name: 'dashboard' } : { name: 'home' })
    }
  } catch (e) {
    // 后端统一返回「用户名或密码错误」，不区分用户是否存在
    ElMessage.error(e.message || '登录失败')
    form.password = ''
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e293b 0%, #334155 100%);
  padding: 24px;
}
.login-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 14px;
  padding: 34px 32px 24px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}
.brand {
  margin-bottom: 24px;
  text-align: center;
}
.brand-title {
  margin: 0 0 6px;
  font-size: 21px;
  font-weight: 600;
  color: #1e293b;
}
.brand-sub {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
}
.submit {
  width: 100%;
  margin-top: 6px;
}
.foot {
  margin-top: 18px;
  text-align: center;
}
.dot {
  margin: 0 8px;
  color: #cbd5e1;
  font-size: 12px;
}
.back {
  font-size: 13px;
  color: #94a3b8;
  text-decoration: none;
}
.back:hover {
  color: #2563eb;
}
</style>
