<template>
  <div class="site">
    <!-- ============ 顶部导航 ============ -->
    <header class="nav">
      <div class="nav-inner">
        <router-link to="/" class="nav-brand">{{ site.title }}</router-link>

        <nav class="nav-links">
          <a href="#about" class="nav-link">关于</a>
          <a v-if="site.moments?.length" href="#moments" class="nav-link">日常</a>
          <a v-if="site.projects?.length" href="#projects" class="nav-link">项目</a>
          <a v-if="site.photos?.length" href="#photos" class="nav-link">照片</a>
          <a href="#knowledge" class="nav-link">知识库</a>
          <!--
            留言板入口放在导航里，而不是靠在右下角或页脚里找。
            导航给的是「站里有哪几个地方可去」，登录不属于这个范畴 ——
            那是「你是谁」的问题，所以它不进这条 nav。
          -->
          <router-link to="/board" class="nav-link">留言板</router-link>
          <button class="nav-link nav-ask" type="button" @click="openChat">
            <el-icon><ChatDotRound /></el-icon>
            问点什么
          </button>
        </nav>

        <div class="nav-user">
          <!--
            ⚠️ 登录入口要留着，但**只作为一个普通链接摊在右上角**。

            项目 owner 的原话是「不要点进网站就去登陆」—— 指的是别做强制登录 /
            一进门就弹登录框，不是把入口藏起来。藏起来的后果是想登录的人找不到门：
            站主每次进站还得手动敲 /login，很折腾。

            所以这里的规矩是：
              · 进站默认就是主页，游客能看能问能留言，不拦；
              · 右上角始终有「登录」这个小入口，要登录的人点得到；
              · 不给游客渲染「注册」，注册的动机（发悄悄话、能编辑）写在留言表单里，
                首屏不劝人注册。已登录则把登录入口换成用户菜单。
          -->
          <router-link v-if="!auth.isLoggedIn" to="/login" class="nav-login">登录</router-link>
          <el-dropdown v-else @command="onCommand">
            <span class="user-chip">
              <span class="user-dot"></span>
              {{ auth.displayName }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="dashboard">我的后台</el-dropdown-item>
                <el-dropdown-item command="documents">上传资料</el-dropdown-item>
                <el-dropdown-item command="visits">访问记录</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <main>
      <!-- ============ 首屏：个人介绍 ============ -->
      <section class="hero">
        <div class="hero-inner">
          <div class="avatar">
            <img
              v-if="site.owner.avatarUrl"
              :src="site.owner.avatarUrl"
              :alt="site.owner.name || '头像'"
            />
            <template v-else>{{ site.owner.avatarText || 'H' }}</template>
          </div>

          <h1 class="hero-title">
            你好，我是 <span class="hero-name">{{ site.owner.name || 'Demo' }}</span>
          </h1>
          <p class="hero-tagline">{{ site.owner.tagline }}</p>

          <div v-if="site.owner.tags?.length" class="hero-tags">
            <span v-for="t in site.owner.tags" :key="t" class="tag">{{ t }}</span>
          </div>

          <div class="hero-actions">
            <button class="btn btn-primary" type="button" @click="openChat">
              <el-icon><ChatDotRound /></el-icon>
              和我的知识库聊聊
            </button>
            <a v-if="site.owner.links?.length" :href="site.owner.links[0].url"
               class="btn btn-ghost" target="_blank" rel="noopener">
              {{ site.owner.links[0].label }}
            </a>
          </div>
        </div>
      </section>

      <!-- ============ 关于 ============ -->
      <section id="about" class="section">
        <div class="section-inner">
          <h2 class="section-title">关于我</h2>
          <div class="about-text">
            <p v-if="site.owner.intro">{{ site.owner.intro }}</p>
            <p v-for="(para, i) in site.owner.about || []" :key="i">{{ para }}</p>
            <p v-if="!site.owner.intro && !site.owner.about?.length" class="muted">
              站主还没写自我介绍。
            </p>
          </div>

          <div v-if="site.owner.links?.length" class="links">
            <a v-for="link in site.owner.links" :key="link.url" :href="link.url"
               class="link-item" target="_blank" rel="noopener">
              {{ link.label }} ↗
            </a>
          </div>
        </div>
      </section>

      <!-- ============ 日常 ============ -->
      <section v-if="site.moments?.length" id="moments" class="section">
        <div class="section-inner">
          <h2 class="section-title">最近的日常</h2>
          <p class="section-sub">随手记下的片段。</p>

          <div class="moment-list">
            <article v-for="m in site.moments" :key="m.id" class="home-moment">
              <p v-if="m.content" class="home-moment-text">{{ m.content }}</p>
              <img
                v-if="m.mediaType === 'IMAGE' && m.mediaUrl"
                :src="m.mediaUrl"
                alt=""
                loading="lazy"
                class="home-moment-img"
                @click="viewPhoto({ url: m.mediaUrl, caption: '' })"
              />
              <video
                v-else-if="m.mediaType === 'VIDEO' && m.mediaUrl"
                :src="m.mediaUrl"
                controls
                preload="metadata"
                playsinline
                class="home-moment-video"
              ></video>
              <span class="home-moment-time">{{ m.createdAt }}</span>
            </article>
          </div>

          <router-link to="/moments" class="btn btn-ghost btn-center">
            看全部日常
          </router-link>
        </div>
      </section>

      <!-- ============ 项目 ============ -->
      <section v-if="site.projects?.length" id="projects" class="section">
        <div class="section-inner">
          <h2 class="section-title">做过的项目</h2>
          <p class="section-sub">一些自己动手搭起来的东西。</p>

          <div class="proj-grid">
            <article v-for="p in site.projects" :key="p.id" class="proj-card">
              <div v-if="p.coverUrl" class="proj-cover">
                <img :src="p.coverUrl" :alt="p.title" loading="lazy" />
              </div>
              <div v-else class="proj-cover proj-cover--empty">
                <el-icon><Box /></el-icon>
              </div>

              <div class="proj-body">
                <h3 class="proj-title">{{ p.title }}</h3>
                <p v-if="p.description" class="proj-desc">{{ p.description }}</p>

                <div v-if="p.tech?.length" class="proj-tech">
                  <span v-for="t in p.tech" :key="t" class="proj-tag">{{ t }}</span>
                </div>

                <div v-if="p.repoUrl || p.demoUrl" class="proj-links">
                  <a v-if="p.repoUrl" :href="p.repoUrl" target="_blank" rel="noopener"
                     class="proj-link">源码 ↗</a>
                  <a v-if="p.demoUrl" :href="p.demoUrl" target="_blank" rel="noopener"
                     class="proj-link">在线 ↗</a>
                </div>
              </div>
            </article>
          </div>
        </div>
      </section>

      <!-- ============ 照片墙 ============ -->
      <section v-if="site.photos?.length" id="photos" class="section section-alt">
        <div class="section-inner">
          <h2 class="section-title">随手拍的</h2>
          <p class="section-sub">生活和路上看到的东西。</p>

          <div class="photo-grid">
            <figure v-for="p in site.photos" :key="p.id" class="photo-item"
                    @click="viewPhoto(p)">
              <img :src="p.url" :alt="p.caption || ''" loading="lazy"
                   :width="p.width || undefined" :height="p.height || undefined" />
              <figcaption v-if="p.caption" class="photo-cap">{{ p.caption }}</figcaption>
            </figure>
          </div>
        </div>
      </section>

      <!-- ============ 知识库 ============ -->
      <section id="knowledge" class="section section-alt">
        <div class="section-inner">
          <h2 class="section-title">我的知识库</h2>
          <p class="section-sub">
            我把读过的资料放了进来。你可以直接提问，答案会标注出处。
          </p>

          <div class="stats">
            <div class="stat">
              <div class="stat-num">{{ site.documentCount ?? 0 }}</div>
              <div class="stat-label">篇资料</div>
            </div>
            <div class="stat">
              <div class="stat-num">{{ site.chunkCount ?? 0 }}</div>
              <div class="stat-label">个片段</div>
            </div>
            <div class="stat">
              <div class="stat-num">{{ site.visitCount ?? 0 }}</div>
              <div class="stat-label">次访问</div>
            </div>
          </div>

          <div v-if="site.recentDocuments?.length" class="recent">
            <div class="recent-label">最近收录</div>
            <div class="recent-list">
              <div v-for="doc in site.recentDocuments" :key="doc.filename" class="recent-item">
                <span class="recent-type">{{ (doc.fileType || '').toUpperCase() }}</span>
                <span class="recent-name">{{ doc.filename }}</span>
                <span class="recent-chunks">{{ doc.chunkCount }} 块</span>
              </div>
            </div>
          </div>

          <button class="btn btn-primary btn-center" type="button" @click="openChat">
            现在提问
          </button>
        </div>
      </section>
    </main>

    <!-- ============ 页脚 ============ -->
    <footer class="foot">
      <div v-if="site.downloads?.length" class="foot-downloads">
        <span class="foot-dl-label">下载</span>
        <a v-for="d in site.downloads" :key="d.id" :href="d.url"
           class="foot-dl" target="_blank" rel="noopener"
           @click.prevent="askDownload(d)">
          <el-icon><Document /></el-icon>
          {{ d.title }}
          <span v-if="d.fileSize" class="foot-dl-size">{{ formatSize(d.fileSize) }}</span>
        </a>
      </div>

      <div class="foot-line">
        <span>© {{ year }} {{ site.owner.name || 'Demo' }}</span>
        <span class="foot-sep">·</span>
        <span>本站问答基于个人知识库生成，可能有误</span>
      </div>
    </footer>

    <!-- ============ 问答浮窗 ============ -->
    <ChatWidget v-model:open="chatOpen" :quota="site.quota" />

    <!-- ============ 照片大图 ============ -->
    <el-dialog v-model="photoOpen" :title="viewingPhoto?.caption || '照片'"
               width="min(92vw, 1000px)" align-center destroy-on-close>
      <img v-if="viewingPhoto" :src="viewingPhoto.url" class="viewer-img"
           :alt="viewingPhoto.caption || ''" />
    </el-dialog>

    <!-- ============ 下载确认 ============ -->
    <!--
      为什么要这一步：OSS 返回的响应头里已经带了 Content-Disposition: attachment，
      点链接会直接落盘，浏览器不给任何提示。访客可能只是手滑点了一下，
      硬盘里就多一个自己都不记得下过的 PDF。
      代价是必须用 @click.prevent 拦掉默认跳转 —— 否则点下去就直接下载了，
      弹窗还没渲染出来文件已经到手了。
    -->
    <el-dialog v-model="downloadOpen" title="确认下载" width="min(92vw, 420px)"
               align-center destroy-on-close>
      <div v-if="pendingDownload" class="dl-sheet">
        <div class="dl-icon"><el-icon :size="20"><Document /></el-icon></div>
        <div class="dl-meta">
          <div class="dl-name">{{ pendingDownload.title }}</div>
          <div class="dl-sub">
            <span>{{ (pendingDownload.fileType || 'file').toUpperCase() }}</span>
            <span v-if="pendingDownload.fileSize"> · {{ formatSize(pendingDownload.fileSize) }}</span>
          </div>
        </div>
      </div>
      <p class="dl-note">将开始下载这份文件，下载后可在浏览器的下载列表里找到。</p>
      <template #footer>
        <el-button @click="downloadOpen = false">取消</el-button>
        <el-button type="primary" :loading="downloading" @click="confirmDownload">
          下载
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Box, ChatDotRound, Document } from '@element-plus/icons-vue'
import { siteApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import ChatWidget from '@/components/ChatWidget.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const chatOpen = ref(false)
const year = computed(() => new Date().getFullYear())

// 照片大图查看
const photoOpen = ref(false)
const viewingPhoto = ref(null)

// 下载确认
const downloadOpen = ref(false)
const pendingDownload = ref(null)
const downloading = ref(false)

const site = reactive({
  title: 'Demo',
  owner: {
    name: 'Demo',
    avatarText: 'H',
    avatarUrl: '',
    tagline: '',
    intro: '',
    about: [],
    tags: [],
    links: []
  },
  documentCount: 0,
  chunkCount: 0,
  visitCount: 0,
  recentDocuments: [],
  quota: { limit: 0, remaining: 0, admin: false },
  photos: [],
  projects: [],
  downloads: [],
  moments: []
})

onMounted(async () => {
  try {
    const data = await siteApi.info()
    if (data) Object.assign(site, data)
  } catch (e) {
    // 拉不到站点信息也不该白屏，用默认值渲染
    console.warn('加载站点信息失败', e.message)
  }

  /**
   * 路由守卫把「已登录但不是站主」的人踢回主页时，会在 query 里带 deny=admin。
   *
   * <p>⚠️ 这个提示必须能显示出来：首页曾经没有登录/注册入口是刻意的，
   * 但不能因此变成「点了后台入口 → 莫名其妙回到首页 → 没有任何解释」。
   * 普通用户点了后台链接，得知道是权限不够而不是页面坏了。</p>
   *
   * <p>用 replace 而不是 push 清 query，避免这条提示在历史记录里留一格。</p>
   */
  if (route.query.deny === 'admin') {
    ElMessage.warning('后台只有站主能进，你的账号是普通用户')
    router.replace({ name: 'home' })
  }
})

function openChat() {
  chatOpen.value = true
}

function viewPhoto(p) {
  viewingPhoto.value = p
  photoOpen.value = true
}

/**
 * 点下载链接时先弹确认，不直接下载。
 *
 * ⚠️ 模板上必须写 @click.prevent，否则 <a href> 的默认行为会先触发，
 * 弹窗还没渲染文件就已经开始下载了，这个函数等于白写。
 */
function askDownload(d) {
  pendingDownload.value = d
  downloadOpen.value = true
}

/**
 * 确认后真正开始下载。
 *
 * 用临时 <a download> 而不是 window.open：download 属性让浏览器
 * 走「保存文件」而不是「尝试用内置 PDF 阅读器打开」。
 * 跨域时 download 属性会被忽略，但 OSS 已经返回了 attachment 头，
 * 两种情况都会落盘，不影响结果。
 */
function confirmDownload() {
  const d = pendingDownload.value
  if (!d) return
  downloading.value = true
  try {
    const a = document.createElement('a')
    a.href = d.url
    a.download = d.title || 'download'
    a.target = '_blank'
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    // 计数是纯统计，失败不该让用户看到红字
    siteApi.hitDownload(d.id).catch(() => {})
  } finally {
    downloading.value = false
    downloadOpen.value = false
  }
}

/** 字节数转可读大小。3538944 → "3.4 MB" */
function formatSize(bytes) {
  if (!bytes) return ''
  const mb = bytes / 1024 / 1024
  if (mb >= 1) return mb.toFixed(1) + ' MB'
  return Math.round(bytes / 1024) + ' KB'
}

async function onCommand(cmd) {
  if (cmd === 'logout') {
    await auth.logout()
    ElMessage.success('已退出登录')
    return
  }
  router.push({ name: cmd })
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

/* ==================== 导航 ==================== */
.nav {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid #f1f5f9;
}
.nav-inner {
  max-width: 880px;
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
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 12px;
  border: none;
  border-radius: 8px;
  background: none;
  font-size: 14px;
  font-family: inherit;
  color: #64748b;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.15s;
}
.nav-link:hover {
  background: #f8fafc;
  color: #0f172a;
}
.nav-ask {
  color: #2563eb;
}
.nav-ask:hover {
  background: #eff6ff;
  color: #1d4ed8;
}
/*
  游客右上角的「登录」。
  做成描边小按钮而不是深色实心按钮 —— 它是入口不是号召，别抢首屏的视觉重心。
*/
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
  color: #0f172a;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 8px;
  font-size: 14px;
  color: #475569;
  cursor: pointer;
  outline: none;
}
.user-chip:hover {
  background: #f8fafc;
}
.user-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
}

