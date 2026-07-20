<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { PhArrowLeft as ArrowLeft, PhArrowRight as ArrowRight } from '@phosphor-icons/vue'

const route = useRoute()
const title = computed(() => String(route.meta.title ?? '极客商城'))
</script>

<template>
  <section class="route-page">
    <p class="route-kicker">GEEK MALL</p>
    <h1>{{ title }}</h1>
    <p>该页面暂未开放，请从首页或搜索继续选购。</p>
    <div>
      <RouterLink to="/"><ArrowLeft :size="18" />返回首页</RouterLink>
      <RouterLink to="/ai-shopping">咨询 AI 导购<ArrowRight :size="18" /></RouterLink>
    </div>
  </section>
</template>
