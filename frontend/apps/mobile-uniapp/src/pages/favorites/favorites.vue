<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'
import { productCover } from '../../covers'

const items = ref<any[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) { loading.value = false; return }
  try {
    items.value = await mobileApi.request('/api/v1/user/favorites')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onShow(load)

function goProduct(id: number) {
  uni.navigateTo({ url: `/pages/product/product?spuId=${id}` })
}
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">登录后查看收藏（接口 GET /api/v1/user/favorites）</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <view v-else-if="!items.length" class="empty">收藏夹还是空的</view>
    <view v-else v-for="item in items" :key="item.id" class="card list-item" @click="goProduct(item.id)">
      <image class="thumb" :src="productCover(item.id || item.spuId, item.coverUrl)" mode="aspectFill" />
      <view>
        <text>{{ item.title }}</text>
        <text class="price">¥{{ Number(item.minPrice).toLocaleString() }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.price { display: block; margin-top: 12rpx; font-weight: 700; }
</style>
