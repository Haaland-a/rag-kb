<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="page-title">我的内容</h1>
        <p class="page-sub">
          照片墙、项目展示、简历下载。主页会实时显示这里的内容，改完刷新主页就能看到。
        </p>
      </div>
      <el-tag v-if="storageType" size="small" effect="plain" type="info">
        存储：{{ storageType === 'oss' ? '阿里云 OSS' : '服务器本地' }}
      </el-tag>
    </div>

    <el-tabs v-model="tab" class="tabs" @tab-change="onTabChange">
      <!-- ==================== 照片墙 ==================== -->
      <el-tab-pane label="照片墙" name="photos">
        <div class="pane-bar">
          <div class="pane-hint">主页的「随手拍的」区域，按排序值从小到大显示。</div>
          <el-upload
            :show-file-list="false"
            :auto-upload="false"
            accept="image/jpeg,image/png,image/webp,image/gif"
            :on-change="onPhotoPicked"
          >
            <el-button type="primary" :loading="uploading">
              <el-icon><Plus /></el-icon>
              上传照片
            </el-button>
          </el-upload>
        </div>

        <el-progress v-if="uploading" :percentage="progress" class="progress" />

        <div v-if="photos.length" class="photo-admin-grid">
          <div v-for="p in photos" :key="p.id" class="photo-card" :class="{ dim: !p.visible }">
            <img :src="p.url" :alt="p.caption || ''" class="photo-thumb" />

            <div class="photo-meta">
              <el-input
                v-model="p.caption"
                size="small"
                maxlength="200"
                placeholder="加一句说明…"
                @blur="savePhoto(p)"
                @keyup.enter="savePhoto(p)"
              />
              <div class="meta-row">
                <el-switch
                  v-model="p.visible"
                  size="small"
                  active-text="显示"
                  @change="savePhoto(p)"
                />
                <span class="size">{{ fmtSize(p.fileSize) }}</span>
                <el-button size="small" text type="danger" @click="removePhoto(p)">
                  删除
                </el-button>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="还没有照片，点右上角上传" />
      </el-tab-pane>

      <!-- ==================== 项目 ==================== -->
      <el-tab-pane label="项目展示" name="projects">
        <div class="pane-bar">
          <div class="pane-hint">主页的「做过的项目」区域，卡片式展示。</div>
          <el-button type="primary" @click="openProjectDialog(null)">
            <el-icon><Plus /></el-icon>
            新建项目
          </el-button>
        </div>

        <div v-if="projects.length" class="proj-admin-list">
          <div v-for="p in projects" :key="p.id" class="proj-row" :class="{ dim: !p.visible }">
            <div class="proj-row-cover">
              <img v-if="p.coverUrl" :src="p.coverUrl" :alt="p.title" />
              <div v-else class="cover-empty">无图</div>
            </div>

            <div class="proj-row-body">
              <div class="proj-row-title">
                {{ p.title }}
                <el-tag v-if="!p.visible" size="small" type="info">隐藏</el-tag>
              </div>
              <div v-if="p.description" class="proj-row-desc">{{ p.description }}</div>
              <div v-if="p.tech?.length" class="proj-row-tech">
                <el-tag v-for="t in p.tech" :key="t" size="small" effect="plain">{{ t }}</el-tag>
              </div>
            </div>

            <div class="proj-row-actions">
              <el-button size="small" @click="openProjectDialog(p)">编辑</el-button>
              <el-button size="small" text type="danger" @click="removeProject(p)">删除</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="还没有项目，点右上角新建" />
      </el-tab-pane>

      <!-- ==================== 简历下载 ==================== -->
      <el-tab-pane label="简历下载" name="downloads">
        <div class="pane-bar">
          <div class="pane-hint">主页页脚的下载区，访客点一次计入下载次数。</div>
          <el-upload
            :show-file-list="false"
            :auto-upload="false"
            accept=".pdf,.doc,.docx,.zip"
            :on-change="onFilePicked"
          >
            <el-button type="primary" :loading="uploading">
              <el-icon><Plus /></el-icon>
              上传文件
            </el-button>
          </el-upload>
        </div>

        <el-table v-if="downloads.length" :data="downloads" class="table">
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <el-input
                v-model="row.title"
                size="small"
                @blur="saveDownload(row)"
                @keyup.enter="saveDownload(row)"
              />
            </template>
          </el-table-column>
          <el-table-column prop="fileType" label="类型" width="80">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ (row.fileType || '').toUpperCase() }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column prop="downloadCount" label="下载" width="80" />
          <el-table-column label="显示" width="90">
            <template #default="{ row }">
              <el-switch v-model="row.visible" size="small" @change="saveDownload(row)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="right">
            <template #default="{ row }">
              <el-button size="small" text @click="openUrl(row.url)">预览</el-button>
              <el-button size="small" text type="danger" @click="removeDownload(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="还没有文件，点右上角上传" />
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 项目编辑弹窗 ==================== -->
    <el-dialog
      v-model="projVisible"
      :title="projForm.id ? '编辑项目' : '新建项目'"
      width="620px"
      destroy-on-close
    >
      <el-form ref="projFormRef" :model="projForm" :rules="projRules" label-position="top">
        <el-form-item label="项目名" prop="title">
          <el-input v-model="projForm.title" maxlength="80" placeholder="比如：个人知识库站点" />
        </el-form-item>

        <el-form-item label="一句话说明">
          <el-input
            v-model="projForm.description"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
            placeholder="这个项目是做什么的"
          />
        </el-form-item>

        <el-form-item label="技术栈（逗号分隔）">
          <el-input v-model="projForm.tech" placeholder="Java,Spring Boot,PostgreSQL" />
        </el-form-item>

        <el-form-item label="截图">
          <div class="cover-picker">
            <div v-if="projForm.coverUrl" class="cover-preview">
              <img :src="projForm.coverUrl" alt="截图" />
              <el-button size="small" text type="danger" @click="projForm.coverUrl = ''">
                移除
              </el-button>
            </div>
            <el-upload
              v-else
              :show-file-list="false"
              :auto-upload="false"
              accept="image/jpeg,image/png,image/webp,image/gif"
              :on-change="onCoverPicked"
            >
              <el-button :loading="coverUploading">选择图片</el-button>
            </el-upload>
          </div>
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="源码地址">
              <el-input v-model="projForm.repoUrl" placeholder="https://github.com/…" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="在线演示">
              <el-input v-model="projForm.demoUrl" placeholder="https://…" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="排序（越小越靠前）">
              <el-input-number v-model="projForm.sortOrder" :min="0" :max="9999" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="在主页显示">
              <el-switch v-model="projForm.visible" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <template #footer>
        <el-button @click="projVisible = false">取消</el-button>
        <el-button type="primary" :loading="projSaving" @click="submitProject">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { mediaApi } from '@/api'

