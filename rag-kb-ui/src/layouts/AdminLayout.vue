<template>
  <div class="shell">
    <!-- 左侧导航：9 个入口横向排一行会挤成两行，竖排才是后台的常规形态 -->
    <aside class="sidebar">
      <router-link to="/" class="brand">
        <span class="brand-mark">{{ brandInitial }}</span>
        <span class="brand-text">
          <span class="brand-name">{{ siteTitle }}</span>
          <span class="brand-role">站主后台</span>
        </span>
      </router-link>

      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          active-class="nav-item--active"
        >
          <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
          <span class="nav-label">{{ item.label }}</span>
          <!-- 未读留言：红点不给数字，避免每次都要精确同步；有这个点就说明有新东西看 -->
          <span v-if="item.badge && unreadComments > 0" class="nav-dot" />
        </router-link>
      </nav>

      <div class="sidebar-foot">
        <router-link to="/" class="nav-item nav-item--ghost">
          <el-icon class="nav-icon"><HomeFilled /></el-icon>
          <span class="nav-label">返回主页</span>
        </router-link>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <h1 class="page-title">{{ pageTitle }}</h1>

        <el-dropdown @command="onCommand">
          <span class="user">
            <span class="user-avatar">{{ userInitial }}</span>
            <span class="user-name">{{ auth.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>

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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  Camera,
  ChatDotRound,
  ChatLineSquare,
  Document,
  HomeFilled,
  Location,
  Odometer,
  Search,
  User,
  UserFilled
} from '@element-plus/icons-vue'
import { siteApi, authApi, commentApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const siteTitle = ref('Demo 的知识库')

/** 顶栏标题跟着路由 meta.title 走，不用每个页面自己画标题 */
const pageTitle = computed(() => route.meta?.title || '后台')

/** 侧栏头像取站名/昵称首字，没有就 H */
const brandInitial = computed(() => (auth.displayName || siteTitle.value || 'H').trim().charAt(0).toUpperCase())
const userInitial = computed(() => (auth.displayName || 'H').trim().charAt(0).toUpperCase())

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
  { to: '/admin/users', label: '注册用户', icon: UserFilled },
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
  background: #f7f8fa;
}

/* ---------------- 左侧导航 ---------------- */
.sidebar {
  width: 224px;
  flex: 0 0 224px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-right: 1px solid #eef0f4;
  position: sticky;
  top: 0;
  height: 100vh;
  padding: 18px 12px 14px;
  box-sizing: border-box;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 8px 18px;
  text-decoration: none;
  min-width: 0;
}
.brand-mark {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.5px;
}
.brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.brand-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brand-role {
  font-size: 11px;
  color: #94a3b8;
  letter-spacing: 0.3px;
}

.nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
  padding: 2px 0;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  font-size: 13.5px;
  color: #475569;
  text-decoration: none;
  /* 关键：不换行、不被压扁。之前 9 个入口挤一行，「概览」被拆成了「概 / 览」 */
  white-space: nowrap;
  flex: 0 0 auto;
  position: relative;
  transition: background 0.15s, color 0.15s;
}
.nav-item:hover {
  background: #f1f5f9;
  color: #0f172a;
}
/* 选中态：浅蓝底 + 左侧细竖条，比整块高亮更轻 */
.nav-item--active {
  background: #eff6ff;
  color: #2563eb;
  font-weight: 600;
}
.nav-item--active::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: #2563eb;
}
.nav-icon {
  font-size: 16px;
  flex: 0 0 16px;
}
.nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 未读留言的小红点 */
.nav-dot {
  position: absolute;
  top: 6px;
  right: 10px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ef4444;
  box-shadow: 0 0 0 2px #fff;
}

.sidebar-foot {
  border-top: 1px solid #f1f5f9;
  padding-top: 8px;
  margin-top: 8px;
}
.nav-item--ghost {
  color: #94a3b8;
  font-size: 13px;
}
.nav-item--ghost:hover {
  color: #2563eb;
}

/* ---------------- 右侧主区 ---------------- */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  flex: 0 0 56px;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(180%) blur(8px);
  border-bottom: 1px solid #eef0f4;
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.page-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
  letter-spacing: 0.2px;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  color: #475569;
  cursor: pointer;
  outline: none;
  padding: 4px 8px 4px 4px;
  border-radius: 999px;
  transition: background 0.15s;
}
.user:hover {
  background: #f1f5f9;
}
.user-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content {
  flex: 1;
  max-width: 1240px;
  width: 100%;
  margin: 0 auto;
  padding: 22px 28px 40px;
  min-width: 0;
  box-sizing: border-box;
}
/* 表格类页面（文档管理）列总宽固定，内容区太窄会把「操作」列挤掉。
   宁可让表格内部横向滚动，也不要截掉按钮。 */
.content :deep(.el-table) {
  width: 100%;
}
.content :deep(.el-table__body-wrapper) {
  overflow-x: auto;
}

/* 窄屏：侧栏收成图标条，避免挤压主内容 */
@media (max-width: 900px) {
  .sidebar {
    width: 64px;
    flex: 0 0 64px;
    padding: 14px 8px;
    align-items: center;
  }
  .brand {
    padding: 4px 0 14px;
    justify-content: center;
  }
  .brand-text,
  .nav-label,
  .user-name {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 10px;
  }
  .nav-item--active::before {
    left: -8px;
  }
  .nav-dot {
    top: 8px;
    right: 8px;
  }
  .content {
    padding: 18px 14px 32px;
  }
}
</style>
