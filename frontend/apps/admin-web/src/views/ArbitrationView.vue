<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'

type ArbitrationRow = {
  id: number
  aftersaleNo: string
  orderNo: string
  type: string
  status: string
  aftersaleStatus: string
  reason: string
  requestedAmount?: number
  decision?: string
  decisionReason?: string
  createdAt?: string
}

const rows = ref<ArbitrationRow[]>([])
const loading = ref(true)
const statusFilter = ref('PENDING')
const decideId = ref(0)
const decision = ref('APPROVED')
const reason = ref('平台支持用户')
const deciding = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.request<ArbitrationRow[]>(
      `/api/v1/admin/arbitrations?status=${encodeURIComponent(statusFilter.value)}`
    )
  } catch (e) {
    rows.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载仲裁列表失败')
  } finally {
    loading.value = false
  }
}

function pick(row: ArbitrationRow) {
  decideId.value = row.id
  reason.value = row.reason || '平台裁决'
}

async function decide() {
  if (!decideId.value) {
    ElMessage.warning('请先选择或输入仲裁 ID')
    return
  }
  deciding.value = true
  try {
    await adminApi.request(
      `/api/v1/admin/arbitrations/${decideId.value}/decide?decision=${decision.value}&reason=${encodeURIComponent(reason.value)}`,
      { method: 'POST' }
    )
    ElMessage.success('裁决已提交')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '裁决失败')
  } finally {
    deciding.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-page arbitration-page">
    <header class="arb-head">
      <div>
        <h1>平台仲裁</h1>
        <p>查看待裁决售后仲裁；APPROVED 将按售后类型推进退款闭环。</p>
      </div>
      <div class="arb-filters">
        <select v-model="statusFilter" @change="load">
          <option value="PENDING">待处理</option>
          <option value="DECIDED">已裁决</option>
          <option value="ALL">全部</option>
        </select>
        <button type="button" @click="load">刷新</button>
      </div>
    </header>

    <div class="arb-table-wrap" v-loading="loading">
      <table class="arb-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>售后单</th>
            <th>订单</th>
            <th>类型</th>
            <th>仲裁状态</th>
            <th>售后状态</th>
            <th>申请原因</th>
            <th>金额</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td colspan="9">暂无仲裁记录</td>
          </tr>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.aftersaleNo }}</td>
            <td>{{ row.orderNo }}</td>
            <td>{{ row.type }}</td>
            <td>{{ row.status }}</td>
            <td>{{ row.aftersaleStatus }}</td>
            <td class="reason">{{ row.reason }}</td>
            <td>{{ row.requestedAmount != null ? `¥${Number(row.requestedAmount).toLocaleString()}` : '—' }}</td>
            <td><button type="button" class="link" @click="pick(row)">裁决</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <form class="arb-form" @submit.prevent="decide">
      <h2>提交裁决</h2>
      <label>仲裁 ID <input v-model.number="decideId" type="number" required /></label>
      <label>结果
        <select v-model="decision">
          <option value="APPROVED">支持用户</option>
          <option value="REJECTED">驳回</option>
        </select>
      </label>
      <label>理由 <input v-model="reason" required /></label>
      <button type="submit" :disabled="deciding">{{ deciding ? '提交中…' : '提交裁决' }}</button>
    </form>
  </section>
</template>

<style scoped>
.arb-head { display: flex; justify-content: space-between; gap: 16px; align-items: end; margin-bottom: 16px; }
.arb-head h1 { margin: 0 0 6px; font-size: 22px; }
.arb-head p { margin: 0; color: #6b7280; }
.arb-filters { display: flex; gap: 8px; }
.arb-table-wrap { overflow: auto; background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; }
.arb-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.arb-table th, .arb-table td { padding: 10px 12px; border-bottom: 1px solid #f3f4f6; text-align: left; }
.arb-table .reason { max-width: 220px; }
.arb-table .link { border: 0; background: transparent; color: #c2410c; cursor: pointer; }
.arb-form { margin-top: 18px; display: grid; gap: 10px; max-width: 480px; padding: 16px; background: #fff; border: 1px solid #e5e7eb; border-radius: 10px; }
.arb-form h2 { margin: 0; font-size: 16px; }
.arb-form label { display: grid; gap: 6px; font-size: 13px; }
.arb-form input, .arb-form select, .arb-filters select, .arb-filters button, .arb-form button {
  min-height: 36px; padding: 0 10px; border: 1px solid #d1d5db; border-radius: 8px; background: #fff;
}
.arb-form button { background: #111827; color: #fff; border-color: #111827; cursor: pointer; }
</style>
