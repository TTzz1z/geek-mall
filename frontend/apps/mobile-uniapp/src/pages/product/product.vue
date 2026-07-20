<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'
import { productCover } from '../../covers'

type Sku = { id: number; salePrice: number }
type Product = {
  id: number; title: string; subtitle: string; brandName: string; categoryName: string
  minPrice: number; coverUrl: string; shopId: number; shopName: string; skus: Sku[]
}

const spuId = ref('')
const product = ref<Product | null>(null)
const error = ref('')
const loading = ref(true)
const adding = ref(false)
const sku = computed(() => product.value?.skus?.[0])

async function load() {
  loading.value = true
  error.value = ''
  try {
    product.value = await mobileApi.request<Product>(`/api/v1/products/spus/${spuId.value}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function addCart() {
  if (!sku.value?.id) return
  if (!isLoggedIn()) {
    uni.switchTab({ url: '/pages/user/user' })
    return
  }
  adding.value = true
  try {
    await mobileApi.request('/api/v1/trade/cart/items', {
      method: 'POST',
      body: { skuId: sku.value.id, quantity: 1 }
    })
    uni.switchTab({ url: '/pages/cart/cart' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加购失败'
  } finally {
    adding.value = false
  }
}

async function favorite() {
  if (!isLoggedIn()) {
    uni.switchTab({ url: '/pages/user/user' })
    return
  }
  try {
    await mobileApi.request(`/api/v1/user/favorites/${spuId.value}`, { method: 'POST' })
    uni.showToast({ title: '已收藏', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '收藏失败', icon: 'none' })
  }
}

onLoad((q) => {
  spuId.value = String(q?.spuId || '')
  load()
})
</script>

<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <view v-else-if="product">
      <image class="cover" :src="productCover(product.id, product.coverUrl)" mode="aspectFill" />
      <view class="card">
        <text class="muted">{{ product.brandName }} / {{ product.categoryName }}</text>
        <text class="title">{{ product.title }}</text>
        <text class="muted" style="display:block;margin-top:8rpx">{{ product.subtitle }}</text>
        <text class="price">¥{{ Number(sku?.salePrice ?? product.minPrice).toLocaleString() }}</text>
        <text class="muted" style="display:block;margin-top:12rpx">店铺：{{ product.shopName }}</text>
        <view class="btn-primary" :class="{ disabled: adding }" @click="addCart">
          {{ adding ? '加入中…' : (isLoggedIn() ? '加入购物车' : '登录后加购') }}
        </view>
        <view class="btn-ghost" @click="favorite">收藏</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.cover { width: 100%; height: 750rpx; background: #e8ebef; }
.title { display: block; margin-top: 12rpx; font-size: 40rpx; font-weight: 800; }
.price { display: block; margin-top: 20rpx; font-size: 40rpx; font-weight: 800; }
.btn-ghost {
  display: flex; justify-content: center; align-items: center;
  min-height: 80rpx; margin-top: 16rpx; border-radius: 16rpx; background: #f0f2f4;
}
</style>
