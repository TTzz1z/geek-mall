<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import type { CheckoutPayload } from '@geek-mall/api-types'
import { isLoggedIn, mobileApi } from '../../api'

const itemIds = ref<number[]>([])
const addresses = ref<Array<{ id: number; receiver: string; mobile: string; detail: string; isDefault?: boolean }>>([])
const addressId = ref<number | null>(null)
const preview = ref<CheckoutPayload | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')

async function calculate() {
  if (!addressId.value) {
    error.value = '请先选择收货地址'
    return
  }
  error.value = ''
  preview.value = await mobileApi.request<CheckoutPayload>('/api/v1/trade/checkout/preview', {
    method: 'POST',
    body: {
      addressId: addressId.value,
      deliveryMethod: 'EXPRESS',
      cartItemIds: itemIds.value.length ? itemIds.value : undefined
    }
  })
}

async function submit() {
  if (!preview.value?.checkoutToken || !addressId.value) return
  submitting.value = true
  try {
    const order = await mobileApi.request<{ orderNo: string }>('/api/v1/trade/orders', {
      method: 'POST',
      headers: { 'Idempotency-Key': `${Date.now()}-${Math.random()}` },
      body: { checkoutToken: preview.value.checkoutToken, addressId: addressId.value }
    })
    uni.redirectTo({ url: `/pages/payment/payment?orderNo=${encodeURIComponent(order.orderNo)}` })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '下单失败'
  } finally {
    submitting.value = false
  }
}

function onAddressPick(e: { detail: { value: string } }) {
  const idx = Number(e.detail.value)
  addressId.value = addresses.value[idx]?.id ?? null
  calculate()
}

function goAddress() {
  uni.navigateTo({ url: '/pages/address/address' })
}

onLoad(async (q) => {
  itemIds.value = String(q?.items || '').split(',').filter(Boolean).map(Number).filter(n => n > 0)
  if (!isLoggedIn()) {
    loading.value = false
    return
  }
  try {
    addresses.value = await mobileApi.request('/api/v1/user/addresses')
    const def = addresses.value.find(a => a.isDefault) || addresses.value[0]
    addressId.value = def?.id ?? null
    if (addressId.value) await calculate()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '结算失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">请先登录</view>
    <view v-else-if="loading" class="empty">计算中…</view>
    <template v-else>
      <view class="card">
        <text class="h">收货地址</text>
        <picker
          v-if="addresses.length"
          :range="addresses.map(a => `${a.receiver} ${a.mobile} · ${a.detail}`)"
          @change="onAddressPick"
        >
          <view class="picker">已选地址 ID：{{ addressId }}</view>
        </picker>
        <text v-else class="warn">暂无地址，请先到「收货地址」添加</text>
        <view class="link" @click="goAddress">管理地址</view>
      </view>
      <view v-if="preview" class="card">
        <text class="h">应付 ¥{{ Number(preview.payableAmount).toLocaleString() }}</text>
        <text class="muted" style="display:block;margin-top:8rpx">
          商品 ¥{{ Number(preview.totalAmount).toLocaleString() }} · 优惠 ¥{{ Number(preview.discountAmount).toLocaleString() }} · 运费 ¥{{ Number(preview.freightAmount).toLocaleString() }}
        </text>
      </view>
      <text v-if="error" class="warn" style="margin:0 24rpx">{{ error }}</text>
      <view class="card">
        <view class="btn-primary" :class="{ disabled: !preview || submitting }" @click="submit">
          {{ submitting ? '提交中…' : '提交订单' }}
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.h { font-size: 30rpx; font-weight: 700; }
.picker { margin-top: 16rpx; padding: 20rpx; background: #f0f2f4; border-radius: 12rpx; }
.link { margin-top: 16rpx; color: #ff6a2b; }
</style>
