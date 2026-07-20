<script setup lang="ts">
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { mobileApi } from '../../api'
import { productCover } from '../../covers'

type CatNode = { id: number; name: string; children?: CatNode[] }
type Product = {
  id: number; title: string; minPrice: number; coverUrl: string
  shopName?: string; brandName?: string
}

const KEYWORD_KEY = 'geek.mobile.categoryKeyword'

const tree = ref<CatNode[]>([])
const activeRootId = ref<number | null>(null)
const activeChildId = ref<number | null>(null)
const keyword = ref('')
const searchMode = ref(false)
const products = ref<Product[]>([])
const loading = ref(false)
const error = ref('')

const activeRoot = computed(() => tree.value.find((n) => n.id === activeRootId.value) || null)
const children = computed(() => activeRoot.value?.children || [])
const filterCategoryId = computed(() => activeChildId.value ?? activeRootId.value)

async function loadTree() {
  const data = await mobileApi.request<CatNode[]>('/api/v1/products/categories/tree')
  tree.value = data || []
  if (!activeRootId.value && tree.value.length) {
    activeRootId.value = Number(tree.value[0].id)
  }
}

async function loadProducts() {
  loading.value = true
  error.value = ''
  try {
    if (searchMode.value && keyword.value.trim()) {
      const q = [
        'page=1',
        'pageSize=40',
        `keyword=${encodeURIComponent(keyword.value.trim())}`
      ]
      const data = await mobileApi.request<{ items: Product[] }>(`/api/v1/search/products?${q.join('&')}`)
      products.value = data.items || []
    } else {
      const id = filterCategoryId.value
      const path = id != null
        ? `/api/v1/products/catalog?categoryId=${encodeURIComponent(String(id))}`
        : '/api/v1/products/catalog'
      products.value = await mobileApi.request<Product[]>(path)
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
    products.value = []
  } finally {
    loading.value = false
  }
}

function selectRoot(id: number) {
  searchMode.value = false
  activeRootId.value = id
  activeChildId.value = null
  void loadProducts()
}

function selectChild(id: number | null) {
  searchMode.value = false
  activeChildId.value = id
  void loadProducts()
}

function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    searchMode.value = false
    void loadProducts()
    return
  }
  searchMode.value = true
  void loadProducts()
}

function clearSearch() {
  keyword.value = ''
  searchMode.value = false
  void loadProducts()
}

function goProduct(id: number) {
  uni.navigateTo({ url: `/pages/product/product?spuId=${id}` })
}

function consumeIncomingKeyword() {
  try {
    const stored = String(uni.getStorageSync(KEYWORD_KEY) || '')
    if (stored) {
      uni.removeStorageSync(KEYWORD_KEY)
      keyword.value = stored
      searchMode.value = true
      return true
    }
  } catch {
    /* ignore */
  }
  return false
}

onLoad((query) => {
  const fromQuery = String(query?.keyword || '')
  if (fromQuery) {
    keyword.value = fromQuery
    searchMode.value = true
  }
  const cat = query?.categoryId || query?.id
  if (cat) {
    activeRootId.value = Number(cat)
    activeChildId.value = null
  }
})

onShow(async () => {
  try {
    if (!tree.value.length) await loadTree()
    const hasKw = consumeIncomingKeyword() || searchMode.value
    if (hasKw && keyword.value.trim()) searchMode.value = true
    await loadProducts()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '分类加载失败'
  }
})
</script>