const tab = ref('photos')
const storageType = ref('')

const photos = ref([])
const projects = ref([])
const downloads = ref([])

const uploading = ref(false)
const progress = ref(0)

// ---------- 项目弹窗 ----------
const projVisible = ref(false)
const projSaving = ref(false)
const projFormRef = ref(null)
const coverUploading = ref(false)

const emptyProject = () => ({
  id: null,
  title: '',
  description: '',
  coverUrl: '',
  tech: '',
  repoUrl: '',
  demoUrl: '',
  sortOrder: 0,
  visible: true
})
const projForm = reactive(emptyProject())

const projRules = {
  title: [{ required: true, message: '请填项目名', trigger: 'blur' }]
}

// ==================== 加载 ====================

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadPhotos(), loadProjects(), loadDownloads()])
}

async function loadPhotos() {
  try {
    photos.value = (await mediaApi.photos()) || []
  } catch (e) {
    ElMessage.error('加载照片失败：' + e.message)
  }
}

async function loadProjects() {
  try {
    projects.value = (await mediaApi.projects()) || []
  } catch (e) {
    ElMessage.error('加载项目失败：' + e.message)
  }
}

async function loadDownloads() {
  try {
    downloads.value = (await mediaApi.downloads()) || []
  } catch (e) {
    ElMessage.error('加载下载列表失败：' + e.message)
  }
}

