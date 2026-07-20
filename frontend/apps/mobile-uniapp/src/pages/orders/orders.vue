<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import type { TradeOrderSummary } from '@geek-mall/api-types'
import { isLoggedIn, mobileApi } from '../../api'

const orders = ref<TradeOrderSummary[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) {
    loading.value = false
    return
  }
  try {
    const data = await mobileApi.request<{ items?: TradeOrderSummary[] } | TradeOrderSummary[]>('/api/v1/trade/orders')
    orders.value = Array.isArray(data) ? data : (data.items ?? [])
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onShow(load)

function pay(orderNo: string) {
  uni.navigateTo({ url: `/pages/payment/payment?orderNo=${orderNo}` })
}
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">登录后查看订单</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <view v-else-if="!orders.length" class="empty">暂无订单</view>
    <view v-else v-for="o in orders" :key="o.orderNo" class="card">
      <text class="no">{{ o.orderNo }}</text>
      <text class="muted" style="display:block;margin-top:8rpx">{{ o.status }}</text>
      <text class="price">¥{{ Number(o.payableAmount).toLocaleString() }}</text>
      <view
        v-if="o.status === 'CREATED' || o.status === 'PENDING_PAYMENT'"
        class="btn-primary"
        style="min-height:72rpx"
        @click="pay(o.orderNo)"
      >去支付</view>
    </view>
  </view>
</template>

<style scoped>
.no { font-weight: 700; }
.price { display: block; margin-top: 12rpx; font-size: 32rpx; font-weight: 800; }
</style>
