<template>
  <div class="cw-root">
    <!-- ============ 收起态：右下角气泡 ============ -->
    <transition name="pop">
      <button
        v-show="!open"
        class="cw-bubble"
        type="button"
        title="和我的知识库聊聊"
        @click="open = true"
      >
        <el-icon :size="22"><ChatDotRound /></el-icon>
        <span class="cw-bubble-dot"></span>
      </button>
    </transition>

    <!-- ============ 展开态：对话窗口 ============ -->
    <transition name="slide">
      <div v-show="open" class="cw-panel">
        <!-- 头部 -->
        <div class="cw-head">
          <div class="cw-head-left">
            <span class="cw-avatar">AI</span>
            <div>
              <div class="cw-head-title">知识库助手</div>
              <div class="cw-head-sub">基于站主上传的资料回答</div>
            </div>
          </div>
          <div class="cw-head-right">
            <span v-if="quotaLeft !== null" class="cw-quota">剩 {{ quotaLeft }} 次</span>
            <button class="cw-icon-btn" type="button" title="收起" @click="open = false">
              <el-icon><Minus /></el-icon>
            </button>
          </div>
        </div>

        <!-- 消息区 -->
        <div ref="scrollRef" class="cw-body">
          <div v-if="!messages.length" class="cw-empty">
            <div class="cw-empty-title">有什么想知道的？</div>
            <div class="cw-empty-sub">我读过站主放的全部资料</div>
            <div class="cw-hints">
              <button
                v-for="hint in hints"
                :key="hint"
                class="cw-hint"
                type="button"
                @click="ask(hint)"
              >
                {{ hint }}
              </button>
            </div>
          </div>

          <div
            v-for="(msg, idx) in messages"
            :key="idx"
            class="cw-msg"
            :class="msg.role === 'user' ? 'cw-msg-user' : 'cw-msg-bot'"
          >
            <div class="cw-bubble-msg">
              <div
                v-if="msg.role === 'assistant'"
                class="cw-text"
                v-html="renderAnswer(msg)"
              ></div>
              <div v-else class="cw-text">{{ msg.content }}</div>

              <!-- 引用来源 -->
              <div v-if="msg.sources && msg.sources.length" class="cw-sources">
                <div
                  v-for="(src, si) in msg.sources"
                  :key="si"
                  class="cw-source"
                >
                  <span class="cw-source-idx">[{{ si + 1 }}]</span>
                  <span class="cw-source-name">{{ src.filename }}</span>
                  <span v-if="src.pageNumber" class="cw-source-meta">p{{ src.pageNumber }}</span>
                </div>
              </div>

              <!-- 哪个模型答的。模型池会轮转，出问题时这点信息很关键 -->
              <div v-if="msg.model" class="cw-model">{{ msg.model }}</div>
            </div>
          </div>

          <div v-if="streaming && !currentContent" class="cw-thinking">
            <span class="cw-dot"></span>
            <span class="cw-dot"></span>
            <span class="cw-dot"></span>
          </div>

          <div v-if="errorMsg" class="cw-error">{{ errorMsg }}</div>
        </div>

        <!-- 输入区 -->
        <div class="cw-foot">
          <textarea
            ref="inputRef"
            v-model="question"
            class="cw-input"
            rows="1"
            :disabled="streaming"
            placeholder="问点什么…"
            @keydown.enter.exact.prevent="ask()"
            @input="autoGrow"
          ></textarea>
          <button
            v-if="!streaming"
            class="cw-send"
            type="button"
            :disabled="!question.trim()"
            @click="ask()"
          >
            <el-icon><Promotion /></el-icon>
          </button>
          <button v-else class="cw-send cw-send-stop" type="button" @click="stop">
            <el-icon><VideoPause /></el-icon>
          </button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ChatDotRound, Minus, Promotion, VideoPause } from '@element-plus/icons-vue'
import { chatApi } from '@/api'

const props = defineProps({
  open: { type: Boolean, default: false },
  quota: { type: Object, default: () => ({ limit: 0, remaining: 0, admin: false }) }
})
const emit = defineEmits(['update:open'])

/** 双向绑定：子组件改 open 要回传父级 */
const open = computed({
  get: () => props.open,
  set: (v) => emit('update:open', v)
})

