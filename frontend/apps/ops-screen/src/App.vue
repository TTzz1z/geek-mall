<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import {
  PhArrowsOut as ArrowsOut,
  PhCaretDown as CaretDown,
  PhLightning as Lightning,
  PhPackage as Package,
  PhPulse as Pulse,
  PhSealWarning as SealWarning,
  PhTrendUp as TrendUp,
  PhWarningCircle as WarningCircle,
  PhHeadset as Headset
} from '@phosphor-icons/vue'
import { opsApi } from './api'

type Overview = Record<string, number>
type CategoryRow = { dimension: string; value: number }
type RiskItem = { key: string; title: string; hint: string; value: string; tone: 'warn' | 'danger' | 'alert' }

const trendEl = ref<HTMLElement>()
const categoryEl = ref<HTMLElement>()
const overview = ref<Overview>({})
const categories = ref<CategoryRow[]>([])
const previous = ref<Overview>({})
const rangeDays = ref(7)
const rangeOpen = ref(false)
const rangeOptions = [
  { days: 1, label: '最近 24 小时' },
  { days: 7, label: '最近 7 天' },
  { days: 30, label: '最近 30 天' }
]
const rangeLabel = computed(() => rangeOptions.find(o => o.days === rangeDays.value)?.label || '最近 7 天')
const metricSeries = ref<Array<{ date: string; gmv: number; orders: number; refund: number }>>([])
const updatedAt = ref('同步中')
const online = ref(true)
const authReady = ref(Boolean(localStorage.getItem('geek.ops.accessToken')))
const loginUser = ref('')
const loginPass = ref('')
const loginError = ref('')
const loggingIn = ref(false)
const adminBase = import.meta.env.VITE_ADMIN_BASE_URL ?? 'http://localhost:8089'

let charts: echarts.ECharts[] = []
let timer: number | undefined

const CATEGORY_LABELS: Record<string, string> = {
  PHONE: '数码 3C',
  PC: '智能家居',
  ACCESSORY: '极客周边',
  WEARABLE: '其他'
}
const DONUT_COLORS = ['#ff6a2b', '#3b82f6', '#22c55e', '#6b7280']

