<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { isMerchantWorkspace } from '../rbac'
import { useSessionStore } from '../session'

const route = useRoute()
const session = useSessionStore()
const isMerchant = computed(() => isMerchantWorkspace(session.principal))
const isOrdersPage = computed(() => route.path === '/orders' || route.path === '/fulfillments')
const isAftersalePage = computed(() => route.path === '/aftersales')

const fulfillments = ref<any[]>([])
const aftersales = ref<any[]>([])
const loading = ref(false)
const dialog = ref(false)
const selected = ref<any>(null)
const approveAmount = ref<number | null>(null)
const shipForm = reactive({
  fulfillmentNo: '',
  carrierCode: 'SF',
  trackingNo: '',
  orderItemId: 0,
  quantity: 1
})

const blocked = computed(() =>
  fulfillments.value.filter((f) => ['PICKING', 'PARTIAL_SHIPPED', 'STOCKOUT_BLOCKED'].includes(String(f.status)))
)

const pendingCount = computed(() => blocked.value.length)

const CARRIER_LABELS: Record<string, string> = {
  SF: '顺丰速运',
  YTO: '圆通速递',
  ZTO: '中通快递',
  YD: '韵达快递',
  JD: '京东物流'
}

function carrierName(code?: string) {
  if (!code) return '顺丰速运'
  return CARRIER_LABELS[code] || code
}

function statusLabel(status?: string) {
  switch (status) {
    case 'PICKING':
    case 'STOCKOUT_BLOCKED':
      return '缺货阻断'
    case 'PARTIAL_SHIPPED':
      return '部分发货'
    case 'SHIPPED':
      return '已发货'
    case 'COMPLETED':
      return '已完成'
    default:
      return status || '—'
  }
}

function statusTone(status?: string) {
  if (status === 'PICKING' || status === 'STOCKOUT_BLOCKED') return 'danger'
  if (status === 'PARTIAL_SHIPPED') return 'warning'
  if (status === 'SHIPPED' || status === 'COMPLETED') return 'success'
  return 'neutral'
}

const loadError = ref('')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    if (isOrdersPage.value || !isAftersalePage.value) {
      const path = isMerchant.value ? '/api/v1/merchant/fulfillments' : '/api/v1/admin/fulfillments'
      try {
        fulfillments.value = await adminApi.request<any[]>(path)
      } catch (e) {
        fulfillments.value = []
        loadError.value = e instanceof Error ? e.message : '履约列表加载失败'
        ElMessage.error(loadError.value)
      }
    }
    if (isAftersalePage.value || isMerchant.value) {
      try {
        aftersales.value = await adminApi.request<any[]>('/api/v1/merchant/aftersales')
      } catch (e) {
        aftersales.value = []
        const msg = e instanceof Error ? e.message : '售后列表加载失败'
        loadError.value = loadError.value ? `${loadError.value}；${msg}` : msg
        ElMessage.error(msg)
      }
    }
  } finally {
    loading.value = false
  }
}

async function openIntervene(row: any) {
  const path = isMerchant.value
    ? `/api/v1/merchant/fulfillments/${row.fulfillmentNo}`
    : `/api/v1/admin/fulfillments/${row.fulfillmentNo}`
  try {
    selected.value = await adminApi.request<any>(path)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载履约明细失败')
    return
  }
  shipForm.fulfillmentNo = row.fulfillmentNo
  shipForm.carrierCode = row.carrierCode || selected.value?.packages?.[0]?.carrierCode || 'SF'
  shipForm.trackingNo = ''
  const first = (selected.value?.items || []).find((i: any) => (i.shippedQty || 0) < (i.quantity || 0))
    || selected.value?.items?.[0]
  if (first) {
    shipForm.orderItemId = first.orderItemId
    shipForm.quantity = Math.max(1, (first.quantity || 1) - (first.shippedQty || 0))
  }
  dialog.value = true
}

async function forceSplitShip() {
  try {
    const path = isMerchant.value
      ? `/api/v1/merchant/fulfillments/${shipForm.fulfillmentNo}/ship`
      : `/api/v1/admin/fulfillments/${shipForm.fulfillmentNo}/force-split-ship`
    await adminApi.request(path, {
      method: 'POST',
      body: JSON.stringify({
        carrierCode: shipForm.carrierCode,
        trackingNo: shipForm.trackingNo,
        lines: [{ orderItemId: shipForm.orderItemId, quantity: shipForm.quantity }]
      })
    })
    ElMessage.success('已执行强制拆单发货')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发货失败')
  }
}

