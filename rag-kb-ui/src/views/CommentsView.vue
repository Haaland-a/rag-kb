<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>留言管理</h1>
        <p class="sub">
          这里的留言来自两个地方：<strong>留言板</strong>（游客直接给你留言）和
          <strong>动态评论</strong>。悄悄话只有你能看到；被屏蔽的留言其他人看不到，
          但留言者自己仍能看到那一条。
        </p>
      </div>
      <div class="head-actions">
        <el-button size="small" :loading="loading" @click="load(true)">刷新</el-button>
      </div>
    </header>

    <!-- 统计条 -->
    <div class="stat-bar">
      <span>未读 <strong>{{ unread }}</strong> 条</span>
      <span class="divider">·</span>
      <span>当前筛选下共 {{ total }} 条</span>
      <el-button v-if="unread > 0" text size="small" class="mark-all" @click="markAll">
        全部标记已读
      </el-button>
    </div>

    <!-- 筛选 -->
    <div class="filters">
      <el-radio-group v-model="unreadOnly" size="small" @change="load(true)">
        <el-radio-button :value="true">只看未读</el-radio-button>
        <el-radio-button :value="false">全部</el-radio-button>
      </el-radio-group>

      <el-radio-group v-model="scope" size="small" @change="load(true)">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="board">留言板</el-radio-button>
        <el-radio-button value="moment">动态评论</el-radio-button>
      </el-radio-group>

      <el-radio-group v-model="visibilityFilter" size="small" @change="noop">
        <el-radio-button value="all">全部类型</el-radio-button>
        <el-radio-button value="private">悄悄话</el-radio-button>
        <el-radio-button value="hidden">已屏蔽</el-radio-button>
      </el-radio-group>
    </div>

    <div v-loading="loading" class="list">
      <el-empty
        v-if="!loading && !filtered.length"
        :description="unreadOnly ? '没有未读留言了' : '还没有人留言'"
      />

      <article
        v-for="c in filtered"
        :key="c.id"
        class="comment"
        :class="{
          read: c.read,
          'is-private': c.visibility === 'PRIVATE',
          'is-hidden': c.hidden
        }"
      >
        <div class="comment-head">
          <span class="nick">{{ c.nickname }}</span>
          <el-tag v-if="!c.read" size="small" type="danger" effect="plain">
            新
          </el-tag>
          <el-tag v-if="c.author.registered" size="small" effect="plain">注册用户</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">游客</el-tag>
          <el-tag v-if="c.visibility === 'PRIVATE'" size="small" type="warning" effect="plain">
            悄悄话
          </el-tag>
          <el-tag v-if="c.hidden" size="small" type="danger" effect="plain">已屏蔽</el-tag>
          <el-tag v-if="c.momentId" size="small" effect="plain">动态 #{{ c.momentId }}</el-tag>
          <el-tag v-else size="small" effect="plain">留言板</el-tag>
          <span class="time">{{ c.createdAt }}</span>
        </div>

        <p class="content">{{ c.content }}</p>

        <div class="comment-actions">
          <el-button size="small" text @click="detail(c)">详情</el-button>
          <el-button size="small" text @click="toggleHide(c)">
            {{ c.hidden ? '取消屏蔽' : '屏蔽' }}
          </el-button>
          <el-button size="small" text type="danger" @click="remove(c)">删除</el-button>
        </div>
      </article>

      <div v-if="filtered.length < total" class="load-more">
        <el-button text :loading="loadingMore" @click="loadMore">加载更多</el-button>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailOpen" title="留言详情" width="560px">
      <div v-if="current" class="detail">
        <div class="d-row">
          <span class="d-label">留言人</span>
          <span>{{ current.nickname }}</span>
          <el-tag v-if="current.author.registered" size="small" effect="plain">
            注册用户
          </el-tag>
          <el-tag v-else size="small" type="info" effect="plain">游客</el-tag>
        </div>
        <div class="d-row">
          <span class="d-label">时间</span>
          <span>{{ current.createdAt }}</span>
        </div>
        <div class="d-row">
          <span class="d-label">来源</span>
          <span>{{ current.momentId ? `动态 #${current.momentId}` : '留言板' }}</span>
        </div>
        <div class="d-row">
          <span class="d-label">可见性</span>
          <span>{{ current.visibility === 'PRIVATE' ? '悄悄话（仅你可见）' : '公开' }}</span>
        </div>
        <div class="d-row">
          <span class="d-label">状态</span>
          <span>{{ current.hidden ? '已屏蔽' : '正常展示' }}</span>
        </div>
        <div class="d-content">{{ current.content }}</div>
        <div v-if="current.replies?.length" class="d-replies">
          <p class="d-replies-title">有 {{ current.replies.length }} 条回复</p>
          <div v-for="r in current.replies" :key="r.id" class="d-reply">
            <span class="d-r-name">{{ r.nickname }}</span>
            <span class="d-r-time">{{ r.createdAt }}</span>
            <p class="d-r-content">{{ r.content }}</p>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { commentApi } from '@/api'

const PAGE = 20

const items = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const total = ref(0)
const offset = ref(0)
const unread = ref(0)

