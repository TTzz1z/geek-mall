<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { PhFileText as FileText } from '@phosphor-icons/vue'
import { userApi, type InvoiceApplication, type InvoiceTitle } from '../api/user'

const loading = ref(true)
const error = ref('')
const titles = ref<InvoiceTitle[]>([])
const invoices = ref<InvoiceApplication[]>([])
const titleForm = reactive({ titleType: 'PERSONAL', title: '', taxNo: '' })
const applyForm = reactive({ orderNo: '', titleId: 0, amount: 0 })
const saving = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[titles.value, invoices.value] = await Promise.all([userApi.invoiceTitles(), userApi.invoices()])
    if (!applyForm.titleId && titles.value.length) applyForm.titleId = titles.value[0].titleId
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function saveTitle() {
  saving.value = true
  try {
    await userApi.saveInvoiceTitle({
      titleType: titleForm.titleType,
      title: titleForm.title,
      taxNo: titleForm.taxNo || undefined
    })
    titleForm.title = ''
    titleForm.taxNo = ''
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存抬头失败'
  } finally {
    saving.value = false
  }
}

async function apply() {
  saving.value = true
  try {
    await userApi.applyInvoice({
      orderNo: applyForm.orderNo,
      titleId: applyForm.titleId,
      amount: Number(applyForm.amount)
    })
    applyForm.orderNo = ''
    applyForm.amount = 0
    await load()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '开票失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="account-panel">
    <header class="panel-head"><h1>发票管理</h1></header>
    <div v-if="loading" class="account-empty">加载中…</div>
    <div v-else-if="error && !titles.length && !invoices.length" class="account-empty">
      {{ error }} <RouterLink class="account-cta" to="/auth/login">登录</RouterLink>
    </div>
    <template v-else>
      <p v-if="error" class="inline-error">{{ error }}</p>

      <section class="panel-block">
        <h2>发票抬头</h2>
        <div v-if="titles.length" class="simple-list soft">
          <article v-for="t in titles" :key="t.titleId">
            <strong>{{ t.title }}</strong>
            <span>{{ t.titleType }} · ID {{ t.titleId }}{{ t.taxNo ? ` · ${t.taxNo}` : '' }}</span>
          </article>
        </div>
        <div v-else class="empty-hint soft-inline">
          <FileText :size="22" weight="thin" /> 暂无抬头，先添加一条
        </div>
        <form class="user-form account-form" @submit.prevent="saveTitle">
          <label>类型
            <select v-model="titleForm.titleType">
              <option value="PERSONAL">个人</option>
              <option value="COMPANY">企业</option>
            </select>
          </label>
          <label>抬头名称<input v-model="titleForm.title" required /></label>
          <label>税号<input v-model="titleForm.taxNo" placeholder="企业选填" /></label>
          <button type="submit" :disabled="saving">保存抬头</button>
        </form>
      </section>

      <section class="panel-block">
        <h2>开票申请</h2>
        <form class="user-form account-form" @submit.prevent="apply">
          <label>订单号<input v-model="applyForm.orderNo" required /></label>
          <label>抬头
            <select v-model.number="applyForm.titleId" required>
              <option v-for="t in titles" :key="t.titleId" :value="t.titleId">{{ t.title }}</option>
            </select>
          </label>
          <label>金额<input v-model.number="applyForm.amount" type="number" min="0.01" step="0.01" required /></label>
          <button type="submit" :disabled="saving || !titles.length">提交开票</button>
        </form>
        <div v-if="invoices.length" class="simple-list soft">
          <article v-for="inv in invoices" :key="inv.applicationNo">
            <strong>{{ inv.applicationNo }}</strong>
            <span>{{ inv.orderNo }} · ¥{{ inv.amount }} · {{ inv.status }}</span>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