async function approve(no: string) {
  const q = approveAmount.value != null ? `?amount=${approveAmount.value}` : ''
  await adminApi.request(`/api/v1/merchant/aftersales/${no}/approve${q}`, { method: 'POST' })
  ElMessage.success('已通过')
  await load()
}

async function receive(no: string) {
  await adminApi.request(`/api/v1/merchant/aftersales/${no}/receive`, { method: 'POST' })
  ElMessage.success('仓审入库完成')
  await load()
}

watch(() => route.path, load)
onMounted(load)
</script>

<template>
  <section class="commerce-page fulfillment-page" v-loading="loading">
    <template v-if="isOrdersPage">
      <header class="fulfillment-heading">
        <h2>订单履约</h2>
        <el-button class="fulfillment-refresh" @click="load">
          <ArrowsClockwise :size="15" weight="bold" />
          刷新数据
        </el-button>
      </header>
      <el-alert v-if="loadError" type="error" :title="loadError" show-icon :closable="false" style="margin-bottom: 12px" />

      <div class="fulfillment-section-head">
        <h3>订单履约监控</h3>
      </div>

      <div class="ops-card fulfillment-card">
        <header class="fulfillment-card-head">
          <h4>异常阻塞履约单</h4>
          <span class="fulfillment-pending">{{ pendingCount }} 待处理</span>
        </header>
        <el-table :data="blocked" class="workspace-table" empty-text="暂无异常阻塞履约单">
          <el-table-column label="履约单号" min-width="160">
            <template #default="{ row }">
              <span class="mono">{{ row.fulfillmentNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="关联总订单" min-width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.orderNo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="当前状态" width="140" align="center">
            <template #default="{ row }">
              <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="承运商" width="130">
            <template #default="{ row }">{{ carrierName(row.carrierCode) }}</template>
          </el-table-column>
          <el-table-column label="操作介入" width="150" align="right">
            <template #default="{ row }">
              <button type="button" class="fulfillment-intervene" @click="openIntervene(row)">强制拆单发货</button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <template v-else>
      <header class="fulfillment-heading">
        <h2>售后审核</h2>
        <el-button class="fulfillment-refresh" @click="load">
          <ArrowsClockwise :size="15" weight="bold" />
          刷新数据
        </el-button>
      </header>
      <div class="ops-card">
        <header class="fulfillment-card-head">
          <h4>售后工单</h4>
          <div class="fulfillment-approve-amount">
            <span>审核金额（可选）</span>
            <el-input-number v-model="approveAmount" :min="0" :step="0.01" controls-position="right" size="small" />
          </div>
        </header>
        <el-table :data="aftersales" class="workspace-table" empty-text="暂无售后单">
          <el-table-column prop="aftersaleNo" label="售后单" min-width="140" />
          <el-table-column prop="type" label="类型" width="120" />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column prop="requestedAmount" label="申请金额" width="120" align="right" />
          <el-table-column prop="approvedAmount" label="审核金额" width="120" align="right" />
          <el-table-column label="操作" width="180" align="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'APPLIED'" type="primary" link @click="approve(row.aftersaleNo)">通过</el-button>
              <el-button v-if="row.status === 'RETURNING'" type="danger" link @click="receive(row.aftersaleNo)">仓审入库</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <el-dialog v-model="dialog" title="强制拆单发货" width="560px">
      <p class="fulfillment-dialog-hint">
        履约单 <strong class="mono">{{ shipForm.fulfillmentNo }}</strong>
        · 将按指定订单项数量拆分发货。
      </p>
      <el-form label-position="top">
        <el-form-item label="承运商">
          <el-select v-model="shipForm.carrierCode" style="width:100%">
            <el-option v-for="(label, code) in CARRIER_LABELS" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="物流单号">
          <el-input v-model="shipForm.trackingNo" placeholder="请输入运单号" />
        </el-form-item>
        <el-form-item label="订单项">
          <el-select v-model="shipForm.orderItemId" style="width:100%">
            <el-option
              v-for="item in selected?.items || []"
              :key="item.orderItemId"
              :label="`项 ${item.orderItemId} · SKU ${item.skuId} · 可发 ${Math.max(0, (item.quantity || 0) - (item.shippedQty || 0))}`"
              :value="item.orderItemId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发货数量">
          <el-input-number v-model="shipForm.quantity" :min="1" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :disabled="!shipForm.trackingNo" @click="forceSplitShip">确认拆单发货</el-button>
      </template>
    </el-dialog>
  </section>
</template>
