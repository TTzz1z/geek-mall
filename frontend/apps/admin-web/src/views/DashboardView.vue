<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  PhArrowsClockwise as ArrowsClockwise,
  PhCalendarBlank as CalendarBlank,
  PhCaretDown as CaretDown,
  PhCaretUp as CaretUp,
  PhStack as Stack
} from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { isMerchantWorkspace } from '../rbac'
import { useSessionStore } from '../session'

type CategoryRow = { dimension: string; value: number | string }
type MetricRow = { metricDate: string; metricCode: string; dimension: string; metricValue: number | string }

const BAR_TONES = ['tone-orange', 'tone-blue', 'tone-violet', 'tone-teal', 'tone-slate']

const session = useSessionStore()
const merchantMode = computed(() => isMerchantWorkspace(session.principal))

const loading = ref(true)
const overview = ref<Record<string, unknown>>({})
const categories = ref<CategoryRow[]>([])
const metrics = ref<MetricRow[]>([])

function num(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function formatValue(code: string, v: unknown) {
  const n = num(v)
  if (n == null) return null
  if (code === 'AI_CONVERT_RATE') return `${(n * 100).toFixed(2)}%`
  if (code === 'ORDER_COUNT' || code === 'PAID_USERS' || code === 'PAY_USER') {
    return Math.round(n).toLocaleString('zh-CN')
  }
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function pickOverview(...keys: string[]) {
  for (const key of keys) {
    if (overview.value[key] != null) return overview.value[key]
  }
  return null
}

function yesterdayValue(code: string) {
  const today = new Date().toISOString().slice(0, 10)
  const rows = metrics.value
    .filter(m => m.metricCode === code && m.dimension === 'ALL' && String(m.metricDate).slice(0, 10) < today)
    .sort((a, b) => String(b.metricDate).localeCompare(String(a.metricDate)))
  return rows[0] ? num(rows[0].metricValue) : null
}

function trendOf(code: string, current: unknown) {
  const cur = num(current)
  const prev = yesterdayValue(code)
  if (cur == null || prev == null || prev === 0) return null
  const pct = ((cur - prev) / Math.abs(prev)) * 100
  return {
    up: pct >= 0,
    text: `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%`
  }
}

const cards = computed(() => {
  const gmv = pickOverview('GMV')
  const orders = pickOverview('ORDER_COUNT')
  const users = pickOverview('PAID_USERS', 'PAY_USER')
  const refund = pickOverview('REFUND', 'REFUND_AMOUNT')
  return [
    {
      key: 'GMV',
      label: 'GMV',
      unit: '元',
      raw: gmv,
      display: formatValue('GMV', gmv),
      empty: '暂无实时数据',
      trend: trendOf('GMV', gmv)
    },
    {
      key: 'ORDER_COUNT',
      label: '订单量',
      unit: '',
      raw: orders,
      display: formatValue('ORDER_COUNT', orders),
      empty: '暂无实时数据',
      trend: trendOf('ORDER_COUNT', orders)
    },
    {
      key: 'PAID_USERS',
      label: '支付用户',
      unit: '',
      raw: users,
      display: formatValue('PAID_USERS', users),
      empty: '暂无实时数据',
      trend: trendOf('PAID_USERS', users)
    },
    {
      key: 'REFUND',
      label: '退款额',
      unit: '元',
      raw: refund,
      display: formatValue('REFUND', refund),
      empty: '待结算同步',
      trend: trendOf('REFUND', refund)
    }
  ]
})

const categoryBars = computed(() => {
  const rows = categories.value.map(c => ({
    dimension: String(c.dimension || ''),
    value: num(c.value) ?? 0
  }))
  const total = rows.reduce((s, r) => s + r.value, 0) || 1
  return rows
    .sort((a, b) => b.value - a.value)
    .map((row, index) => ({
      ...row,
      pct: (row.value / total) * 100,
      tone: BAR_TONES[index % BAR_TONES.length],
      display: formatValue('GMV', row.value) || '0.00'
    }))
})

const metricRows = computed(() =>
  [...metrics.value].sort((a, b) => String(b.metricDate).localeCompare(String(a.metricDate)))
)

function formatDate(value: string) {
  return String(value || '').slice(0, 10) || '—'
}

async function load() {
  loading.value = true
  try {
    if (merchantMode.value) {
      const dash = await adminApi.request<any>('/api/v1/merchant/analytics/dashboard')
      overview.value = dash.overview || {}
      categories.value = dash.categories || []
      metrics.value = await adminApi.request<MetricRow[]>('/api/v1/merchant/analytics/metrics')
    } else {
      const dash = await adminApi.request<any>('/api/v1/admin/analytics/dashboard')
      overview.value = dash.overview || {}
      categories.value = dash.categories || []
      metrics.value = await adminApi.request<MetricRow[]>('/api/v1/admin/analytics/metrics')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="commerce-page dash-page" v-loading="loading">
    <header class="dash-hero">
      <div>
        <h2>{{ merchantMode ? '本店经营总览' : '经营总览' }}</h2>
        <p>
          <span>{{ merchantMode ? 'MERCHANT DASHBOARD' : 'OPS DASHBOARD' }}</span>
          {{ merchantMode
            ? `仅展示商家 ${session.principal?.merchantId ?? ''} 的成交与退款汇总。`
            : '今日核心指标与近 7 日明细，数据来自结算分析库。' }}
        </p>
      </div>
      <button type="button" class="dash-refresh" :disabled="loading" @click="load">
        <ArrowsClockwise :size="16" :class="{ spin: loading }" />
        刷新
      </button>
    </header>

    <div class="dash-kpis">
      <article v-for="card in cards" :key="card.key" class="dash-kpi">
        <small>
          {{ card.label }}
          <em v-if="card.unit">（{{ card.unit }}）</em>
        </small>
        <strong v-if="card.display != null">{{ card.display }}</strong>
        <strong v-else class="is-empty">—</strong>
        <div v-if="card.trend" class="dash-trend" :class="card.trend.up ? 'up' : 'down'">
          <CaretUp v-if="card.trend.up" :size="12" weight="bold" />
          <CaretDown v-else :size="12" weight="bold" />
          {{ card.trend.text }}
        </div>
        <span v-else-if="card.display == null" class="dash-kpi-hint">{{ card.empty }}</span>
        <span v-else class="dash-kpi-hint">当日汇总</span>
      </article>
    </div>

    <section class="dash-panel">
      <header>
        <Stack :size="18" weight="duotone" class="dash-panel-icon" />
        <h3>{{ merchantMode ? '结算状态分布' : '类目 GMV 分布' }}</h3>
      </header>
      <div v-if="categoryBars.length" class="dash-bars">
        <div v-for="row in categoryBars" :key="row.dimension" class="dash-bar-row">
          <span class="dash-bar-name">{{ row.dimension }}</span>
          <div class="dash-bar-track" :aria-label="`${row.dimension} ${row.pct.toFixed(0)}%`">
            <i class="dash-bar-fill" :class="row.tone" :style="{ width: `${Math.max(row.pct, 2)}%` }" />
          </div>
          <span class="dash-bar-pct">{{ row.pct.toFixed(0) }}%</span>
          <strong class="dash-bar-value">{{ row.display }}</strong>
        </div>
      </div>
      <div v-else class="dash-empty">暂无类目维度数据</div>
    </section>

    <section class="dash-panel">
      <header>
        <CalendarBlank :size="18" weight="duotone" class="dash-panel-icon" />
        <h3>近 7 日指标明细</h3>
      </header>
      <el-table :data="metricRows" class="dash-table" empty-text="暂无历史指标">
        <el-table-column label="日期" min-width="120">
          <template #default="{ row }">{{ formatDate(row.metricDate) }}</template>
        </el-table-column>
        <el-table-column label="指标" min-width="160">
          <template #default="{ row }">
            <span class="metric-pill">{{ row.metricCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="dimension" label="维度" min-width="100" />
        <el-table-column label="值" min-width="140" align="right">
          <template #default="{ row }">
            {{ formatValue(row.metricCode, row.metricValue) ?? row.metricValue ?? '—' }}
          </template>
        </el-table-column>
      </el-table>
    </section>
  </section>
</template>
