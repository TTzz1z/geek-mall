<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { PhArrowLeft as ArrowLeft } from '@phosphor-icons/vue'

const route = useRoute()
const kind = computed(() => String(route.meta.legal || 'privacy'))
const title = computed(() => (kind.value === 'terms' ? '使用条款' : '隐私政策'))
</script>

<template>
  <section class="route-page legal-page">
    <p class="route-kicker">GEEK MALL / LEGAL</p>
    <h1>{{ title }}</h1>
    <article v-if="kind === 'privacy'">
      <p>极客商城会处理完成账号注册、登录、下单、配送与客服所必需的信息。</p>
      <p>我们仅在业务所需范围内使用这些信息，并采取合理措施保护账号与交易安全。</p>
      <ul>
        <li>账号标识与会话令牌</li>
        <li>收货地址与订单快照</li>
        <li>支付结果状态（本项目使用模拟支付，不会真实扣款）</li>
      </ul>
      <p>如需了解更多，可通过「客户服务」联系我们。</p>
    </article>
    <article v-else>
      <p>使用极客商城即表示你同意遵守平台交易规则，包括商品信息展示、下单履约、售后处理与账号安全相关约定。</p>
      <p>本项目支付为模拟通道，用于完整体验下单流程，不会产生真实资金往来。请勿提交真实敏感证件或银行卡信息。</p>
      <p>AI 导购仅提供选购建议，不会代替你完成支付、退款或提交正式订单。</p>
    </article>
    <div><RouterLink to="/auth/login"><ArrowLeft :size="18" />返回登录</RouterLink></div>
  </section>
</template>
