<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { logisticsApi } from '../api/phase4'

const route = useRoute()
const pkg = ref<any>(null)
const loading = ref(true)
const error = ref('')
async function load() {
  try { pkg.value = await logisticsApi.packageDetail(String(route.params.packageNo)) } catch (e) { error.value = e instanceof Error ? e.message : '加载失败' } finally { loading.value = false }
}
async function receive() { await logisticsApi.receive(String(route.params.packageNo)); await load() }
onMounted(load)
</script>
<template>
  <section class="route-page">
    <h1>物流详情</h1>
    <p v-if="loading">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <template v-else-if="pkg">
      <p>{{ pkg.carrierCode }} / {{ pkg.trackingNo }} / {{ pkg.status }}</p>
      <button v-if="pkg.status!=='RECEIVED'" type="button" @click="receive">确认收货</button>
      <el-timeline>
        <el-timeline-item v-for="(t,i) in pkg.tracks || []" :key="i" :timestamp="t.occurredAt">{{ t.detail }}</el-timeline-item>
      </el-timeline>
    </template>
  </section>
</template>
