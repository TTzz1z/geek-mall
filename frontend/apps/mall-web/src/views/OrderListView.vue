<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { PhClipboard as Clipboard } from '@phosphor-icons/vue'
import type { TradeOrderSummary } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'

const route = useRoute()
const orders = ref<TradeOrderSummary[]>([])
const loading = ref(true)
const error = ref('')
const filter = ref(String(route.query.status || ''))

const labels: Record<string, string> = {
  RESERVING: '锁定库存中',
  RESERVATION_RETRY: '库存确认中',
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  CANCELLED: '已关闭'
}

async function load() {
  loading.value = true
  try {
    orders.value = await tradeApi.orders(filter.value)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => route.query.status, v => {
  filter.value = String(v || '')
  load()
})

onMounted(load)
</script>

<template>
  <div class="account-panel">
    <header class="panel-head">
      <h1>我的订单</h1>
      <div class="status-tabs soft">
        <button
          v-for="item in [{ v: '', l: '全部' }, { v: 'PENDING_PAYMENT', l: '待支付' }, { v: 'PAID', l: '已支付' }, { v: 'CANCELLED', l: '已关闭' }]"
          :key="item.v"
          :class="{ active: filter === item.v }"
          @click="filter = item.v; load()"
        >{{ item.l }}</button>
      </div>
    </header>

    <div v-if="loading" class="account-empty">正在载入订单…</div>
    <div v-else-if="error" class="account-empty">{{ error }}</div>
    <div v-else-if="!orders.length" class="account-empty soft">
      <Clipboard :size="48" weight="thin" />
      <h3>暂无订单</h3>
      <p>下单后可在此查看进度与物流</p>
      <RouterLink class="account-cta" to="/search">去选购</RouterLink>
    </div>
    <div v-else class="recent-orders">
      <RouterLink
        v-for="order in orders"
        :key="order.orderNo"
        :to="`/user/orders/${order.orderNo}`"
        class="recent-order"
      >
        <div>
          <strong>{{ order.orderNo }}</strong>
          <span>{{ new Date(order.createdAt).toLocaleString() }}</span>
        </div>
        <em>{{ labels[order.status] ?? order.status }}</em>
        <b>¥{{ Number(order.payableAmount).toLocaleString() }}</b>
      </RouterLink>
    </div>
  </div>
</template>
