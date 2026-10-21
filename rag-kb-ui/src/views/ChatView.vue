<template>
  <div class="chat-page">
    <!-- 左：文档筛选 -->
    <aside class="doc-panel">
      <div class="panel-title">知识范围</div>
      <el-checkbox-group v-model="selectedDocIds" @change="onFilterChange">
        <div v-for="d in docs" :key="d.id" class="doc-item">
          <el-checkbox :value="d.id">{{ d.filename }}</el-checkbox>
          <el-tag size="small" :type="statusType(d.status)">{{ d.status }}</el-tag>
        </div>
      </el-checkbox-group>
      <el-empty v-if="!docs.length" description="还没有文档，去上传" :image-size="60" />
    </aside>

    <!-- 右：对话流 -->
    <section class="chat-panel">
      <div ref="msgBox" class="messages">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="bubble">
            <span v-if="m.role === 'assistant'">{{ m.text }}<span v-if="m.streaming" class="cursor">▍</span></span>
            <span v-else>{{ m.text }}</span>
            <div v-if="m.sources && m.sources.length" class="sources">
              <div v-for="s in m.sources" :key="s.chunkId" class="source-chip" @click="showSource(s)">
                [{{ s.chunkId }}] {{ s.filename }}<template v-if="s.pageNumber"> · P{{ s.pageNumber }}</template>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="input-bar">
        <el-input
          v-model="question"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="基于选中文档提问…（Enter 发送，Shift+Enter 换行）"
          @keydown.enter.exact.prevent="onSend"
        />
        <el-button type="primary" :loading="sending" @click="onSend">发送</el-button>
        <el-button v-if="sending" @click="onStop">停止</el-button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { chatApi } from '@/api'
import { useDocumentStore } from '@/stores/documents'

const documentStore = useDocumentStore()
const docs = ref([])
const selectedDocIds = ref([])
const messages = ref([])
const question = ref('')
const sending = ref(false)
const msgBox = ref(null)
let cancelStream = null

onMounted(async () => {
  docs.value = await documentStore.fetchAll()
})

function statusType(s) {
  return { READY: 'success', INDEXING: 'warning', FAILED: 'danger' }[s] || 'info'
}

function onFilterChange() {
  // 切换知识范围时清空对话，避免上一轮的引用编号误导
  messages.value = []
}

function scrollBottom() {
  nextTick(() => {
    if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
  })
}

function onSend() {
  const q = question.value.trim()
  if (!q || sending.value) return
  messages.value.push({ role: 'user', text: q })
  const assistant = { role: 'assistant', text: '', sources: [], streaming: true }
  messages.value.push(assistant)
  question.value = ''
  sending.value = true
  scrollBottom()

  cancelStream = chatApi.stream(
    { question: q, documentIds: selectedDocIds.value },
    {
      onSources: (sources) => {
        assistant.sources = sources
      },
      onToken: (t) => {
        assistant.text += t
        scrollBottom()
      },
      onDone: () => {
        assistant.streaming = false
        sending.value = false
        scrollBottom()
      },
      onError: (msg) => {
        assistant.streaming = false
        assistant.text += '\n[错误] ' + msg
        sending.value = false
      }
    }
  )
}

function onStop() {
  cancelStream?.()
  sending.value = false
}

function showSource(s) {
  ElMessageBox.alert(
    `<div style="white-space:pre-wrap">${s.snippet || ''}</div>`,
    `${s.filename}${s.pageNumber ? ' · 第 ' + s.pageNumber + ' 页' : ''}`,
    { dangerouslyUseHTMLString: true, customClass: 'source-dialog' }
  )
}
</script>

<style scoped>
.chat-page { display: flex; height: 100%; }
.doc-panel { width: 240px; border-right: 1px solid #ebeef5; padding: 12px; overflow: auto; }
.panel-title { font-weight: 600; margin-bottom: 8px; }
.doc-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 4px 0; }
.chat-panel { flex: 1; display: flex; flex-direction: column; }
.messages { flex: 1; overflow: auto; padding: 16px; }
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 70%; padding: 10px 14px; border-radius: 8px; background: #f4f4f5; white-space: pre-wrap; }
.msg.user .bubble { background: #409eff; color: #fff; }
.cursor { animation: blink 1s steps(2) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.sources { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.source-chip { font-size: 12px; background: #ecf5ff; color: #409eff; padding: 2px 8px; border-radius: 4px; cursor: pointer; }
.input-bar { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #ebeef5; }
.input-bar .el-textarea { flex: 1; }
</style>