function onTabChange(name) {
  // 切标签时重拉一次：别的设备上改过，这里能看到最新
  if (name === 'photos') loadPhotos()
  else if (name === 'projects') loadProjects()
  else loadDownloads()
}

// ==================== 照片 ====================

async function onPhotoPicked(uploadFile) {
  const raw = uploadFile.raw
  if (!raw) return

  // 前端先挡一道，省一次白跑的网络往返。后端还会再校验（不能只信前端）
  if (raw.size > 8 * 1024 * 1024) {
    ElMessage.error('图片不能超过 8MB，当前 ' + (raw.size / 1024 / 1024).toFixed(1) + 'MB')
    return
  }

  const fd = new FormData()
  fd.append('file', raw)
  fd.append('caption', '')

  uploading.value = true
  progress.value = 0
  try {
    await mediaApi.uploadPhoto(fd, (p) => (progress.value = p))
    ElMessage.success('上传成功')
    await loadPhotos()
  } catch (e) {
    ElMessage.error('上传失败：' + e.message)
  } finally {
    uploading.value = false
    progress.value = 0
  }
}

async function savePhoto(p) {
  try {
    await mediaApi.updatePhoto(p.id, {
      caption: p.caption || '',
      visible: p.visible
    })
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  }
}

async function removePhoto(p) {
  try {
    await ElMessageBox.confirm(
      '删除后图片文件和记录都会消失，主页立刻看不到。确定删除？',
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await mediaApi.removePhoto(p.id)
    ElMessage.success('已删除')
    await loadPhotos()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

// ==================== 项目 ====================

function openProjectDialog(row) {
  Object.assign(projForm, emptyProject())
  if (row) {
    Object.assign(projForm, {
      id: row.id,
      title: row.title,
      description: row.description || '',
      coverUrl: row.coverUrl || '',
      tech: (row.tech || []).join(','),
      repoUrl: row.repoUrl || '',
      demoUrl: row.demoUrl || '',
      sortOrder: row.sortOrder || 0,
      visible: row.visible
    })
  }
  projVisible.value = true
}

async function onCoverPicked(uploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  if (raw.size > 8 * 1024 * 1024) {
    ElMessage.error('图片不能超过 8MB')
    return
  }
  const fd = new FormData()
  fd.append('file', raw)
  coverUploading.value = true
  try {
    const res = await mediaApi.uploadCover(fd)
    projForm.coverUrl = res?.url || ''
    ElMessage.success('截图已上传')
  } catch (e) {
    ElMessage.error('上传失败：' + e.message)
  } finally {
    coverUploading.value = false
  }
}

async function submitProject() {
  if (!projFormRef.value) return
  try {
    await projFormRef.value.validate()
  } catch {
    return
  }

  const body = {
    title: projForm.title,
    description: projForm.description,
    coverUrl: projForm.coverUrl,
    tech: projForm.tech,
    repoUrl: projForm.repoUrl,
    demoUrl: projForm.demoUrl,
    sortOrder: projForm.sortOrder,
    visible: projForm.visible
  }

  projSaving.value = true
  try {
    if (projForm.id) await mediaApi.updateProject(projForm.id, body)
    else await mediaApi.createProject(body)
    ElMessage.success('已保存')
    projVisible.value = false
    await loadProjects()
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally {
    projSaving.value = false
  }
}

async function removeProject(p) {
  try {
    await ElMessageBox.confirm(`删除项目「${p.title}」？`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await mediaApi.removeProject(p.id)
    ElMessage.success('已删除')
    await loadProjects()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

// ==================== 下载 ====================

async function onFilePicked(uploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  if (raw.size > 20 * 1024 * 1024) {
    ElMessage.error('文件不能超过 20MB')
    return
  }

  // 默认标题取原文件名（去掉扩展名），用户可以在表格里改
  const baseName = raw.name.replace(/\.[^.]+$/, '')

  const fd = new FormData()
  fd.append('file', raw)
  fd.append('title', baseName)

  uploading.value = true
  progress.value = 0
  try {
    await mediaApi.uploadDownload(fd, (p) => (progress.value = p))
    ElMessage.success('上传成功')
    await loadDownloads()
  } catch (e) {
    ElMessage.error('上传失败：' + e.message)
  } finally {
    uploading.value = false
    progress.value = 0
  }
}

async function saveDownload(row) {
  try {
    await mediaApi.updateDownload(row.id, {
      title: row.title,
      visible: row.visible
    })
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  }
}

async function removeDownload(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.title}」？文件会一并删掉。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await mediaApi.removeDownload(row.id)
    ElMessage.success('已删除')
    await loadDownloads()
  } catch (e) {
    ElMessage.error('删除失败：' + e.message)
  }
}

function openUrl(url) {
  window.open(url, '_blank', 'noopener')
}

// ==================== 工具 ====================

function fmtSize(bytes) {
  if (!bytes) return '-'
  const mb = bytes / 1024 / 1024
  if (mb >= 1) return mb.toFixed(1) + ' MB'
  return Math.max(1, Math.round(bytes / 1024)) + ' KB'
}
</script>

<style scoped>
.page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 32px 28px 56px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  color: #0f172a;
}
.page-sub {
  margin: 0;
  font-size: 13.5px;
  color: #64748b;
  line-height: 1.7;
}

.pane-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.pane-hint {
  font-size: 13px;
  color: #94a3b8;
}
.progress {
  margin-bottom: 16px;
}

/* ---------- 照片管理网格 ---------- */
.photo-admin-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}
.photo-card {
  border: 1px solid #eef1f5;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  transition: all 0.15s;
}
.photo-card:hover {
  border-color: #dbeafe;
}
/* 隐藏的照片整体压暗，一眼看出主页上不会显示 */
.photo-card.dim {
  opacity: 0.55;
}
.photo-thumb {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  display: block;
  background: #f8fafc;
}
.photo-meta {
  padding: 10px 12px 12px;
}
.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}
.size {
  font-size: 11.5px;
  color: #a8b3c4;
  margin-left: auto;
  margin-right: 4px;
}

/* ---------- 项目列表 ---------- */
.proj-admin-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.proj-row {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 12px 16px;
  border: 1px solid #eef1f5;
  border-radius: 12px;
  background: #fff;
}
.proj-row.dim {
  opacity: 0.55;
}
.proj-row-cover {
  width: 108px;
  height: 68px;
  flex-shrink: 0;
  border-radius: 8px;
  overflow: hidden;
  background: #f8fafc;
}
.proj-row-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.cover-empty {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #cbd5e1;
}
.proj-row-body {
  flex: 1;
  min-width: 0;
}
.proj-row-title {
  font-size: 14.5px;
  font-weight: 600;
  color: #0f172a;
  display: flex;
  align-items: center;
  gap: 8px;
}
.proj-row-desc {
  font-size: 13px;
  color: #64748b;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.proj-row-tech {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.proj-row-actions {
  flex-shrink: 0;
}

/* ---------- 弹窗里的封面上传 ---------- */
.cover-picker {
  width: 100%;
}
.cover-preview {
  display: flex;
  align-items: center;
  gap: 12px;
}
.cover-preview img {
  width: 180px;
  height: 112px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid #eef1f5;
}
.table {
  border-radius: 12px;
}
.tabs {
  margin-top: 8px;
}
</style>
