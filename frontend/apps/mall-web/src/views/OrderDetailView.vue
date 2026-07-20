<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { TradeOrderDetail } from '@geek-mall/api-types'
import { tradeApi } from '../api/trade'
import { aftersaleApi } from '../api/phase4'

const route = useRoute()
const router = useRouter()
const order = ref<TradeOrderDetail | null>(null)
const loading = ref(true)
const error = ref('')
const applying = ref(false)
const applyForm = ref({ orderItemId: 0, merchantId: 0, type: 'REFUND_ONLY', quantity: 1, reason: '质量问题', amount: 0 })

async function load() {
  try {
    order.value = await tradeApi.order(String(route.params.orderNo))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}

async function cancel() {
  if (!order.value || !confirm('确认取消该订单并释放库存与优惠？')) return
  order.value = await tradeApi.cancel(order.value.orderNo)
}

function prepareApply(item: any, sub: any) {
  applyForm.value = {
    orderItemId: item.id,
    merchantId: sub.merchantId,
    type: 'REFUND_ONLY',
    quantity: item.quantity,
    reason: '质量问题',
    amount: Number(item.payableAmount)
  }
}

async function submitAftersale() {
  if (!order.value || !applyForm.value.orderItemId) return
  applying.value = true
  try {
    const result = await aftersaleApi.apply({
      orderNo: order.value.orderNo,
      orderItemId: applyForm.value.orderItemId,
      merchantId: applyForm.value.merchantId,
      type: applyForm.value.type,
      quantity: applyForm.value.quantity,
      reason: applyForm.value.reason,
      amount: applyForm.value.amount
    })
    await router.push(`/user/aftersales/${result.aftersaleNo}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '售后申请失败'
  } finally {
    applying.value = false
  }
}

onMounted(load)
</script>
<template>
  <section class="trade-page order-detail-page">
    <div v-if="loading" class="trade-state">正在载入订单详情…</div>
    <div v-else-if="error" class="trade-state error">{{ error }}</div>
    <template v-else-if="order">
      <header class="order-detail-head">
        <p>ORDER {{ order.orderNo }}</p>
        <h1>{{ order.status }}</h1>
        <span>库存状态 {{ order.inventoryStatus }} · 应付 ¥{{ Number(order.payableAmount).toLocaleString() }}</span>
        <div>
          <button v-if="order.status==='PENDING_PAYMENT'" @click="router.push(`/cashier/${order.orderNo}`)">继续支付</button>
          <button v-if="order.status==='PENDING_PAYMENT'" class="secondary" @click="cancel">取消订单</button>
        </div>
      </header>
      <article class="order-address">
        <h2>收货信息</h2>
        <p>{{ order.address.receiver }} · {{ order.address.mobile }}</p>
        <span>{{ order.address.regionCode }} {{ order.address.detail }}</span>
      </article>
      <article v-for="sub in order.subOrders" :key="sub.subOrderNo" class="order-sub">
        <header>
          <div>
            <small>子订单 {{ sub.subOrderNo }}</small>
            <h2>{{ sub.shopName }}</h2>
          </div>
          <strong>{{ sub.status }}</strong>
        </header>
        <div v-for="item in sub.items" :key="item.id" class="order-item">
          <img :src="item.coverUrl" :alt="item.title" />
          <div>
            <b>{{ item.title }}</b>
            <small>SKU {{ item.skuId }} · × {{ item.quantity }}</small>
            <button v-if="order.status==='PAID' || order.status==='COMPLETED'" type="button" class="secondary" @click="prepareApply(item, sub)">申请售后</button>
          </div>
          <span>¥{{ Number(item.payableAmount).toLocaleString() }}</span>
        </div>
        <footer>
          <span v-for="plan in sub.fulfillmentPlans" :key="`${plan.warehouseId}-${plan.skuId}`">仓 {{ plan.warehouseId }} · SKU {{ plan.skuId }} × {{ plan.quantity }}</span>
        </footer>
      </article>
      <aside v-if="applyForm.orderItemId" class="order-total">
        <h2>售后申请</h2>
        <select v-model="applyForm.type">
          <option value="REFUND_ONLY">仅退款</option>
          <option value="RETURN_REFUND">退货退款</option>
          <option value="EXCHANGE">换货</option>
          <option value="REPAIR">维修</option>
        </select>
        <input v-model.number="applyForm.quantity" type="number" min="1" />
        <input v-model="applyForm.reason" placeholder="原因" />
        <input v-model.number="applyForm.amount" type="number" min="0" step="0.01" />
        <button :disabled="applying" type="button" @click="submitAftersale">提交售后</button>
      </aside>
      <aside class="order-total">
        <span>商品金额 ¥{{ Number(order.totalAmount).toLocaleString() }}</span>
        <span>优惠 −¥{{ Number(order.discountAmount).toLocaleString() }}</span>
        <strong>实付 ¥{{ Number(order.paidAmount || order.payableAmount).toLocaleString() }}</strong>
      </aside>
    </template>
  </section>
</template>