const unreadOnly = ref(false)
const scope = ref('all')
const visibilityFilter = ref('all')

const detailOpen = ref(false)
const current = ref(null)

/**
 * 前端二次过滤（只管「悄悄话 / 已屏蔽」这一列）。
 *
 * <p>⚠️ 范围（全部 / 留言板 / 动态评论）不在这里筛，交给后端的 scope 参数 ——
 * 本地筛只能筛当前这一页，会出现「列表里有 3 条但 total 写着 20」的错乱。
 * 悄悄话和已屏蔽是每条记录自带的标记，本地筛不会影响分页正确性。</p>
 */
const filtered = computed(() => {
  return items.value.filter((c) => {
    if (visibilityFilter.value === 'private' && c.visibility !== 'PRIVATE') return false
    if (visibilityFilter.value === 'hidden' && !c.hidden) return false
    return true
  })
})

onMounted(() => load(true))

async function load(reset = false) {
  if (reset) {
    loading.value = true
    offset.value = 0
  }
  try {
    const params = { limit: PAGE, offset: offset.value, unreadOnly: unreadOnly.value }
    // all / board / moment 直接交给后端，不再拉全量再在本地筛
    if (scope.value !== 'all') params.scope = scope.value

    const page = await commentApi.adminList(params)
    const list = page?.items || []

    if (reset) {
      items.value = list
    } else {
      items.value.push(...list)
    }
    total.value = page?.total || 0
    offset.value += page?.items?.length || 0

    const u = await commentApi.unread()
    unread.value = u?.unread || 0
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

function noop() {
  // 纯前端筛选，不需要重新请求
}

function detail(c) {
  current.value = c
  detailOpen.value = true
  markOne(c)
}

async function markOne(c) {
  if (c.read) return
  try {
    await commentApi.markRead(c.id)
    // 就地改字段，比重新拉列表省一次请求，也不会让列表滚动位置跳动
    c.read = true
    unread.value = Math.max(0, unread.value - 1)
  } catch {
    /* 标记失败不影响阅读，下次刷新会重试 */
  }
}

async function markAll() {
  try {
    await commentApi.markRead(null)
    items.value.forEach((c) => {
      c.read = true
    })
    unread.value = 0
    ElMessage.success('已全部标记已读')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function toggleHide(c) {
  const nextStatus = c.hidden ? 'VISIBLE' : 'HIDDEN'
  if (!c.hidden) {
    try {
      await ElMessageBox.confirm(
        '屏蔽后其他人看不到这条留言，但留言者本人仍然能看到（他会知道被屏蔽了）。确定吗？',
        '屏蔽留言',
        { confirmButtonText: '屏蔽', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
  }
  try {
    await commentApi.setStatus(c.id, nextStatus)
    c.hidden = nextStatus === 'HIDDEN'
    ElMessage.success(c.hidden ? '已屏蔽' : '已恢复展示')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function remove(c) {
  try {
    await ElMessageBox.confirm('删除后不能恢复，确定吗？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await commentApi.remove(c.id)
    ElMessage.success('已删除')
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
</script>

<style scoped>
.page {
  max-width: 1080px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-head h1 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
  color: #1e293b;
}
.sub {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.7;
  max-width: 620px;
}
.sub strong {
  color: #475569;
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.stat-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  font-size: 13px;
  color: #64748b;
}
.stat-bar strong {
  color: #1e293b;
}
.divider {
  color: #cbd5e1;
}
.mark-all {
  margin-left: auto;
}

.filters {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
}

.comment {
  background: #fff;
  border: 1px solid #eef0f4;
  border-left: 3px solid #f87171;
  border-radius: 10px;
  padding: 13px 16px;
}
.comment.read {
  border-left-color: #e2e8f0;
}
.comment.is-private {
  background: #fdf4ff;
  border-color: #f5d0fe;
}
.comment.is-hidden {
  background: #fafafa;
  border-style: dashed;
}

.comment-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.nick {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.time {
  margin-left: auto;
  font-size: 12px;
  color: #b0b8c4;
}

.content {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-actions {
  display: flex;
  gap: 4px;
  margin-top: 8px;
}

.load-more {
  text-align: center;
  padding: 8px 0;
}

/* 详情弹窗 */
.detail {
  font-size: 13px;
}
.d-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  color: #475569;
}
.d-label {
  width: 64px;
  color: #94a3b8;
  flex-shrink: 0;
}
.d-content {
  margin-top: 12px;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.75;
  color: #1e293b;
  white-space: pre-wrap;
  word-break: break-word;
}
.d-replies {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #eef0f4;
}
.d-replies-title {
  margin: 0 0 8px;
  font-size: 12px;
  color: #94a3b8;
}
.d-reply {
  padding: 8px 0;
}
.d-r-name {
  font-weight: 600;
  color: #475569;
  font-size: 13px;
}
.d-r-time {
  margin-left: 8px;
  font-size: 12px;
  color: #b0b8c4;
}
.d-r-content {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.7;
  color: #475569;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }
  .filters {
    flex-direction: column;
  }
}
</style>
