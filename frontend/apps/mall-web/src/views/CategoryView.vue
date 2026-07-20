<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { PhArrowRight as ArrowRight, PhFolders as Folders } from '@phosphor-icons/vue'
import type { ProductSummary } from '@geek-mall/api-types'
import { mallApi } from '../api'
import { resolveCategoryId, sellerLabelOf } from '../catalog'

type CatNode = { id: number; name: string; children?: CatNode[] }

const route = useRoute()
const tree = ref<CatNode[]>([])
const products = ref<ProductSummary[]>([])
const loading = ref(true)
const error = ref('')
const categoryId = computed(() => resolveCategoryId(route.params.id as string))

const current = computed(() => {
  const id = categoryId.value
  if (id == null) return null
  const walk = (nodes: CatNode[]): CatNode | null => {
    for (const n of nodes) {
      if (n.id === id) return n
      const hit = walk(n.children || [])
      if (hit) return hit
    }
    return null
  }
  return walk(tree.value)
})

const title = computed(() => current.value?.name || (categoryId.value == null ? '全部分类' : `分类 ${categoryId.value}`))
const children = computed(() => current.value?.children || (categoryId.value == null ? tree.value : []))

async function loadTree() {
  tree.value = await mallApi.request<CatNode[]>('/api/v1/products/categories/tree')
}

async function loadProducts() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams()
    if (categoryId.value != null) params.set('categoryId', String(categoryId.value))
    products.value = await mallApi.request<ProductSummary[]>(`/api/v1/products/catalog?${params}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '分类商品加载失败'
    products.value = []
  } finally {
    loading.value = false
  }
}

watch(() => route.params.id, loadProducts)
onMounted(async () => {
  try {
    await loadTree()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '分类树加载失败'
  }
  await loadProducts()
})
</script>

<template>
  <section class="catalog-page">
    <header class="catalog-hero">
      <p>PLATFORM CATEGORY TREE</p>
      <h1>{{ title }}</h1>
      <span>分类由平台统一维护；GEEK 自营、官方旗舰店与第三方店铺商品共用同一棵树。</span>
    </header>

    <div v-if="children.length" class="category-children">
      <RouterLink v-for="child in children" :key="child.id" :to="`/category/${child.id}`">
        <Folders :size="18" />
        <strong>{{ child.name }}</strong>
        <span>{{ (child.children || []).length ? `${child.children!.length} 个子类` : '查看商品' }}</span>
      </RouterLink>
    </div>

    <div class="catalog-state" aria-live="polite">
      <span v-if="loading">正在加载分类商品…</span>
      <span v-else-if="!error">共 {{ products.length }} 件在售商品</span>
      <strong v-if="error" role="alert">{{ error }}</strong>
    </div>

    <div v-if="!loading && products.length" class="product-grid">
      <RouterLink
        v-for="product in products"
        :key="product.id"
        class="product-tile"
        :to="{ path: `/product/${product.id}`, query: { from: route.fullPath } }"
      >
        <div><img :src="product.coverUrl" :alt="product.title" loading="lazy" /></div>
        <small>{{ product.brandName }} · {{ product.shopName }}</small>
        <h2>{{ product.title }}</h2>
        <p>{{ product.subtitle }}</p>
        <div class="seller-tags">
          <span class="seller-tag">{{ sellerLabelOf(product) }}</span>
          <span v-if="product.ownBrand" class="seller-tag own">自有品牌</span>
        </div>
        <footer>
          <strong>¥{{ Number(product.minPrice).toLocaleString() }}</strong>
          <span>{{ product.rating }} 分 <ArrowRight :size="17" /></span>
        </footer>
      </RouterLink>
    </div>
    <div v-else-if="!loading && !error" class="empty-catalog">
      <h2>该分类暂无在售商品</h2>
      <p>可切换子类，或前往搜索查看全站货盘。</p>
      <RouterLink to="/search">去搜索</RouterLink>
    </div>
  </section>
</template>
