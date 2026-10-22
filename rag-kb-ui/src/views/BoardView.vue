<template>
  <div class="board">
    <!-- ============ 顶部导航 ============ -->
    <header class="topbar">
      <router-link to="/" class="brand">← {{ siteTitle }}</router-link>
      <nav class="nav">
        <router-link to="/moments">日常</router-link>
        <router-link to="/board" class="active">留言板</router-link>
        <router-link v-if="auth.isAdmin" to="/admin/dashboard">后台</router-link>
        <!--
          登录入口固定摆在最右，和主页 / 日常页保持一致。
          留意：它只是入口，不是门槛 —— 游客不登录照样能看能留言，
          所以这里不写「登录后才能留言」之类的话（事实也不是那样）。
        -->
        <router-link v-if="!auth.isLoggedIn" to="/login" class="nav-login">登录</router-link>
        <span v-else class="nav-who">{{ auth.displayName }}</span>
      </nav>
    </header>

    <main class="wrap">
      <div class="page-head">
        <h1 class="page-title">留言板</h1>
        <p class="page-sub">
          有什么想说的都可以留在这里。不用注册也能留言。
        </p>
      </div>

      <!-- ============ 身份提示条 ============ -->
      <!--
        ⚠️ 这一段是需求明确要求的「发评论时要提示他这个信息」。
        三种身份三种说法，不能让用户猜自己发的留言会被谁看到。

        ⚠️ 这一段不是登录入口，顶栏右上角那个才是。
        这里只回答「我这条会被谁看到」，游客看到的是「匿名留言的发出去会怎样」，
        想注册的动机在下面表单的提示里。别在这里塞「去登录」，会让人以为不登录不能用。
      -->
      <div class="identity-note" :class="identityClass">
        <el-icon class="note-icon"><InfoFilled /></el-icon>
        <div class="note-body">
          <strong>{{ identityTitle }}</strong>
          <p>{{ identityText }}</p>
        </div>
      </div>

      <!-- ============ 留言表单 ============ -->
      <section class="card form-card">
        <div class="form-row" v-if="!auth.isLoggedIn">
          <el-input
            v-model="form.nickname"
            placeholder="你的昵称（不填就是「匿名访客」）"
            maxlength="30"
            class="nick-input"
          />
        </div>

        <el-input
          v-model="form.content"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          placeholder="写点什么…"
          class="content-input"
        />

        <div class="form-foot">
          <!--
            可见性选择。
            ⚠️ 只有注册用户能选悄悄话。游客不显示这个选项 ——
            不是「显示了但被后端拒绝」，而是压根不给他这个念想，
            因为游客发悄悄话没有任何意义（他收不到回复）。
          -->
          <div v-if="auth.isLoggedIn" class="vis-picker">
            <label class="vis-opt" :class="{ on: form.visibility === 'PUBLIC' }">
              <input v-model="form.visibility" type="radio" value="PUBLIC" />
              <span class="vis-label">公开</span>
              <span class="vis-hint">其他人能看到</span>
            </label>
            <label class="vis-opt" :class="{ on: form.visibility === 'PRIVATE' }">
              <input v-model="form.visibility" type="radio" value="PRIVATE" />
              <span class="vis-label">悄悄话</span>
              <span class="vis-hint">只有站主能看到</span>
            </label>
          </div>
          <div v-else class="vis-locked">
            <el-icon><Lock /></el-icon>
            <span>匿名留言仅站主可见，且发布后不能修改</span>
          </div>

          <el-button
            type="primary"
            :loading="submitting"
            :disabled="!form.content.trim()"
            @click="submit"
          >
            发送
          </el-button>
        </div>
      </section>

      <!-- ============ 留言列表 ============ -->
      <section class="list">
        <div v-if="loading" class="skeleton">
          <div v-for="i in 3" :key="i" class="sk-card">
            <div class="sk-line sk-name"></div>
            <div class="sk-line sk-text"></div>
          </div>
        </div>

        <div v-else-if="!comments.length" class="empty">
          <el-icon class="empty-icon"><ChatDotSquare /></el-icon>
          <p>还没有人留言，你是第一个</p>
        </div>

        <template v-else>
          <article
            v-for="c in comments"
            :key="c.id"
            class="comment"
            :class="{ 'is-private': c.visibility === 'PRIVATE', 'is-hidden': c.hidden }"
          >
            <header class="c-head">
              <span class="c-name">{{ c.nickname }}</span>
              <span v-if="c.author.admin" class="c-tag owner">站主</span>
              <span v-else-if="c.author.registered" class="c-tag reg">注册用户</span>
              <span v-if="c.visibility === 'PRIVATE'" class="c-tag priv">
                <el-icon><Lock /></el-icon>悄悄话
              </span>
              <span v-if="c.mine" class="c-tag mine">我</span>
              <span v-if="c.hidden" class="c-tag hidden">已被站主隐藏</span>
              <span class="c-time">{{ c.createdAt }}</span>
            </header>

            <p class="c-content">{{ c.content }}</p>

            <div v-if="c.hidden" class="c-hidden-note">
              这条留言已被站主隐藏，其他人看不到，只有你自己能看到它。
            </div>

            <!-- 操作区：只有作者本人能编辑/删除 -->
            <div v-if="canOperate(c)" class="c-actions">
              <button class="link-btn" @click="startEdit(c)">编辑</button>
              <button class="link-btn danger" @click="remove(c)">删除</button>
            </div>

            <!-- 编辑态 -->
            <div v-if="editingId === c.id" class="edit-box">
              <el-input
                v-model="editContent"
                type="textarea"
                :rows="3"
                maxlength="1000"
                show-word-limit
              />
              <div class="edit-actions">
                <el-button size="small" @click="cancelEdit">取消</el-button>
                <el-button
                  size="small"
                  type="primary"
                  :loading="saving"
                  :disabled="!editContent.trim()"
                  @click="saveEdit"
                >
                  保存
                </el-button>
              </div>
            </div>

            <!-- 回复区 -->
            <div v-if="!c.mine" class="reply-btn-wrap">
              <button class="link-btn" @click="toggleReply(c)">
                {{ replyTo === c.id ? '取消回复' : '回复' }}
              </button>
            </div>

            <div v-if="replyTo === c.id" class="reply-box">
              <el-input
                v-model="replyContent"
                type="textarea"
                :rows="2"
                maxlength="1000"
                placeholder="回复这条留言…"
              />
              <div class="edit-actions">
                <el-button size="small" @click="closeReply">取消</el-button>
                <el-button
                  size="small"
                  type="primary"
                  :loading="replying"
                  :disabled="!replyContent.trim()"
                  @click="sendReply(c)"
                >
                  发送回复
                </el-button>
              </div>
            </div>

            <!-- 子回复（只做一层） -->
            <div v-if="c.replies?.length" class="replies">
              <div v-for="r in c.replies" :key="r.id" class="reply">
                <header class="r-head">
                  <span class="r-name">{{ r.nickname }}</span>
                  <span v-if="r.author.admin" class="c-tag owner">站主</span>
                  <span v-else-if="r.author.registered" class="c-tag reg">注册用户</span>
                  <span v-if="r.hidden" class="c-tag hidden">已被隐藏</span>
                  <span class="c-time">{{ r.createdAt }}</span>
                </header>
                <p class="r-content">{{ r.content }}</p>
                <div v-if="r.hidden" class="c-hidden-note">
                  这条回复已被站主隐藏，只有你自己能看到它。
                </div>
                <div v-if="canOperate(r)" class="c-actions">
                  <button class="link-btn" @click="startEdit(r)">编辑</button>
                  <button class="link-btn danger" @click="remove(r)">删除</button>
                </div>
              </div>
            </div>
          </article>

          <div v-if="hasMore" class="load-more">
            <el-button text :loading="loadingMore" @click="loadMore">加载更多</el-button>
          </div>
        </template>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotSquare, InfoFilled, Lock } from '@element-plus/icons-vue'
