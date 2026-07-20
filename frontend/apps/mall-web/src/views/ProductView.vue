<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { PhHeart as Heart, PhStorefront as Storefront, PhShieldCheck as Shield, PhTruck as Truck } from '@phosphor-icons/vue'
import type { ProductDetail } from '@geek-mall/api-types'
import { mallApi } from '../api'
import { tradeApi } from '../api/trade'
import { requireAuth } from '../auth'
import { useCartSummaryStore } from '../stores/cart-summary'
import { productCover } from '../catalog/covers'

const route = useRoute()
const router = useRouter()
const cartStore = useCartSummaryStore()
const product = ref<ProductDetail | null>(null)
const selectedSku = ref<number | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const adding = ref(false)
const sku = computed(() => product.value?.skus.find(item => item.id === selectedSku.value) ?? product.value?.skus[0])

async function load() {
  loading.value = true
  error.value = ''
  try {
    product.value = await mallApi.request<ProductDetail>(`/api/v1/products/spus/${route.params.spuId}`)
    selectedSku.value = product.value.skus[0]?.id ?? null
  } catch (e) {
    error.value = e instanceof Error ? e.message : '商品加载失败'
  } finally {
    loading.value = false
  }
}

async function favorite() {
  notice.value = ''
  if (!requireAuth(router, { mode: 'register', reason: 'cart', redirect: route.fullPath })) return
  try {
    await mallApi.request(`/api/v1/user/favorites/${route.params.spuId}`, { method: 'POST' })
    notice.value = '已加入收藏'
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '收藏失败'
  }
}

async function addToCart(buyNow = false) {
  if (!sku.value) return
  notice.value = ''
  if (!requireAuth(router, {
    mode: 'register',
    reason: buyNow ? 'buy' : 'cart',
    redirect: route.fullPath
  })) return
  adding.value = true
  try {
    const result = await tradeApi.addCart(sku.value.id, 1)
    await cartStore.refresh()
    notice.value = `已加入购物车，共 ${result.itemCount} 件`
    if (buyNow) {
      const item = result.shops.flatMap(shop => shop.items).find(item => item.skuId === sku.value?.id)
      if (item) await router.push({ path: '/checkout', query: { items: String(item.id) } })
    }
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '加购失败'
  } finally {
    adding.value = false
  }
}

watch(() => route.params.spuId, load)
onMounted(load)
</script>

<template>
  <section class="product-page" aria-live="polite">
    <div v-if="loading" class="detail-state">正在载入商品资料…</div>
    <div v-else-if="error" class="detail-state error" role="alert">{{ error }}</div>
    <template v-else-if="product">
      <div class="product-visual"><img :src="productCover(product.id, product.coverUrl)" :alt="product.title" /></div>
      <article class="product-buy">
        <p>{{ product.brandName }} / {{ product.categoryName }}</p>
        <h1>{{ product.title }}</h1>
        <h2>{{ product.subtitle }}</h2>
        <div class="seller-tags detail-tags">
          <span class="seller-tag">{{ product.sellerLabel || '第三方店铺' }}</span>
          <span v-if="product.ownBrand" class="seller-tag own">自有品牌</span>
          <span class="seller-tag mute">{{ product.deliveryHint || '多仓发货' }}</span>
        </div>
        <div class="detail-price">
          <small>到手价</small>
          <strong>¥{{ Number(sku?.salePrice ?? product.minPrice).toLocaleString() }}</strong>
          <del v-if="sku">¥{{ Number(sku.marketPrice).toLocaleString() }}</del>
        </div>
        <fieldset>
          <legend>选择规格</legend>
          <button
            v-for="item in product.skus"
            :key="item.id"
            type="button"
            :class="{ active: selectedSku === item.id }"
            @click="selectedSku = item.id"
          >
            <b>{{ item.saleAttributes.map(attr => attr.value).join(' / ') || item.skuCode }}</b>
            <span>¥{{ Number(item.salePrice).toLocaleString() }}</span>
          </button>
        </fieldset>
        <div class="buy-actions three">
          <button type="button" @click="favorite"><Heart :size="20" />收藏</button>
          <button type="button" :disabled="adding" @click="addToCart(false)">加入购物车</button>
          <button type="button" :disabled="adding" @click="addToCart(true)">立即购买</button>
        </div>
        <p v-if="notice" class="action-notice">{{ notice }}</p>
        <ul>
          <li><Shield :size="19" />{{ product.sellerLabel || '正品保障' }} · 品牌授权</li>
          <li><Truck :size="19" />{{ product.deliveryHint || '多仓发货' }} · 配送进度透明</li>
        </ul>
      </article>
      <aside class="shop-summary">
        <img :src="product.shopLogo" alt="" />
        <div>
          <small>由此店铺销售 · {{ product.sellerLabel }}</small>
          <h2>{{ product.shopName }}</h2>
          <span>{{ product.shopScore }} 分</span>
        </div>
        <RouterLink :to="`/shop/${product.shopId}`"><Storefront :size="18" />进入店铺</RouterLink>
      </aside>
      <article class="product-story">
        <nav>
          <a href="#details">商品详情</a>
          <a href="#parameters">规格参数</a>
          <a href="#service">服务说明</a>
        </nav>
        <section id="details" v-html="product.descriptionHtml"></section>
        <section id="parameters">
          <h2>规格参数</h2>
          <dl>
            <div v-for="attr in product.attributes" :key="attr.id">
              <dt>{{ attr.name }}</dt>
              <dd>{{ attr.value }}{{ attr.unit ?? '' }}</dd>
            </div>
          </dl>
        </section>
        <section id="service">
          <h2>包装与服务</h2>
          <p>{{ product.packageList }}</p>
          <p>{{ product.serviceDesc }}</p>
        </section>
      </article>
    </template>
  </section>
</template>
