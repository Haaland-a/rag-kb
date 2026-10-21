<template>
  <div class="detail" v-loading="loading">
    <!-- 头部 -->
    <div class="head">
      <el-button :icon="ArrowLeft" text @click="$router.back()">返回</el-button>
      <div class="head-main">
        <span class="title">{{ doc?.filename || '加载中…' }}</span>
        <div class="badges">
          <el-tag v-if="doc" :type="statusType(doc.status)" size="small">
            {{ statusText(doc.status) }}
          </el-tag>
          <el-tag v-if="doc" size="small" effect="plain">{{ doc.fileType.toUpperCase() }}</el-tag>
          <span class="meta">
            {{ doc?.pageCount ?? '-' }} 页 · {{ doc?.chunkCount ?? 0 }} 块
          </span>
        </div>
      </div>
    </div>

    <el-alert
      v-if="doc?.errorMsg"
      type="error"
      :title="doc.errorMsg"
      :closable="false"
      show-icon
      class="err-alert"
    />

    <!-- chunk 表格：切块边界肉眼可见，调 chunk 参数就靠这个 -->
    <el-table :data="chunks" border stripe size="small">
      <el-table-column prop="chunkIndex" label="#" width="60" align="center" />
      <el-table-column label="页码" width="70" align="center">
        <template #default="{ row }">{{ row.pageNumber ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="标题路径" width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.headingPath" class="heading">{{ row.headingPath }}</span>
          <span v-else class="empty-cell">—</span>
        </template>
      </el-table-column>
      <el-table-column label="字数" width="76" align="right">
        <template #default="{ row }">{{ row.content?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="token(估)" width="90" align="right">
        <template #default="{ row }">{{ row.tokenCount }}</template>
      </el-table-column>
      <el-table-column label="内容" min-width="440">
        <template #default="{ row }">
          <div class="chunk-content">{{ row.content }}</div>
        </template>
      </el-table-column>
      <el-table-column label="向量" width="110" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="row.vectorId" :content="row.vectorId" placement="top">
            <span class="vid">{{ row.vectorId.slice(0, 8) }}</span>
          </el-tooltip>
          <el-tag v-else size="small" type="danger" effect="plain">缺失</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="loadChunks"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { documentApi } from '@/api'

const route = useRoute()
const id = Number(route.params.id)

const doc = ref(null)
const chunks = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

onMounted(async () => {
  loading.value = true
  try {
    const res = await documentApi.detail(id)
    doc.value = res ?? null
    if (!doc.value) {
      ElMessage.error('文档不存在')
      return
    }
    await loadChunks()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
})

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

async function loadChunks() {
  try {
    const res = await documentApi.chunks(id, page.value, size.value)
    // 后端返回 {items,total,page,size}
    chunks.value = res?.items || []
    total.value = res?.total || 0
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 改每页条数时回到第一页，否则会出现「第 5 页但只有 2 页」的空白 */
function onSizeChange() {
  page.value = 1
  loadChunks()
}
</script>

<style scoped>
.detail {
  background: #fff;
  border-radius: 12px;
  padding: 20px 24px 26px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
}
.head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;
}
.head-main {
  flex: 1;
  min-width: 0;
}
.title {
  display: block;
  font-size: 16px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 6px;
  word-break: break-all;
}
.badges {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.meta {
  font-size: 12px;
  color: #94a3b8;
}
.err-alert {
  margin-bottom: 14px;
}
.heading {
  font-size: 12px;
  color: #475569;
}
.empty-cell {
  color: #cbd5e1;
}
.chunk-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.65;
  max-height: 180px;
  overflow-y: auto;
  color: #334155;
}
.vid {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: #94a3b8;
  cursor: help;
}
.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
