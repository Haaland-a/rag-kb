<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1>关于我</h1>
        <p class="sub">
          这里改的是主页「关于我」区块。保存后立刻生效，不用重启服务。
        </p>
      </div>
      <div class="head-actions">
        <el-tag v-if="fromConfig" type="warning" effect="plain" size="small">
          当前是配置文件里的默认内容
        </el-tag>
        <el-button v-if="fromConfig" text @click="reset">恢复默认</el-button>
      </div>
    </header>

    <el-alert
      v-if="fromConfig"
      type="info"
      :closable="false"
      show-icon
      class="tip"
      title="你还没在后台编辑过，现在显示的是 application-prod.yml 里配的内容。第一次保存后会存到数据库，之后都以数据库为准。"
    />

    <el-form v-loading="loading" label-position="top" class="form">
      <!-- ---------- 基本信息 ---------- -->
      <section class="card">
        <h2 class="card-title">基本信息</h2>

        <el-form-item label="头像">
          <div class="avatar-row">
            <div class="avatar-preview">
              <img v-if="form.avatarUrl" :src="form.avatarUrl" alt="头像" />
              <span v-else>{{ form.avatarText || 'H' }}</span>
            </div>
            <div class="avatar-actions">
              <input
                ref="avatarInput"
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                hidden
                @change="onAvatarPicked"
              />
              <el-button :loading="avatarUploading" @click="avatarInput?.click()">
                上传照片
              </el-button>
              <el-button v-if="form.avatarUrl" text type="danger" @click="clearAvatar">
                用文字头像
              </el-button>
              <p class="hint">
                支持 jpg / png / webp / gif，8MB 以内。方形图效果最好，
                页面会按圆形裁切显示。
              </p>
            </div>
          </div>
        </el-form-item>

        <div class="grid-2">
          <el-form-item label="名字">
            <el-input v-model="form.owner" maxlength="40" show-word-limit placeholder="Demo" />
          </el-form-item>
          <el-form-item label="头像文字">
            <el-input
              v-model="form.avatarText"
              maxlength="4"
              show-word-limit
              placeholder="H"
            />
            <p class="hint">没有上传照片时，用这个字代替。一到两个字符最好看。</p>
          </el-form-item>
        </div>
        <el-form-item label="一句话介绍">
          <el-input
            v-model="form.tagline"
            maxlength="120"
            show-word-limit
            placeholder="记录、思考与分享"
          />
        </el-form-item>
        <el-form-item label="自我介绍">
          <el-input
            v-model="form.intro"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="这里是我的个人空间……"
          />
        </el-form-item>
        <el-form-item label="标签">
          <div class="tags">
            <el-tag
              v-for="(t, i) in form.tags"
              :key="i"
              closable
              type="info"
              effect="plain"
              @close="form.tags.splice(i, 1)"
            >
              {{ t }}
            </el-tag>
            <el-input
              v-if="tagInput"
              ref="tagRef"
              v-model="tagDraft"
              size="small"
              class="tag-input"
              maxlength="20"
              @keyup.enter="commitTag"
              @blur="commitTag"
            />
            <el-button v-else size="small" text @click="startTag">+ 添加标签</el-button>
          </div>
        </el-form-item>
      </section>

      <!-- ---------- 关于我段落 ---------- -->
      <section class="card">
        <h2 class="card-title">关于我段落</h2>
        <p class="hint">每一条会渲染成一个段落，可以放多段介绍、经历、正在做的事。</p>
        <div v-for="(p, i) in form.about" :key="i" class="para-row">
          <el-input
            v-model="form.about[i]"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
          />
          <el-button text type="danger" @click="form.about.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="form.about.push('')">+ 加一段</el-button>
      </section>

      <!-- ---------- 外链 ---------- -->
      <section class="card">
        <h2 class="card-title">外链</h2>
        <p class="hint">
          只支持 http:// / https:// / mailto: 开头的地址（javascript: 会被拒绝）。
        </p>
        <div v-for="(l, i) in form.links" :key="i" class="link-row">
          <el-input v-model="form.links[i].label" placeholder="显示名，如 GitHub" maxlength="40" />
          <el-input v-model="form.links[i].url" placeholder="https://github.com/xxx" maxlength="500" />
          <el-button text type="danger" @click="form.links.splice(i, 1)">删除</el-button>
        </div>
        <el-button size="small" @click="form.links.push({ label: '', url: '' })">
          + 加一个链接
        </el-button>
      </section>

      <div class="actions">
        <el-button @click="load">放弃修改</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { profileApi } from '@/api'

const loading = ref(false)
const saving = ref(false)
const fromConfig = ref(false)

const avatarInput = ref(null)
const avatarUploading = ref(false)

const form = reactive({
  owner: '',
  avatarText: '',
  avatarUrl: '',
  tagline: '',
  intro: '',
  about: [],
  links: [],
  tags: []
})

