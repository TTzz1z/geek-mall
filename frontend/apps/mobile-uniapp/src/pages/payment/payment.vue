<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'

const orderNo = ref('')
const loading = ref(false)
const paid = ref(false)
const message = ref('')
const error = ref('')
const paymentNo = ref('')

async function paySimulated() {
  if (!orderNo.value) return
  loading.value = true
  error.value = ''
  try {
    const payment = await mobileApi.request<{ paymentNo: string }>('/api/v1/payments', {
      method: 'POST',
      body: { orderNo: orderNo.value, channel: 'SIMULATED' }
    })
    paymentNo.value = payment.paymentNo
    await mobileApi.request(`/api/v1/payments/${payment.paymentNo}/simulate`, { method: 'POST' })
    paid.value = true
    message.value = '支付成功（展示通道，无真实扣款）'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '支付失败'
  } finally {
    loading.value = false
  }
}

onLoad((q) => {
  orderNo.value = String(q?.orderNo || '')
})
function goOrders() {
  uni.navigateTo({ url: '/pages/orders/orders' })
}
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">请先登录</view>
    <view v-else class="card">
      <text class="h">收银台</text>
      <text class="muted" style="display:block;margin-top:12rpx">订单号：{{ orderNo || '—' }}</text>
      <text class="muted" style="display:block;margin-top:8rpx">本展示项目使用模拟支付，不会产生真实扣款</text>
      <text v-if="paymentNo" class="muted" style="display:block;margin-top:8rpx">支付单：{{ paymentNo }}</text>
      <text v-if="message" class="ok" style="display:block;margin-top:12rpx">{{ message }}</text>
      <text v-if="error" class="warn" style="display:block;margin-top:12rpx">{{ error }}</text>
      <view v-if="!paid" class="btn-primary" :class="{ disabled: loading || !orderNo }" @click="paySimulated">
        {{ loading ? '支付中…' : '确认支付' }}
      </view>
      <view v-else class="btn-primary" @click="goOrders">查看订单</view>
    </view>
  </view>
</template>

<style scoped>
.h { font-size: 36rpx; font-weight: 800; }
</style>
