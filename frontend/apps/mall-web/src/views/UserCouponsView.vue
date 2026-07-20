<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { PhTicket as Ticket } from '@phosphor-icons/vue'
import { userApi, type AvailableCoupon, type UserCoupon } from '../api/user'

const loading = ref(true)
const error = ref('')
const mine = ref<UserCoupon[]>([])
const available = ref<AvailableCoupon[]>([])
const receiving = ref<number | null>(null)
const tab = ref<'mine' | 'available'>('mine')

async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[mine.value, available.value] = await Promise.all([userApi.myCoupons(), userApi.availableCoupons()])
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function receive(id: number) {
  receiving.value = id
  try {
    await userApi.receiveCoupon(id)
    await load()
    tab.value = 'mine'
  } catch (e) {
    error.value = e instanceof Error ? e.message : '领取失败'
  } finally {
    receiving.value = null
  }
}

onMounted(load)
</script>

<template>
  <div class="account-panel">
    <header class="panel-head">
      <h1>我的优惠券</h1>
      <div class="status-tabs soft">
        <button type="button" :class="{ active: tab === 'mine' }" @click="tab = 'mine'">已领取</button>
        <button type="button" :class="{ active: tab === 'available' }" @click="tab = 'available'">可领取</button>
      </div>
    </header>

    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error" class="account-empty">{{ error }} <RouterLink class="account-cta" to="/auth/login">登录</RouterLink></div>
    <template v-else-if="tab === 'mine'">
      <div v-if="!mine.length" class="account-empty soft">
        <Ticket :size="48" weight="thin" />
        <h3>暂无优惠券</h3>
        <button type="button" class="account-cta" @click="tab = 'available'">去领券</button>
      </div>
      <div v-else class="coupon-list soft">
        <article v-for="c in mine" :key="c.id">
          <strong>¥{{ c.discountAmount }}</strong>
          <div>
            <b>{{ c.name }}</b>
            <span>满 {{ c.thresholdAmount }} 可用 · {{ c.status }}</span>
          </div>
        </article>
      </div>
    </template>
    <template v-else>
      <div v-if="!available.length" class="account-empty soft">
        <Ticket :size="48" weight="thin" />
        <h3>暂无可领券</h3>
      </div>
      <div v-else class="coupon-list soft">
        <article v-for="c in available" :key="c.id">
          <strong>¥{{ c.discountAmount }}</strong>
          <div>
            <b>{{ c.name }}</b>
            <span>满 {{ c.thresholdAmount }} · 截止 {{ c.endAt }}</span>
          </div>
          <button type="button" :disabled="receiving === c.id" @click="receive(c.id)">
            {{ receiving === c.id ? '领取中…' : '领取' }}
          </button>
        </article>
      </div>
    </template>
  </div>
</template>