<template>
  <view class="page category-page">
    <view class="search-bar">
      <input
        v-model="keyword"
        class="search-input"
        placeholder="搜索商品"
        confirm-type="search"
        @confirm="doSearch"
      />
      <view class="search-btn" @click="doSearch">搜索</view>
    </view>

    <view v-if="searchMode" class="search-panel">
      <view class="search-head">
        <text class="search-title">搜索「{{ keyword }}」</text>
        <text class="link" @click="clearSearch">返回分类</text>
      </view>
      <view v-if="loading" class="empty">加载中…</view>
      <view v-else-if="error" class="empty warn">{{ error }}</view>
      <view v-else-if="!products.length" class="empty">未找到相关商品</view>
      <view v-else class="product-list">
        <view v-for="item in products" :key="item.id" class="product-row" @click="goProduct(item.id)">
          <image class="thumb" :src="productCover(item.id, item.coverUrl)" mode="aspectFill" />
          <view class="meta">
            <text class="title">{{ item.title }}</text>
            <text class="muted">{{ item.shopName || item.brandName || '' }}</text>
            <text class="price">¥{{ Number(item.minPrice).toLocaleString() }}</text>
          </view>
        </view>
      </view>
    </view>

    <view v-else class="body">
      <scroll-view scroll-y class="nav">
        <view
          v-for="root in tree"
          :key="root.id"
          class="nav-item"
          :class="{ active: activeRootId === root.id }"
          @click="selectRoot(root.id)"
        >
          {{ root.name }}
        </view>
      </scroll-view>

      <scroll-view scroll-y class="content">
        <view v-if="children.length" class="child-wrap">
          <view
            class="child-chip"
            :class="{ active: activeChildId == null }"
            @click="selectChild(null)"
          >全部</view>
          <view
            v-for="child in children"
            :key="child.id"
            class="child-chip"
            :class="{ active: activeChildId === child.id }"
            @click="selectChild(child.id)"
          >{{ child.name }}</view>
        </view>

        <view v-if="loading" class="empty">加载中…</view>
        <view v-else-if="error" class="empty warn">{{ error }}</view>
        <view v-else-if="!products.length" class="empty">该分类暂无商品</view>
        <view v-else class="product-list">
          <view v-for="item in products" :key="item.id" class="product-row" @click="goProduct(item.id)">
            <image class="thumb" :src="productCover(item.id, item.coverUrl)" mode="aspectFill" />
            <view class="meta">
              <text class="title">{{ item.title }}</text>
              <text class="muted">{{ item.shopName || item.brandName || '' }}</text>
              <text class="price">¥{{ Number(item.minPrice).toLocaleString() }}</text>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped>
.category-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding-bottom: 0;
  box-sizing: border-box;
  background: #f3f5f7;
}

.search-bar {
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx;
  background: #fff;
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  min-height: 72rpx;
  padding: 0 24rpx;
  background: #f0f2f4;
  border-radius: 999rpx;
  font-size: 26rpx;
}

.search-btn {
  margin-left: 16rpx;
  padding: 0 28rpx;
  min-height: 72rpx;
  line-height: 72rpx;
  border-radius: 999rpx;
  background: #191919;
  color: #fff;
  font-size: 26rpx;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

.nav {
  width: 200rpx;
  height: 100%;
  background: #f0f2f4;
  flex-shrink: 0;
}

.nav-item {
  padding: 32rpx 20rpx;
  font-size: 26rpx;
  color: #4b5563;
  text-align: center;
  border-left: 6rpx solid transparent;
}

.nav-item.active {
  background: #fff;
  color: #17191c;
  font-weight: 700;
  border-left-color: #ff6a2b;
}

.content {
  flex: 1;
  height: 100%;
  background: #fff;
  padding: 16rpx 20rpx 40rpx;
  box-sizing: border-box;
}

.child-wrap {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 12rpx;
}

.child-chip {
  margin: 0 12rpx 12rpx 0;
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  background: #f3f5f7;
  color: #4b5563;
  font-size: 24rpx;
}

.child-chip.active {
  background: #fff3ec;
  color: #ff6a2b;
  font-weight: 600;
}

.search-panel {
  flex: 1;
  min-height: 0;
  background: #fff;
  padding: 16rpx 24rpx 40rpx;
  box-sizing: border-box;
}

.search-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12rpx;
}

.search-title {
  font-size: 28rpx;
  font-weight: 700;
}

.link {
  color: #ff6a2b;
  font-size: 24rpx;
}

.product-list {
  padding-bottom: 24rpx;
}

.product-row {
  display: flex;
  padding: 20rpx 0;
  border-bottom: 1px solid #f0f2f4;
}

.thumb {
  width: 160rpx;
  height: 160rpx;
  margin-right: 20rpx;
  border-radius: 12rpx;
  background: #e8ebef;
  flex-shrink: 0;
}

.meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.title {
  font-size: 28rpx;
  font-weight: 600;
  line-height: 1.35;
}

.price {
  margin-top: 12rpx;
  font-size: 30rpx;
  font-weight: 700;
  color: #17191c;
}

.empty {
  padding: 80rpx 24rpx;
  text-align: center;
  color: #6b7280;
  font-size: 26rpx;
}
</style>
