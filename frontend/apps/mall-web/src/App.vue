<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, RouterLink, useRoute } from 'vue-router'
import {
  PhRobot as Robot,
  PhShieldCheck as ShieldCheck,
  PhHeadset as Headset,
  PhTruck as Truck
} from '@phosphor-icons/vue'
import StoreHeader from './components/StoreHeader.vue'

const route = useRoute()
const immersive = computed(() => !!route.meta.immersive)

const promises = [
  { icon: ShieldCheck, title: '正品保障', desc: '品牌授权 · 假一赔十' },
  { icon: Truck, title: '极速配送', desc: '多仓直发 · 进度透明' },
  { icon: Headset, title: '无忧售后', desc: '专业客服 · 全程可追溯' }
] as const
</script>

<template>
  <template v-if="immersive">
    <RouterView />
  </template>
  <template v-else>
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <StoreHeader />
    <main id="main-content" tabindex="-1"><RouterView /></main>

    <footer class="site-end">
      <div class="site-end-glow" aria-hidden="true" />
      <div class="site-end-grid" aria-hidden="true" />
      <div class="site-end-inner">
        <ul class="site-end-promises">
          <li v-for="item in promises" :key="item.title">
            <component :is="item.icon" :size="22" weight="light" />
            <div>
              <strong>{{ item.title }}</strong>
              <small>{{ item.desc }}</small>
            </div>
          </li>
        </ul>

        <div class="site-end-main">
          <div class="site-end-brand">
            <RouterLink class="site-end-mark" to="/" aria-label="极客商城首页">
              <b>GEEK</b><span>MALL</span>
            </RouterLink>
            <p>只卖值得研究的数码好物</p>
          </div>
          <nav class="site-end-nav" aria-label="页脚">
            <RouterLink to="/service">帮助中心</RouterLink>
            <RouterLink to="/user/orders">订单查询</RouterLink>
            <RouterLink to="/auth/login">登录 / 注册</RouterLink>
            <RouterLink to="/ai-shopping">AI 导购</RouterLink>
          </nav>
        </div>

        <div class="site-end-meta">
          <span>CURATED TECH · TRUSTED SUPPLY</span>
          <small>© {{ new Date().getFullYear() }} GEEK MALL</small>
        </div>
      </div>
    </footer>

    <RouterLink class="ai-float" to="/ai-shopping" aria-label="打开 AI 导购">
      <Robot :size="24" weight="light" />
    </RouterLink>
  </template>
</template>
