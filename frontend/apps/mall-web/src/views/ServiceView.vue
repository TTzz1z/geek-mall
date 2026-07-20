<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { userApi } from '../api/user'

const form = reactive({
  category: 'ORDER',
  priority: 'NORMAL',
  title: '',
  content: '',
  merchantId: '' as string | number
})
const submitting = ref(false)
const error = ref('')
const created = ref<any>(null)

async function submit() {
  submitting.value = true
  error.value = ''
  created.value = null
  try {
    created.value = await userApi.createTicket({
      category: form.category,
      priority: form.priority,
      title: form.title,
      content: form.content,
      merchantId: form.merchantId === '' ? null : Number(form.merchantId)
    })
    form.title = ''
    form.content = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="account-panel">
    <header class="panel-head">
      <div>
        <h1>客户服务</h1>
        <p class="panel-tip">提交咨询工单后，客服将在后台领取处理。</p>
      </div>
    </header>

    <form class="user-form account-form wide" @submit.prevent="submit">
      <label>类目
        <select v-model="form.category">
          <option value="ORDER">订单</option>
          <option value="AFTERSALE">售后</option>
          <option value="PRODUCT">商品</option>
          <option value="OTHER">其他</option>
        </select>
      </label>
      <label>优先级
        <select v-model="form.priority">
          <option value="LOW">低</option>
          <option value="NORMAL">普通</option>
          <option value="HIGH">高</option>
          <option value="URGENT">紧急</option>
        </select>
      </label>
      <label class="wide">标题<input v-model="form.title" required maxlength="80" /></label>
      <label class="wide">内容<textarea v-model="form.content" required rows="5" /></label>
      <label>商家 ID（可选）<input v-model="form.merchantId" type="number" /></label>
      <button type="submit" :disabled="submitting">{{ submitting ? '提交中…' : '提交工单' }}</button>
    </form>

    <p v-if="error" class="inline-error">{{ error }}</p>
    <article v-if="created" class="created-ticket soft">
      <strong>工单已创建</strong>
      <span>{{ created.ticketNo || created.ticket_no }} · {{ created.status }}</span>
      <RouterLink to="/user/messages">查看消息中心</RouterLink>
    </article>
  </div>
</template>
