<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'

const mine = ref<any[]>([])
const available = ref<any[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) { loading.value = false; return }
  try {
    const [m, a] = await Promise.all([
      mobileApi.request<any[]>('/api/v1/marketing/coupons/mine'),
      mobileApi.request<any[]>('/api/v1/marketing/coupons/available')
    ])
    mine.value = m || []
    available.value = a || []
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function receive(id: number) {
  try {
    await mobileApi.request(`/api/v1/marketing/coupons/${id}/receive`, { method: 'POST' })
    uni.showToast({ title: '领取成功', icon: 'success' })
    await load()
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '领取失败', icon: 'none' })
  }
}

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">登录后查看优惠券（/marketing/coupons/mine & available）</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <template v-else>
      <view class="card">
        <text class="h">我的优惠券</text>
        <view v-if="!mine.length" class="muted" style="margin-top:12rpx">暂无已领优惠券</view>
        <view v-for="c in mine" :key="c.id" class="coupon">
          <text>{{ c.name }}</text>
          <text class="muted">满{{ c.thresholdAmount }}减{{ c.discountAmount }} · {{ c.status }}</text>
        </view>
      </view>
      <view class="card">
        <text class="h">可领取</text>
        <view v-if="!available.length" class="muted" style="margin-top:12rpx">暂无可领券</view>
        <view v-for="c in available" :key="c.id" class="coupon">
          <view>
            <text>{{ c.name }}</text>
            <text class="muted" style="display:block">满{{ c.thresholdAmount }}减{{ c.discountAmount }}</text>
          </view>
          <view class="get" @click="receive(c.id)">领取</view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.h { font-weight: 800; font-size: 30rpx; }
.coupon { display: flex; justify-content: space-between; align-items: center; gap: 16rpx; padding: 20rpx 0; border-top: 1px solid #f0f2f4; }
.get { padding: 10rpx 20rpx; background: #191919; color: #fff; border-radius: 10rpx; font-size: 24rpx; }
</style>