/* ==================== 首屏 ==================== */
.hero {
  padding: 88px 24px 72px;
  text-align: center;
}
.hero-inner {
  max-width: 640px;
  margin: 0 auto;
}
.avatar {
  width: 76px;
  height: 76px;
  margin: 0 auto 26px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  color: #fff;
  font-size: 30px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 24px rgba(99, 102, 241, 0.28);
  overflow: hidden;
}
.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.hero-title {
  margin: 0 0 12px;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: -0.5px;
  line-height: 1.35;
}
.hero-name {
  background: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.hero-tagline {
  margin: 0 0 24px;
  font-size: 16px;
  color: #64748b;
  line-height: 1.7;
}
.hero-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-bottom: 30px;
}
.tag {
  padding: 5px 13px;
  border-radius: 999px;
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  font-size: 13px;
  color: #64748b;
}
.hero-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

/* ==================== 按钮 ==================== */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 11px 22px;
  border-radius: 10px;
  border: none;
  font-family: inherit;
  font-size: 14.5px;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.18s;
}
.btn-primary {
  background: #2563eb;
  color: #fff;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.25);
}
.btn-primary:hover {
  background: #1d4ed8;
  box-shadow: 0 6px 20px rgba(37, 99, 235, 0.32);
  transform: translateY(-1px);
}
.btn-ghost {
  background: #fff;
  color: #475569;
  border: 1px solid #e2e8f0;
}
.btn-ghost:hover {
  border-color: #cbd5e1;
  background: #f8fafc;
  color: #0f172a;
}
.btn-center {
  margin: 32px auto 0;
  display: flex;
}

