<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { PhHeart as Heart, PhStorefront as Store, PhClock as Clock } from '@phosphor-icons/vue'
import { mallApi } from '../api'

const route = useRoute()
const items = ref<any[]>([])
const error = ref('')
const loading = ref(true)

const config = computed(() => {
  if (route.path.includes('/shops')) {
    return { title: '关注店铺', path: '/api/v1/user/shops', shop: true, icon: Store, empty: '还没有关注店铺' }
  }
  if (route.path.includes('/history')) {
    return { title: '浏览历史', path: '/api/v1/user/history', shop: false, icon: Clock, empty: '暂无浏览记录' }
  }
  return { title: '我的收藏', path: '/api/v1/user/favorites', shop: false, icon: Heart, empty: '收藏夹还是空的' }
})

onMounted(async () => {
  try {
    items.value = await mallApi.request<any[]>(config.value.path)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '请先登录后查看'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="account-panel">
    <header class="panel-head">
      <h1>{{ config.title }}</h1>
    </header>
    <div v-if="loading" class="account-empty">正在加载…</div>
    <div v-else-if="error" class="account-empty">
      {{ error }}
      <RouterLink class="account-cta" to="/auth/login">前往登录</RouterLink>
    </div>
    <div v-else-if="!items.length" class="account-empty soft">
      <component :is="config.icon" :size="48" weight="thin" />
      <h3>{{ config.empty }}</h3>
      <RouterLink class="account-cta" to="/search">去逛逛</RouterLink>
    </div>
    <div v-else class="account-collect">
      <RouterLink
        v-for="item in items"
        :key="item.id"
        :to="config.shop ? `/shop/${item.id}` : `/product/${item.id}`"
      >
        <img :src="item.logoUrl || item.coverUrl" :alt="item.shopName || item.title" />
        <div>
          <h2>{{ item.shopName || item.title }}</h2>
          <p>{{ item.subtitle || (item.score != null ? `店铺评分 ${item.score}` : '') }}</p>
          <strong v-if="item.minPrice">¥{{ Number(item.minPrice).toLocaleString() }}</strong>
        </div>
      </RouterLink>
    </div>
  </div>
</template>
