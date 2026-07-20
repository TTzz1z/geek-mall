<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise, PhClock as Clock } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { useSessionStore } from '../session'

const session = useSessionStore()
const rows = ref<any[]>([])
const status = ref('OPEN')
const loading = ref(false)
const now = ref(Date.now())
let timer: number | undefined

const CATEGORY_LABELS: Record<string, string> = {
  REFUND: '退款授权',
  REFUND_CONFIRM: '退款确权',
  LOGISTICS: '物流延时',
  COMPLAINT: '投诉纠纷',
  PRODUCT: '商品咨询',
  OTHER: '其他'
}

const statusOptions = [
  { label: '状态：全部', value: '' },
  { label: '状态：待处理', value: 'OPEN' },
  { label: '状态：处理中', value: 'PROCESSING' },
  { label: '状态：已完结', value: 'RESOLVED' }
]

async function load() {
  loading.value = true
  try {
    const q = status.value ? `?status=${encodeURIComponent(status.value)}` : ''
    rows.value = await adminApi.request(`/api/v1/admin/service/tickets${q}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

function categoryLabel(code?: string) {
  if (!code) return '—'
  return CATEGORY_LABELS[code] || code
}

function priorityTone(priority?: string) {
  const p = String(priority || '').toUpperCase()
  if (p === 'URGENT' || p === 'P0') return 'urgent'
  if (p === 'HIGH' || p === 'P1') return 'high'
  if (p === 'LOW' || p === 'P3') return 'low'
  return 'normal'
}

function priorityText(priority?: string) {
  const p = String(priority || 'NORMAL').toUpperCase()
  if (p === 'P0') return 'URGENT'
  if (p === 'P1') return 'HIGH'
  if (p === 'P2') return 'NORMAL'
  if (p === 'P3') return 'LOW'
  return p
}

function slaMs(row: any) {
  if (!row?.slaDueAt) return null
  const due = new Date(row.slaDueAt).getTime()
  if (Number.isNaN(due)) return null
  return due - now.value
}

function slaTone(row: any) {
  const ms = slaMs(row)
  if (ms == null) return 'neutral'
  if (ms <= 0) return 'critical'
  if (ms <= 30 * 60 * 1000) return 'critical'
  if (ms <= 4 * 60 * 60 * 1000) return 'warn'
  return 'ok'
}

function slaText(row: any) {
  const ms = slaMs(row)
  if (ms == null) return '—'
  if (ms <= 0) return '已超时'
  const mins = Math.floor(ms / 60000)
  if (mins < 60) return `剩余 ${mins} 分钟`
  const hours = Math.floor(mins / 60)
  if (hours < 48) return `剩余 ${hours} 小时`
  return `剩余 ${Math.floor(hours / 24)} 天`
}

async function assign(ticketNo: string) {
  const assigneeId = session.principal?.userId
  if (!assigneeId) return
  try {
    await adminApi.request(
      `/api/v1/admin/service/tickets/${ticketNo}/assign?assigneeId=${assigneeId}`,
      { method: 'POST' }
    )
    ElMessage.success('已领取')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '领取失败')
  }
}

async function handleNow(row: any) {
  try {
    if (row.status === 'OPEN') {
      const assigneeId = session.principal?.userId
      if (!assigneeId) return
      await adminApi.request(
        `/api/v1/admin/service/tickets/${row.ticketNo}/assign?assigneeId=${assigneeId}`,
        { method: 'POST' }
      )
    }
    const { value } = await ElMessageBox.prompt('处理备注', '立即处理', {
      inputValue: '已处理并闭环',
      inputPattern: /\S+/,
      confirmButtonText: '完结工单'
    })
    await adminApi.request(
      `/api/v1/admin/service/tickets/${row.ticketNo}/resolve?remark=${encodeURIComponent(value)}`,
      { method: 'POST' }
    )
    ElMessage.success('已完结')
    await load()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e instanceof Error ? e.message : '处理失败')
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(() => { now.value = Date.now() }, 30000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <section class="commerce-page ticket-page" v-loading="loading">
    <header class="ticket-heading">
      <h2>客服工单</h2>
      <el-button class="ticket-refresh" @click="load">
        <ArrowsClockwise :size="15" weight="bold" />
        刷新数据
      </el-button>
    </header>

    <div class="ops-card ticket-card">
      <header class="ticket-card-head">
        <div>
          <p>SERVICE DESK</p>
          <h3>客服工单 (SLA 监控)</h3>
        </div>
        <el-select v-model="status" class="ticket-status-filter" @change="load">
          <el-option v-for="opt in statusOptions" :key="opt.value || 'all'" :label="opt.label" :value="opt.value" />
        </el-select>
      </header>

      <el-table :data="rows" class="workspace-table ticket-table" empty-text="暂无工单">
        <el-table-column label="工单号" width="130">
          <template #default="{ row }">
            <span class="mono ticket-no">{{ row.ticketNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="110">
          <template #default="{ row }">
            <span class="ticket-priority" :class="`tone-${priorityTone(row.priority)}`">
              {{ priorityText(row.priority) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="业务线" width="120">
          <template #default="{ row }">
            <span class="ticket-line">{{ categoryLabel(row.category) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="工单标题" min-width="220">
          <template #default="{ row }">
            <span class="ticket-title" :title="row.title">{{ row.title || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="SLA 倒计时" width="150">
          <template #default="{ row }">
            <span class="ticket-sla" :class="`tone-${slaTone(row)}`">
              <Clock :size="14" weight="bold" />
              {{ slaText(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <button
              v-if="row.status === 'OPEN' || row.status === 'PROCESSING'"
              type="button"
              class="ticket-action is-primary"
              @click="handleNow(row)"
            >
              立即处理
            </button>
            <button
              v-else-if="row.status === 'OPEN'"
              type="button"
              class="ticket-action"
              @click="assign(row.ticketNo)"
            >
              领取
            </button>
            <button
              v-else-if="row.status === 'OPEN'"
              type="button"
              class="ticket-action"
              @click="assign(row.ticketNo)"
            >
              领取
            </button>
            <span v-else class="ticket-done">已完结</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>