/* ==================== 内容段 ==================== */
.section {
  padding: 72px 24px;
}
.section-alt {
  background: #fafbfc;
  border-top: 1px solid #f1f5f9;
  border-bottom: 1px solid #f1f5f9;
}
.section-inner {
  max-width: 720px;
  margin: 0 auto;
}
.section-title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.3px;
}
.section-sub {
  margin: 0 0 32px;
  font-size: 14.5px;
  color: #64748b;
  line-height: 1.75;
}
.about-text {
  font-size: 15px;
  line-height: 1.9;
  color: #334155;
}
.about-text p {
  margin: 0 0 16px;
}
.muted {
  color: #94a3b8;
}
.links {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 24px;
}
.link-item {
  padding: 8px 15px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  font-size: 13.5px;
  color: #475569;
  text-decoration: none;
  transition: all 0.15s;
}
.link-item:hover {
  border-color: #93c5fd;
  background: #f0f7ff;
  color: #2563eb;
}

/* ==================== 统计 ==================== */
.stats {
  display: flex;
  gap: 44px;
  margin-bottom: 36px;
}
.stat-num {
  font-size: 30px;
  font-weight: 600;
  color: #0f172a;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.stat-label {
  margin-top: 4px;
  font-size: 13px;
  color: #94a3b8;
}

