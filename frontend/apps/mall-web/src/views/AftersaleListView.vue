<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { aftersaleApi } from '../api/phase4'

const rows = ref<any[]>([])
const loading = ref(true)
const error = ref('')
onMounted(async () => {
  try { rows.value = await aftersaleApi.list() } catch (e) { error.value = e instanceof Error ? e.message : '加载失败' } finally { loading.value = false }
})
</script>
<template>
  <section class="route-page">
    <h1>售后服务</h1>
    <p v-if="loading">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <el-table v-else :data="rows">
      <el-table-column prop="aftersaleNo" label="售后单号">
        <template #default="{ row }"><RouterLink :to="`/user/aftersales/${row.aftersaleNo}`">{{ row.aftersaleNo }}</RouterLink></template>
      </el-table-column>
      <el-table-column prop="orderNo" label="订单号" />
      <el-table-column prop="type" label="类型" />
      <el-table-column prop="status" label="状态" />
      <el-table-column prop="requestedAmount" label="申请金额" />
    </el-table>
  </section>
</template>
