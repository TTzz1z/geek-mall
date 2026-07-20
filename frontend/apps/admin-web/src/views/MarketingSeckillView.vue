<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'

const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.request('/api/v1/admin/marketing/seckill/sessions')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function warmup(row: any) {
  if (!row.sessionId || !row.skuId) return
  try {
    await adminApi.request(
      `/api/v1/admin/marketing/seckill/warmup?sessionId=${row.sessionId}&skuId=${row.skuId}`,
      { method: 'POST' }
    )
    ElMessage.success('预热已触发')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '预热失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="commerce-page" v-loading="loading">
    <header class="workspace-heading">
      <div>
        <p>SECKILL OPS</p>
        <h2>营销与秒杀</h2>
        <span>管理秒杀场次与 SKU 库存预热，不与首页装修混用。</span>
      </div>
      <el-button @click="load">刷新</el-button>
    </header>
    <el-table :data="rows" class="workspace-table" empty-text="暂无秒杀场次">
      <el-table-column prop="sessionCode" label="场次" min-width="120" />
      <el-table-column prop="skuId" label="SKU" width="120" />
      <el-table-column prop="price" label="秒杀价" width="100" />
      <el-table-column prop="stockQty" label="库存" width="90" />
      <el-table-column prop="limitPerUser" label="限购" width="80" />
      <el-table-column prop="warmStatus" label="预热" width="100" />
      <el-table-column prop="startAt" label="开始" min-width="160" />
      <el-table-column prop="endAt" label="结束" min-width="160" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!row.skuId" @click="warmup(row)">预热</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>
