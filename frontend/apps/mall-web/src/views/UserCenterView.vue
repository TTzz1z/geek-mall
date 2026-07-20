<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import {
  PhClipboard as Clipboard,
  PhArrowRight as ArrowRight
} from '@phosphor-icons/vue'
import type { AuthPrincipal, TradeOrderSummary } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'
import { clearMallAuth, userApi, type MemberProfile } from '../api/user'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const principal = ref<AuthPrincipal | null>(null)
const member = ref<MemberProfile | null>(null)
const orders = ref<TradeOrderSummary[]>([])
const counts = ref({ favorites: 0, coupons: 0, messages: 0 })

const recentOrders = computed(() => orders.value.slice(0, 3))
const pendingPay = computed(() => orders.value.filter(o => o.status === 'PENDING_PAYMENT').length)
const paid = computed(() => orders.value.filter(o => o.status === 'PAID').length)

const statusLabel: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  CANCELLED: '已关闭',
  RESERVING: '锁定库存',
  RESERVATION_RETRY: '库存确认中'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    principal.value = await userApi.session()
    const [memberData, orderData, favorites, coupons, messages] = await Promise.all([
      userApi.member().catch(() => null),
      tradeApi.orders().catch(() => [] as TradeOrderSummary[]),
      userApi.favorites().catch(() => []),
      userApi.myCoupons().catch(() => []),
      userApi.messages().catch(() => [])
    ])
    member.value = memberData
    orders.value = orderData
    counts.value = {
      favorites: favorites.length,
      coupons: coupons.length,
      messages: messages.length
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '请先登录'
    principal.value = null
  } finally {
    loading.value = false
  }
}

async function logout() {
  const refresh = localStorage.getItem('geek.refreshToken')
  try {
    if (refresh) await userApi.logout(refresh)
  } catch { /* ignore */ }
  clearMallAuth()
  await router.push('/auth/login')
}

onMounted(load)
</script>

<template>
  <div class="account-panel account-home">
    <div v-if="loading" class="account-empty">正在加载账户信息…</div>
    <div v-else-if="error || !principal" class="account-empty">
      <p>{{ error || '未登录' }}</p>
      <RouterLink class="account-cta" to="/auth/login">前往登录</RouterLink>
    </div>

    <template v-else>
      <div class="account-mosaic">
        <article class="account-card profile-card">
          <div class="avatar" aria-hidden="true">{{ principal.username.slice(0, 1).toUpperCase() }}</div>
          <div class="profile-copy">
            <small>当前账号</small>
            <h1>{{ principal.username }}</h1>
            <p>UID {{ principal.userId }} · {{ member?.levelCode || '普通会员' }}</p>
            <div class="profile-links">
              <RouterLink to="/user/security">账户安全</RouterLink>
              <button type="button" @click="logout">退出登录</button>
            </div>
          </div>
        </article>

        <article class="account-card points-card">
          <header>
            <span>我的积分</span>
            <RouterLink to="/user/points">明细 <ArrowRight :size="14" /></RouterLink>
          </header>
          <strong>{{ member?.pointBalance ?? 0 }}</strong>
          <p>冻结 {{ member?.frozenPoints ?? 0 }} · 成长值 {{ member?.growthValue ?? 0 }}</p>
          <div class="mini-stats">
            <RouterLink to="/user/coupons"><b>{{ counts.coupons }}</b><span>优惠券</span></RouterLink>
            <RouterLink to="/user/favorites"><b>{{ counts.favorites }}</b><span>收藏</span></RouterLink>
            <RouterLink to="/user/messages"><b>{{ counts.messages }}</b><span>消息</span></RouterLink>
          </div>
        </article>
      </div>

      <article class="account-card orders-card">
        <header class="orders-head">
          <div>
            <h2>最近订单</h2>
            <p>
              待支付 {{ pendingPay }}
              <i>·</i>
              已支付 {{ paid }}
            </p>
          </div>
          <RouterLink to="/user/orders">全部订单 <ArrowRight :size="14" /></RouterLink>
        </header>

        <div v-if="!recentOrders.length" class="account-empty soft">
          <Clipboard :size="48" weight="thin" />
          <h3>暂无订单</h3>
          <p>去挑一件值得研究的数码好物</p>
          <RouterLink class="account-cta" to="/search">去选购</RouterLink>
        </div>

        <div v-else class="recent-orders">
          <RouterLink
            v-for="order in recentOrders"
            :key="order.orderNo"
            :to="`/user/orders/${order.orderNo}`"
            class="recent-order"
          >
            <div>
              <strong>{{ order.orderNo }}</strong>
              <span>{{ new Date(order.createdAt).toLocaleString() }}</span>
            </div>
            <em>{{ statusLabel[order.status] ?? order.status }}</em>
            <b>¥{{ Number(order.payableAmount).toLocaleString() }}</b>
          </RouterLink>
        </div>
      </article>
    </template>
  </div>
</template>