/* ==================== 最近收录 ==================== */
.recent {
  margin-bottom: 8px;
}
.recent-label {
  font-size: 12.5px;
  color: #94a3b8;
  margin-bottom: 12px;
  letter-spacing: 0.3px;
}
.recent-list {
  border: 1px solid #eef0f4;
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.recent-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid #f6f8fa;
  font-size: 14px;
}
.recent-item:last-child {
  border-bottom: none;
}
.recent-type {
  padding: 2px 7px;
  border-radius: 5px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
  font-weight: 500;
  flex-shrink: 0;
}
.recent-name {
  flex: 1;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-chunks {
  color: #94a3b8;
  font-size: 12.5px;
  flex-shrink: 0;
}

/* ==================== 项目 ==================== */
/* 项目/照片区比文字区宽，双列才不挤。文字段落仍保持 720px 好读的行宽 */
.section-inner--wide {
  max-width: 920px;
}
#projects .section-inner,
#photos .section-inner {
  max-width: 920px;
}
.proj-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 20px;
}
.proj-card {
  display: flex;
  flex-direction: column;
  border: 1px solid #eef1f5;
  border-radius: 14px;
  overflow: hidden;
  background: #fff;
  transition: all 0.18s;
}
.proj-card:hover {
  border-color: #dbeafe;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.07);
  transform: translateY(-2px);
}
.proj-cover {
  aspect-ratio: 16 / 10;
  background: #f8fafc;
  overflow: hidden;
}
.proj-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.proj-cover--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #cbd5e1;
  font-size: 28px;
}
.proj-body {
  padding: 16px 18px 18px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  flex: 1;
}
.proj-title {
  margin: 0;
  font-size: 15.5px;
  font-weight: 600;
  color: #0f172a;
}
.proj-desc {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: #64748b;
}
.proj-tech {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: auto;
  padding-top: 4px;
}
.proj-tag {
  padding: 2px 8px;
  border-radius: 5px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 11.5px;
  font-weight: 500;
}
.proj-links {
  display: flex;
  gap: 14px;
  padding-top: 6px;
  border-top: 1px solid #f8fafc;
}
.proj-link {
  font-size: 12.5px;
  color: #64748b;
  text-decoration: none;
  transition: color 0.15s;
}
.proj-link:hover {
  color: #2563eb;
}

