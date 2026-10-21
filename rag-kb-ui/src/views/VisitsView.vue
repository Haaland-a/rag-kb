<template>
  <div class="visits">
    <div class="page-head">
      <h3 class="page-title">访客记录</h3>
      <p class="page-sub">
        所有访问过你站点的人。归属地是后台异步补的（每 IP 一次，不拖慢访客）
      </p>
    </div>

    <!-- 概览卡 -->
    <div class="cards">
      <div class="card">
        <div class="card-label">总访问次数</div>
        <div class="card-value">{{ stats.totalVisits ?? 0 }}</div>
      </div>
      <div class="card">
        <div class="card-label">独立 IP</div>
        <div class="card-value">{{ stats.uniqueIps ?? 0 }}</div>
      </div>
      <div class="card">
        <div class="card-label">其中提问次数</div>
        <div class="card-value">{{ askCount }}</div>
      </div>
    </div>

    <el-tabs v-model="tab" class="tabs">
      <!-- ============ 按 IP 聚合 ============ -->
      <el-tab-pane label="谁来过" name="summary">
        <el-table :data="summary" v-loading="loading" border stripe size="small">
          <el-table-column label="IP" width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.ip }}</span>
            </template>
          </el-table-column>
          <el-table-column label="地点" min-width="180">
            <template #default="{ row }">
              <span v-if="row.country || row.region || row.city">
                {{ [row.country, row.region, row.city].filter(Boolean).join(' ') }}
              </span>
              <span v-else class="pending">查询中…</span>
            </template>
          </el-table-column>
          <el-table-column prop="hits" label="访问次数" width="100" align="right" />
          <el-table-column label="最近访问" width="180">
            <template #default="{ row }">{{ formatTime(row.lastSeen) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 原始流水 ============ -->
      <el-tab-pane label="访问流水" name="detail">
        <el-table :data="visits" v-loading="loading" border stripe size="small">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="IP" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.ip }}</span>
            </template>
          </el-table-column>
          <el-table-column label="地点" width="180">
            <template #default="{ row }">
              <span v-if="row.city || row.region || row.country">
                {{ [row.country, row.region, row.city].filter(Boolean).join(' ') }}
              </span>
              <el-tag v-else-if="row.geoStatus === 'FAILED'" size="small" type="info">
                未知
              </el-tag>
              <span v-else class="pending">查询中…</span>
            </template>
          </el-table-column>
          <el-table-column label="路径" width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono path">{{ row.path }}</span>
            </template>
          </el-table-column>
          <el-table-column label="问的问题" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.question">{{ row.question }}</span>
              <span v-else class="pending">—</span>
            </template>
          </el-table-column>
          <el-table-column label="站主" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.admin" size="small" type="warning">本人</el-tag>
              <span v-else class="pending">—</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            :page-size="size"
            :total="total"
            layout="total, prev, pager, next"
            @current-change="loadDetail"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { visitApi } from '@/api'

const tab = ref('summary')
const loading = ref(false)

const visits = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const summary = ref([])
const stats = ref({ totalVisits: 0, uniqueIps: 0 })

const askCount = computed(() => visits.value.filter((v) => v.question).length)

onMounted(async () => {
  await Promise.all([loadSummary(), loadStats()])
  await loadDetail()
})

// 切到流水标签时才拉详细列表，省一次请求
watch(tab, (v) => {
  if (v === 'detail' && !visits.value.length) loadDetail()
})

async function loadSummary() {
  try {
    const res = await visitApi.summary(100)
    summary.value = res || []
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadStats() {
  try {
    const res = await visitApi.stats()
    stats.value = res || { totalVisits: 0, uniqueIps: 0 }
  } catch {
    /* 统计失败不影响列表 */
  }
}

async function loadDetail() {
  loading.value = true
  try {
    const offset = (page.value - 1) * size.value
    const res = await visitApi.list(size.value, offset)
    visits.value = res?.items || []
    total.value = res?.total || 0
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 后端返回的是 UTC 时间（OffsetDateTime 序列化为 ISO 字符串），浏览器自动按本地时区显示 */
function formatTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<style scoped>
.visits {
  background: #fff;
  border-radius: 12px;
  padding: 22px 24px 28px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
}
.page-head {
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

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.path {
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
.tabs :deep(.el-tabs__item) {
  font-size: 14px;
}
</style>
