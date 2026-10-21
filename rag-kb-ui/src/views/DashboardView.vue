<template>
  <div class="dash">
    <!-- 欢迎 -->
    <div class="welcome">
      <div>
        <h2 class="welcome-title">你好，{{ auth.displayName }}</h2>
        <p class="welcome-sub">这里是你的后台。上传的资料会立刻出现在主页的知识库里。</p>
      </div>
      <el-button type="primary" :icon="Upload" @click="uploadVisible = true">
        上传资料
      </el-button>
    </div>

    <!-- 数据概览 -->
    <div class="cards">
      <router-link to="/admin/documents" class="card">
        <div class="card-icon card-icon-blue"><el-icon><Document /></el-icon></div>
        <div class="card-body">
          <div class="card-num">{{ stats.documentCount }}</div>
          <div class="card-label">篇资料</div>
        </div>
      </router-link>

      <div class="card">
        <div class="card-icon card-icon-green"><el-icon><Files /></el-icon></div>
        <div class="card-body">
          <div class="card-num">{{ stats.chunkCount }}</div>
          <div class="card-label">个知识片段</div>
        </div>
      </div>

      <router-link to="/admin/visits" class="card">
        <div class="card-icon card-icon-purple"><el-icon><View /></el-icon></div>
        <div class="card-body">
          <div class="card-num">{{ stats.visitCount }}</div>
          <div class="card-label">次访问</div>
        </div>
      </router-link>

      <div class="card">
        <div class="card-icon card-icon-orange"><el-icon><ChatDotRound /></el-icon></div>
        <div class="card-body">
          <div class="card-num">{{ stats.questionCount }}</div>
          <div class="card-label">次提问</div>
        </div>
      </div>
    </div>

    <!-- 最近上传 -->
    <div class="panel">
      <div class="panel-head">
        <h3 class="panel-title">最近的资料</h3>
        <router-link to="/admin/documents" class="panel-more">全部 →</router-link>
      </div>

      <div v-if="docs.length" class="doc-list">
        <div v-for="doc in docs" :key="doc.id" class="doc-item">
          <span class="doc-type">{{ (doc.fileType || '').toUpperCase() }}</span>
          <div class="doc-main">
            <div class="doc-name">{{ doc.filename }}</div>
            <div class="doc-meta">
              {{ formatSize(doc.fileSize) }} · {{ doc.chunkCount }} 块 · {{ formatTime(doc.createdAt) }}
            </div>
          </div>
          <el-tag :type="statusType(doc.status)" size="small">{{ statusText(doc.status) }}</el-tag>
          <el-button
            size="small"
            link
            type="primary"
            @click="$router.push({ name: 'document-detail', params: { id: doc.id } })"
          >
            详情
          </el-button>
        </div>
      </div>

      <el-empty v-else description="还没有资料，点右上角上传" :image-size="70" />
    </div>

    <!-- 快捷入口 -->
    <div class="panel">
      <div class="panel-head">
        <h3 class="panel-title">快捷操作</h3>
      </div>
      <div class="quick">
        <button class="quick-item" type="button" @click="uploadVisible = true">
          <el-icon><Upload /></el-icon>
          <span>上传新资料</span>
        </button>
        <router-link to="/admin/documents" class="quick-item">
          <el-icon><Document /></el-icon>
          <span>管理文档</span>
        </router-link>
        <router-link to="/admin/visits" class="quick-item">
          <el-icon><Location /></el-icon>
          <span>查看访客</span>
        </router-link>
        <router-link to="/" class="quick-item">
          <el-icon><HomeFilled /></el-icon>
          <span>回主页</span>
        </router-link>
      </div>
    </div>

    <!-- 上传弹窗 -->
    <el-dialog v-model="uploadVisible" title="上传资料" width="520px" @closed="onDialogClosed">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="false"
        accept=".pdf,.md,.txt"
        :on-change="onFileChange"
        :disabled="uploading"
      >
        <div class="up-inner">
          <el-icon :size="38" class="up-icon"><UploadFilled /></el-icon>
          <div class="up-text">拖拽 PDF / MD / TXT 到这里，或点击选择</div>
          <div class="up-sub">单文件上限 50MB，上传后后台自动切块向量化</div>
        </div>
      </el-upload>

      <el-progress
        v-if="uploading"
        :percentage="uploadProgress"
        :stroke-width="6"
        class="up-progress"
      />

      <div v-if="lastUploaded" class="up-result">
        已提交：<b>{{ lastUploaded.filename }}</b>
        <span class="up-hint">
          {{ lastUploaded.status === 'READY' ? '（已存在，直接复用）' : '（后台索引中，稍候刷新）' }}
        </span>
      </div>

      <template #footer>
        <el-button @click="uploadVisible = false">关闭</el-button>
        <el-button type="primary" @click="$router.push('/admin/documents')">
          去文档管理
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Document,
  Files,
  HomeFilled,
  Location,
  Upload,
  UploadFilled,
  View
} from '@element-plus/icons-vue'
import { documentApi, siteApi, visitApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const stats = reactive({
  documentCount: 0,
  chunkCount: 0,
  visitCount: 0,
  questionCount: 0
})

const docs = ref([])

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const lastUploaded = ref(null)

onMounted(load)

async function load() {
  // 站点信息（文档数/片段数/访问数）
  try {
    const info = await siteApi.info()
    if (info) {
      stats.documentCount = info.documentCount ?? 0
      stats.chunkCount = info.chunkCount ?? 0
      stats.visitCount = info.visitCount ?? 0
    }
  } catch {
    /* 统计失败不影响上传 */
  }

  // 提问数
  try {
    const s = await visitApi.stats()
    stats.questionCount = s?.questionCount ?? s?.withQuestion ?? 0
  } catch {
    /* 忽略 */
  }

  // 最近文档
  try {
    const list = await documentApi.list()
    docs.value = (list || []).slice(0, 5)
  } catch {
    /* 忽略 */
  }
}

function onFileChange(file) {
  const raw = file.raw
  if (!raw) return

  if (!/\.(pdf|md|markdown|txt)$/i.test(raw.name)) {
    ElMessage.error('只支持 PDF / MD / TXT')
    return
  }
  if (raw.size > 50 * 1024 * 1024) {
    ElMessage.error('文件不能超过 50MB')
    return
  }
  doUpload(raw)
}

async function doUpload(raw) {
  uploading.value = true
  uploadProgress.value = 0
  lastUploaded.value = null
  try {
    const doc = await documentApi.upload(formDataOf(raw), (p) => {
      uploadProgress.value = p
    })
    lastUploaded.value = doc
    ElMessage.success(
      doc?.status === 'READY' ? '该文件已存在，直接复用' : '已加入索引队列'
    )
    await load()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

function formDataOf(file) {
  const fd = new FormData()
  fd.append('file', file)
  return fd
}

function onDialogClosed() {
  uploadProgress.value = 0
  lastUploaded.value = null
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

function formatTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function statusText(status) {
  return {
    PENDING: '排队中',
    INDEXING: '索引中',
    READY: '已就绪',
    FAILED: '失败'
  }[status] || status || '-'
}

function statusType(status) {
  return {
    PENDING: 'info',
    INDEXING: 'warning',
    READY: 'success',
    FAILED: 'danger'
  }[status] || 'info'
}
</script>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ==================== 欢迎 ==================== */
.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px;
  background: linear-gradient(135deg, #1e293b 0%, #334155 100%);
  border-radius: 14px;
  color: #fff;
}
.welcome-title {
  margin: 0 0 6px;
  font-size: 19px;
  font-weight: 600;
}
.welcome-sub {
  margin: 0;
  font-size: 13.5px;
  color: #cbd5e1;
}
.welcome :deep(.el-button) {
  background: #fff;
  border-color: #fff;
  color: #1e293b;
  font-weight: 500;
}
.welcome :deep(.el-button:hover) {
  background: #f1f5f9;
  border-color: #f1f5f9;
}

/* ==================== 数据卡 ==================== */
.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.card {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 18px;
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 12px;
  text-decoration: none;
  color: inherit;
  transition: all 0.16s;
}
.card:hover {
  border-color: #dbe2ee;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.06);
  transform: translateY(-1px);
}
.card-icon {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  flex-shrink: 0;
}
.card-icon-blue {
  background: #eff6ff;
  color: #2563eb;
}
.card-icon-green {
  background: #f0fdf4;
  color: #16a34a;
}
.card-icon-purple {
  background: #faf5ff;
  color: #9333ea;
}
.card-icon-orange {
  background: #fff7ed;
  color: #ea580c;
}
.card-num {
  font-size: 22px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.card-label {
  margin-top: 2px;
  font-size: 12.5px;
  color: #94a3b8;
}

/* ==================== 面板 ==================== */
.panel {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 12px;
  padding: 18px 20px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}
.panel-more {
  font-size: 13px;
  color: #2563eb;
  text-decoration: none;
}
.panel-more:hover {
  text-decoration: underline;
}

/* ==================== 文档列表 ==================== */
.doc-list {
  display: flex;
  flex-direction: column;
}
.doc-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid #f6f8fa;
}
.doc-item:last-child {
  border-bottom: none;
}
.doc-type {
  padding: 2px 7px;
  border-radius: 5px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
  font-weight: 500;
  flex-shrink: 0;
}
.doc-main {
  flex: 1;
  min-width: 0;
}
.doc-name {
  font-size: 14px;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-meta {
  margin-top: 3px;
  font-size: 12px;
  color: #94a3b8;
}

/* ==================== 快捷操作 ==================== */
.quick {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.quick-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px 10px;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  background: #fafbfc;
  color: #475569;
  font-size: 13px;
  font-family: inherit;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.15s;
}
.quick-item:hover {
  border-color: #93c5fd;
  background: #f0f7ff;
  color: #2563eb;
}
.quick-item :deep(.el-icon) {
  font-size: 19px;
}

/* ==================== 上传 ==================== */
.up-inner {
  padding: 16px 8px;
}
.up-icon {
  color: #94a3b8;
}
.up-text {
  margin-top: 10px;
  font-size: 14px;
  color: #475569;
}
.up-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
}
.up-progress {
  margin-top: 14px;
}
.up-result {
  margin-top: 12px;
  padding: 10px 13px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  font-size: 13px;
  color: #15803d;
}
.up-hint {
  color: #65a30d;
  font-size: 12px;
}

@media (max-width: 900px) {
  .cards {
    grid-template-columns: repeat(2, 1fr);
  }
  .quick {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
