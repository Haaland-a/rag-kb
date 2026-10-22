<template>
  <div class="site">
    <!-- ============ 顶部导航 ============ -->
    <header class="nav">
      <div class="nav-inner">
        <router-link to="/" class="nav-brand">{{ title }}</router-link>

        <nav class="nav-links">
          <router-link to="/" class="nav-link">主页</router-link>
          <a href="#feed" class="nav-link">日常</a>
          <router-link to="/board" class="nav-link">留言板</router-link>
        </nav>

        <div class="nav-user">
          <!--
            登录入口留在右上角，但不做强制登录 —— 游客进来先看内容。
            已登录则把入口换成昵称（点回留言板）。
          -->
          <router-link v-if="auth.isLoggedIn" to="/board" class="nav-login">
            {{ auth.displayName }}
          </router-link>
          <router-link v-else to="/login" class="nav-login">登录</router-link>
        </div>
      </div>
    </header>

    <main>
      <section class="head">
        <div class="head-inner">
          <h1 class="head-title">日常</h1>
          <p class="head-sub">
            随手记的一些片段。看到什么想法，可以在下面给我留言。
          </p>
        </div>
      </section>

      <section id="feed" class="feed-wrap">
        <div class="feed-inner">
          <div v-if="loading" class="skeleton-list">
            <div v-for="i in 3" :key="i" class="skeleton"></div>
          </div>

          <el-empty
            v-else-if="!items.length"
            description="还没发过动态，过几天再来看看"
          />

          <article v-for="m in items" :key="m.id" class="moment">
            <p v-if="m.content" class="moment-text">{{ m.content }}</p>

            <div v-if="m.mediaUrl" class="moment-media">
              <img
                v-if="m.mediaType === 'IMAGE'"
                :src="m.mediaUrl"
                alt=""
                loading="lazy"
                class="moment-img"
                @click="preview(m.mediaUrl)"
              />
              <video
                v-else-if="m.mediaType === 'VIDEO'"
                :src="m.mediaUrl"
                controls
                preload="metadata"
                playsinline
                class="moment-video"
              />
            </div>

            <div class="moment-foot">
              <span class="moment-time">{{ m.createdAt }}</span>
              <button
                class="comment-toggle"
                type="button"
                @click="toggleForm(m.id)"
              >
                <el-icon><ChatLineSquare /></el-icon>
                {{ openFormId === m.id ? '收起' : '留言' }}
                <span v-if="commentCounts[m.id]" class="count-badge">
                  {{ commentCounts[m.id] }}
                </span>
              </button>
            </div>

            <!-- 已有留言（公开的那些，游客也能看到） -->
            <div v-if="commentsByMoment[m.id]?.length" class="moment-comments">
              <div
                v-for="c in commentsByMoment[m.id]"
                :key="c.id"
                class="mc-item"
                :class="{ 'mc-mine': c.mine }"
              >
                <span class="mc-name">{{ c.nickname }}</span>
                <span v-if="c.author.admin" class="mc-tag mc-tag-owner">站主</span>
                <span v-else-if="c.author.registered" class="mc-tag">注册用户</span>
                <span v-if="c.mine" class="mc-tag mc-tag-mine">我</span>
                <span v-if="c.hidden" class="mc-tag mc-tag-hidden">已被站主隐藏</span>
                <span class="mc-time">{{ c.createdAt }}</span>
                <p class="mc-content">{{ c.content }}</p>
              </div>
              <router-link to="/board" class="mc-more">在留言板看全部 →</router-link>
            </div>

            <!-- ============ 留言表单 ============ -->
            <transition name="fade">
              <form
                v-if="openFormId === m.id"
                class="comment-form"
                @submit.prevent="submit(m)"
              >
                <!--
                  ⚠️ 提示文案分三种身份，这是需求明确的硬要求：
                  留言的人必须在发出去之前就知道「谁能看到我这条」。
                  游客 = 只有站主能看到；注册用户 = 自己选；站主 = 全都能看到。
                -->
                <p v-if="!auth.isLoggedIn" class="privacy-note">
                  <el-icon><Lock /></el-icon>
                  <span>
                    <strong>匿名留言只有站主本人能看到</strong>，不会公开显示。
                    发布后不能修改也不能删除，你也不会再找回它 ——
                    <router-link to="/register" class="note-link">注册</router-link>
                    后可以选择公开或悄悄话，还能自己编辑。
                  </span>
                </p>
                <p v-else class="privacy-note is-user">
                  <el-icon><InfoFilled /></el-icon>
                  <span>
                    选<strong>「公开」</strong>这条会出现在动态下面，其他人也能看到；
                    选<strong>「悄悄话」</strong>只有站主能看到。发布后你可以编辑或删除自己的留言。
                  </span>
                </p>

                <div v-if="!auth.isLoggedIn" class="form-row">
                  <input
                    v-model="form.nickname"
                    class="field field-nick"
                    type="text"
                    maxlength="30"
                    placeholder="怎么称呼你（可不填）"
                  />
                </div>

                <!-- 可见性：只有注册用户能选悄悄话 -->
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

                <textarea
                  v-model="form.content"
                  class="field field-content"
                  rows="3"
                  maxlength="1000"
                  placeholder="想说的话…"
                ></textarea>

                <div class="form-actions">
                  <span class="form-count">{{ form.content.length }}/1000</span>
                  <button
                    class="btn btn-primary btn-sm"
                    type="submit"
                    :disabled="sending || !form.content.trim()"
                  >
                    {{ sending ? '发送中…' : '发送留言' }}
                  </button>
                </div>
              </form>
            </transition>
          </article>
        </div>
      </section>
    </main>

    <footer class="foot">
      <router-link to="/" class="foot-link">← 回主页</router-link>
    </footer>

    <el-dialog v-model="imageOpen" width="min(92vw, 1000px)" align-center destroy-on-close>
      <img :src="imageUrl" alt="" class="viewer-img" />
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, InfoFilled, Lock } from '@element-plus/icons-vue'
import { commentApi, momentApi, siteApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { getDeviceId } from '@/utils/device'

const auth = useAuthStore()

const title = ref('Demo')
const loading = ref(true)
const items = ref([])

// 同一时刻只展开一个留言框：多个表单同时开着，用户填到一半切走容易丢
const openFormId = ref(null)
const sending = ref(false)
const form = reactive({ nickname: '', content: '', visibility: 'PUBLIC' })

// 每条动态下已经公开的留言：{ [momentId]: CommentDto[] }
const commentsByMoment = ref({})
// 每条动态的留言总数：{ [momentId]: number }
const commentCounts = ref({})

const imageOpen = ref(false)
const imageUrl = ref('')

onMounted(async () => {
  try {
    const site = await siteApi.info()
    if (site?.title) title.value = site.title
  } catch {
    /* 站名拿不到不影响看动态 */
  }

  try {
    const list = await momentApi.publicList()
    items.value = list || []
    // 动态拿到后再并发补留言，避免串行等 10 条 × 一次请求
    await Promise.all((list || []).map((m) => loadComments(m.id)))
  } catch (e) {
    ElMessage.error(e.message || '加载动态失败')
  } finally {
    loading.value = false
  }
})

function toggleForm(id) {
  if (openFormId.value === id) {
    openFormId.value = null
    return
  }
  openFormId.value = id
  form.nickname = ''
  form.content = ''
  form.visibility = 'PUBLIC'
}

async function submit(m) {
  const content = form.content.trim()
  if (!content) return
  sending.value = true
  try {
    const res = await commentApi.submitToMoment(m.id, {
      // ⚠️ 注册用户不传 nickname：后端会用账号显示名覆盖，
      // 传了也白传，还容易让人以为「改这里能改署名」
      nickname: auth.isLoggedIn ? null : form.nickname.trim() || null,
      content,
      visibility: auth.isLoggedIn ? form.visibility : 'PUBLIC',
      deviceId: getDeviceId()
    })
    // 用后端返回的文案：三种身份三种说法，文案只维护一处
    if (res?.notice) {
      ElMessageBox.alert(res.notice, '已发送', { confirmButtonText: '知道了' })
    } else {
      ElMessage.success('已发送')
    }
    form.content = ''
    form.nickname = ''
    openFormId.value = null
    await loadComments(m.id)
  } catch (e) {
    ElMessage.error(e.message || '发送失败')
  } finally {
    sending.value = false
  }
}

/** 拉某条动态下「访客可见」的留言（公开且未被屏蔽，外加自己发的那些） */
async function loadComments(momentId) {
  try {
    const page = await commentApi.momentComments(momentId, 30, 0)
    const list = page?.items || []
    commentsByMoment.value = { ...commentsByMoment.value, [momentId]: list }
    commentCounts.value = { ...commentCounts.value, [momentId]: page?.total || list.length }
  } catch {
    /* 留言拉不到不影响看动态主体 */
  }
}

function preview(url) {
  imageUrl.value = url
  imageOpen.value = true
}
</script>

<style scoped>
.site {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #1e293b;
}

/* ==================== 导航（与主页一致） ==================== */
.nav {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid #f1f5f9;
}
.nav-inner {
  max-width: 720px;
  margin: 0 auto;
  height: 60px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.nav-brand {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  text-decoration: none;
  letter-spacing: -0.2px;
}
.nav-links {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 4px;
}
.nav-link {
  padding: 7px 12px;
  border-radius: 8px;
  font-size: 14px;
  color: #64748b;
  text-decoration: none;
  transition: all 0.15s;
}
.nav-link:hover {
  background: #f8fafc;
  color: #0f172a;
}
.nav-login {
  padding: 7px 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 14px;
  color: #475569;
  text-decoration: none;
  transition: all 0.15s;
}
.nav-login:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
}

/* ==================== 页头 ==================== */
.head {
  padding: 64px 24px 28px;
}
.head-inner {
  max-width: 660px;
  margin: 0 auto;
}
.head-title {
  margin: 0 0 10px;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: -0.5px;
}
.head-sub {
  margin: 0;
  font-size: 15px;
  color: #64748b;
  line-height: 1.75;
}

/* ==================== 动态流 ==================== */
.feed-wrap {
  padding: 8px 24px 72px;
}
.feed-inner {
  max-width: 660px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.moment {
  padding: 22px 24px;
  border: 1px solid #eef1f5;
  border-radius: 14px;
  background: #fff;
  transition: box-shadow 0.2s;
}
.moment:hover {
  box-shadow: 0 6px 24px rgba(15, 23, 42, 0.05);
}
.moment-text {
  margin: 0 0 14px;
  font-size: 15px;
  line-height: 1.85;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}
.moment-media {
  margin-bottom: 14px;
}
.moment-img {
  max-width: 100%;
  max-height: 460px;
  border-radius: 10px;
  display: block;
  cursor: zoom-in;
}
.moment-video {
  max-width: 100%;
  max-height: 460px;
  border-radius: 10px;
  display: block;
  background: #000;
}
.moment-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 4px;
}
.moment-time {
  font-size: 12.5px;
  color: #a8b3c4;
  font-variant-numeric: tabular-nums;
}
.comment-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid #eef1f5;
  border-radius: 8px;
  background: #fff;
  font-family: inherit;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}
