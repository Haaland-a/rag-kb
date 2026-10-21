<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>问答记录</h1>
        <p class="sub">
          别人在你站上问了什么、你怎么答的。默认<strong>只看访客</strong>的提问 ——
          你自己调试时问的那些不会混进来。点开一条可以看回答全文和引用来源。
        </p>
      </div>
      <div class="head-actions">
        <el-radio-group v-model="onlyVisitor" size="small" @change="load(true)">
          <el-radio-button :value="true">只看访客</el-radio-button>
          <el-radio-button :value="false">全部</el-radio-button>
        </el-radio-group>
        <el-button size="small" :loading="loading" @click="load(true)">刷新</el-button>
        <el-button
          v-if="total > 0"
          size="small"
          type="danger"
          plain
          @click="clearAll"
        >
          清空
        </el-button>
      </div>
    </header>

    <div class="stat-bar">
      <span>共 <strong>{{ total }}</strong> 条</span>
      <span class="divider">·</span>
      <span>{{ onlyVisitor ? '访客提问' : '含站主调试' }}</span>
    </div>

    <div v-loading="loading" class="list">
      <el-empty
        v-if="!loading && !items.length"
        :description="onlyVisitor ? '还没有访客提问过' : '还没有问答记录'"
      />

      <article
        v-for="c in items"
        :key="c.id"
        class="chat-item"
        :class="{ incomplete: !c.complete }"
      >
        <div class="chat-head">
          <span class="q-mark">问</span>
          <span class="question">{{ c.question }}</span>
          <el-tag v-if="!c.complete" size="small" type="warning" effect="plain">
            未答完
          </el-tag>
          <el-tag v-if="c.admin" size="small" type="info" effect="plain">站主</el-tag>
        </div>

        <p v-if="c.answerPreview" class="answer-preview">{{ c.answerPreview }}</p>
        <p v-else class="answer-preview empty">（没有生成回答）</p>

        <div class="chat-meta">
          <span class="time">{{ c.createdAt }}</span>
          <span class="dot">·</span>
          <span class="ip">{{ c.ip }}</span>
          <template v-if="c.location">
            <span class="dot">·</span>
            <span class="loc">{{ c.location }}</span>
          </template>
          <span class="dot">·</span>
          <span>{{ c.sourceCount }} 条引用</span>
          <template v-if="c.answerChars">
            <span class="dot">·</span>
            <span>{{ c.answerChars }} 字</span>
          </template>
          <template v-if="c.elapsedMs">
            <span class="dot">·</span>
            <span>{{ c.elapsedMs }} ms</span>
          </template>

          <span class="spacer" />
          <el-button text size="small" @click="openDetail(c)">详情</el-button>
          <el-button text size="small" @click="removeOne(c)">删除</el-button>
        </div>
      </article>

      <div v-if="items.length < total" class="more">
        <el-button :loading="loadingMore" @click="load(false)">加载更多</el-button>
      </div>
    </div>

    <!-- 详情 -->
    <el-dialog
      v-model="detailOpen"
      title="问答详情"
      width="min(94vw, 760px)"
      align-center
      destroy-on-close
    >
      <div v-if="detail" class="detail">
        <div class="detail-block">
          <div class="detail-label">问题</div>
          <p class="detail-question">{{ detail.question }}</p>
        </div>

        <div class="detail-block">
          <div class="detail-label">回答</div>
          <p v-if="detail.answer" class="detail-answer">{{ detail.answer }}</p>
          <p v-else class="detail-answer empty">（没有生成回答）</p>
        </div>

        <div v-if="detail.sources?.length" class="detail-block">
          <div class="detail-label">引用来源</div>
          <ol class="src-list">
            <li v-for="(s, i) in detail.sources" :key="i" class="src-item">
              <div class="src-head">
                <span class="src-idx">[{{ i + 1 }}]</span>
                <span class="src-file">{{ s.filename }}</span>
                <span v-if="s.pageNumber" class="src-page">第 {{ s.pageNumber }} 页</span>
                <span v-if="s.score != null" class="src-score">
                  {{ s.score.toFixed(3) }}
                </span>
              </div>
              <p v-if="s.headingPath" class="src-path">{{ s.headingPath }}</p>
              <p v-if="s.preview" class="src-preview">{{ s.preview }}</p>
            </li>
          </ol>
        </div>

        <div class="detail-foot">
          <span>{{ detail.createdAt }}</span>
          <span class="dot">·</span>
          <span>{{ detail.ip }}</span>
          <template v-if="detail.location">
            <span class="dot">·</span>
            <span>{{ detail.location }}</span>
          </template>
          <template v-if="detail.elapsedMs">
            <span class="dot">·</span>
            <span>耗时 {{ detail.elapsedMs }} ms</span>
          </template>
          <span v-if="!detail.complete" class="dot">·</span>
          <span v-if="!detail.complete" class="warn">访客中途离开，回答未生成完</span>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatApi } from '@/api'