function money(v: unknown) {
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '—'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

function num(v: unknown) {
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '—'
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

function pctRate(v: unknown) {
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(1)}%`
}

function deltaOf(code: string, asRate = false) {
  const cur = Number(overview.value[code])
  const prev = Number(previous.value[code])
  if (!Number.isFinite(cur) || !Number.isFinite(prev) || prev === 0) {
    return { text: '暂无对比', tone: 'flat' as const }
  }
  const raw = asRate ? (cur - prev) * 100 : ((cur - prev) / Math.abs(prev)) * 100
  const sign = raw > 0 ? '+' : ''
  return {
    text: `${sign}${raw.toFixed(1)}%`,
    tone: raw >= 0 ? ('up' as const) : ('down' as const)
  }
}

const kpis = computed(() => [
  {
    label: '实时成交额',
    value: `¥ ${money(overview.value.GMV)}`,
    delta: deltaOf('GMV'),
    compare: 'vs 昨日同期'
  },
  {
    label: '支付订单数',
    value: num(overview.value.ORDER_COUNT),
    delta: deltaOf('ORDER_COUNT'),
    compare: 'vs 昨日同期'
  },
  {
    label: '当前活跃用户',
    value: num(overview.value.PAID_USERS),
    delta: deltaOf('PAID_USERS'),
    compare: 'vs 昨日同期'
  },
  {
    label: 'AI 推荐转化率',
    value: pctRate(overview.value.AI_CONVERT_RATE),
    delta: deltaOf('AI_CONVERT_RATE', true),
    compare: 'vs 昨日同期'
  }
])

const liveRisks = ref({ lowStock: 0, slaBreach: 0, loaded: false })

const risks = computed<RiskItem[]>(() => {
  const ov = overview.value
  const series = metricSeries.value
  const refund = Number(ov.REFUND_AMOUNT ?? series.reduce((s, r) => s + r.refund, 0))
  const gmv = Number(ov.GMV ?? 0)
  const refundRate = gmv > 0 ? refund / gmv : 0
  const lowStockSignal = liveRisks.value.lowStock
  const slaSignal = liveRisks.value.slaBreach
  return [
    {
      key: 'stock',
      title: '热销品库存不足',
      hint: liveRisks.value.loaded
        ? `实时聚合 inventory_sku_stock（available≤warning）`
        : '正在拉取库存聚合…',
      value: String(lowStockSignal),
      tone: 'warn'
    },
    {
      key: 'refund',
      title: '大额或高频退款',
      hint: `退款累计 ¥${money(refund)} · 占比 ${(refundRate * 100).toFixed(1)}%`,
      value: String(Math.max(0, Math.round(refund > 0 ? Math.min(99, refund / 1000) : 0))),
      tone: refundRate > 0.08 ? 'danger' : 'warn'
    },
    {
      key: 'sla',
      title: '客服响应 SLA 告警',
      hint: liveRisks.value.loaded
        ? `实时聚合 service_ticket（未关闭且超过 sla_due_at）`
        : '正在拉取工单聚合…',
      value: String(slaSignal),
      tone: 'alert'
    }
  ]
})

async function loginAdmin() {
  loggingIn.value = true
  loginError.value = ''
  try {
    if (!loginUser.value.trim() || !loginPass.value) {
      throw new Error('请输入管理员用户名和密码')
    }
    const tokens = await opsApi.request<{ accessToken: string; refreshToken: string }>('/api/v1/auth/login/password', {
      method: 'POST',
      body: JSON.stringify({ username: loginUser.value.trim(), password: loginPass.value })
    })
    localStorage.setItem('geek.ops.accessToken', tokens.accessToken)
    localStorage.setItem('geek.ops.refreshToken', tokens.refreshToken)
    loginPass.value = ''
    authReady.value = true
    await loadDashboard()
  } catch (e) {
    authReady.value = false
    loginError.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loggingIn.value = false
  }
}

function logoutAdmin() {
  localStorage.removeItem('geek.ops.accessToken')
  localStorage.removeItem('geek.ops.refreshToken')
  authReady.value = false
  online.value = false
}

function isoDaysAgo(days: number) {
  const d = new Date()
  d.setDate(d.getDate() - days)
  return d.toISOString().slice(0, 10)
}

function yesterdayIso() {
  return isoDaysAgo(1)
}

async function loadLiveRisks() {
  try {
    const [stock, sla] = await Promise.all([
      opsApi.request<{ lowStockSkuCount: number }>('/api/v1/admin/inventory/low-stock-summary'),
      opsApi.request<{ slaBreachCount: number }>('/api/v1/admin/service/sla-breach-summary')
    ])
    liveRisks.value = {
      lowStock: Number(stock?.lowStockSkuCount ?? 0),
      slaBreach: Number(sla?.slaBreachCount ?? 0),
      loaded: true
    }
  } catch {
    liveRisks.value = { lowStock: 0, slaBreach: 0, loaded: true }
  }
}

async function loadPrevious() {
  try {
    const y = yesterdayIso()
    const rows = await opsApi.request<Array<{ metricCode: string; dimension: string; metricValue: number }>>(
      `/api/v1/admin/analytics/metrics?from=${y}&to=${y}`
    )
    const map: Overview = {}
    for (const row of rows || []) {
      if (String(row.dimension) === 'ALL') map[String(row.metricCode)] = Number(row.metricValue)
    }
    previous.value = map
  } catch {
    previous.value = {}
  }
}

async function loadTrend() {
  const from = isoDaysAgo(Math.max(0, rangeDays.value - 1))
  const to = isoDaysAgo(0)
  const rows = await opsApi.request<Array<{ metricDate: string; metricCode: string; dimension: string; metricValue: number }>>(
    `/api/v1/admin/analytics/metrics?from=${from}&to=${to}`
  )
  const byDate = new Map<string, { date: string; gmv: number; orders: number; refund: number }>()
  for (const row of rows || []) {
    if (String(row.dimension) !== 'ALL') continue
    const key = String(row.metricDate).slice(0, 10)
    const bucket = byDate.get(key) || { date: key, gmv: 0, orders: 0, refund: 0 }
    const code = String(row.metricCode)
    const val = Number(row.metricValue) || 0
    if (code === 'GMV') bucket.gmv = val
    if (code === 'ORDER_COUNT') bucket.orders = val
    if (code === 'REFUND_AMOUNT') bucket.refund = val
    byDate.set(key, bucket)
  }
  metricSeries.value = [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date))
  renderTrendSeries(metricSeries.value)
}

function renderTrendSeries(seriesRows: Array<{ date: string; gmv: number }>) {
  if (!trendEl.value) return
  const chart = charts[0] || echarts.init(trendEl.value)
  if (!charts[0]) charts[0] = chart
  const labels = seriesRows.length ? seriesRows.map(r => r.date.slice(5)) : ['暂无数据']
  const series = seriesRows.length ? seriesRows.map(r => Math.round(Number(r.gmv) / 10000)) : [0]
  chart.setOption({
    grid: { left: 44, right: 18, top: 28, bottom: 36 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(17,24,39,.92)',
      borderColor: '#374151',
      textStyle: { color: '#e5e7eb' }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: labels,
      axisLine: { lineStyle: { color: '#2a3441' } },
      axisTick: { show: false },
      axisLabel: { color: '#6b7280', fontSize: 12 }
    },
    yAxis: {
      type: 'value',
      splitNumber: 4,
      axisLabel: { color: '#6b7280', fontSize: 12 },
      splitLine: { lineStyle: { color: '#1f2937', type: 'dashed' } }
    },
    series: [{
      type: 'line',
      smooth: true,
      symbol: 'none',
      data: series,
      lineStyle: { color: '#ff6a2b', width: 3 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(255,106,43,.35)' },
          { offset: 1, color: 'rgba(255,106,43,0)' }
        ])
      }
    }]
  })
}

async function setRange(days: number) {
  rangeDays.value = days
  rangeOpen.value = false
  try {
    await loadTrend()
  } catch {
    metricSeries.value = []
    renderTrendSeries([])
  }
}

function renderCategory(rows: CategoryRow[]) {
  if (!categoryEl.value) return
  const chart = charts[1] || echarts.init(categoryEl.value)
  if (!charts[1]) charts[1] = chart
  const data = (rows.length ? rows : [
    { dimension: 'PHONE', value: 1 },
    { dimension: 'PC', value: 1 },
    { dimension: 'ACCESSORY', value: 1 },
    { dimension: 'WEARABLE', value: 1 }
  ]).slice(0, 4).map((row, i) => ({
    name: CATEGORY_LABELS[row.dimension] || row.dimension,
    value: Number(row.value) || 0,
    itemStyle: { color: DONUT_COLORS[i % DONUT_COLORS.length] }
  }))
  chart.setOption({
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(17,24,39,.92)',
      borderColor: '#374151',
      textStyle: { color: '#e5e7eb' },
      formatter: '{b}: {c} ({d}%)'
    },
    series: [{
      type: 'pie',
      radius: ['58%', '78%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      label: { show: false },
      labelLine: { show: false },
      data,
      itemStyle: { borderColor: '#151b22', borderWidth: 3 }
    }],
    graphic: [{
      type: 'text',
      left: 'center',
      top: '42%',
      style: {
        text: `${data.length} 核心品类`,
        fill: '#9ca3af',
        fontSize: 13,
        fontWeight: 600,
        textAlign: 'center'
      }
    }]
  })
}

async function loadDashboard() {
  if (!localStorage.getItem('geek.ops.accessToken')) {
    authReady.value = false
    online.value = false
    return
  }
  try {
    const data = await opsApi.request<{
      overview: Overview
      categories: CategoryRow[]
      updatedAt: string
    }>('/api/v1/admin/analytics/dashboard')
    overview.value = data.overview || {}
    categories.value = data.categories || []
    online.value = true
    authReady.value = true
    updatedAt.value = new Date(data.updatedAt || Date.now()).toLocaleTimeString('zh-CN', { hour12: false })
    renderCategory(categories.value)
    await Promise.all([loadPrevious(), loadTrend(), loadLiveRisks()])
  } catch {
    online.value = false
    updatedAt.value = '离线'
  }
}

function resize() {
  charts.forEach(c => c?.resize())
}

async function fullscreen() {
  if (!document.fullscreenElement) await document.documentElement.requestFullscreen()
  else await document.exitFullscreen()
}

onMounted(async () => {
  if (trendEl.value) charts[0] = echarts.init(trendEl.value)
  if (categoryEl.value) charts[1] = echarts.init(categoryEl.value)
  window.addEventListener('resize', resize)
  await loadDashboard()
  timer = window.setInterval(loadDashboard, 10_000)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (timer) window.clearInterval(timer)
  charts.forEach(c => c?.dispose())
})
</script>

<template>
  <main class="ops">
    <section v-if="!authReady" class="ops-login">
      <h1>GEEK LIVE</h1>
      <p>请使用管理员账号登录后查看经营驾驶舱</p>
      <label>用户名 <input v-model="loginUser" autocomplete="username" /></label>
      <label>密码 <input v-model="loginPass" type="password" autocomplete="current-password" /></label>
      <button type="button" :disabled="loggingIn" @click="loginAdmin">登录</button>
      <p v-if="loginError" class="ops-login-error">{{ loginError }}</p>
    </section>
    <template v-else>
    <header class="ops-top">
      <div class="ops-brand">
        <span class="ops-logo">GEEK <b>LIVE</b></span>
        <small>极客商城实时经营驾驶舱</small>
      </div>
      <div class="ops-live" :class="{ offline: !online }">
        <i class="ops-dot" />
        <Pulse :size="15" weight="bold" />
        <span>{{ online ? `聚合数据 · 实时更新` : '聚合数据 · 离线' }}</span>
        <em v-if="online">{{ updatedAt }}</em>
      </div>
      <nav class="ops-actions">
        <div class="ops-range-wrap">
          <button type="button" class="ops-range" @click="rangeOpen = !rangeOpen">
            {{ rangeLabel }}
            <CaretDown :size="14" weight="bold" />
          </button>
          <div v-if="rangeOpen" class="ops-range-menu">
            <button
              v-for="opt in rangeOptions"
              :key="opt.days"
              type="button"
              :class="{ active: opt.days === rangeDays }"
              @click="setRange(opt.days)"
            >{{ opt.label }}</button>
          </div>
        </div>
        <button type="button" class="ops-icon" aria-label="退出" @click="logoutAdmin">退出</button>
        <button type="button" class="ops-icon" aria-label="全屏" @click="fullscreen">
          <ArrowsOut :size="18" weight="bold" />
        </button>
      </nav>
    </header>

    <section class="kpi-row">
      <article v-for="item in kpis" :key="item.label" class="kpi-card">
        <small>{{ item.label }}</small>
        <strong>{{ item.value }}</strong>
        <footer>
          <span class="kpi-delta" :class="item.delta.tone">{{ item.delta.text }}</span>
          <em>{{ item.compare }}</em>
        </footer>
      </article>
    </section>

    <section class="ops-grid">
      <article class="panel trend-panel">
        <header>
          <h2><TrendUp :size="18" weight="bold" />成交趋势</h2>
          <span>单位：万元</span>
        </header>
        <div ref="trendEl" class="chart"></div>
      </article>

      <article class="panel category-panel">
        <header>
          <h2><Lightning :size="18" weight="fill" />品类贡献</h2>
          <span>成交指数</span>
        </header>
        <div ref="categoryEl" class="chart chart-donut"></div>
        <ul class="cat-legend">
          <li v-for="(row, i) in (categories.length ? categories : [{ dimension: 'PHONE', value: 0 }, { dimension: 'PC', value: 0 }, { dimension: 'ACCESSORY', value: 0 }, { dimension: 'WEARABLE', value: 0 }]).slice(0, 4)" :key="row.dimension">
            <i :style="{ background: DONUT_COLORS[i % DONUT_COLORS.length] }" />
            {{ CATEGORY_LABELS[row.dimension] || row.dimension }}
          </li>
        </ul>
      </article>

      <article class="panel risk-panel">
        <header>
          <h2><WarningCircle :size="18" weight="fill" />风险雷达</h2>
          <span>实时拦截</span>
        </header>
        <ul class="risk-list">
          <li v-for="item in risks" :key="item.key" :class="`tone-${item.tone}`">
            <span class="risk-icon">
              <Package v-if="item.key === 'stock'" :size="18" weight="fill" />
              <SealWarning v-else-if="item.key === 'refund'" :size="18" weight="fill" />
              <Headset v-else :size="18" weight="fill" />
            </span>
            <div>
              <b>{{ item.title }}</b>
              <small>{{ item.hint }}</small>
            </div>
            <strong>{{ item.value }}</strong>
          </li>
        </ul>
        <a class="risk-link" :href="`${adminBase}/inventory`" target="_blank" rel="noreferrer">
          查看完整风控报告 →
        </a>
      </article>
    </section>
    </template>
  </main>
</template>
