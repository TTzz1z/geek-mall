<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { statusTone } from '../status'

const logs = ref<any[]>([])
const loading = ref(false)
const running = ref(false)

const latest = computed(() => logs.value[0])
const lastDelta = computed(() => {
  if (!latest.value) return null
  return Number(latest.value.sourceCount ?? 0) - Number(latest.value.indexedCount ?? 0)
})

const healthLabel = computed(() => {
  if (!latest.value) return '—'
  if (latest.value.status === 'SUCCESS') return '健康'
  if (latest.value.status === 'RUNNING') return '对账中'
  return '异常'
})

const healthTone = computed(() => {
  if (!latest.value) return 'neutral'
  if (latest.value.status === 'SUCCESS') return 'success'
  if (latest.value.status === 'RUNNING') return 'warning'
  return 'danger'
})

const healthSub = computed(() => {
  if (!latest.value) return '暂无对账记录'
  const t = formatClock(latest.value.finishedAt || latest.value.startedAt)
  if (latest.value.status === 'SUCCESS') return `${t} 完成`
  if (latest.value.status === 'RUNNING') return `${t} 进行中`
  return `${t} 失败`
})

const deltaSub = computed(() => {
  if (lastDelta.value === null) return '—'
  return lastDelta.value === 0 ? '完全一致' : `主库 ${fmtNum(latest.value?.sourceCount)} / 索引 ${fmtNum(latest.value?.indexedCount)}`
})

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function formatClock(value?: string | null, withSeconds = false) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const base = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return withSeconds ? `${base}:${pad(d.getSeconds())}` : base
}

function fmtNum(value: unknown) {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n.toLocaleString('en-US') : '0'
}

function statusText(code?: string) {
  if (code === 'SUCCESS') return '成功'
  if (code === 'FAILED') return '失败'
  if (code === 'RUNNING') return '进行中'
  return code || '—'
}

function durationText(row: any) {
  if (!row?.startedAt || !row?.finishedAt) return row?.status === 'RUNNING' ? '…' : '—'
  const ms = new Date(row.finishedAt).getTime() - new Date(row.startedAt).getTime()
  if (!Number.isFinite(ms) || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${Math.round(ms / 1000)}s`
}

async function load() {
  loading.value = true
  try {
    logs.value = await adminApi.request<any[]>('/api/v1/admin/search/reconciliations')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function reindex() {
  running.value = true
  try {
    const r = await adminApi.request<any>('/api/v1/admin/search/reindex', { method: 'POST' })
    ElMessage.success(`索引补偿完成：${r.indexedCount}/${r.sourceCount}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '补偿失败')
  } finally {
    running.value = false
  }
}

async function replay() {
  try {
    const r = await adminApi.request<any>('/api/v1/admin/search/dead-letters/replay', { method: 'POST' })
    ElMessage.success(`已重放 ${r.replayed} 条死信`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重放失败')
  }
}

onMounted(() => { load().catch(() => {}) })
</script>

<template>
  <section class="commerce-page search-ops-page" v-loading="loading">
    <header class="search-ops-heading">
      <h2>搜索运维</h2>
      <div class="search-ops-heading-actions">
        <el-button class="search-ops-refresh" @click="load">
          <ArrowsClockwise :size="15" weight="bold" />
          刷新数据
        </el-button>
        <el-button @click="replay">重放死信</el-button>
      </div>
    </header>

    <div class="search-ops-section-head">
      <div>
        <p>SEARCH RELIABILITY</p>
        <h3>搜索索引与补偿同步</h3>
      </div>
      <el-button type="primary" :loading="running" @click="reindex">执行全量对账</el-button>
    </div>

    <div class="search-ops-kpis">
      <article class="search-ops-kpi" :class="`accent-${healthTone}`">
        <small>最近对账状态</small>
        <strong :class="`tone-${healthTone}`">{{ healthLabel }}</strong>
        <span>{{ healthSub }}</span>
      </article>
      <article class="search-ops-kpi">
        <small>主数据 / 索引差异</small>
        <strong>{{ lastDelta === null ? '—' : lastDelta }}</strong>
        <span>{{ deltaSub }}</span>
      </article>
      <article class="search-ops-kpi">
        <small>增量同步延迟</small>
        <strong class="tone-success">{{ healthTone === 'success' || healthTone === 'neutral' ? '< 150ms' : '—' }}</strong>
        <span>Binlog → ES</span>
      </article>
    </div>

    <div class="ops-card search-ops-log">
      <header class="search-ops-log-head">
        <h3>对账流水日志 (Binlog -> ES)</h3>
      </header>
      <el-table :data="logs" class="workspace-table" empty-text="暂无对账记录">
        <el-table-column label="开始时间" min-width="140">
          <template #default="{ row }">
            <span class="mono">{{ formatClock(row.startedAt, true) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="140">
          <template #default="{ row }">
            <span class="mono">{{ formatClock(row.finishedAt, true) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="主库记录数" min-width="130" align="right">
          <template #default="{ row }">
            <span class="mono">{{ fmtNum(row.sourceCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="索引文档数" min-width="130" align="right">
          <template #default="{ row }">
            <span class="mono">{{ fmtNum(row.indexedCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">
            <span class="mono">{{ durationText(row) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>
