<script setup lang="ts">
const tip = '告诉我预算、场景和偏好，AI 会从多品牌、多店铺里给出可下单的选购建议。小程序内完整对话即将开放；当前可先逛商城，或在电脑端打开商城 AI 导购体验完整能力。'

function openH5Hint() {
  uni.showModal({
    title: 'AI 导购',
    content: '完整对话请在电脑端打开商城「AI 导购」页面体验；小程序端可先浏览商品与下单流程。',
    showCancel: false
  })
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="page ai-page">
    <view class="hero-card">
      <text class="kicker">GEEK AI</text>
      <text class="title">参数很多，答案可以很简单</text>
      <text class="desc">{{ tip }}</text>
      <view class="actions">
        <view class="btn-primary brand" @click="openH5Hint">如何体验完整 AI</view>
        <view class="btn-secondary" @click="goHome">先逛首页精选</view>
      </view>
    </view>
    <view class="hint card">
      <text class="hint-title">你可以这样问</text>
      <text class="hint-item">· 8000 元内轻薄办公本推荐</text>
      <text class="hint-item">· 适合打游戏的旗舰手机</text>
      <text class="hint-item">· 100W 氮化镓充电器怎么选</text>
    </view>
  </view>
</template>

<style scoped>
.ai-page { padding-top: 8rpx; }
.hero-card {
  margin: 20rpx 24rpx;
  padding: 40rpx 32rpx;
  border-radius: 28rpx;
  background: linear-gradient(145deg, #1b1e21, #3a4048);
  color: #fff;
}
.kicker { display:block; font-size: 22rpx; letter-spacing: 0.12em; opacity: 0.7; }
.title { display:block; margin-top: 16rpx; font-size: 40rpx; font-weight: 800; line-height: 1.3; }
.desc { display:block; margin-top: 16rpx; font-size: 26rpx; line-height: 1.6; opacity: 0.85; }
.actions { display:flex; flex-direction: column; gap: 16rpx; margin-top: 28rpx; }
.hint { margin: 20rpx 24rpx; padding: 28rpx; }
.hint-title { display:block; font-size: 28rpx; font-weight: 700; margin-bottom: 12rpx; }
.hint-item { display:block; font-size: 26rpx; color: #666; line-height: 1.8; }
</style>
