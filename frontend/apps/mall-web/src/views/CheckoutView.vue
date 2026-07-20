<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { mallApi } from '../api'
import { addressApi, marketingApi } from '../api/phase4'
import type { CheckoutPayload } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'
import { useRoute, useRouter } from 'vue-router'
import { authPath, isLoggedIn } from '../auth'

const route = useRoute()
const router = useRouter()
const preview = ref<CheckoutPayload | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const needAuth = ref(!isLoggedIn())
const idempotencyKey = ref(crypto.randomUUID())
const addresses = ref<any[]>([])
const coupons = ref<any[]>([])
const selectedAddressId = ref<number | null>(null)
const selectedCouponId = ref<number | null>(null)
const pointsToUse = ref(0)

const itemIds = String(route.query.items ?? '').split(',').map(Number).filter(Boolean)

async function loadAddresses() {
  if (!isLoggedIn()) {
    needAuth.value = true
    return
  }
  needAuth.value = false
  try { addresses.value = await addressApi.list(); const d = addresses.value.find(a => a.isDefault); if (d) selectedAddressId.value = d.id } catch {}
  try { coupons.value = (await marketingApi.myCoupons()).filter((c: any) => c.status === 'UNUSED') } catch {}
}

async function calculate() {
  if (!selectedAddressId.value) {
    error.value = '请先选择地址簿中的收货地址'
    preview.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    const addr = addresses.value.find(a => a.id === selectedAddressId.value)
    preview.value = await tradeApi.preview({
      cartItemIds: itemIds,
      addressId: selectedAddressId.value,
      receiver: addr.receiver,
      mobile: addr.mobile,
      regionCode: addr.regionCode,
      addressDetail: addr.detail,
      deliveryMethod: 'STANDARD',
      userCouponId: selectedCouponId.value,
      pointsToUse: pointsToUse.value || null
    } as any)
  } catch (e) { error.value = e instanceof Error ? e.message : '结算失败' } finally { loading.value = false }
}

async function submit() {
  if (!preview.value || submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    const order = await tradeApi.createOrder({ checkoutToken: preview.value.checkoutToken, addressId: selectedAddressId.value, remarkByShop: {} }, idempotencyKey.value)
    await router.push(`/cashier/${order.orderNo}`)
  } catch (e) { error.value = e instanceof Error ? e.message : '订单提交失败' } finally { submitting.value = false }
}

onMounted(async () => { await loadAddresses(); if (!needAuth.value) await calculate() })
</script>
<template>
  <section class="trade-page checkout-page">
    <header class="trade-hero compact"><h1>确认订单</h1></header>
    <div v-if="needAuth" class="trade-empty auth-needed">
      <h2>结算前请先注册或登录</h2>
      <p>未登录无法创建订单与支付。</p>
      <div class="auth-needed-actions">
        <RouterLink class="account-cta" :to="authPath('register', { reason: 'checkout', redirect: route.fullPath })">去注册</RouterLink>
        <RouterLink class="account-cta ghost" :to="authPath('login', { reason: 'checkout', redirect: route.fullPath })">去登录</RouterLink>
      </div>
    </div>
    <div v-else class="checkout-grid">
      <div>
        <article class="checkout-panel">
          <h2>收货地址</h2>
          <select v-model="selectedAddressId" @change="calculate">
            <option disabled :value="null">请选择地址簿地址</option>
            <option v-for="a in addresses" :key="a.id" :value="a.id">{{ a.receiver }} · {{ a.regionName }}{{ a.detail }}</option>
          </select>
          <p v-if="!addresses.length">暂无地址，请先到「地址簿」添加。</p>
          <h2>优惠券 / 积分</h2>
          <select v-model="selectedCouponId" @change="calculate">
            <option :value="null">不使用优惠券</option>
            <option v-for="c in coupons" :key="c.id" :value="c.id">{{ c.name }} -¥{{ c.discountAmount }}</option>
          </select>
          <label>使用积分<input v-model.number="pointsToUse" type="number" min="0" @change="calculate" /></label>
        </article>
        <div v-if="loading" class="trade-state">正在生成结算快照…</div>
        <div v-else-if="error" class="trade-state error">{{ error }}</div>
        <template v-else-if="preview">
          <article v-for="shop in preview.shopGroups" :key="shop.shopId" class="checkout-panel shop-checkout">
            <h2>{{ shop.shopName }}</h2>
            <div v-for="item in shop.items" :key="item.skuId" class="checkout-item">
              <img :src="item.coverUrl" :alt="item.title" />
              <div><strong>{{ item.title }}</strong><small>× {{ item.quantity }}</small></div>
              <span>¥{{ Number(item.payableAmount).toLocaleString() }}</span>
            </div>
          </article>
          <article v-if="preview.promotionLines?.length" class="checkout-panel">
            <h3>优惠分摊</h3>
            <p v-for="line in preview.promotionLines" :key="line.type + line.sourceId">{{ line.label }}：-¥{{ line.amount }}</p>
          </article>
        </template>
      </div>
      <aside v-if="preview" class="checkout-summary">
        <strong>¥{{ Number(preview.payableAmount).toLocaleString() }}</strong>
        <button type="button" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '提交订单' }}</button>
      </aside>
    </div>
  </section>
</template>
