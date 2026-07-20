<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { PhTrash as Trash, PhArrowRight as ArrowRight, PhShoppingCart as ShoppingCart } from '@phosphor-icons/vue'
import type { CartSummary } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'
import { useCartSummaryStore } from '../stores/cart-summary'
import { authPath, isLoggedIn } from '../auth'
import { productCover } from '../catalog/covers'

const router = useRouter()
const store = useCartSummaryStore()
const cart = ref<CartSummary | null>(null)
const loading = ref(true)
const error = ref('')
const guest = ref(!isLoggedIn())
const selectedIds = computed(() =>
  cart.value?.shops.flatMap(s => s.items.filter(i => i.selected && i.saleable && i.stockEnough).map(i => i.id)) ?? []
)

async function load() {
  loading.value = true
  error.value = ''
  guest.value = !isLoggedIn()
  if (guest.value) {
    loading.value = false
    return
  }
  try {
    cart.value = await tradeApi.cart()
    await store.refresh()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '购物车加载失败'
  } finally {
    loading.value = false
  }
}

async function update(id: number, body: { quantity?: number; selected?: boolean }) {
  try {
    cart.value = await tradeApi.updateCart(id, body)
    await store.refresh()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '更新失败'
  }
}

async function remove(id: number) {
  cart.value = await tradeApi.deleteCart(id)
  await store.refresh()
}

function checkout() {
  if (selectedIds.value.length) router.push({ path: '/checkout', query: { items: selectedIds.value.join(',') } })
}

onMounted(load)
</script>

<template>
  <section class="trade-page cart-page">
    <header class="trade-hero">
      <p>YOUR CART</p>
      <h1>购物车</h1>
      <span>价格、库存与可售状态会在结算前再次校验。</span>
    </header>

    <div v-if="guest" class="trade-empty auth-needed">
      <ShoppingCart :size="42" />
      <h2>需要注册后才能使用购物车</h2>
      <p>你可以先浏览商品；加购与结算需登录极客账号。</p>
      <div class="auth-needed-actions">
        <RouterLink class="account-cta" :to="authPath('register', { reason: 'cart', redirect: '/cart' })">去注册</RouterLink>
        <RouterLink class="account-cta ghost" :to="authPath('login', { reason: 'cart', redirect: '/cart' })">已有账号登录</RouterLink>
      </div>
    </div>
    <div v-else-if="loading" class="trade-state">正在同步购物车…</div>
    <div v-else-if="error" class="trade-state error" role="alert">
      <p>{{ error }}</p>
      <RouterLink :to="authPath('login', { redirect: '/cart' })">登录后查看购物车</RouterLink>
    </div>
    <div v-else-if="!cart?.itemCount" class="trade-empty">
      <ShoppingCart :size="42" />
      <h2>购物车还是空的</h2>
      <p>从真正值得研究的数码好物开始。</p>
      <RouterLink to="/search">去逛逛</RouterLink>
    </div>
    <div v-else class="cart-layout">
      <div class="cart-shops">
        <article v-for="shop in cart?.shops" :key="shop.shopId" class="cart-shop">
          <h2>{{ shop.shopName }}</h2>
          <div
            v-for="item in shop.items"
            :key="item.id"
            class="cart-row"
            :class="{ invalid: !item.saleable || !item.stockEnough }"
          >
            <input
              :id="`cart-${item.id}`"
              type="checkbox"
              :checked="item.selected"
              :disabled="!item.saleable || !item.stockEnough"
              @change="update(item.id, { selected: ($event.target as HTMLInputElement).checked })"
            />
            <img :src="productCover(item.spuId, item.coverUrl)" :alt="item.titleSnapshot || `商品 ${item.spuId}`" />
            <label :for="`cart-${item.id}`">
              <strong>{{ item.titleSnapshot || `商品 #${item.spuId}` }}</strong>
              <small>SKU {{ item.skuId }} · 库存 {{ item.availableQty }}</small>
              <em v-if="item.invalidReason">{{ item.invalidReason }}</em>
              <em v-else-if="item.priceChanged">价格已由 ¥{{ Number(item.addedPrice).toLocaleString() }} 更新</em>
            </label>
            <div class="quantity-stepper">
              <button type="button" :disabled="item.quantity <= 1" @click="update(item.id, { quantity: item.quantity - 1 })">−</button>
              <span>{{ item.quantity }}</span>
              <button type="button" :disabled="item.quantity >= item.availableQty" @click="update(item.id, { quantity: item.quantity + 1 })">＋</button>
            </div>
            <strong class="line-price">¥{{ Number(item.lineAmount).toLocaleString() }}</strong>
            <button class="icon-button" type="button" aria-label="删除商品" @click="remove(item.id)"><Trash :size="19" /></button>
          </div>
        </article>
      </div>
      <aside class="cart-summary">
        <small>ORDER SUMMARY</small>
        <h2>订单摘要</h2>
        <dl>
          <div><dt>已选商品</dt><dd>{{ cart?.selectedCount }} 件</dd></div>
          <div><dt>商品金额</dt><dd>¥{{ Number(cart?.selectedAmount ?? 0).toLocaleString() }}</dd></div>
          <div><dt>优惠</dt><dd>结算页计算</dd></div>
        </dl>
        <button type="button" :disabled="!selectedIds.length" @click="checkout">去结算 <ArrowRight :size="18" /></button>
      </aside>
    </div>
  </section>
</template>