// 标签输入的小交互：点「+ 添加」才出现输入框
const tagInput = ref(false)
const tagDraft = ref('')
const tagRef = ref(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const data = await profileApi.get()
    if (!data) return
    form.owner = data.owner || ''
    form.avatarText = data.avatarText || ''
    form.avatarUrl = data.avatarUrl || ''
    form.tagline = data.tagline || ''
    form.intro = data.intro || ''
    form.about = [...(data.about || [])]
    form.links = (data.links || []).map((l) => ({ label: l.label || '', url: l.url || '' }))
    form.tags = [...(data.tags || [])]
    fromConfig.value = !!data.fromConfig
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  // 前端先挡一道明显的空链接，省一次往返
  for (const l of form.links) {
    if (!l.label.trim() || !l.url.trim()) {
      ElMessage.warning('外链的显示名和地址都要填，空的请删掉')
      return
    }
  }
  saving.value = true
  try {
    const data = await profileApi.save({
      owner: form.owner,
      avatarText: form.avatarText,
      avatarUrl: form.avatarUrl,
      tagline: form.tagline,
      intro: form.intro,
      about: form.about.filter((p) => p && p.trim()),
      links: form.links,
      tags: form.tags
    })
    fromConfig.value = !!(data && data.fromConfig)
    ElMessage.success('已保存，主页刷新就能看到')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function reset() {
  try {
    await ElMessageBox.confirm(
      '会删掉你在后台编辑的内容，恢复成配置文件里的默认值。确定吗？',
      '恢复默认',
      { type: 'warning', confirmButtonText: '确定恢复', cancelButtonText: '算了' }
    )
  } catch {
    return
  }
  try {
    const data = await profileApi.reset()
    if (data) {
      form.owner = data.owner || ''
      form.avatarText = data.avatarText || ''
      form.avatarUrl = data.avatarUrl || ''
      form.tagline = data.tagline || ''
      form.intro = data.intro || ''
      form.about = [...(data.about || [])]
      form.links = (data.links || []).map((l) => ({ label: l.label, url: l.url }))
      form.tags = [...(data.tags || [])]
      fromConfig.value = !!data.fromConfig
    }
    ElMessage.success('已恢复成配置文件里的内容')
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  }
}

async function startTag() {
  tagInput.value = true
  await nextTick()
  tagRef.value?.focus()
}

function commitTag() {
  const t = tagDraft.value.trim()
  if (t && !form.tags.includes(t)) form.tags.push(t)
  tagDraft.value = ''
  tagInput.value = false
}

/**
 * 选完图就上传，拿到 url 填进 form.avatarUrl。
 *
 * ⚠️ 这里**不自动保存**：站主可以连传几张挑一张，点「保存」才生效。
 * 如果上传即写库，中间那几张都会短暂出现在主页上。
 */
async function onAvatarPicked(e) {
  const file = e.target.files && e.target.files[0]
  // 允许连续选同一个文件：不清空的话第二次选同一张不会触发 change
  e.target.value = ''
  if (!file) return
  if (!/^image\/(jpeg|png|webp|gif)$/.test(file.type)) {
    ElMessage.warning('只支持 jpg / png / webp / gif')
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 8MB')
    return
  }
  avatarUploading.value = true
  try {
    const res = await profileApi.uploadAvatar(file)
    form.avatarUrl = res?.url || ''
    ElMessage.success('图片已上传，记得点保存')
  } catch (err) {
    ElMessage.error(err.message || '上传失败')
  } finally {
    avatarUploading.value = false
  }
}

function clearAvatar() {
  form.avatarUrl = ''
  ElMessage.info('已切回文字头像，记得点保存')
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
.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tip {
  border-radius: 8px;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 10px;
  padding: 18px 20px;
}
.card-title {
  margin: 0 0 4px;
  font-size: 15px;
  color: #1e293b;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 12px;
}
.avatar-row {
  display: flex;
  align-items: flex-start;
  gap: 18px;
  flex-wrap: wrap;
}
.avatar-preview {
  width: 96px;
  height: 96px;
  flex: 0 0 96px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-size: 34px;
  font-weight: 600;
  border: 3px solid #fff;
  box-shadow: 0 0 0 1px #eef0f4;
}
.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding-top: 6px;
}
.avatar-actions .el-button + .el-button {
  margin-left: 0;
}
.avatar-actions .hint {
  max-width: 320px;
}
.hint {
  margin: 5px 0 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.6;
}
.tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.tag-input {
  width: 120px;
}
.para-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 10px;
}
.para-row .el-button {
  flex-shrink: 0;
  margin-top: 4px;
}
.link-row {
  display: grid;
  grid-template-columns: 1fr 2fr auto;
  gap: 8px;
  margin-bottom: 10px;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-bottom: 20px;
}
@media (max-width: 640px) {
  .grid-2,
  .link-row {
    grid-template-columns: 1fr;
  }
}
</style>
