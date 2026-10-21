<template>
  <div class="shell">
    <!-- 顶栏：站主后台导航 -->
    <header class="topbar">
      <div class="topbar-inner">
        <div class="brand">
          <router-link to="/" class="brand-link">
            <el-icon><HomeFilled /></el-icon>
            <span>{{ siteTitle }}</span>
          </router-link>
          <el-tag size="small" type="info" effect="plain" class="role-tag">站主</el-tag>
        </div>

        <nav class="nav">
          <router-link
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            active-class="nav-item--active"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <!-- 未读留言：红点不给数字，避免每次都要精确同步；有这个点就说明有新东西看 -->
            <span v-if="item.badge && unreadComments > 0" class="nav-dot" />
          </router-link>
        </nav>

        <el-dropdown @command="onCommand">
          <span class="user">
            {{ auth.displayName }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main class="content">
      <router-view />
    </main>

    <!-- 修改密码 -->
    <el-dialog v-model="pwdVisible" title="修改密码" width="420px">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-position="top">
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirm">
          <el-input v-model="pwdForm.confirm" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" @click="submitPassword">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  Camera,
  ChatDotRound,
  ChatLineSquare,
  Document,
  HomeFilled,
  Location,
  Lock,
  Odometer,
  Picture,
  Search,
  User
} from '@element-plus/icons-vue'
import { siteApi, authApi, commentApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const siteTitle = ref('Demo 的知识库')

/** 未读留言数，导航上那个小红点 */
const unreadComments = ref(0)

const navItems = [
  { to: '/admin/dashboard', label: '概览', icon: Odometer },
  { to: '/admin/documents', label: '文档管理', icon: Document },
  { to: '/admin/media', label: '我的内容', icon: Camera },
  { to: '/admin/moments', label: '日常动态', icon: ChatDotRound },
  { to: '/admin/comments', label: '留言管理', icon: ChatLineSquare, badge: true },
  { to: '/admin/chats', label: '问答记录', icon: Search },
  { to: '/admin/profile', label: '关于我', icon: User },
  { to: '/admin/visits', label: '访问记录', icon: Location }
]

const pwdVisible = ref(false)
const pwdLoading = ref(false)
const pwdFormRef = ref(null)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 128, message: '密码长度 6-128 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, cb) => {
        if (value !== pwdForm.newPassword) cb(new Error('两次输入不一致'))
        else cb()
      },
      trigger: 'blur'
    }
  ]
}

onMounted(async () => {
  // 顶栏站名从后端拿，避免改站名要重新 build 前端
  try {
    const info = await siteApi.info()
    if (info?.title) siteTitle.value = info.title
  } catch {
    /* 拿不到就用默认标题 */
  }
  await refreshUnread()
})

/** 未读数：拿不到就当 0，不能让导航坏掉 */
async function refreshUnread() {
  try {
    // ⚠️ 走 commentApi：旧路径 /api/moments/comments/unread 还在（转调），
    // 但留言只有一套实现了，别再从 momentApi 借道。
    const res = await commentApi.unread()
    unreadComments.value = res?.unread || 0
  } catch {
    unreadComments.value = 0
  }
}

// ⚠️ 每 60 秒刷新一次未读数：个人站量级很小，这个频率足够，
// 而且比 WebSocket/SSE 推送简单太多。$subscribe 监听路由变化也能刷，
// 但主导航的 router-link 跳转不会走 layout 的 onMounted，得用 watch
let unreadTimer = null
onMounted(() => {
  unreadTimer = setInterval(refreshUnread, 60000)
})
onBeforeUnmount(() => {
  if (unreadTimer) clearInterval(unreadTimer)
})

async function onCommand(cmd) {
  if (cmd === 'logout') {
    await auth.logout()
    ElMessage.success('已退出登录')
    router.push({ name: 'home' })
  } else if (cmd === 'password') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirm = ''
    pwdVisible.value = true
  }
}

async function submitPassword() {
  const valid = await pwdFormRef.value?.validate().catch(() => false)
  if (!valid) return
  pwdLoading.value = true
  try {
    await authApi.changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    pwdVisible.value = false
    ElMessage.success('密码已修改，请重新登录')
    // 后端改密后踢掉所有会话，本地也要清
    await auth.logout()
    router.push({ name: 'login' })
  } catch (e) {
    ElMessage.error(e.message || '修改失败')
  } finally {
    pwdLoading.value = false
  }
}
</script>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f7f8fa;
}

.topbar {
  background: #fff;
  border-bottom: 1px solid #eef0f4;
  position: sticky;
  top: 0;
  z-index: 10;
}
.topbar-inner {
  max-width: 1080px;
  margin: 0 auto;
  height: 56px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
}
.brand-link {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #1e293b;
  font-weight: 600;
  font-size: 15px;
  text-decoration: none;
}
.role-tag {
  transform: scale(0.9);
}
.nav {
  flex: 1;
  display: flex;
  gap: 4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border-radius: 7px;
  font-size: 14px;
  color: #64748b;
  text-decoration: none;
  transition: all 0.15s;
  position: relative;
}
.nav-item:hover {
  background: #f1f5f9;
  color: #1e293b;
}
.nav-item--active {
  background: #eff6ff;
  color: #2563eb;
  font-weight: 500;
}
/* 未读留言的小红点 */
.nav-dot {
  position: absolute;
  top: 5px;
  right: 4px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ef4444;
  box-shadow: 0 0 0 2px #fff;
}
.user {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: #475569;
  cursor: pointer;
  outline: none;
}
.content {
  flex: 1;
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
  padding: 22px 20px 40px;
}
</style>