const messages = ref([])
const question = ref('')
const streaming = ref(false)
const errorMsg = ref('')
const currentContent = ref('')
const scrollRef = ref(null)
const inputRef = ref(null)

/** 剩余次数：站主是 -1（不限），游客是具体数字 */
const quotaLeft = computed(() => {
  const r = props.quota?.remaining
  if (typeof r !== 'number') return null
  return r < 0 ? null : r
})

const hints = ['这里都讲了些什么？', '帮我总结一下主要内容', '有哪些关键点？']

let abortStream = null

watch(open, (v) => {
  if (v) {
    nextTick(() => {
      scrollToBottom()
      inputRef.value?.focus()
    })
  }
})

onBeforeUnmount(() => {
  if (abortStream) abortStream()
})

function ask(preset) {
  const text = (preset ?? question.value).trim()
  if (!text || streaming.value) return

  errorMsg.value = ''
  question.value = ''
  currentContent.value = ''
  resetInputHeight()
  messages.value.push({ role: 'user', content: text })

  const botMsg = reactive({ role: 'assistant', content: '', sources: [], model: '' })
  messages.value.push(botMsg)
  streaming.value = true
  scrollToBottom()

  abortStream = chatApi.stream(
    { question: text },
    {
      onSources: (sources) => {
        botMsg.sources = sources || []
        scrollToBottom()
      },
      onToken: (token) => {
        botMsg.content += token
        currentContent.value = botMsg.content
        scrollToBottom()
      },
      onDone: (done) => {
        streaming.value = false
        abortStream = null
        if (typeof done?.remainingQuota === 'number' && done.remainingQuota >= 0) {
          // 服务端回传的额度比前端自己算的准
          props.quota.remaining = done.remainingQuota
        }
        // 模型池切换过模型时把最终用到的模型挂上去，让人知道是谁答的
        if (done?.model) {
          botMsg.model = done.model
        }
      },
      onError: (msg) => {
        streaming.value = false
        abortStream = null
        errorMsg.value = msg || '回答失败了，请稍后再试'
        if (!botMsg.content) {
          const i = messages.value.indexOf(botMsg)
          if (i >= 0) messages.value.splice(i, 1)
        }
      }
    }
  )
}

