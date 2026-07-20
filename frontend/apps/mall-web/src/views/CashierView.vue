<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { PhCreditCard as CreditCard, PhLockKey as Lock } from '@phosphor-icons/vue'
import type { TradeOrderDetail } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'
import { authPath, isLoggedIn } from '../auth'

const route = useRoute()
const router = useRouter()
const order = ref<TradeOrderDetail | null>(null)
const loading = ref(true)
const paying = ref(false)
const error = ref('')
const needAuth = ref(!isLoggedIn())

async function load() {
  if (!isLoggedIn()) {
    needAuth.value = true
    loading.value = false
    return
  }
  needAuth.value = false
  try {
    order.value = await tradeApi.order(String(route.params.orderNo))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}

async function pay() {
  if (!order.value || paying.value) return
  paying.value = true
  error.value = ''
  try {
    const payment = await tradeApi.createPayment(order.value.orderNo)
    const result = await tradeApi.simulatePayment(payment.paymentNo)
    await router.push({ path: '/payment/result', query: { paymentNo: payment.paymentNo, orderNo: result.orderNo } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '支付失败'
  } finally {
    paying.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="trade-page cashier-page">
    <div v-if="needAuth" class="trade-empty auth-needed">
      <h2>支付需要登录账号</h2>
      <p>注册或登录后可继续完成支付。</p>
      <div class="auth-needed-actions">
        <RouterLink class="account-cta" :to="authPath('register', { reason: 'pay', redirect: route.fullPath })">去注册</RouterLink>
        <RouterLink class="account-cta ghost" :to="authPath('login', { reason: 'pay', redirect: route.fullPath })">去登录</RouterLink>
      </div>
    </div>
    <div v-else-if="loading" class="trade-state">正在载入收银台…</div>
    <div v-else-if="error" class="trade-state error" role="alert">{{ error }}</div>
    <article v-else-if="order" class="cashier-card">
      <p>GEEK PAY</p>
      <CreditCard :size="52" />
      <h1>确认支付</h1>
      <span>订单 {{ order.orderNo }}</span>
      <strong>¥{{ Number(order.payableAmount).toLocaleString() }}</strong>
      <ul>
        <li><Lock :size="17" />本展示项目使用模拟支付，不会产生真实扣款</li>
        <li>订单状态：{{ order.status }}</li>
        <li>库存状态：{{ order.inventoryStatus }}</li>
      </ul>
      <button type="button" :disabled="paying || order.status !== 'PENDING_PAYMENT'" @click="pay">
        {{ paying ? '正在确认支付…' : '确认支付' }}
      </button>
    </article>
  </section>
</template>
