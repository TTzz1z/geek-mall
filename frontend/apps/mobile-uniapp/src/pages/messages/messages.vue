<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'

const items = ref<any[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) { loading.value = false; return }
  try {
    items.value = await mobileApi.request('/api/v1/user/messages')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">登录后查看消息（GET /api/v1/user/messages）</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <view v-else-if="!items.length" class="empty">暂无消息</view>
    <view v-else v-for="m in items" :key="m.taskNo" class="card">
      <text class="h">{{ m.scene }} · {{ m.channel }}</text>
      <text class="muted" style="display:block;margin-top:8rpx">{{ m.status }} · {{ m.createdAt }}</text>
      <text style="display:block;margin-top:12rpx;font-size:24rpx">{{ m.payload }}</text>
    </view>
  </view>
</template>

<style scoped>
.h { font-weight: 700; }
</style>