function stop() {
  if (abortStream) {
    abortStream()
    abortStream = null
  }
  streaming.value = false
}

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/** 输入框随内容长高，最多 4 行 */
function autoGrow() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 96)}px`
}

function resetInputHeight() {
  nextTick(() => {
    if (inputRef.value) inputRef.value.style.height = 'auto'
  })
}

/**
 * 渲染回答正文：把 [1] [2] 变成角标。
 *
 * ⚠️ 安全：先 escapeHtml 再插自己的标签，顺序不能反。
 * 模型输出是不可信内容（文档里可能被注入 </div><script>）。
 */
function renderAnswer(msg) {
  const escaped = escapeHtml(msg.content || '')
  return escaped.replace(
    /\[(\d+)\]/g,
    (m, n) => `<sup class="cw-cite">${n}</sup>`
  )
}

function escapeHtml(s) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
</script>

<style scoped>
.cw-root {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1000;
}

/* ==================== 气泡 ==================== */
.cw-bubble {
  position: relative;
  width: 56px;
  height: 56px;
  border: none;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb 0%, #6366f1 100%);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(37, 99, 235, 0.35);
  transition: all 0.2s;
}
.cw-bubble:hover {
  transform: translateY(-2px) scale(1.04);
  box-shadow: 0 12px 30px rgba(37, 99, 235, 0.42);
}
.cw-bubble-dot {
  position: absolute;
  top: 12px;
  right: 13px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #4ade80;
  border: 2px solid #fff;
}

/* ==================== 面板 ==================== */
.cw-panel {
  width: 380px;
  height: 540px;
  max-height: calc(100vh - 48px);
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 12px 48px rgba(15, 23, 42, 0.18);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #eef0f4;
}

.cw-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: linear-gradient(135deg, #1e293b 0%, #334155 100%);
  color: #fff;
  flex-shrink: 0;
}
.cw-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cw-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}
.cw-head-title {
  font-size: 14px;
  font-weight: 600;
}
.cw-head-sub {
  font-size: 11.5px;
  color: #94a3b8;
  margin-top: 2px;
}
.cw-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cw-quota {
  font-size: 11.5px;
  color: #94a3b8;
}
.cw-icon-btn {
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}
.cw-icon-btn:hover {
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
}

/* ==================== 消息区 ==================== */
.cw-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fcfcfd;
  scroll-behavior: smooth;
}
.cw-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 12px;
}
.cw-empty-title {
  font-size: 15px;
  font-weight: 500;
  color: #334155;
}
.cw-empty-sub {
  font-size: 12.5px;
  color: #94a3b8;
  margin-bottom: 12px;
}
.cw-hints {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.cw-hint {
  padding: 9px 14px;
  border: 1px solid #e8ecf2;
  border-radius: 9px;
  background: #fff;
  color: #475569;
  font-size: 13px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
}
.cw-hint:hover {
  border-color: #93c5fd;
  background: #f0f7ff;
  color: #2563eb;
}

.cw-msg {
  display: flex;
  margin-bottom: 12px;
}
.cw-msg-user {
  justify-content: flex-end;
}
.cw-msg-bot {
  justify-content: flex-start;
}
.cw-bubble-msg {
  max-width: 86%;
  padding: 9px 13px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.72;
  word-break: break-word;
}
.cw-msg-user .cw-bubble-msg {
  background: #2563eb;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.cw-msg-bot .cw-bubble-msg {
  background: #fff;
  color: #1e293b;
  border: 1px solid #eef0f4;
  border-bottom-left-radius: 4px;
}
.cw-text {
  white-space: pre-wrap;
}
.cw-text :deep(.cw-cite) {
  color: #2563eb;
  font-size: 10.5px;
  padding: 0 2px;
  cursor: help;
}
.cw-model {
  margin-top: 8px;
  font-size: 10.5px;
  color: #b0b8c4;
  text-align: right;
  letter-spacing: .2px;
}
.cw-sources {
  margin-top: 8px;
  padding-top: 7px;
  border-top: 1px dashed #e8ecf2;
}
.cw-source {
  display: flex;
  align-items: baseline;
  gap: 5px;
  font-size: 11.5px;
  color: #94a3b8;
  line-height: 1.85;
}
.cw-source-idx {
  color: #2563eb;
  font-weight: 600;
  flex-shrink: 0;
}
.cw-source-name {
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cw-source-meta {
  flex-shrink: 0;
}

.cw-thinking {
  display: flex;
  gap: 4px;
  padding: 10px 14px;
}
.cw-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #cbd5e1;
  animation: cw-blink 1.2s infinite;
}
.cw-dot:nth-child(2) {
  animation-delay: 0.2s;
}
.cw-dot:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes cw-blink {
  0%, 60%, 100% { opacity: 0.3; }
  30% { opacity: 1; }
}
.cw-error {
  margin-top: 8px;
  padding: 9px 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #b91c1c;
  font-size: 12.5px;
}

/* ==================== 输入区 ==================== */
.cw-foot {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid #eef0f4;
  background: #fff;
  flex-shrink: 0;
}
.cw-input {
  flex: 1;
  min-height: 38px;
  max-height: 96px;
  padding: 9px 12px;
  border: 1px solid #e8ecf2;
  border-radius: 10px;
  background: #fafbfc;
  color: #1e293b;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.5;
  resize: none;
  outline: none;
  transition: all 0.15s;
}
.cw-input:focus {
  border-color: #93c5fd;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.08);
}
.cw-send {
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 10px;
  background: #2563eb;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}
.cw-send:hover:not(:disabled) {
  background: #1d4ed8;
}
.cw-send:disabled {
  background: #cbd5e1;
  cursor: not-allowed;
}
.cw-send-stop {
  background: #ef4444;
}
.cw-send-stop:hover {
  background: #dc2626;
}

/* ==================== 动画 ==================== */
.pop-enter-active,
.pop-leave-active {
  transition: all 0.18s;
}
.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: scale(0.8);
}
.slide-enter-active,
.slide-leave-active {
  transition: all 0.22s cubic-bezier(0.4, 0, 0.2, 1);
  transform-origin: bottom right;
}
.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(0.96);
}

@media (max-width: 640px) {
  .cw-root {
    right: 14px;
    bottom: 14px;
  }
  .cw-panel {
    width: calc(100vw - 28px);
    height: calc(100vh - 120px);
  }
}
</style>
