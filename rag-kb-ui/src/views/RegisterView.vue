<template>
  <div class="reg">
    <div class="reg-card">
      <div class="brand">
        <h2 class="brand-title">注册</h2>
        <p class="brand-sub">注册后留言可以选「公开」或「悄悄话」，也能编辑和删除自己的留言</p>
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
            placeholder="3-20 位字母、数字、下划线"
            autocomplete="username"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item label="昵称（可选）" prop="displayName">
          <el-input
            v-model="form.displayName"
            placeholder="留言时显示的名字，留空就用用户名"
            maxlength="30"
            :prefix-icon="Avatar"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="至少 6 位"
            autocomplete="new-password"
            show-password
            :prefix-icon="Lock"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirm">
          <el-input
            v-model="form.confirm"
            type="password"
            placeholder="再输一次"
            autocomplete="new-password"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </el-form-item>

        <!--
          设备限制说明。⚠️ 必须明写在界面上，不能让用户在注册失败时
          才第一次知道有这条规则。
        -->
        <div class="device-note">
          <el-icon><InfoFilled /></el-icon>
          <span>一个浏览器只能注册一个账号。换设备可以再注册一个。</span>
        </div>

        <el-button
          type="primary"
          class="submit"
          size="large"
          :loading="loading"
          @click="submit"
        >
          注册并登录
        </el-button>
      </el-form>

      <div class="foot">
        <router-link to="/login" class="back">已有账号，去登录</router-link>
        <span class="dot">·</span>
        <router-link to="/" class="back">返回主页</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Avatar, InfoFilled, Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getDeviceId } from '@/utils/device'

const router = useRouter()
const auth = useAuthStore()

const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', displayName: '', password: '', confirm: '' })

/**
 * 校验规则与后端 AuthService.register 保持一致。
 *
 * <p>⚠️ 前端校验只是提前反馈，后端会再验一遍 —— 前端能被绕过，
 * 这是常识，不是不信任前端的问题。</p>
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名要 3 到 20 个字符', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]+$/,
      message: '只能用字母、数字和下划线',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 128, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再输一次密码', trigger: 'blur' },
    {
      // 自定义校验：把「两次不一致」的提示挂在确认框上，用户一眼知道改哪个
      validator: (rule, value, cb) => {
        if (value !== form.password) cb(new Error('两次输入的密码不一致'))
        else cb()
      },
      trigger: 'blur'
    }
  ]
}

async function submit() {
  if (loading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.register({
      username: form.username.trim(),
      password: form.password,
      displayName: form.displayName.trim() || null,
      // ⚠️ 设备指纹：后端用它挡「同一设备多账号」。
      // 拿不到也照样能注册（比如被隐私插件拦了），只是少了这道限制
      deviceId: getDeviceId()
    })
    ElMessage.success('注册成功，已自动登录')
    // 普通用户没有后台，回主页
    router.replace({ name: 'home' })
  } catch (e) {
    // 后端把「设备已注册」「同 IP 太频繁」「用户名被占」都放在 message 里
    ElMessage.error(e.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reg {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e293b 0%, #334155 100%);
  padding: 24px;
}
.reg-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 14px;
  padding: 32px 32px 24px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
}
.brand {
  margin-bottom: 20px;
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
  line-height: 1.6;
}
.device-note {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: -4px 0 16px;
  padding: 10px 12px;
  background: #f0f7ff;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  font-size: 12px;
  color: #1e40af;
  line-height: 1.6;
}
.device-note .el-icon {
  margin-top: 2px;
  flex-shrink: 0;
}
.submit {
  width: 100%;
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
