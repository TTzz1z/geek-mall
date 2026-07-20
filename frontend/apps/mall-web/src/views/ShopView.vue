<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { mallApi } from '../api'
import type { ProductSummary } from '@geek-mall/api-types'
import { sellerLabelOf, shopTypeLabel } from '../catalog'

const route = useRoute()
const shop = ref<any>(null)
const error = ref('')
const loading = ref(true)
const sellerLabel = computed(() => shopTypeLabel(shop.value?.shopType))

async function load() {
  loading.value = true
  error.value = ''
  try {
    shop.value = await mallApi.request(`/api/v1/shops/${route.params.shopId}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '店铺加载失败'
    shop.value = null
  } finally {
    loading.value = false
  }
}

watch(() => route.params.shopId, load)
onMounted(load)
</script>

<template>
  <section class="shop-page">
    <div v-if="loading" class="detail-state">正在载入店铺…</div>
    <div v-else-if="error" class="detail-state error">{{ error }}</div>
    <template v-else-if="shop">
      <header>
        <img :src="shop.logoUrl" alt="" />
        <div>
          <p>VERIFIED STORE</p>
          <h1>{{ shop.shopName }}</h1>
          <div class="seller-tags detail-tags">
            <span class="seller-tag">{{ sellerLabel }}</span>
            <span class="seller-tag mute">{{ shop.shopType }}</span>
          </div>
          <span>{{ shop.announcement }}</span>
        </div>
        <strong>{{ shop.score }}<small>店铺评分</small></strong>
      </header>
      <div class="shop-products">
        <RouterLink
          v-for="p in (shop.products as ProductSummary[])"
          :key="p.id"
          :to="{ path: `/product/${p.id}`, query: { from: route.fullPath } }"
        >
          <img :src="p.coverUrl" :alt="p.title" />
          <small>{{ p.brandName || shop.shopName }}</small>
          <h2>{{ p.title }}</h2>
          <p>{{ p.subtitle }}</p>
          <div class="seller-tags">
            <span class="seller-tag">{{ sellerLabelOf({ ...p, shopType: shop.shopType, sellerLabel: p.sellerLabel || sellerLabel }) }}</span>
            <span v-if="p.ownBrand" class="seller-tag own">自有品牌</span>
          </div>
          <strong>¥{{ Number(p.minPrice).toLocaleString() }}</strong>
        </RouterLink>
      </div>
    </template>
  </section>
</template>
