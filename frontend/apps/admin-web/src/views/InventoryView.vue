<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PhMapPin as MapPin } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { isMerchantWorkspace } from '../rbac'
import { useSessionStore } from '../session'

const session = useSessionStore()
const stocks = ref<any[]>([])
const warehouses = ref<any[]>([])
const logs = ref<any[]>([])
const dialog = ref(false)
const stockPanel = ref(false)
const activeWarehouse = ref<any | null>(null)
const form = reactive({ warehouseId: 9001, skuId: 11001, quantity: 1 })
const merchantMode = computed(() => isMerchantWorkspace(session.principal))

const filteredStocks = computed(() => {
  if (!activeWarehouse.value) return stocks.value
  return stocks.value.filter((s) => Number(s.warehouseId) === Number(activeWarehouse.value.id))
})

function utilization(w: any) {
  const n = Number(w.utilizationRate)
  return Number.isFinite(n) ? Math.max(0, Math.min(100, Math.round(n))) : 0
}

function utilTone(w: any) {
  return utilization(w) >= 70 ? 'high' : 'ok'
}

function inStockSku(w: any) {
  const n = Number(w.physicalTotal ?? w.skuCount ?? 0)
  return Number.isFinite(n) ? n.toLocaleString('en-US') : '0'
}

async function load() {
  try {
    warehouses.value = await adminApi.request<any[]>('/api/v1/admin/warehouses')
    if (merchantMode.value) {
      stocks.value = await adminApi.request<any[]>('/api/v1/merchant/inventory/stocks')
      logs.value = await adminApi.request<any[]>('/api/v1/merchant/inventory/logs')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '库存加载失败')
  }
}

async function inbound() {
  try {
    const result = await adminApi.request<any>('/api/v1/merchant/inventory/inbounds', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(form)
    })
    ElMessage.success(`入库完成：${result.businessNo}`)
    dialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '入库失败')
  }
}

function stocktake(w: any) {
  activeWarehouse.value = w
  form.warehouseId = Number(w.id)
  if (merchantMode.value) {
    stockPanel.value = true
  } else {
    ElMessageBox.alert(
      [
        `仓库编码：${w.warehouseCode}`,
        `名称：${w.warehouseName}`,
        `地址：${w.address || '—'}`,
        `库位容积率：${utilization(w)}%`,
        `在库 SKU：${inStockSku(w)}`
      ].join('\n'),
      '盘点库存',
      { confirmButtonText: '关闭' }
    )
  }
}

function openInbound(w?: any) {
  if (w) {
    activeWarehouse.value = w
    form.warehouseId = Number(w.id)
  }
  dialog.value = true
}

onMounted(load)
</script>

<template>
  <section class="commerce-page inventory-page">
    <header class="inventory-heading">
      <h2>仓库库存</h2>
    </header>

    <div class="inventory-section-head">
      <h3>仓库与履约节点</h3>
      <p>管理各地区 CDC / RDC 物理仓储与库存配额。</p>
    </div>

    <div class="warehouse-grid">
      <article v-for="w in warehouses" :key="w.id" class="warehouse-card">
        <div class="warehouse-card-top">
          <small class="mono">{{ w.warehouseCode }}</small>
          <span class="warehouse-status">{{ w.status || 'ENABLED' }}</span>
        </div>
        <h4>{{ w.warehouseName }}</h4>
        <p class="warehouse-address">
          <MapPin :size="14" weight="fill" />
          <span>{{ w.address || '—' }}</span>
        </p>
        <div class="warehouse-util">
          <div class="warehouse-util-meta">
            <span>库位容积率</span>
            <strong>{{ utilization(w) }}%</strong>
          </div>
          <div class="warehouse-util-track">
            <i :class="`tone-${utilTone(w)}`" :style="{ width: `${utilization(w)}%` }" />
          </div>
        </div>
        <div class="warehouse-card-foot">
          <span>在库 SKU: <strong class="mono">{{ inStockSku(w) }}</strong></span>
          <button type="button" class="warehouse-action" @click="stocktake(w)">盘点库存</button>
        </div>
      </article>
    </div>

    <div v-if="merchantMode && stockPanel" class="ops-card inventory-stock-panel">
      <header class="inventory-panel-head">
        <div>
          <h3>{{ activeWarehouse?.warehouseName || '库存明细' }}</h3>
          <span>按仓库查看可售、锁定与预警库存。</span>
        </div>
        <div class="inventory-panel-actions">
          <el-button @click="openInbound(activeWarehouse)">库存入库</el-button>
          <el-button @click="stockPanel = false">收起</el-button>
        </div>
      </header>
      <el-table :data="filteredStocks" class="workspace-table" empty-text="该仓库暂无库存">
        <el-table-column prop="skuId" label="SKU" width="120">
          <template #default="{ row }"><span class="mono">{{ row.skuId }}</span></template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="仓库" min-width="140" />
        <el-table-column prop="physicalQty" label="实际" width="90" align="right" />
        <el-table-column prop="lockedQty" label="锁定" width="90" align="right" />
        <el-table-column prop="availableQty" label="可售" width="90" align="right">
          <template #default="{ row }">
            <strong :class="{ warning: row.availableQty <= row.warningQty }">{{ row.availableQty }}</strong>
          </template>
        </el-table-column>
        <el-table-column prop="inTransitQty" label="在途" width="90" align="right" />
        <el-table-column prop="defectiveQty" label="残次" width="90" align="right" />
        <el-table-column prop="warningQty" label="预警线" width="90" align="right" />
      </el-table>
    </div>

    <article v-if="logs.length" class="log-panel">
      <h3>最近库存流水</h3>
      <el-table :data="logs.slice(0, 10)">
        <el-table-column prop="businessNo" label="业务单号" />
        <el-table-column prop="skuId" label="SKU" />
        <el-table-column prop="businessType" label="类型" />
        <el-table-column prop="changePhysical" label="变化" />
        <el-table-column prop="afterPhysical" label="变更后" />
        <el-table-column prop="createdAt" label="时间" />
      </el-table>
    </article>

    <el-dialog v-model="dialog" title="创建入库流水" width="520px">
      <el-form label-position="top">
        <el-form-item label="仓库">
          <el-select v-model="form.warehouseId">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="SKU ID"><el-input-number v-model="form.skuId" :min="1" /></el-form-item>
        <el-form-item label="入库数量"><el-input-number v-model="form.quantity" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="inbound">确认入库</el-button>
      </template>
    </el-dialog>
  </section>
</template>
