<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { PhBell as Bell } from '@phosphor-icons/vue'
import { userApi, type UserMessage } from '../api/user'

const loading = ref(true)
const error = ref('')
const rows = ref<UserMessage[]>([])

function payloadText(raw: string) {
  try {
    const obj = JSON.parse(raw)
    return typeof obj === 'object' ? JSON.stringify(obj) : String(obj)
  } catch {
    return raw
  }
}

onMounted(async () => {
  try {
    rows.value = await userApi.messages()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="account-panel">
    <header class="panel-head"><h1>消息中心</h1></header>
    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error" class="account-empty">{{ error }} <RouterLink class="account-cta" to="/auth/login">登录</RouterLink></div>
    <div v-else-if="!rows.length" class="account-empty soft">
      <Bell :size="48" weight="thin" />
      <h3>暂无消息</h3>
    </div>
    <div v-else class="message-list soft">
      <article v-for="row in rows" :key="row.taskNo">
        <header>
          <strong>{{ row.scene }}</strong>
          <span>{{ row.status }} · {{ row.channel }}</span>
        </header>
        <p>{{ payloadText(row.payload) }}</p>
        <small>{{ row.taskNo }} · {{ new Date(row.createdAt).toLocaleString() }}</small>
      </article>
    </div>
  </div>
</template>