.comment-toggle:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
  color: #0f172a;
}

/* ==================== 留言表单 ==================== */
.comment-form {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px dashed #eef1f5;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.privacy-note {
  display: flex;
  gap: 8px;
  margin: 0;
  padding: 11px 13px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 9px;
  font-size: 12.5px;
  line-height: 1.7;
  color: #92400e;
}
.privacy-note strong {
  color: #78350f;
}
.privacy-note .el-icon {
  margin-top: 2px;
  flex-shrink: 0;
}
/* 注册用户的提示：不是警告，是说明选项，所以用蓝色而不是黄色 */
.privacy-note.is-user {
  background: #f0f7ff;
  border-color: #dbeafe;
  color: #1e40af;
}
.privacy-note.is-user strong {
  color: #1d4ed8;
}
.note-link {
  color: #2563eb;
  text-decoration: underline;
  font-weight: 500;
}

/* 可见性选择 */
.vis-picker {
  display: flex;
  gap: 8px;
}
.vis-opt {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
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
  margin-left: auto;
  font-size: 11px;
  color: #94a3b8;
}
.vis-opt.on .vis-hint {
  color: #60a5fa;
}

/* 动态下已公开的留言 */
.moment-comments {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #eef1f5;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.mc-item {
  padding: 9px 11px;
  background: #f8fafc;
  border-radius: 8px;
  font-size: 13px;
}
/* 自己发的那条：淡蓝底，一眼能找到 */
.mc-item.mc-mine {
  background: #f0f7ff;
}
.mc-name {
  font-weight: 600;
  color: #334155;
}
.mc-tag {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 3px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 10.5px;
}
.mc-tag-owner {
  background: #fff7ed;
  color: #b45309;
  font-weight: 600;
}
.mc-tag-mine {
  background: #f0fdf4;
  color: #15803d;
}
.mc-tag-hidden {
  background: #fef2f2;
  color: #b91c1c;
}
.mc-time {
  margin-left: 6px;
  font-size: 11px;
  color: #b0b8c4;
}
.mc-content {
  margin: 4px 0 0;
  line-height: 1.7;
  color: #475569;
  white-space: pre-wrap;
  word-break: break-word;
}
.mc-more {
  font-size: 12px;
  color: #64748b;
  text-decoration: none;
  align-self: flex-start;
}
.mc-more:hover {
  color: #2563eb;
}
.count-badge {
  display: inline-block;
  min-width: 16px;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 8px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 10.5px;
  text-align: center;
}
.field {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  font-family: inherit;
  font-size: 14px;
  color: #334155;
  outline: none;
  box-sizing: border-box;
  transition: border-color 0.15s;
}
.field:focus {
  border-color: #93c5fd;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.08);
}
.field::placeholder {
  color: #b0b9c6;
}
.field-nick {
  max-width: 240px;
}
.field-content {
  resize: vertical;
  line-height: 1.7;
}
.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.form-count {
  font-size: 12px;
  color: #cbd5e1;
  font-variant-numeric: tabular-nums;
}

