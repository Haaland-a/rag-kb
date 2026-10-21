<template>
  <div class="documents">
    <div class="page-head">
      <div>
        <h3 class="page-title">文档管理</h3>
        <p class="page-sub">
          上传的资料会切块、向量化后进入知识库，访客就能基于这些内容提问
        </p>
      </div>
      <el-button :loading="loading" :icon="Refresh" @click="reload">刷新</el-button>
    </div>

    <!-- 上传区 -->
    <el-upload
      drag
      class="uploader"
      :auto-upload="false"
      :show-file-list="false"
      accept=".pdf,.md,.txt"
      :on-change="onFileChange"
      :disabled="uploading"
    >
      <div class="upload-inner">
        <el-icon :size="42" class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">拖拽 PDF / MD / TXT 到这里，或点击选择</div>
        <div class="upload-sub">单文件上限 50MB · 上传后异步入库，状态会自动刷新</div>
      </div>
    </el-upload>

    <el-progress
      v-if="uploading"
      :percentage="uploadProgress"
      :stroke-width="6"
      class="upload-progress"
    />

    <!-- 文档表 -->
    <el-table :data="docs" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="68" />
      <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="filename">{{ row.filename }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="76">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.fileType.toUpperCase() }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="94">
        <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column label="页/块" width="94" align="center">
        <template #default="{ row }">
          {{ row.pageCount ?? '-' }} / {{ row.chunkCount ?? 0 }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.errorMsg"
            :content="row.errorMsg"
            placement="top"
          >
            <el-tag :type="statusType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </el-tooltip>
          <el-tag v-else :type="statusType(row.status)" size="small">
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" align="right">
        <template #default="{ row }">
          <el-button
            size="small"
            link
            type="primary"
            @click="$router.push({ name: 'document-detail', params: { id: row.id } })"
          >
            看切块
          </el-button>
          <el-button
            size="small"
            link
            type="warning"
            :disabled="row.status === 'INDEXING'"
            @click="onReindex(row)"
          >
            重建
          </el-button>
          <el-button size="small" link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !docs.length" description="还没有文档，先上传一个试试" />
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, UploadFilled } from '@element-plus/icons-vue'
import { documentApi } from '@/api'
import { useDocumentStore } from '@/stores/documents'

const store = useDocumentStore()
const docs = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)

/** 轮询定时器：有 PENDING/INDEXING 的文档时每 2.5 秒刷一次状态 */
let pollTimer = null

onMounted(reload)
onBeforeUnmount(stopPolling)

async function reload() {
  loading.value = true
  try {
    docs.value = await store.fetchAll()
    // 有文档在索引中就开轮询，都结束了就停 —— 别让定时器白跑
    if (docs.value.some((d) => d.status === 'PENDING' || d.status === 'INDEXING')) {
      startPolling()
    } else {
      stopPolling()
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      // 轮询不显示 loading 遮罩，否则整张表每 2.5 秒闪一次，眼都瞎了
      docs.value = await store.fetchAll()
      if (!docs.value.some((d) => d.status === 'PENDING' || d.status === 'INDEXING')) {
        stopPolling()
      }
    } catch {
      // 轮询失败静默，下次再试
    }
  }, 2500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function statusType(s) {
  return {
    READY: 'success',
    INDEXING: 'warning',
    PENDING: 'info',
    FAILED: 'danger'
  }[s] || 'info'
}

function statusText(s) {
  return {
    READY: '已完成',
    INDEXING: '索引中',
    PENDING: '排队中',
    FAILED: '失败'
  }[s] || s
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function formatTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function onFileChange(file) {
  const raw = file.raw
  if (!raw) return

  // 前端先挡一道：后端也会校验，但让人在上传前就知道比等报错好
  const lower = raw.name.toLowerCase()
  if (!/\.(pdf|md|markdown|txt)$/.test(lower)) {
    ElMessage.error('只支持 PDF / MD / TXT')
    return
  }
  if (raw.size > 50 * 1024 * 1024) {
    ElMessage.error('文件超过 50MB')
    return
  }

  uploading.value = true
  uploadProgress.value = 0
  try {
    const res = await store.upload(raw, (p) => {
      uploadProgress.value = p
    })
    const doc = res
    if (doc?.status === 'READY') {
      // 查重命中已有文档 —— 后端直接返回了那个已完成的文档
      ElMessage.success(`「${doc.filename}」已存在，直接复用`)
    } else {
      ElMessage.success(`已加入队列：${doc?.filename || ''}，正在后台索引`)
    }
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
    uploadProgress.value = 0
  }
}

async function onReindex(row) {
  try {
    await ElMessageBox.confirm(
      `重建会删除现有切块和向量，用原文件重新索引「${row.filename}」。继续？`,
      '重建索引',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await documentApi.reindex(row.id)
    ElMessage.success('已触发重建')
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.filename}」及其所有切块和向量？此操作不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    await store.remove(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.documents {
  background: #fff;
  border-radius: 12px;
  padding: 22px 24px 28px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}
.page-title {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 600;
  color: #1e293b;
}
.page-sub {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
}

.uploader {
  margin-bottom: 14px;
}
.uploader :deep(.el-upload-dragger) {
  padding: 26px 20px;
  border-radius: 10px;
  border-color: #dbe3ec;
  background: #fbfcfe;
}
.uploader :deep(.el-upload-dragger:hover) {
  border-color: #93c5fd;
  background: #f5faff;
}
.upload-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
}
.upload-icon {
  color: #94a3b8;
}
.upload-text {
  font-size: 14px;
  color: #475569;
}
.upload-sub {
  font-size: 12px;
  color: #a8b3c4;
}
.upload-progress {
  margin-bottom: 14px;
}

.filename {
  font-weight: 500;
  color: #1e293b;
}
</style>
