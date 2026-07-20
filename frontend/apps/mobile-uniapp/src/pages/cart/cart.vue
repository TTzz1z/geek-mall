<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import type { CartSummary } from '@geek-mall/api-types'
import { isLoggedIn, mobileApi } from '../../api'
import { productCover } from '../../covers'

const cart = ref<CartSummary | null>(null)
const loading = ref(true)
const error = ref('')
const selectedIds = computed(() =>
  cart.value?.shops.flatMap(s => s.items.filter(i => i.selected && i.saleable && i.stockEnough).map(i => i.id)) ?? []
)

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) {
    cart.value = null
    loading.value = false
    return
  }
  try {
    cart.value = await mobileApi.request<CartSummary>('/api/v1/trade/cart')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function update(id: number, body: { quantity?: number; selected?: boolean }) {
  cart.value = await mobileApi.request<CartSummary>(`/api/v1/trade/cart/items/${id}`, {
    method: 'PATCH',
    body
  })
}

async function remove(id: number) {
  cart.value = await mobileApi.request<CartSummary>(`/api/v1/trade/cart/items/${id}`, { method: 'DELETE' })
}

function checkout() {
  if (!selectedIds.value.length) return
  uni.navigateTo({ url: `/pages/checkout/checkout?items=${selectedIds.value.join(',')}` })
}

function goLogin() {
  uni.switchTab({ url: '/pages/user/user' })
}

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">
      <text>登录后同步购物车</text>
      <view class="btn-primary" style="margin:24rpx 80rpx" @click="goLogin">去登录</view>
    </view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <view v-else-if="!cart?.shops?.length" class="empty">购物车是空的</view>
    <template v-else>
      <view v-for="shop in cart.shops" :key="shop.shopId" class="card">
        <text class="shop">{{ shop.shopName }}</text>
        <view v-for="item in shop.items" :key="item.id" class="row">
          <checkbox
            :checked="item.selected"
            :disabled="!item.saleable || !item.stockEnough"
            @click="update(item.id, { selected: !item.selected })"
          />
          <image :src="productCover(item.spuId, item.coverUrl)" mode="aspectFill" />
          <view class="meta">
            <text>{{ item.titleSnapshot }}</text>
            <text v-if="item.invalidReason" class="warn">{{ item.invalidReason }}</text>
            <text class="price">¥{{ Number(item.currentPrice).toLocaleString() }}</text>
            <view class="qty">
              <text @click="item.quantity > 1 && update(item.id, { quantity: item.quantity - 1 })">−</text>
              <text>{{ item.quantity }}</text>
              <text @click="update(item.id, { quantity: item.quantity + 1 })">+</text>
              <text class="del" @click="remove(item.id)">删除</text>
            </view>
          </view>
        </view>
      </view>
      <view class="bar">
        <view>
          <text class="muted">已选 {{ cart.selectedCount }} 件</text>
          <text class="price">¥{{ Number(cart.selectedAmount).toLocaleString() }}</text>
        </view>
        <view class="btn-primary" style="margin:0;min-width:200rpx" :class="{ disabled: !selectedIds.length }" @click="checkout">去结算</view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.shop { font-weight: 700; margin-bottom: 12rpx; display: block; }
.row { display: flex; padding: 20rpx 0; border-top: 1px solid #eef1f3; align-items: flex-start; }
.row checkbox { margin-right: 12rpx; margin-top: 50rpx; }
.row image { width: 140rpx; height: 140rpx; margin-right: 16rpx; border-radius: 16rpx; background: #eef1f3; }
.meta { flex: 1; }
.price { display: block; margin-top: 8rpx; font-weight: 750; color: #ff6a2b; }
.qty { display: flex; margin-top: 14rpx; align-items: center; }
.qty text { min-width: 48rpx; text-align: center; }
.del { margin-left: auto; color: #8b919a; }
.bar {
  position: sticky; bottom: 0; display: flex; justify-content: space-between; align-items: center;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); background: rgba(255,255,255,0.96);
  border-top: 1px solid #eef1f3; box-shadow: 0 -8rpx 24rpx rgba(23,25,28,0.04);
}
.bar .btn-primary { border-radius: 999rpx; background: #ff6a2b; }
</style>
