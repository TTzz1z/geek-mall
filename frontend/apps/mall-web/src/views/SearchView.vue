<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { PhMagnifyingGlass as SearchIcon, PhSlidersHorizontal as Sliders, PhArrowRight as ArrowRight } from '@phosphor-icons/vue'
import type { PageResult, ProductSummary } from '@geek-mall/api-types'
import { mallApi } from '../api'
import { sellerLabelOf } from '../catalog'

const route = useRoute(), router = useRouter()
const keyword = ref(String(route.query.keyword ?? ''))
const sort = ref(String(route.query.sort ?? 'comprehensive'))
const priceMin = ref(String(route.query.priceMin ?? ''))
const priceMax = ref(String(route.query.priceMax ?? ''))
const result = ref<PageResult<ProductSummary>>({ items: [], page: 1, pageSize: 20, total: 0, hasNext: false })
const loading = ref(false), error = ref('')
const title = computed(() => keyword.value ? `“${keyword.value}” 的搜索结果` : '全部数码好物')

async function load() {
  loading.value = true; error.value = ''
  const params = new URLSearchParams({ page: '1', pageSize: '20', sort: sort.value })
  if (keyword.value) params.set('keyword', keyword.value)
  if (route.query.categoryId) params.set('categoryId', String(route.query.categoryId))
  if (route.query.shopId) params.set('shopId', String(route.query.shopId))
  if (priceMin.value) params.set('priceMin', priceMin.value)
  if (priceMax.value) params.set('priceMax', priceMax.value)
  try { result.value = await mallApi.request<PageResult<ProductSummary>>(`/api/v1/search/products?${params}`) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '搜索服务暂不可用' }
  finally { loading.value = false }
}
function submit() { router.replace({ path: '/search', query: { ...route.query, keyword: keyword.value || undefined, sort: sort.value, priceMin: priceMin.value || undefined, priceMax: priceMax.value || undefined } }); load() }
watch(() => route.query.keyword, value => { keyword.value = String(value ?? ''); load() })
onMounted(load)
</script>

<template>
  <section class="catalog-page">
    <header class="catalog-hero"><p>CURATED TECH / REAL-TIME INDEX</p><h1>{{ title }}</h1><span>来自 Elasticsearch 的实时商品索引，可按价格、销量与评分筛选。</span></header>
    <form class="catalog-toolbar" role="search" @submit.prevent="submit">
      <label class="catalog-search"><SearchIcon :size="20" /><span class="sr-only">搜索关键词</span><input v-model="keyword" placeholder="试试：轻薄本、MiniLED、32GB" /></label>
      <label><span>最低价</span><input v-model="priceMin" inputmode="decimal" placeholder="¥ 0" /></label><label><span>最高价</span><input v-model="priceMax" inputmode="decimal" placeholder="不限" /></label>
      <label><span>排序</span><select v-model="sort"><option value="comprehensive">综合推荐</option><option value="sales">销量优先</option><option value="priceAsc">价格从低到高</option><option value="priceDesc">价格从高到低</option><option value="rating">评分优先</option></select></label>
      <button type="submit"><Sliders :size="18" />应用筛选</button>
    </form>
    <div class="catalog-state" aria-live="polite"><span v-if="loading">正在检索商品索引…</span><span v-else-if="!error">共 {{ result.total }} 件商品</span><strong v-if="error" role="alert">{{ error }}</strong></div>
    <div v-if="!loading && result.items.length" class="product-grid">
      <RouterLink
        v-for="product in result.items"
        :key="product.id"
        class="product-tile"
        :to="{ path: `/product/${product.id}`, query: { from: route.fullPath } }"
      >
        <div><img :src="product.coverUrl" :alt="product.title" loading="lazy" /></div><small>{{ product.brandName }} · {{ product.shopName }}</small><h2>{{ product.title }}</h2><p>{{ product.subtitle }}</p>
        <div class="seller-tags"><span class="seller-tag">{{ sellerLabelOf(product) }}</span><span v-if="product.ownBrand" class="seller-tag own">自有品牌</span></div>
        <footer><strong>¥{{ Number(product.minPrice).toLocaleString() }}</strong><span>{{ product.rating }} 分 · 已售 {{ product.salesCount }}<ArrowRight :size="17" /></span></footer>
      </RouterLink>
    </div>
    <div v-else-if="!loading && !error" class="empty-catalog"><SearchIcon :size="34" /><h2>暂时没有匹配商品</h2><p>尝试减少筛选条件，或搜索更宽泛的型号与品类。</p></div>
  </section>
</template>
