<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import type { AuthPrincipal } from '@geek-mall/api-types'
import { clearMallAuth, userApi } from '../api/user'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const principal = ref<AuthPrincipal | null>(null)

async function load() {
  loading.value = true
  try {
    principal.value = await userApi.session()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '请先登录'
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
  <div class="account-panel">
    <header class="panel-head"><h1>账户安全</h1></header>
    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error || !principal" class="account-empty">
      {{ error || '未登录' }}
      <RouterLink class="account-cta" to="/auth/login">前往登录</RouterLink>
    </div>
    <article v-else class="security-card soft">
      <dl>
        <div><dt>用户名</dt><dd>{{ principal.username }}</dd></div>
        <div><dt>用户 ID</dt><dd>{{ principal.userId }}</dd></div>
        <div><dt>身份</dt><dd>{{ principal.audience }}</dd></div>
        <div><dt>角色</dt><dd>{{ principal.roles.join(', ') || '—' }}</dd></div>
      </dl>
      <p>本项目暂未开放在线改密；如需切换账号，请退出后重新登录。</p>
      <button type="button" class="account-cta" @click="logout">退出登录</button>
    </article>
  </div>
</template>
