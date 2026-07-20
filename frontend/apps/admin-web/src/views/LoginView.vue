<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiClient } from '@geek-mall/api-client'
import type { TokenPair } from '@geek-mall/api-types'
import { useSessionStore } from '../session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

const tokenStore = {
  getAccessToken: () => localStorage.getItem('geek.admin.accessToken'),
  getRefreshToken: () => localStorage.getItem('geek.admin.refreshToken'),
  setTokens: (t: TokenPair) => session.save(t),
  clear: () => session.clear()
}
const api = new ApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080', tokenStore)

async function login() {
  loading.value = true
  error.value = ''
  try {
    const t = await api.request<TokenPair>('/api/v1/auth/login/password', {
      method: 'POST',
      body: JSON.stringify({ username: username.value, password: password.value })
    }, false)
    session.save(t)
    router.push(String(route.query.redirect ?? '/dashboard'))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="admin-login">
    <div class="login-hero" aria-hidden="true"></div>
    <div class="login-stage">
      <section class="login-brand">
        <a class="wordmark" href="/">
          <b>GEEK</b><span>MALL</span>
        </a>
        <p>OPERATIONS</p>
        <h1>把复杂经营<br />变成清晰行动</h1>
        <h2>平台、商家、运营共用一套权限基座，风格与商城前台同源。</h2>
      </section>
      <form class="login-card" @submit.prevent="login">
        <p>SECURE ACCESS</p>
        <h2>登录管理后台</h2>
        <label>账号<input v-model="username" autocomplete="username" /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" /></label>
        <p v-if="error" class="login-error" role="alert">{{ error }}</p>
        <button type="submit" :disabled="loading">{{ loading ? '正在验证…' : '进入工作台' }}</button>
      </form>
    </div>
  </main>
</template>
