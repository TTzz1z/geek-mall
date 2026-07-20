<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { mobileApi } from '../../api'
import { productCover } from '../../covers'

type Product = {
  id: number; title: string; subtitle?: string; minPrice: number; coverUrl: string
  brandName?: string; shopName?: string; sellerLabel?: string; shopType?: string
}
type Floor = { code: string; title: string; subtitle: string; items: Product[] }

const query = ref('')
const floors = ref<Floor[]>([])
const hero = ref<Product | null>(null)
const error = ref('')
const statusBarH = ref(20)

function sellerLabel(p: Product) {
  if (p.sellerLabel) return p.sellerLabel
  if (p.shopType === 'SELF_OPERATED') return '平台自营'
  if (p.shopType === 'OFFICIAL_FLAGSHIP') return '官方旗舰店'
  return '第三方店铺'
}

function coverSrc(p: Product) {
  return productCover(p.id, p.coverUrl)
}

function goSearch() {
  const kw = (query.value || '').trim()
  if (kw) uni.setStorageSync('geek.mobile.categoryKeyword', kw)
  uni.switchTab({ url: '/pages/category/category' })
}

function goProduct(id: number) {
  uni.navigateTo({ url: `/pages/product/product?spuId=${id}` })
}

function goAi() {
  uni.switchTab({ url: '/pages/ai/ai' })
}

onMounted(async () => {
  try {
    const sys = uni.getSystemInfoSync()
    statusBarH.value = sys.statusBarHeight || 20
  } catch { /* ignore */ }

  try {
    const data = await mobileApi.request<{ floors: Floor[]; hero?: Product }>('/api/v1/products/showcase')
    floors.value = data.floors || []
    hero.value = data.hero || data.floors?.[0]?.items?.[0] || null
  } catch (e) {
    error.value = e instanceof Error ? e.message : '首页加载失败'
  }
})
</script>

<template>
  <view class="page home">
    <view class="top" :style="{ paddingTop: statusBarH + 'px' }">
      <view class="top-row">
        <text class="brand">GEEK MALL</text>
        <text class="ai-chip" @click="goAi">AI 导购</text>
      </view>
      <view class="search" @click="goSearch">
        <text class="search-ico">⌕</text>
        <text class="search-ph">{{ query || '搜索数码好物' }}</text>
      </view>
    </view>

    <view v-if="error" class="warn card">{{ error }}</view>

    <view v-if="hero" class="hero" @click="goProduct(hero.id)">
      <image class="hero-img" :src="coverSrc(hero)" mode="aspectFill" />
      <view class="hero-veil" />
      <view class="hero-body">
        <text class="tag">精选</text>
        <text class="title">{{ hero.title }}</text>
        <text class="sub">{{ hero.subtitle || sellerLabel(hero) }}</text>
        <text class="price">¥{{ Number(hero.minPrice).toLocaleString() }}</text>
      </view>
    </view>

    <view v-for="floor in floors" :key="floor.code" class="floor">
      <view class="floor-head">
        <view>
          <text class="floor-kicker">{{ floor.code.replace(/_/g, ' ').toUpperCase() }}</text>
          <text class="floor-title">{{ floor.title }}</text>
          <text class="muted">{{ floor.subtitle }}</text>
        </view>
      </view>
      <scroll-view scroll-x class="rail" :show-scrollbar="false">
        <view v-for="item in floor.items" :key="item.id" class="rail-item" @click="goProduct(item.id)">
          <image :src="coverSrc(item)" mode="aspectFill" />
          <text class="item-title">{{ item.title }}</text>
          <text class="item-meta">{{ sellerLabel(item) }}</text>
          <text class="price">¥{{ Number(item.minPrice).toLocaleString() }}</text>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped>
.home { background: #f3f5f7; }
.top {
  padding-left: 28rpx;
  padding-right: 28rpx;
  padding-bottom: 20rpx;
  background: linear-gradient(180deg, #ffffff 0%, #f7f8fa 100%);
}
.top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 72rpx;
}
.brand {
  font-size: 38rpx;
  font-weight: 800;
  letter-spacing: -1rpx;
  color: #17191c;
}
.ai-chip {
  padding: 10rpx 20rpx;
  border-radius: 999rpx;
  background: #17191c;
  color: #fff;
  font-size: 22rpx;
  font-weight: 650;
}
.search {
  margin-top: 12rpx;
  padding: 18rpx 24rpx;
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 999rpx;
  border: 1px solid #e8ebef;
}
.search-ico {
  margin-right: 12rpx;
  color: #8b919a;
  font-size: 28rpx;
}
.search-ph { color: #8b919a; font-size: 26rpx; }

.hero {
  position: relative;
  margin: 8rpx 24rpx 20rpx;
  height: 420rpx;
  border-radius: 28rpx;
  overflow: hidden;
  background: #1a1f24;
}
.hero-img { width: 100%; height: 100%; }
.hero-veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(0deg, rgba(10, 12, 16, 0.72) 0%, rgba(10, 12, 16, 0.08) 55%, transparent 100%);
}
.hero-body {
  position: absolute;
  left: 28rpx;
  right: 28rpx;
  bottom: 28rpx;
  display: flex;
  flex-direction: column;
}
.tag {
  align-self: flex-start;
  margin-bottom: 10rpx;
  padding: 4rpx 14rpx;
  border-radius: 8rpx;
  background: #ff6a2b;
  color: #fff;
  font-size: 20rpx;
  font-weight: 700;
}
.title {
  color: #fff;
  font-size: 36rpx;
  font-weight: 750;
  line-height: 1.25;
}
.sub {
  margin-top: 8rpx;
  color: rgba(255, 255, 255, 0.72);
  font-size: 24rpx;
}
.price {
  margin-top: 14rpx;
  color: #fff;
  font-size: 36rpx;
  font-weight: 750;
}

.floor {
  margin: 0 0 16rpx;
  padding: 28rpx 0 24rpx 28rpx;
  background: #fff;
}
.floor-head { padding-right: 28rpx; margin-bottom: 20rpx; }
.floor-kicker {
  display: block;
  color: #ff6a2b;
  font-size: 20rpx;
  font-weight: 750;
  letter-spacing: 0.12em;
}
.floor-title {
  display: block;
  margin-top: 6rpx;
  font-size: 34rpx;
  font-weight: 750;
  color: #17191c;
}
.rail { white-space: nowrap; }
.rail-item {
  display: inline-flex;
  flex-direction: column;
  width: 268rpx;
  margin-right: 18rpx;
  vertical-align: top;
}
.rail-item image {
  width: 268rpx;
  height: 268rpx;
  border-radius: 18rpx;
  background: #eef1f3;
}
.item-title {
  margin-top: 14rpx;
  font-size: 26rpx;
  font-weight: 650;
  color: #17191c;
  white-space: normal;
  line-height: 1.35;
  max-height: 72rpx;
  overflow: hidden;
}
.item-meta {
  margin-top: 6rpx;
  color: #8b919a;
  font-size: 22rpx;
}
.rail-item .price {
  margin-top: 8rpx;
  color: #ff6a2b;
  font-size: 30rpx;
}
</style>