const PAGE = 20

const items = ref([])
const total = ref(0)
const offset = ref(0)
const loading = ref(false)
const loadingMore = ref(false)
const onlyVisitor = ref(true)

const detailOpen = ref(false)
const detail = ref(null)

onMounted(() => load(true))

async function load(reset) {
  if (reset) {
    loading.value = true
  } else {
    loadingMore.value = true
  }
  try {
    if (reset) offset.value = 0
    const page = await chatApi.logs(PAGE, offset.value, onlyVisitor.value)
    const list = page?.items || []
    items.value = reset ? list : [...items.value, ...list]
    total.value = page?.total || 0
    offset.value = items.value.length
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function openDetail(c) {
  detail.value = null
  detailOpen.value = true
  try {
    detail.value = await chatApi.logDetail(c.id)
  } catch (e) {
    ElMessage.error(e.message || '加载详情失败')
    detailOpen.value = false
  }
}

async function removeOne(c) {
  try {
    await ElMessageBox.confirm(
      '删除后这条问答记录就找不回来了，确定吗？',
      '删除记录',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await chatApi.removeLog(c.id)
    items.value = items.value.filter((x) => x.id !== c.id)
    total.value = Math.max(0, total.value - 1)
    offset.value = items.value.length
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function clearAll() {
  try {
    await ElMessageBox.confirm(
      `确定清空全部 ${total.value} 条问答记录吗？这个操作不可撤销。`,
      '清空问答记录',
      { type: 'warning', confirmButtonText: '全部清空', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await chatApi.clearLogs()
    ElMessage.success(`已清空 ${res?.deleted ?? 0} 条`)
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.page-head h1 {
  margin: 0;
  font-size: 20px;
  color: #1e293b;
}
.sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #94a3b8;
  line-height: 1.7;
  max-width: 640px;
}
.sub strong {
  color: #b45309;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.stat-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #64748b;
}
.stat-bar strong {
  color: #1e293b;
}
.divider {
  color: #cbd5e1;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}

.chat-item {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
}
.chat-item.incomplete {
  border-left: 3px solid #f59e0b;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.q-mark {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 11px;
  font-weight: 700;
}
.question {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.6;
  word-break: break-word;
}

.answer-preview {
  margin: 8px 0 0 28px;
  font-size: 13px;
  line-height: 1.75;
  color: #64748b;
  word-break: break-word;
}
.answer-preview.empty {
  color: #cbd5e1;
  font-style: italic;
}

.chat-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin: 10px 0 0 28px;
  font-size: 11.5px;
  color: #94a3b8;
}
.chat-meta .dot {
  color: #d5dbe5;
}
.chat-meta .ip,
.chat-meta .loc {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.chat-meta .spacer {
  flex: 1;
}

.more {
  display: flex;
  justify-content: center;
  padding: 6px 0 10px;
}

/* ---------- 详情 ---------- */
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.detail-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.detail-label {
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
  letter-spacing: 0.5px;
}
.detail-question {
  margin: 0;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.75;
  color: #1e293b;
  word-break: break-word;
}
.detail-answer {
  margin: 0;
  padding: 12px;
  background: #fafbfc;
  border: 1px solid #eef0f4;
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.85;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 380px;
  overflow-y: auto;
}
.detail-answer.empty {
  color: #cbd5e1;
  font-style: italic;
}

.src-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.src-item {
  padding: 10px 12px;
  background: #fafbfc;
  border: 1px solid #eef0f4;
  border-radius: 8px;
}
.src-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12.5px;
}
.src-idx {
  color: #2563eb;
  font-weight: 600;
}
.src-file {
  color: #334155;
  font-weight: 500;
}
.src-page,
.src-score {
  color: #94a3b8;
  font-size: 11.5px;
}
.src-path {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: #94a3b8;
}
.src-preview {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: #64748b;
  word-break: break-word;
}

.detail-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 11.5px;
  color: #94a3b8;
  padding-top: 12px;
  border-top: 1px solid #f1f5f9;
}
.detail-foot .dot {
  color: #d5dbe5;
}
.detail-foot .warn {
  color: #b45309;
}
</style>
