<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ApiClient } from '@geek-mall/api-client'
import type { TokenPair } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'
import { clearGuestCart, readGuestCart } from '../guest-cart'
import heroImage from '../assets/hero-geek-alpine.png'

const route = useRoute()
const router = useRouter()
const mode = computed(() => (route.path.includes('register') ? 'register' : 'login'))
const username = ref('')
const password = ref('')
const confirm = ref('')
const agree = ref(true)
const loading = ref(false)
const error = ref('')

const reasonText = computed(() => {
  const reason = String(route.query.reason || '')
  if (reason === 'cart') return '加入购物车需要先注册或登录账号'
  if (reason === 'pay' || reason === 'checkout') return '结算与支付需要先注册或登录账号'
  if (reason === 'buy') return '立即购买需要先注册或登录账号'
  return ''
})

const tokenStore = {
  getAccessToken: () => localStorage.getItem('geek.accessToken'),
  getRefreshToken: () => localStorage.getItem('geek.refreshToken'),
  setTokens: (tokens: TokenPair) => {
    localStorage.setItem('geek.accessToken', tokens.accessToken)
    localStorage.setItem('geek.refreshToken', tokens.refreshToken)
  },
  clear: () => {
    localStorage.removeItem('geek.accessToken')
    localStorage.removeItem('geek.refreshToken')
  }
}
const api = new ApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080', tokenStore)

watch(mode, () => {
  error.value = ''
  password.value = ''
  confirm.value = ''
})

async function finish(tokens: TokenPair) {
  tokenStore.setTokens(tokens)
  const guestItems = readGuestCart()
  if (guestItems.length) {
    try {
      await tradeApi.mergeCart(guestItems)
      clearGuestCart()
    } catch { /* ignore merge failure */ }
  }
  const redirect = String(route.query.redirect || '')
  if (redirect.startsWith('/') && !redirect.startsWith('//')) {
    await router.push(redirect)
    return
  }
  await router.push(guestItems.length ? '/cart' : '/user')
}

async function submit() {
  loading.value = true
  error.value = ''
  try {
    if (!agree.value) throw new Error('请先同意隐私政策与使用条款')
    if (mode.value === 'register') {
      if (password.value !== confirm.value) throw new Error('两次输入的密码不一致')
      const tokens = await api.request<TokenPair>('/api/v1/auth/register', {
        method: 'POST',
        body: JSON.stringify({ username: username.value.trim(), password: password.value })
      }, false)
      await finish(tokens)
      return
    }
    const tokens = await api.request<TokenPair>('/api/v1/auth/login/password', {
      method: 'POST',
      body: JSON.stringify({ username: username.value.trim(), password: password.value })
    }, false)
    await finish(tokens)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
  } finally {
    loading.value = false
  }
}

function switchMode(next: 'login' | 'register') {
  const q = { ...route.query }
  router.push({ path: `/auth/${next}`, query: q })
}
</script>

<template>
  <section class="auth-stage" :style="{ backgroundImage: `url(${heroImage})` }">
    <div class="auth-stage-veil" aria-hidden="true" />
    <p class="auth-credit">GEEK MALL · Alpine Field</p>

    <article class="auth-card">
      <RouterLink class="auth-brand" to="/">GEEK MALL</RouterLink>
      <h1>{{ mode === 'register' ? '注册极客账号' : '登录极客账号' }}</h1>
      <p v-if="reasonText" class="auth-reason">{{ reasonText }}</p>

      <nav class="auth-tabs" aria-label="登录注册切换">
        <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">密码登录</button>
        <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册账号</button>
      </nav>

      <form class="auth-form" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input
            v-model="username"
            autocomplete="username"
            :placeholder="mode === 'register' ? '字母开头，4-32 位' : '请输入用户名'"
            required
          />
        </label>
        <label>
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            placeholder="至少 8 位"
            required
            minlength="8"
          />
        </label>
        <label v-if="mode === 'register'">
          <span>确认密码</span>
          <input v-model="confirm" type="password" autocomplete="new-password" placeholder="再次输入密码" required minlength="8" />
        </label>

        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>

        <button type="submit" :disabled="loading">
          {{ loading ? '请稍候…' : (mode === 'register' ? '注册并登录' : '登录') }}
        </button>
      </form>

      <p class="auth-switch">
        <template v-if="mode === 'login'">
          新用户？
          <button type="button" @click="switchMode('register')">注册极客账号</button>
        </template>
        <template v-else>
          已有账号？
          <button type="button" @click="switchMode('login')">返回登录</button>
        </template>
      </p>

      <label class="auth-agree">
        <input v-model="agree" type="checkbox" />
        <span>继续，则表示同意 <RouterLink to="/legal/privacy">隐私政策</RouterLink> 和 <RouterLink to="/legal/terms">使用条款</RouterLink></span>
      </label>

      <RouterLink class="auth-back" to="/">继续浏览商品</RouterLink>
    </article>
  </section>
</template>
