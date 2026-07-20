<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise, PhUser as User } from '@phosphor-icons/vue'
import { adminApi } from '../api'

const rows = ref<any[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.request('/api/v1/admin/audit-logs?limit=100')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

function formatTime(value?: string) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function actionTone(action?: string) {
  const a = String(action || '').toUpperCase()
  if (['LOGIN', 'REGISTER', 'ENABLE', 'APPROVE', 'SUCCESS'].includes(a)) return 'success'
  if (['LOGOUT', 'DISABLE', 'REJECT', 'PAUSE', 'DELETE'].includes(a)) return 'warn'
  if (['FAILED', 'DENY', 'ERROR'].includes(a)) return 'danger'
  return 'neutral'
}

function shortTrace(id?: string) {
  const s = String(id || '')
  if (!s) return '—'
  if (s.length <= 12) return s
  return `${s.slice(0, 8)}…`
}

onMounted(load)
</script>

<template>
  <section class="commerce-page iam-page" v-loading="loading">
    <header class="iam-heading">
      <h2>审计日志</h2>
      <el-button class="iam-refresh" @click="load">
        <ArrowsClockwise :size="15" weight="bold" />
        刷新数据
      </el-button>
    </header>

    <div class="ops-card iam-card">
      <header class="iam-card-head">
        <div>
          <p>AUDIT</p>
          <h3>审计日志</h3>
        </div>
      </header>

      <el-table :data="rows" class="workspace-table audit-table" empty-text="暂无日志">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <span class="mono audit-time">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人 ID" width="130">
          <template #default="{ row }">
            <span class="audit-operator">
              <User :size="14" weight="fill" />
              <em class="mono">{{ row.userId ?? '—' }}</em>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模块" width="120">
          <template #default="{ row }">
            <strong class="audit-module">{{ row.module || '—' }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="130">
          <template #default="{ row }">
            <span class="audit-action" :class="`tone-${actionTone(row.action)}`">{{ row.action || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="对象" min-width="160">
          <template #default="{ row }">
            <span class="audit-target" :title="row.targetId">{{ row.targetId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="追踪 ID" min-width="140" align="right">
          <template #default="{ row }">
            <span class="mono audit-trace" :title="row.requestId">{{ shortTrace(row.requestId) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>
