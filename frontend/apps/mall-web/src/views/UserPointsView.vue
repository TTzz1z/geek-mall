<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { PhCoin as Coin } from '@phosphor-icons/vue'
import { userApi, type PointLog } from '../api/user'

const loading = ref(true)
const error = ref('')
const rows = ref<PointLog[]>([])

onMounted(async () => {
  try {
    rows.value = await userApi.pointLogs()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="account-panel">
    <header class="panel-head"><h1>我的积分</h1></header>
    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error" class="account-empty">{{ error }} <RouterLink class="account-cta" to="/auth/login">登录</RouterLink></div>
    <div v-else-if="!rows.length" class="account-empty soft">
      <Coin :size="48" weight="thin" />
      <h3>暂无积分流水</h3>
    </div>
    <div v-else class="point-list soft">
      <article v-for="(row, idx) in rows" :key="`${row.createdAt}-${idx}`">
        <div>
          <strong>{{ row.changeType }}</strong>
          <span>{{ row.sourceType }} · {{ row.sourceId }}</span>
        </div>
        <b :class="{ plus: row.points > 0 }">{{ row.points > 0 ? '+' : '' }}{{ row.points }}</b>
        <small>余额 {{ row.balance }} · {{ new Date(row.createdAt).toLocaleString() }}</small>
      </article>
    </div>
  </div>
</template>
