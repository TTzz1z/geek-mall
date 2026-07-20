<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { userApi, type MemberProfile } from '../api/user'

const loading = ref(true)
const error = ref('')
const member = ref<MemberProfile | null>(null)

onMounted(async () => {
  try {
    member.value = await userApi.member()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="account-panel">
    <header class="panel-head"><h1>会员中心</h1></header>
    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error" class="account-empty">{{ error }} <RouterLink class="account-cta" to="/auth/login">登录</RouterLink></div>
    <article v-else-if="member" class="member-hero">
      <div>
        <small>当前等级</small>
        <strong>{{ member.levelCode }}</strong>
        <p>成长值 {{ member.growthValue }}</p>
      </div>
      <ul>
        <li><span>可用积分</span><b>{{ member.pointBalance }}</b></li>
        <li><span>冻结积分</span><b>{{ member.frozenPoints }}</b></li>
      </ul>
      <RouterLink to="/user/points">查看积分明细 →</RouterLink>
    </article>
  </div>
</template>
