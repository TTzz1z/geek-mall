<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { PhArrowLeft as ArrowLeft, PhArrowRight as ArrowRight } from '@phosphor-icons/vue'
import type { PageResult, ProductSummary } from '@geek-mall/api-types'
import { mallApi } from '../api'

const route = useRoute()
const keyword = computed(() => String(route.query.keyword || route.params.brandId || route.params.code || '数码'))
const result = ref<PageResult<ProductSummary>>({ items: [], page: 1, pageSize: 12, total: 0, hasNext: false })
const loading = ref(false)
const error = ref('')
const title = computed(() => String(route.meta.title ?? '精选'))

async function load() {
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ page: '1', pageSize: '12', keyword: keyword.value })
  if (route.query.categoryId) params.set('categoryId', String(route.query.categoryId))
  try {
    result.value = await mallApi.request(`/api/v1/search/products?${params}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => [route.path, route.params, route.query], load, { deep: true })
onMounted(load)
</script>

<template>
  <section class="route-page collection-lite">
    <p class="route-kicker">GEEK MALL / {{ route.path }}</p>
    <h1>{{ title }}</h1>
    <p>基于搜索索引的专题页：关键词「{{ keyword }}」。</p>
    <p v-if="loading">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <div v-else class="lite-grid">
      <RouterLink v-for="item in result.items" :key="item.id" :to="`/product/${item.id}`" class="lite-card">
        <img :src="item.coverUrl" :alt="item.title" />
        <strong>{{ item.title }}</strong>
        <b>¥{{ Number(item.minPrice).toLocaleString() }}</b>
      </RouterLink>
      <p v-if="!result.items.length">暂无商品，试试换个关键词。</p>
    </div>
    <div class="lite-actions">
      <RouterLink to="/"><ArrowLeft :size="18" />返回首页</RouterLink>
      <RouterLink :to="{ path: '/search', query: { keyword: keyword } }">完整搜索 <ArrowRight :size="18" /></RouterLink>
    </div>
  </section>
</template>

<style scoped>
.collection-lite .error { color: #c62828; }
.lite-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 14px; margin: 24px 0; }
.lite-card { display: flex; flex-direction: column; gap: 8px; text-decoration: none; color: inherit; }
.lite-card img { width: 100%; aspect-ratio: 1; object-fit: cover; background: #eef1f4; }
.lite-actions { display: flex; gap: 18px; }
.lite-actions a { display: inline-flex; align-items: center; gap: 6px; }
</style>
