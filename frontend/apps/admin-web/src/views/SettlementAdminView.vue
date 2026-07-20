<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'
import { formatAdminTime, statusLabel, statusTone } from '../status'

const tab = ref<'settlements' | 'withdrawals'>('settlements')
const settlements = ref<any[]>([])
const withdrawals = ref<any[]>([])
const status = ref('')
const loading = ref(false)

const settlementStatusOptions = [
  { label: '全部', value: '' },
  { label: '待确认', value: 'PENDING' },
  { label: '已确认', value: 'CONFIRMED' }
]
const withdrawalStatusOptions = [
  { label: '全部', value: '' },
  { label: '待审', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' }
]

const statusOptions = computed(() =>
  tab.value === 'settlements' ? settlementStatusOptions : withdrawalStatusOptions
)

async function load() {
  loading.value = true
  try {
    const q = status.value ? `?status=${encodeURIComponent(status.value)}` : ''
    if (tab.value === 'settlements') {
      settlements.value = await adminApi.request(`/api/v1/admin/settlements${q}`)
    } else {
      withdrawals.value = await adminApi.request(`/api/v1/admin/withdrawals${q}`)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function approve(no: string, pass: boolean) {
  try {
    await adminApi.request(`/api/v1/admin/withdrawals/${no}/approve?pass=${pass}`, { method: 'POST' })
    ElMessage.success(pass ? '已通过' : '已驳回')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

function switchTab(next: 'settlements' | 'withdrawals') {
  if (tab.value === next) return
  tab.value = next
  status.value = ''
  load()
}

watch(status, () => load())

onMounted(load)
</script>

<template>
  <section class="commerce-page settlement-page" v-loading="loading">
    <header class="workspace-heading">
      <div>
        <p>SETTLEMENT</p>
        <h2>商家结算</h2>
        <span>结算单查询与提现审批。</span>
      </div>
      <div class="workspace-actions">
        <el-select v-model="status" clearable placeholder="状态" style="width:140px">
          <el-option v-for="opt in statusOptions" :key="`${tab}-${opt.value}`" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </header>

    <div class="ops-card">
      <div class="ops-tabs">
        <button type="button" :class="{ active: tab === 'settlements' }" @click="switchTab('settlements')">
          结算单
          <span v-if="settlements.length" class="count">{{ settlements.length }}</span>
        </button>
        <button type="button" :class="{ active: tab === 'withdrawals' }" @click="switchTab('withdrawals')">
          提现审批
          <span v-if="withdrawals.length" class="count">{{ withdrawals.length }}</span>
        </button>
      </div>

      <el-table
        v-if="tab === 'settlements'"
        :data="settlements"
        class="workspace-table"
        empty-text="暂无结算单"
        max-height="520"
      >
        <el-table-column prop="settlementNo" label="结算单号" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.settlementNo }}</span></template>
        </el-table-column>
        <el-table-column prop="merchantId" label="商家" width="100">
          <template #default="{ row }"><span class="mono">{{ row.merchantId }}</span></template>
        </el-table-column>
        <el-table-column label="账期" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ String(row.periodStart || '').slice(0, 10) }} ~ {{ String(row.periodEnd || '').slice(0, 10) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="income" label="收入" width="110" align="right">
          <template #default="{ row }"><span class="mono">{{ Number(row.income || 0).toLocaleString() }}</span></template>
        </el-table-column>
        <el-table-column prop="commission" label="佣金" width="100" align="right">
          <template #default="{ row }"><span class="mono">{{ Number(row.commission || 0).toLocaleString() }}</span></template>
        </el-table-column>
        <el-table-column prop="payable" label="应结" width="110" align="right">
          <template #default="{ row }"><strong class="mono">{{ Number(row.payable || 0).toLocaleString() }}</strong></template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-table
        v-else
        :data="withdrawals"
        class="workspace-table"
        empty-text="暂无提现单"
        max-height="520"
      >
        <el-table-column prop="withdrawalNo" label="提现单号" min-width="160">
          <template #default="{ row }"><span class="mono">{{ row.withdrawalNo }}</span></template>
        </el-table-column>
        <el-table-column prop="merchantId" label="商家" width="100">
          <template #default="{ row }"><span class="mono">{{ row.merchantId }}</span></template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="120" align="right">
          <template #default="{ row }"><strong class="mono">¥{{ Number(row.amount || 0).toLocaleString() }}</strong></template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="申请时间" min-width="140">
          <template #default="{ row }">{{ formatAdminTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="danger" @click="approve(row.withdrawalNo, false)">驳回</el-button>
              <el-button type="primary" @click="approve(row.withdrawalNo, true)">通过</el-button>
            </template>
            <span v-else style="color:#9ca3af;">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>
