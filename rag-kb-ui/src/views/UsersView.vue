<template>
  <div class="users">
    <div class="page-head">
      <div class="head-left">
        <h3 class="page-title">注册用户</h3>
        <p class="page-sub">
          每个浏览器只能注册一个账号（设备指纹挡的），这里看的是实际注册成功的人
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <!-- 概览卡 -->
    <div class="cards">
      <div class="card">
        <div class="card-label">注册用户总数</div>
        <div class="card-value">{{ data.total ?? 0 }}</div>
      </div>
      <div class="card">
        <div class="card-label">近 7 天新增</div>
        <div class="card-value accent">{{ data.newIn7Days ?? 0 }}</div>
      </div>
      <div class="card">
        <div class="card-label">管理员</div>
        <div class="card-value">{{ data.adminCount ?? 0 }}</div>
      </div>
    </div>

    <el-table :data="data.items || []" v-loading="loading" border stripe size="small">
      <el-table-column label="ID" width="70" align="right">
        <template #default="{ row }">{{ row.id }}</template>
      </el-table-column>

      <el-table-column label="用户名" width="160">
        <template #default="{ row }">
          <span class="uname">{{ row.username }}</span>
          <el-tag v-if="row.admin" size="small" type="warning" class="tag-admin">站主</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="昵称" min-width="130">
        <template #default="{ row }">
          <span v-if="row.displayName">{{ row.displayName }}</span>
          <span v-else class="pending">—</span>
        </template>
      </el-table-column>

      <el-table-column label="注册时间" width="170">
        <template #default="{ row }">{{ row.createdAt || '-' }}</template>
      </el-table-column>

      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">
          <span v-if="row.lastLoginAt">{{ row.lastLoginAt }}</span>
          <span v-else class="pending">从没登录过</span>
        </template>
      </el-table-column>

      <el-table-column label="注册 IP" width="140">
        <template #default="{ row }">
          <span v-if="row.registerIp" class="mono">{{ row.registerIp }}</span>
          <span v-else class="pending">—</span>
        </template>
      </el-table-column>

      <el-table-column label="设备绑定" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.deviceBound" size="small" type="success">已绑定</el-tag>
          <el-tag v-else size="small" type="info">无</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="!loading && !(data.items || []).length"
      description="还没有人注册。留言板首页那个「注册」链接是唯一入口"
      :image-size="90"
    />

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="data.total || 0"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <p class="footnote">
      ⚠️ 这里不显示密码散列，也不显示浏览器指纹原文 —— 前者没什么好看的，
      后者泄露出去等于把别人设备的唯一标识公开。
    </p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api'

const loading = ref(false)
const page = ref(1)
const size = ref(20)
const data = ref({ items: [], total: 0, adminCount: 0, newIn7Days: 0, page: 1, size: 20 })

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await authApi.users(page.value, size.value)
    data.value = res || { items: [], total: 0, adminCount: 0, newIn7Days: 0 }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 改每页条数必须回到第一页，否则会出现「第 5 页但只有 2 页」的空白 */
function onSizeChange() {
  page.value = 1
  load()
}
</script>

<style scoped>
.users {
  background: #fff;
  border-radius: 12px;
  padding: 22px 24px 28px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.head-left {
  min-width: 0;
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

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}
.card {
  background: #f8fafc;
  border: 1px solid #eef0f4;
  border-radius: 9px;
  padding: 14px 16px;
}
.card-label {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 6px;
}
.card-value {
  font-size: 22px;
  font-weight: 600;
  color: #1e293b;
  font-variant-numeric: tabular-nums;
}
.card-value.accent {
  color: #2563eb;
}

.uname {
  font-weight: 500;
  color: #1e293b;
}
.tag-admin {
  margin-left: 6px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: #64748b;
}
.pending {
  color: #cbd5e1;
  font-size: 12px;
}

.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
.footnote {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: #94a3b8;
}
</style>