/* ==================== 按钮 ==================== */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  border-radius: 9px;
  font-family: inherit;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.18s;
}
.btn-primary {
  background: #2563eb;
  color: #fff;
}
.btn-primary:hover:not(:disabled) {
  background: #1d4ed8;
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-sm {
  padding: 8px 18px;
  font-size: 13.5px;
}

/* ==================== 骨架屏 ==================== */
.skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.skeleton {
  height: 140px;
  border-radius: 14px;
  background: linear-gradient(90deg, #f8fafc 25%, #f1f5f9 37%, #f8fafc 63%);
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}
@keyframes shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

/* ==================== 页脚 ==================== */
.foot {
  margin-top: auto;
  padding: 28px 24px 40px;
  text-align: center;
  border-top: 1px solid #f8fafc;
}
.foot-link {
  font-size: 13px;
  color: #94a3b8;
  text-decoration: none;
}
.foot-link:hover {
  color: #2563eb;
}
.viewer-img {
  width: 100%;
  height: auto;
  display: block;
  border-radius: 8px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 640px) {
  .nav-links {
    display: none;
  }
  .head {
    padding: 40px 20px 20px;
  }
  .head-title {
    font-size: 25px;
  }
  .feed-wrap {
    padding: 8px 20px 56px;
  }
  .moment {
    padding: 18px;
  }
  .field-nick {
    max-width: 100%;
  }
}
</style>
