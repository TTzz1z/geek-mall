<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { isLoggedIn, mobileApi } from '../../api'

const member = ref<any>(null)
const logs = ref<any[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  if (!isLoggedIn()) { loading.value = false; return }
  try {
    member.value = await mobileApi.request('/api/v1/user/member')
    logs.value = await mobileApi.request('/api/v1/user/points/logs')
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
    <view v-if="!isLoggedIn()" class="empty">登录后查看会员（GET /api/v1/user/member）</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty warn">{{ error }}</view>
    <template v-else>
      <view class="card">
        <text class="h">会员等级 {{ member?.levelCode || '—' }}</text>
        <text class="muted" style="display:block;margin-top:12rpx">成长值 {{ member?.growthValue ?? 0 }}</text>
        <text class="muted" style="display:block;margin-top:8rpx">积分余额 {{ member?.pointBalance ?? 0 }}（冻结 {{ member?.frozenPoints ?? 0 }}）</text>
      </view>
      <view class="card">
        <text class="h">积分明细</text>
        <view v-if="!logs.length" class="muted" style="margin-top:12rpx">暂无积分流水</view>
        <view v-for="(log, idx) in logs" :key="idx" class="row">
          <text>{{ log.changeType }} · {{ log.points }}</text>
          <text class="muted">{{ log.createdAt }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.h { font-weight: 800; font-size: 30rpx; }
.row { padding: 16rpx 0; border-top: 1px solid #f0f2f4; display: flex; flex-direction: column; gap: 6rpx; }
</style>
