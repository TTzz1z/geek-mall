<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'

const rows = ref<any[]>([])
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
    const data = await mobileApi.request<any>('/api/v1/aftersales')
    rows.value = Array.isArray(data) ? data : (data.items ?? data.list ?? [])
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

onShow(load)
</script>

<template>
  <view class="page">
    <view v-if="!isLoggedIn()" class="empty">登录后查看售后</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <view v-else-if="!rows.length" class="empty">暂无售后单</view>
    <view v-else v-for="row in rows" :key="row.aftersaleNo || row.id" class="card">
      <text class="h">{{ row.aftersaleNo || row.id }}</text>
      <text class="muted" style="display:block;margin-top:8rpx">{{ row.status }} · {{ row.type || '' }}</text>
      <text class="muted" style="display:block;margin-top:8rpx">{{ row.orderNo || '' }}</text>
    </view>
  </view>
</template>

<style scoped>
.h { font-weight: 700; }
</style>
