<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { aftersaleApi } from '../api/phase4'

const route = useRoute()
const detail = ref<any>(null)
const loading = ref(true)
const error = ref('')
const trackingNo = ref('')

async function load() {
  try { detail.value = await aftersaleApi.detail(String(route.params.no)) } catch (e) { error.value = e instanceof Error ? e.message : '加载失败' } finally { loading.value = false }
}
async function submitReturn() {
  await aftersaleApi.returnLogistics(String(route.params.no), trackingNo.value)
  await load()
}
onMounted(load)
</script>
<template>
  <section class="route-page">
    <h1>售后详情</h1>
    <p v-if="loading">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <template v-else-if="detail">
      <p>单号：{{ detail.aftersaleNo }} / 状态：{{ detail.status }} / 类型：{{ detail.type }}</p>
      <div v-if="detail.status==='APPROVED' && detail.type==='RETURN_REFUND'">
        <input v-model="trackingNo" placeholder="回寄物流单号" />
        <button type="button" @click="submitReturn">提交回寄物流</button>
      </div>
      <el-table :data="detail.logs || []">
        <el-table-column prop="fromStatus" label="原状态" />
        <el-table-column prop="toStatus" label="新状态" />
        <el-table-column prop="reason" label="说明" />
      </el-table>
    </template>
  </section>
</template>