import { commentApi, siteApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { getDeviceId } from '@/utils/device'

const auth = useAuthStore()

const PAGE = 20
const siteTitle = ref('')
const comments = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const submitting = ref(false)
const total = ref(0)
const offset = ref(0)

const form = reactive({ nickname: '', content: '', visibility: 'PUBLIC' })

const editingId = ref(null)
const editContent = ref('')
const saving = ref(false)

const replyTo = ref(null)
const replyContent = ref('')
const replying = ref(false)

const hasMore = computed(() => comments.value.length < total.value)

/**
 * 身份提示文案。
 *
 * <p>⚠️ 三种身份必须给三种说法，这是需求的硬要求：
 * 留言的人得在发出去之前就知道「谁能看到我这条」。
 * 用 computed 而不是模板里写三元：文案长了以后模板会散得看不懂。</p>
 */
const identityTitle = computed(() => {
  if (auth.isAdmin) return '你是站主'
  if (auth.isLoggedIn) return `已登录：${auth.displayName}`
  return '你还没有登录（匿名留言）'
})

const identityText = computed(() => {
  if (auth.isAdmin) {
    return '站主能看到所有留言，包括悄悄话和被隐藏的。在后台「访客留言」里管理。'
  }
  if (auth.isLoggedIn) {
    return '公开留言其他人能看到；选「悄悄话」只有站主能看到。你发布后可以随时编辑或删除自己的留言。'
  }
  return '匿名留言会正常发出，站主能看到。但发布后不能修改、不能删除，你也不会再找回它。想要能编辑就先注册。'
})

const identityClass = computed(() => {
  if (auth.isAdmin) return 'is-admin'
  if (auth.isLoggedIn) return 'is-user'
  return 'is-guest'
})

/** 只有作者本人能编辑/删除，且注册用户才有稳定身份 */
function canOperate(c) {
  return c.mine && c.author.registered
}

onMounted(async () => {
  // 站名失败不影响留言板本身，静默兜底
  siteApi
    .info()
    .then((s) => {
      siteTitle.value = s?.title || ''
    })
    .catch(() => {})
  await load(true)
})

async function load(reset = false) {
  if (reset) {
    loading.value = true
    offset.value = 0
  }
  try {
    const page = await commentApi.board(PAGE, offset.value)
    const items = page?.items || []
    if (reset) {
      comments.value = items
    } else {
      comments.value.push(...items)
    }
    total.value = page?.total || 0
    offset.value += items.length
  } catch (e) {
    ElMessage.error(e.message || '加载留言失败')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadMore() {
  loadingMore.value = true
  await load(false)
}

async function submit() {
  const content = form.content.trim()
  if (!content) return
  submitting.value = true
  try {
    const res = await commentApi.submitToBoard({
      content,
      nickname: auth.isLoggedIn ? null : form.nickname.trim() || null,
      visibility: auth.isLoggedIn ? form.visibility : 'PUBLIC',
      deviceId: getDeviceId()
    })
    // ⚠️ 用后端返回的 notice 而不是前端自己拼文案：
    // 三种身份三种说法，文案只维护一处，改规则时不会前后端不一致
    if (res?.notice) {
      ElMessageBox.alert(res.notice, '已发送', { confirmButtonText: '知道了' })
    } else {
      ElMessage.success('已发送')
    }
    form.content = ''
    form.nickname = ''
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '发送失败')
  } finally {
    submitting.value = false
  }
}

function startEdit(c) {
  editingId.value = c.id
  editContent.value = c.content
  replyTo.value = null
}

function cancelEdit() {
  editingId.value = null
  editContent.value = ''
}

async function saveEdit() {
  saving.value = true
  try {
    await commentApi.edit(editingId.value, editContent.value.trim())
    ElMessage.success('已保存')
    cancelEdit()
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(c) {
  try {
    await ElMessageBox.confirm('删除后不能恢复，确定要删除这条留言吗？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return // 用户点了取消
  }
  try {
    await commentApi.remove(c.id)
    ElMessage.success('已删除')
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function toggleReply(c) {
  if (replyTo.value === c.id) {
    closeReply()
  } else {
    replyTo.value = c.id
    replyContent.value = ''
    editingId.value = null
  }
}

function closeReply() {
  replyTo.value = null
  replyContent.value = ''
}

async function sendReply(parent) {
  const content = replyContent.value.trim()
  if (!content) return
  replying.value = true
  try {
    const res = await commentApi.submitToBoard({
      content,
      nickname: auth.isLoggedIn ? null : form.nickname.trim() || null,
      visibility: 'PUBLIC',
      deviceId: getDeviceId(),
      parentId: parent.id
    })
    if (res?.notice) {
      ElMessage.success(res.notice)
    } else {
      ElMessage.success('回复已发送')
    }
    closeReply()
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '回复失败')
  } finally {
    replying.value = false
  }
}
</script>

<style scoped>
.board {
  min-height: 100vh;
  background: #f7f8fa;
}

/* ---------- 顶部 ---------- */
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid #eef0f4;
}
.brand {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  text-decoration: none;
}
.nav {
  display: flex;
  gap: 20px;
}
.nav a {
  font-size: 14px;
  color: #64748b;
  text-decoration: none;
}
.nav a:hover,
.nav a.active {
  color: #2563eb;
}
/* 顶栏最右的登录入口。描边小按钮，别和普通导航项抢注意力。
   选择器要写成 .nav a.nav-login —— 单写 .nav-login 会被上面 .nav a 的
   颜色规则压过去（特异性 0,1,1 > 0,1,0），只能靠 !important 硬顶，不值得。 */
.nav a.nav-login {
  padding: 4px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 7px;
  color: #475569;
}
.nav a.nav-login:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
  color: #0f172a;
}
/* 已登录时显示昵称，纯文字不可点 */
.nav-who {
  font-size: 14px;
  color: #94a3b8;
}

/* ---------- 主体 ---------- */
.wrap {
  max-width: 760px;
  margin: 0 auto;
  padding: 28px 24px 80px;
}
.page-head {
  margin-bottom: 18px;
}
.page-title {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: #1e293b;
}
.page-sub {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
}

/* ---------- 身份提示 ---------- */
.identity-note {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 9px;
  margin-bottom: 16px;
  font-size: 13px;
  line-height: 1.65;
}
.identity-note .note-icon {
  margin-top: 3px;
  flex-shrink: 0;
}
.note-body p {
  margin: 4px 0 0;
}
/* 游客：提示「不能改」是最重要的一句，用偏暖的黄色 */
.is-guest {
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
}
.is-user {
  background: #f0f7ff;
  border: 1px solid #dbeafe;
  color: #1e40af;
}
.is-admin {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}

/* ---------- 表单 ---------- */
.card {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 16px;
}
.form-card {
  margin-bottom: 20px;
}
.form-row {
  margin-bottom: 10px;
}
.nick-input {
  max-width: 320px;
}
.content-input :deep(textarea) {
  font-size: 14px;
  line-height: 1.7;
}
.form-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}

/* 可见性选择 */
.vis-picker {
  display: flex;
  gap: 8px;
}
.vis-opt {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #475569;
  transition: all 0.15s;
}
.vis-opt.on {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
}
.vis-opt input {
  display: none;
}
.vis-label {
  font-weight: 500;
}
.vis-hint {
  font-size: 11px;
  color: #94a3b8;
}
.vis-opt.on .vis-hint {
  color: #60a5fa;
}
.vis-locked {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #94a3b8;
}

/* ---------- 列表 ---------- */
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.comment {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 14px 16px;
}
/* 悄悄话：给站主一个视觉区分，但不要抢眼到像告警 */
.comment.is-private {
  background: #fdf4ff;
  border-color: #f5d0fe;
}
/* 被屏蔽：灰掉，提示这是「只有你看得到」的状态 */
.comment.is-hidden {
  background: #fafafa;
  border-style: dashed;
}
.c-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.c-name {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}
.c-time {
  margin-left: auto;
  font-size: 12px;
  color: #b0b8c4;
}
.c-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 1.7;
}
.c-tag.owner {
  background: #fff7ed;
  color: #b45309;
  font-weight: 600;
}
.c-tag.reg {
  background: #eff6ff;
  color: #1d4ed8;
}
.c-tag.priv {
  background: #fae8ff;
  color: #a21caf;
}
.c-tag.mine {
  background: #f0fdf4;
  color: #15803d;
}
.c-tag.hidden {
  background: #fef2f2;
  color: #b91c1c;
}
.c-content {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}
.c-hidden-note {
  margin-top: 8px;
  padding: 8px 10px;
  background: #fef2f2;
  border-radius: 6px;
  font-size: 12px;
  color: #b91c1c;
}
.c-actions {
  display: flex;
  gap: 14px;
  margin-top: 10px;
}
.link-btn {
  border: none;
  background: none;
  padding: 0;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
}
.link-btn:hover {
  color: #2563eb;
}
.link-btn.danger:hover {
  color: #dc2626;
}

/* 编辑 / 回复 */
.edit-box,
.reply-box {
  margin-top: 10px;
}
.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
.reply-btn-wrap {
  margin-top: 8px;
}

/* 子回复 */
.replies {
  margin-top: 12px;
  padding-left: 14px;
  border-left: 2px solid #eef0f4;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.reply {
  font-size: 13px;
}
.r-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.r-name {
  font-weight: 600;
  color: #475569;
}
.r-content {
  margin: 0;
  color: #475569;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 空态 / 骨架屏 ---------- */
.empty {
  padding: 60px 0;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}
.empty-icon {
  font-size: 32px;
  color: #cbd5e1;
  margin-bottom: 10px;
}
.skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sk-card {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 16px;
}
.sk-line {
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, #f1f5f9 25%, #e8eef5 37%, #f1f5f9 63%);
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}
.sk-name {
  width: 90px;
  margin-bottom: 10px;
}
.sk-text {
  width: 80%;
}
@keyframes shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

.load-more {
  text-align: center;
  padding: 8px 0;
}

@media (max-width: 640px) {
  .wrap {
    padding: 20px 14px 60px;
  }
  .nav {
    gap: 14px;
  }
  .form-foot {
    flex-direction: column;
    align-items: stretch;
  }
  .vis-picker {
    flex-direction: column;
  }
  .vis-opt {
    justify-content: space-between;
  }
}
</style>