/* ==================== 照片墙 ==================== */
/*
 * 用 CSS columns 做瀑布流而不是 grid：
 * 照片长宽比差异大，grid 会留下大片空白；columns 让每列自动填满。
 */
.photo-grid {
  columns: 3;
  column-gap: 14px;
}
.photo-item {
  break-inside: avoid;
  margin: 0 0 14px;
  border-radius: 12px;
  overflow: hidden;
  cursor: zoom-in;
  background: #f8fafc;
  position: relative;
  transition: transform 0.2s;
}
.photo-item:hover {
  transform: translateY(-2px);
}
.photo-item img {
  width: 100%;
  display: block;
  transition: transform 0.3s;
}
.photo-item:hover img {
  transform: scale(1.03);
}
.photo-cap {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 18px 12px 10px;
  font-size: 12px;
  color: #fff;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.55), transparent);
  /* 长说明只留一行，多了破坏瀑布流节奏 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.viewer-img {
  width: 100%;
  height: auto;
  display: block;
  border-radius: 8px;
}

/* ==================== 日常动态 ==================== */
.moment-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.home-moment {
  padding: 18px 20px;
  border: 1px solid #eef1f5;
  border-radius: 12px;
  background: #fff;
}
.home-moment-text {
  margin: 0 0 12px;
  font-size: 14.5px;
  line-height: 1.85;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}
.home-moment-img {
  max-width: 100%;
  max-height: 380px;
  border-radius: 9px;
  display: block;
  margin-bottom: 10px;
  cursor: zoom-in;
}
.home-moment-video {
  max-width: 100%;
  max-height: 380px;
  border-radius: 9px;
  display: block;
  margin-bottom: 10px;
  background: #000;
}
.home-moment-time {
  font-size: 12.5px;
  color: #a8b3c4;
  font-variant-numeric: tabular-nums;
}

/* ==================== 页脚 ==================== */
.foot {
  margin-top: auto;
  padding: 32px 24px;
  text-align: center;
  font-size: 12.5px;
  color: #a8b3c4;
  border-top: 1px solid #f8fafc;
}
.foot-sep {
  margin: 0 8px;
}
.foot-downloads {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid #f8fafc;
}
.foot-dl-label {
  font-size: 11.5px;
  color: #cbd5e1;
  letter-spacing: 0.5px;
}
.foot-dl {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 12.5px;
  color: #475569;
  text-decoration: none;
  transition: all 0.15s;
}
.foot-dl:hover {
  border-color: #93c5fd;
  background: #f0f7ff;
  color: #2563eb;
}
.foot-dl-size {
  color: #cbd5e1;
  font-size: 11px;
}
.foot-line {
  display: block;
}

/* ============ 下载确认弹窗 ============ */
.dl-sheet {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  background: #fafbfc;
}
.dl-icon {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 9px;
  background: #eff6ff;
  color: #2563eb;
}
.dl-meta {
  min-width: 0;
}
.dl-name {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dl-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #94a3b8;
}
.dl-note {
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: #64748b;
}

@media (max-width: 640px) {
  .hero {
    padding: 56px 20px 48px;
  }
  .hero-title {
    font-size: 26px;
  }
  .nav-links {
    display: none;
  }
  .section {
    padding: 48px 20px;
  }
  .stats {
    gap: 28px;
  }
  .photo-grid {
    columns: 2;
    column-gap: 10px;
  }
  .photo-item {
    margin-bottom: 10px;
  }
  .proj-grid {
    grid-template-columns: 1fr;
  }
}
</style>
