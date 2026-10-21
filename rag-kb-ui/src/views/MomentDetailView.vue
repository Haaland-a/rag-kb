<template>
  <div class="site">
    <!-- ============ 顶部导航 ============ -->
    <header class="nav">
      <div class="nav-inner">
        <router-link to="/" class="nav-brand">{{ title }}</router-link>

        <nav class="nav-links">
          <router-link to="/" class="nav-link">主页</router-link>
          <router-link to="/moments" class="nav-link">日常</router-link>
          <router-link to="/board" class="nav-link">留言板</router-link>
        </nav>

        <div class="nav-user">
          <router-link v-if="auth.isLoggedIn" to="/board" class="nav-login">
            {{ auth.displayName }}
          </router-link>
          <router-link v-else to="/login" class="nav-login">登录</router-link>
        </div>
      </div>
    </header>

    <main class="main">
      <div class="wrap">
        <router-link to="/moments" class="back">← 回日常列表</router-link>

        <!-- 加载占位 -->
        <div v-if="loading" class="skeleton-card">
          <div class="sk sk-line"></div>
          <div class="sk sk-line short"></div>
          <div class="sk sk-img"></div>
        </div>

        <!-- 404 / 已删除 -->
        <el-empty
          v-else-if="!moment"
          description="这条动态不存在，或者已经被删掉了"
        >
          <router-link to="/moments" class="btn-link">去看别的日常</router-link>
        </el-empty>

        <!-- 正文 -->
        <template v-else>
          <article class="moment">
            <p v-if="moment.content" class="moment-text">{{ moment.content }}</p>

            <div v-if="moment.mediaUrl" class="moment-media">
              <img
                v-if="moment.mediaType === 'IMAGE'"
                :src="moment.mediaUrl"
                alt=""
                class="moment-img"
                @click="preview(moment.mediaUrl)"
              />
              <video
                v-else-if="moment.mediaType === 'VIDEO'"
                :src="moment.mediaUrl"
                controls
                preload="metadata"
                playsinline
                class="moment-video"
              />
            </div>

            <div class="moment-foot">
              <span class="moment-time">{{ moment.createdAt }}</span>
              <button class="comment-toggle" type="button" @click="toggleForm">
                <el-icon><ChatLineSquare /></el-icon>
                {{ formOpen ? '收起' : '留言' }}
                <span v-if="commentTotal" class="count-badge">{{ commentTotal }}</span>
              </button>
            </div>
          </article>

          <!-- ============ 留言表单 ============ -->
          <transition name="fade">
            <form v-if="formOpen" class="comment-form" @submit.prevent="submit">
              <!--
                ⚠️ 三种身份三种提示，和 `/moments` 页保持逐字一致：
                留言的人必须在发出去之前就知道「谁能看到我这条」。
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

          <!-- ============ 全部留言 ============ -->
          <section class="comments">
            <h2 class="comments-title">
              留言
              <span v-if="commentTotal" class="comments-count">{{ commentTotal }}</span>
            </h2>

            <p v-if="!comments.length" class="comments-empty">
              还没有人留言，你可以是第一句。
            </p>

            <div v-else class="comment-list">
              <div
                v-for="c in comments"
                :key="c.id"
                class="mc-item"
                :class="{ 'mc-mine': c.mine, 'mc-hidden': c.hidden }"
              >
                <div class="mc-head">
                  <span class="mc-name">{{ c.nickname }}</span>
                  <span v-if="c.author.registered" class="mc-tag">注册用户</span>
                  <span v-if="c.mine" class="mc-tag mc-tag-mine">我</span>
                  <span v-if="c.hidden" class="mc-tag mc-tag-hidden">已被站主隐藏</span>
                  <span class="mc-time">{{ c.createdAt }}</span>
                </div>
                <p class="mc-content">{{ c.content }}</p>

                <!-- 一层回复（列表页和留言板都只做一层，不做无限嵌套） -->
                <div v-if="c.replies?.length" class="mc-replies">
                  <div v-for="r in c.replies" :key="r.id" class="mc-reply">
                    <span class="mc-name">{{ r.nickname }}</span>
                    <span class="mc-content">{{ r.content }}</span>
                  </div>
                </div>
              </div>
            </div>

            <p v-if="commentTotal > comments.length" class="comments-note">
              只显示了最近 {{ comments.length }} 条，共 {{ commentTotal }} 条。
            </p>
          </section>
        </template>
      </div>
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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineSquare, InfoFilled, Lock } from '@element-plus/icons-vue'
import { commentApi, momentApi, siteApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { getDeviceId } from '@/utils/device'

const auth = useAuthStore()
const route = useRoute()

const title = ref('Haalan')
const loading = ref(true)
/** 动态本体；null = 不存在/被隐藏/已删（三种情况后端统一 404，这里也统一一个空态） */
const moment = ref(null)

const comments = ref([])
const commentTotal = ref(0)

const formOpen = ref(false)
const sending = ref(false)
const form = reactive({ nickname: '', content: '', visibility: 'PUBLIC' })

const imageOpen = ref(false)
const imageUrl = ref('')

const momentId = computed(() => Number(route.params.id))

async function load() {
  loading.value = true
  try {
    const site = await siteApi.info()
    if (site?.title) title.value = site.title
  } catch {
    /* 站名拿不到不影响看内容 */
  }

  try {
    const d = await momentApi.publicDetail(momentId.value)
    moment.value = d?.moment || null
    comments.value = d?.comments || []
    commentTotal.value = d?.commentTotal || comments.value.length
    if (moment.value?.content) {
      // 标题跟内容走，方便分享时一眼看出是哪条
      document.title = `${moment.value.content.slice(0, 20)} · ${title.value}`
    }
  } catch {
    moment.value = null
    comments.value = []
    commentTotal.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(load)

// 在详情页之间跳（比如「上一条/下一条」将来加上）也要重新拉
watch(momentId, (v) => {
  if (v) load()
})

function toggleForm() {
  formOpen.value = !formOpen.value
  if (formOpen.value) {
    form.nickname = ''
    form.content = ''
    form.visibility = 'PUBLIC'
  }
}

async function submit() {
  const content = form.content.trim()
  if (!content || !moment.value) return
  sending.value = true
  try {
    const res = await commentApi.submitToMoment(moment.value.id, {
      nickname: auth.isLoggedIn ? null : form.nickname.trim() || null,
      content,
      visibility: auth.isLoggedIn ? form.visibility : 'PUBLIC',
      deviceId: getDeviceId()
    })
    if (res?.notice) {
      ElMessageBox.alert(res.notice, '已发送', { confirmButtonText: '知道了' })
    } else {
      ElMessage.success('已发送')
    }
    form.content = ''
    form.nickname = ''
    formOpen.value = false
    await reloadComments()
  } catch (e) {
    ElMessage.error(e.message || '发送失败')
  } finally {
    sending.value = false
  }
}

/** 只重拉留言，不动动态本体（发完留言没必要整页重载） */
async function reloadComments() {
  if (!moment.value) return
  try {
    const page = await commentApi.momentComments(moment.value.id, 30, 0)
    comments.value = page?.items || []
    commentTotal.value = page?.total || comments.value.length
  } catch {
    /* 留言拉不到不影响正文 */
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

/* ==================== 导航（与主页/日常页一致） ==================== */
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
  font-weight: 700;
  color: #0f172a;
  text-decoration: none;
}
.nav-links {
  display: flex;
  gap: 18px;
  margin-left: auto;
}
.nav-link {
  font-size: 14px;
  color: #64748b;
  text-decoration: none;
}
.nav-link:hover,
.nav-link.router-link-active {
  color: #0f172a;
}
.nav-user {
  display: flex;
  align-items: center;
}
.nav-login {
  font-size: 13.5px;
  color: #475569;
  text-decoration: none;
  padding: 6px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}
.nav-login:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
  color: #0f172a;
}

/* ==================== 主体 ==================== */
.main {
  flex: 1;
  padding: 32px 24px 56px;
}
.wrap {
  max-width: 720px;
  margin: 0 auto;
}
.back {
  display: inline-block;
  margin-bottom: 18px;
  font-size: 13.5px;
  color: #64748b;
  text-decoration: none;
}
.back:hover {
  color: #2563eb;
}

.moment {
  padding: 22px 24px;
  border: 1px solid #eef1f5;
  border-radius: 14px;
  background: #fff;
}
.moment-text {
  margin: 0 0 14px;
  font-size: 15.5px;
  line-height: 1.9;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}
.moment-media {
  margin-bottom: 12px;
}
.moment-img {
  max-width: 100%;
  max-height: 520px;
  border-radius: 10px;
  display: block;
  cursor: zoom-in;
}
.moment-video {
  max-width: 100%;
  max-height: 520px;
  border-radius: 10px;
  display: block;
  background: #000;
}
.moment-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
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
  padding: 6px 12px;
  font-size: 13px;
  color: #475569;
  background: #f8fafc;
  border: 1px solid #eef1f5;
  border-radius: 8px;
  cursor: pointer;
}
.comment-toggle:hover {
  border-color: #cbd5e1;
  color: #0f172a;
}
.count-badge {
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  font-size: 10.5px;
  line-height: 17px;
  text-align: center;
  color: #2563eb;
  background: #eff6ff;
  border-radius: 9px;
}

/* ==================== 留言表单 ==================== */
.comment-form {
  margin-top: 14px;
  padding: 16px 18px;
  background: #fafbfc;
  border: 1px solid #eef1f5;
  border-radius: 12px;
}
.privacy-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 0 12px;
  padding: 10px 12px;
  font-size: 12.5px;
  line-height: 1.7;
  color: #92400e;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
}
.privacy-note.is-user {
  color: #1e40af;
  background: #f0f7ff;
  border-color: #bfdbfe;
}
.note-link {
  color: #2563eb;
  text-decoration: underline;
}
.form-row {
  margin-bottom: 10px;
}
.field {
  width: 100%;
  padding: 9px 12px;
  font-size: 13.5px;
  color: #1e293b;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  outline: none;
  box-sizing: border-box;
}
.field:focus {
  border-color: #93b4f8;
}
.field-nick {
  max-width: 260px;
}
.field-content {
  resize: vertical;
  line-height: 1.7;
  font-family: inherit;
}
.vis-picker {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}
.vis-opt {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
}
.vis-opt.on {
  border-color: #2563eb;
  background: #f0f7ff;
}
.vis-opt input {
  display: none;
}
.vis-label {
  font-size: 13px;
  color: #1e293b;
}
.vis-hint {
  font-size: 11.5px;
  color: #94a3b8;
}
.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}
.form-count {
  font-size: 12px;
  color: #a8b3c4;
  font-variant-numeric: tabular-nums;
}
.btn {
  padding: 8px 18px;
  font-size: 13.5px;
  border-radius: 8px;
  border: 1px solid transparent;
  cursor: pointer;
}
.btn-primary {
  color: #fff;
  background: #2563eb;
}
.btn-primary:hover {
  background: #1d4ed8;
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-sm {
  padding: 7px 16px;
  font-size: 13px;
}

/* ==================== 留言列表 ==================== */
.comments {
  margin-top: 28px;
}
.comments-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.comments-count {
  font-size: 12px;
  font-weight: 400;
  color: #64748b;
}
.comments-empty {
  margin: 0;
  padding: 22px 0;
  font-size: 13.5px;
  color: #94a3b8;
  text-align: center;
}
.comment-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mc-item {
  padding: 12px 14px;
  background: #f8fafc;
  border-radius: 9px;
  font-size: 13.5px;
}
.mc-item.mc-mine {
  background: #f0f7ff;
}
.mc-item.mc-hidden {
  background: #fdf3f3;
  border: 1px dashed #e8b4b4;
}
.mc-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.mc-name {
  font-weight: 600;
  color: #2563eb;
  font-size: 13px;
}
.mc-tag {
  padding: 0 5px;
  font-size: 10.5px;
  line-height: 16px;
  color: #475569;
  background: #eef1f5;
  border-radius: 3px;
}
.mc-tag-mine {
  color: #1e40af;
  background: #dbeafe;
}
.mc-tag-hidden {
  color: #b91c1c;
  background: #fee2e2;
}
.mc-time {
  margin-left: auto;
  font-size: 11.5px;
  color: #a8b3c4;
  font-variant-numeric: tabular-nums;
}
.mc-content {
  margin: 0;
  line-height: 1.8;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}
.mc-replies {
  margin-top: 8px;
  padding-left: 10px;
  border-left: 2px solid #e2e8f0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.mc-reply {
  font-size: 12.5px;
  line-height: 1.7;
  color: #475569;
}
.comments-note {
  margin: 14px 0 0;
  font-size: 12.5px;
  color: #94a3b8;
  text-align: center;
}

/* ==================== 骨架屏 / 页脚 ==================== */
.skeleton-card {
  padding: 22px 24px;
  border: 1px solid #eef1f5;
  border-radius: 14px;
}
.sk {
  background: linear-gradient(90deg, #f1f5f9 25%, #e8eef5 37%, #f1f5f9 63%);
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
  border-radius: 6px;
}
.sk-line {
  height: 15px;
  margin-bottom: 10px;
}
.sk-line.short {
  width: 55%;
}
.sk-img {
  height: 220px;
  margin-top: 14px;
}
@keyframes shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
.foot {
  padding: 26px 24px;
  text-align: center;
  font-size: 12.5px;
  color: #a8b3c4;
  border-top: 1px solid #f1f5f9;
}
.foot-link {
  color: #94a3b8;
  text-decoration: none;
}
.foot-link:hover {
  color: #2563eb;
}
.btn-link {
  color: #2563eb;
  text-decoration: none;
  font-size: 13.5px;
}
.viewer-img {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 6px;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
