<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>日常动态</h1>
        <p class="sub">发文字、图片或视频。可以顺手同步一份到照片墙。</p>
      </div>
      <el-button type="primary" @click="openComposer">
        <el-icon><Plus /></el-icon>
        <span>发一条</span>
      </el-button>
    </header>

    <!-- ============ 发表框 ============ -->
    <el-dialog
      v-model="composerOpen"
      :title="editingId ? '编辑动态' : '发一条动态'"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetComposer"
    >
      <el-form label-position="top">
        <el-form-item label="说点什么">
          <el-input
            v-model="draft.content"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="今天……"
          />
        </el-form-item>

        <!-- 编辑已有动态时不支持换媒体 -->
        <el-form-item v-if="!editingId" label="配图或视频（可选）">
          <div v-if="draft.mediaUrl" class="media-preview">
            <img
              v-if="draft.mediaType === 'IMAGE'"
              :src="draft.mediaUrl"
              alt=""
              class="preview-img"
            />
            <video
              v-else
              :src="draft.mediaUrl"
              controls
              class="preview-video"
            />
            <el-button
              text
              type="danger"
              size="small"
              class="remove-media"
              @click="clearMedia"
            >
              移除
            </el-button>
          </div>
          <el-upload
            v-else
            drag
            :auto-upload="false"
            :show-file-list="false"
            accept="image/*,video/mp4,video/webm,video/quicktime"
            :on-change="onMediaPicked"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              拖进来，或<em>点击选择</em>
            </div>
            <template #tip>
              <div class="upload-tip">
                图片 ≤ 8MB（jpg/png/webp/gif）；视频 ≤ 50MB（mp4/webm/mov）
              </div>
            </template>
          </el-upload>
          <el-progress
            v-if="uploading"
            :percentage="uploadPercent"
            :stroke-width="4"
            class="upload-progress"
          />
        </el-form-item>

        <!-- 只有图片才有「同步到照片墙」 -->
        <el-form-item v-if="canSyncToPhotos" label="同时发到照片墙">
          <div class="sync-block">
            <el-switch v-model="draft.syncToPhotos" />
            <span class="sync-hint">照片墙只收图片，文字和视频不会同步</span>
          </div>
          <el-input
            v-if="draft.syncToPhotos"
            v-model="draft.photoCaption"
            class="caption-input"
            maxlength="200"
            show-word-limit
            placeholder="照片墙上那条的说明，留空就用动态文字"
          />
        </el-form-item>

        <el-form-item label="谁能看到">
          <el-radio-group v-model="draft.visible">
            <el-radio :value="true">公开</el-radio>
            <el-radio :value="false">仅自己</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="composerOpen = false">取消</el-button>
        <el-button type="primary" :loading="publishing" @click="publish">
          {{ editingId ? '保存' : '发布' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ============ 动态列表 ============ -->
    <div v-loading="loading" class="list">
      <el-empty v-if="!loading && !items.length" description="还没发过动态" />

      <article v-for="m in items" :key="m.id" class="moment" :class="{ hidden: !m.visible }">
        <div class="moment-body">
          <p v-if="m.content" class="moment-text">{{ m.content }}</p>

          <img
            v-if="m.mediaType === 'IMAGE'"
            :src="m.mediaUrl"
            alt=""
            class="moment-img"
            @click="preview(m.mediaUrl)"
          />
          <video
            v-else-if="m.mediaType === 'VIDEO'"
            :src="m.mediaUrl"
            controls
            preload="metadata"
            class="moment-video"
          />

          <div class="moment-meta">
            <span class="time">{{ m.createdAt }}</span>
            <el-tag v-if="!m.visible" size="small" type="info" effect="plain">仅自己</el-tag>
            <el-tag v-if="m.synced" size="small" type="success" effect="plain">
              已在照片墙
            </el-tag>
            <span v-if="m.comments > 0" class="comments-tip">
              {{ m.comments }} 条留言
            </span>
          </div>
        </div>

        <div class="moment-actions">
          <el-button
            v-if="!m.synced && m.mediaType === 'IMAGE'"
            text
            size="small"
            @click="syncToPhotos(m)"
          >
            发到照片墙
          </el-button>
          <el-button
            v-if="m.comments > 0"
            text
            size="small"
            @click="viewComments(m)"
          >
            看留言
          </el-button>
          <el-button text size="small" @click="editMoment(m)">编辑</el-button>
          <el-button text size="small" type="danger" @click="removeMoment(m)">删除</el-button>
        </div>
      </article>

      <div v-if="total > items.length" class="more">
        <el-button text :loading="loading" @click="loadMore">加载更多</el-button>
      </div>
    </div>

    <!-- ============ 看某条动态的留言 ============ -->
    <el-dialog v-model="commentsOpen" title="这条动态下的留言" width="600px">
      <p class="comments-note">
        <el-icon><Lock /></el-icon>
        <span>这里能看到包括悄悄话和已被屏蔽在内的全部留言；访客那边只看得到公开且未屏蔽的。</span>
      </p>
      <el-empty v-if="!loadingComments && !momentComments.length" description="还没有人留言" />
      <div v-for="c in momentComments" :key="c.id" class="comment" :class="{ 'c-private': c.visibility === 'PRIVATE', 'c-hidden': c.hidden }">
        <div class="comment-head">
          <strong>{{ c.nickname }}</strong>
          <el-tag v-if="c.author?.admin" size="small" type="warning">站主</el-tag>
          <el-tag v-else-if="c.author?.registered" size="small" type="info">注册用户</el-tag>
          <el-tag v-else size="small">游客</el-tag>
          <el-tag v-if="c.visibility === 'PRIVATE'" size="small" type="warning">悄悄话</el-tag>
          <el-tag v-if="c.hidden" size="small" type="danger">已屏蔽</el-tag>
          <span class="comment-time">{{ c.createdAt }}</span>
        </div>
        <p class="comment-text">{{ c.content }}</p>
        <div v-for="r in c.replies || []" :key="r.id" class="comment-reply">
          <strong>{{ r.nickname }}</strong>
          <span class="comment-reply-time">{{ r.createdAt }}</span>
          <p class="comment-text">{{ r.content }}</p>
        </div>
      </div>
      <template #footer>
        <el-button @click="commentsOpen = false">关闭</el-button>
        <el-button type="primary" plain @click="goComments">去留言管理</el-button>
      </template>
    </el-dialog>

    <!-- 大图 -->
    <el-dialog v-model="imageOpen" width="auto" class="img-dialog">
      <img :src="imageUrl" alt="" class="big-img" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, Plus, UploadFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { momentApi, commentApi } from '@/api'

const router = useRouter()

const loading = ref(false)
const items = ref([])
const total = ref(0)
const offset = ref(0)
const PAGE = 20

const composerOpen = ref(false)
const publishing = ref(false)
const editingId = ref(null)
const uploading = ref(false)
const uploadPercent = ref(0)

const draft = reactive({
  content: '',
  mediaUrl: '',
  mediaType: '',
  width: null,
  height: null,
  size: null,
  syncToPhotos: true,
  photoCaption: '',
  visible: true
})

// 只有「新发的图片」才谈得上同步到照片墙
const canSyncToPhotos = computed(
  () => !editingId.value && draft.mediaUrl && draft.mediaType === 'IMAGE'
)

const commentsOpen = ref(false)
const momentComments = ref([])
const loadingComments = ref(false)

const imageOpen = ref(false)
const imageUrl = ref('')

onMounted(() => load(true))

async function load(reset = false) {
  loading.value = true
  try {
    if (reset) offset.value = 0
    const page = await momentApi.list(PAGE, offset.value)
    const list = page?.items || []
    items.value = reset ? list : [...items.value, ...list]
    total.value = page?.total || 0
    offset.value = items.value.length
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function loadMore() {
  load(false)
}

function openComposer() {
  editingId.value = null
  resetComposer()
  composerOpen.value = true
}

function resetComposer() {
  draft.content = ''
  clearMedia()
  draft.syncToPhotos = true
  draft.photoCaption = ''
  draft.visible = true
}

function clearMedia() {
  draft.mediaUrl = ''
  draft.mediaType = ''
  draft.width = null
  draft.height = null
  draft.size = null
}

/**
 * 选到文件就立刻上传，拿到 URL 再提交动态。
 *
 * ⚠️ 两步走的原因：50MB 视频上传可能要几十秒，如果和文字一起提交，
 * 用户等着等着关了页面就全白费；先传完文件，文字还能慢慢改。
 */
async function onMediaPicked(file) {
  const raw = file.raw
  if (!raw) return
  const isVideo = raw.type.startsWith('video/')
  const maxMB = isVideo ? 50 : 8
  if (raw.size > maxMB * 1024 * 1024) {
    ElMessage.error(`${isVideo ? '视频' : '图片'}不能超过 ${maxMB}MB`)
    return
  }
  const formData = new FormData()
  formData.append('file', raw)
  uploading.value = true
  uploadPercent.value = 0
  try {
    const res = await momentApi.upload(formData, (p) => {
      uploadPercent.value = p
    })
    if (res) {
      draft.mediaUrl = res.url
      draft.mediaType = res.mediaType
      draft.width = res.width
      draft.height = res.height
      draft.size = res.size
    }
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}

async function publish() {
  if (!draft.content.trim() && !draft.mediaUrl) {
    ElMessage.warning('写点什么，或者传张图/一段视频')
    return
  }
  publishing.value = true
  try {
    if (editingId.value) {
      await momentApi.update(editingId.value, {
        content: draft.content,
        visible: draft.visible
      })
      ElMessage.success('已保存')
    } else {
      await momentApi.create({
        content: draft.content,
        mediaUrl: draft.mediaUrl || null,
        mediaType: draft.mediaType || null,
        width: draft.width,
        height: draft.height,
        size: draft.size,
        syncToPhotos: draft.syncToPhotos,
        photoCaption: draft.photoCaption,
        visible: draft.visible
      })
      ElMessage.success(
        draft.syncToPhotos && draft.mediaType === 'IMAGE' ? '已发布，并同步到照片墙' : '已发布'
      )
    }
    composerOpen.value = false
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  } finally {
    publishing.value = false
  }
}

function editMoment(m) {
  editingId.value = m.id
  draft.content = m.content || ''
  draft.mediaUrl = m.mediaUrl || ''
  draft.mediaType = m.mediaType || ''
  draft.width = m.width
  draft.height = m.height
  draft.size = m.size
  draft.visible = m.visible
  composerOpen.value = true
}

async function removeMoment(m) {
  try {
    await ElMessageBox.confirm(
      m.synced
        ? '删除后这条动态和照片墙里对应的那张图都会没掉，确定吗？'
        : '删掉就找不回来了，确定吗？',
      '删除动态',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '算了' }
    )
  } catch {
    return
  }
  try {
    await momentApi.remove(m.id)
    ElMessage.success('已删除')
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function syncToPhotos(m) {
  try {
    await momentApi.syncToPhotos(m.id, '')
    ElMessage.success('已经发到照片墙了')
    await load(true)
  } catch (e) {
    ElMessage.error(e.message || '同步失败')
  }
}

async function viewComments(m) {
  momentComments.value = []
  commentsOpen.value = true
  loadingComments.value = true
  try {
    // ⚠️ 走 commentApi 而不是 momentApi.commentsOf：留言的读写现在只有
    // CommentService 一处实现。旧端点还在（@Deprecated 转调），但两套 DTO
    // 并存正是「站主看不到悄悄话」那个 bug 的土壤，新代码一律走这套。
    const list = await commentApi.momentComments(m.id)
    momentComments.value = list || []
    // 打开就算读过了，避免后台一直顶着小红点
    if (momentComments.value.some((c) => !c.read)) {
      await commentApi.markRead()
    }
  } catch (e) {
    ElMessage.error(e.message || '加载留言失败')
  } finally {
    loadingComments.value = false
  }
}

function goComments() {
  commentsOpen.value = false
  router.push({ name: 'admin-comments' })
}

function preview(url) {
  imageUrl.value = url
  imageOpen.value = true
}
</script>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  margin: 5px 0 0;
  font-size: 13px;
  color: #94a3b8;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}
.moment {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 16px 18px;
}
.moment.hidden {
  background: #fafbfc;
  border-style: dashed;
}
.moment-text {
  margin: 0 0 12px;
  font-size: 14px;
  color: #334155;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}
.moment-img {
  max-width: 320px;
  max-height: 320px;
  border-radius: 8px;
  cursor: zoom-in;
  display: block;
  margin-bottom: 12px;
}
.moment-video {
  max-width: 420px;
  max-height: 340px;
  border-radius: 8px;
  display: block;
  margin-bottom: 12px;
  background: #000;
}
.moment-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #94a3b8;
}
.comments-tip {
  color: #2563eb;
}
.moment-actions {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f4f6f9;
}
.more {
  text-align: center;
  padding: 8px 0;
}
.media-preview {
  position: relative;
  display: inline-block;
  margin-bottom: 10px;
}
.preview-img {
  max-width: 260px;
  max-height: 200px;
  border-radius: 8px;
  display: block;
}
.preview-video {
  max-width: 320px;
  max-height: 220px;
  border-radius: 8px;
  display: block;
  background: #000;
}
.remove-media {
  position: absolute;
  top: 4px;
  right: 4px;
  background: rgba(255, 255, 255, 0.9);
}
.upload-tip {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}
.upload-progress {
  margin-top: 8px;
}
.sync-block {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sync-hint {
  font-size: 12px;
  color: #94a3b8;
}
.caption-input {
  margin-top: 8px;
}
.comments-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 14px;
  padding: 9px 12px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
  font-size: 12px;
  color: #92400e;
}
.comment {
  padding: 11px 0;
  border-bottom: 1px solid #f4f6f9;
}
.comment:last-child {
  border-bottom: none;
}
/* 悄悄话和已屏蔽在后台也要一眼分得出，否则站主得逐条读正文才能判断 */
.comment.c-private {
  background: #fffbf0;
  border-left: 3px solid #f0b429;
  padding-left: 10px;
}
.comment.c-hidden {
  background: #fdf3f3;
  border-left: 3px solid #e88;
  padding-left: 10px;
}
.comment-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  color: #1e293b;
}
.comment-time {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
}
.comment-text {
  margin: 6px 0 4px;
  font-size: 14px;
  color: #334155;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.comment-reply {
  margin: 8px 0 0 16px;
  padding-left: 10px;
  border-left: 2px solid #eef2f7;
  font-size: 13px;
}
.comment-reply-time {
  margin-left: 8px;
  font-size: 12px;
  color: #94a3b8;
}
.big-img {
  max-width: 80vw;
  max-height: 80vh;
  display: block;
}
</style>
