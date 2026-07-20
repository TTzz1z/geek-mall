<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  PhArrowsClockwise as ArrowsClockwise,
  PhCheckCircle as CheckCircle
} from '@phosphor-icons/vue'
import { adminApi } from '../api'

const loading = ref(false)
const metrics = ref<Record<string, any>>({})
const logs = ref<any[]>([])
const running = ref(false)

async function load() {
  loading.value = true
  try {
    metrics.value = await adminApi.request('/api/v1/admin/ai/metrics')
    logs.value = await adminApi.request('/api/v1/admin/ai/call-logs')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function runEval() {
  running.value = true
  try {
    const r = await adminApi.request<any>('/api/v1/admin/ai/evaluations/run', { method: 'POST' })
    ElMessage.success(`评测完成：${r.passed ?? r.status ?? 'OK'}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评测失败')
  } finally {
    running.value = false
  }
}

function fmtNum(n: unknown) {
  const v = Number(n ?? 0)
  return Number.isFinite(v) ? v.toLocaleString('en-US') : '0'
}

function intentText(row: any) {
  const raw = String(row.detail || '').trim()
  if (!raw) return '—'
  const cleaned = raw.replace(/^\[模型回复\]\s*/i, '').replace(/\s+/g, ' ')
  return cleaned.length > 48 ? `${cleaned.slice(0, 48)}…` : cleaned
}

function modelTone(name?: string) {
  const n = String(name || '').toLowerCase()
  if (n.includes('gpt') || n.includes('openai')) return 'violet'
  if (n.includes('claude') || n.includes('anthropic')) return 'blue'
  if (n.includes('gemini') || n.includes('qwen')) return 'teal'
  return 'slate'
}

function latencyTone(ms: unknown) {
  const n = Number(ms ?? 0)
  if (!Number.isFinite(n)) return 'neutral'
  return n >= 1000 ? 'slow' : 'fast'
}

function fmtLatency(ms: unknown) {
  const n = Number(ms ?? 0)
  return Number.isFinite(n) ? n.toLocaleString('en-US') : '—'
}

function fmtCost(cost: unknown) {
  const n = Number(cost ?? 0)
  if (!Number.isFinite(n)) return '—'
  return n.toFixed(4)
}

function fmtTime(value?: string) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (x: number) => String(x).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function hasTool(row: any) {
  return row.toolUsed === true || row.toolUsed === 1 || row.toolUsed === '1'
}

function showTrace(row: any) {
  ElMessageBox.alert(
    [
      `请求：${row.requestId || '—'}`,
      `模型：${row.modelName || '—'}`,
      `耗时：${fmtLatency(row.latencyMs)} ms`,
      `状态：${row.status || '—'}`,
      `成本：$${fmtCost(row.cost)}`,
      `时间：${row.createdAt || '—'}`,
      '',
      String(row.detail || '无详情')
    ].join('\n'),
    'Trace',
    { confirmButtonText: '关闭' }
  )
}

onMounted(load)
</script>

<template>
  <section class="commerce-page ai-ops-page" v-loading="loading">
    <header class="ai-ops-heading">
      <h2>AI 调用与评测</h2>
      <el-button class="ai-ops-refresh" @click="load">
        <ArrowsClockwise :size="15" weight="bold" />
        刷新数据
      </el-button>
    </header>

    <div class="ai-ops-section-head">
      <div>
        <p>AI OPS</p>
        <h3>AI 调用与评测</h3>
      </div>
      <el-button type="primary" :loading="running" @click="runEval">运行评测集</el-button>
    </div>

    <div class="ai-ops-kpis">
      <article>
        <small>模型调用总计</small>
        <strong>{{ fmtNum(metrics.modelCalls) }}</strong>
      </article>
      <article>
        <small>成功</small>
        <strong class="tone-success">{{ fmtNum(metrics.modelSuccess) }}</strong>
      </article>
      <article>
        <small>Planner 调用</small>
        <strong>{{ fmtNum(metrics.planCalls) }}</strong>
      </article>
      <article>
        <small>Answer 调用</small>
        <strong>{{ fmtNum(metrics.answerCalls) }}</strong>
      </article>
      <article>
        <small>工具调用</small>
        <strong>{{ fmtNum(metrics.toolCalls) }}</strong>
      </article>
      <article>
        <small>安全拒管</small>
        <strong class="tone-danger">{{ fmtNum(metrics.toolDenied) }}</strong>
      </article>
      <article>
        <small>预估总成本 ($)</small>
        <strong class="mono">{{ fmtCost(metrics.costTotal) }}</strong>
      </article>
      <article>
        <small>Planner 成本 ($)</small>
        <strong class="mono">{{ fmtCost(metrics.planCost) }}</strong>
      </article>
      <article>
        <small>Token（prompt+comp）</small>
        <strong class="mono">{{ fmtNum(Number(metrics.promptTokens || 0) + Number(metrics.completionTokens || 0)) }}</strong>
      </article>
      <article>
        <small>平均延迟 (ms)</small>
        <strong class="mono">{{ fmtLatency(metrics.avgLatencyMs) }}</strong>
      </article>
      <article>
        <small>Plan 缓存命中</small>
        <strong class="mono">{{ fmtNum(metrics.planCache?.hits) }} / {{ fmtNum(metrics.planCache?.puts) }}</strong>
      </article>
      <article>
        <small>Planner 并发拒</small>
        <strong class="tone-danger mono">{{ fmtNum(metrics.plannerConcurrency?.rejected) }}</strong>
        <em class="ai-ops-sub">max {{ metrics.plannerConcurrency?.maxConcurrent ?? '—' }}</em>
      </article>
    </div>

    <div class="ops-card ai-ops-log">
      <el-table :data="logs" class="workspace-table ai-ops-table" empty-text="暂无调用日志">
        <el-table-column label="访客意图" min-width="220">
          <template #default="{ row }">
            <span class="ai-intent" :title="row.detail">“{{ intentText(row) }}”</span>
          </template>
        </el-table-column>
        <el-table-column label="场景" width="100" align="center">
          <template #default="{ row }">
            <span class="ai-model tone-slate">{{ row.scene || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模型路由" width="150">
          <template #default="{ row }">
            <span class="ai-model" :class="`tone-${modelTone(row.modelName)}`">{{ row.modelName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Tokens" width="110" align="right">
          <template #default="{ row }">
            <span class="mono">{{ fmtNum(Number(row.promptTokens || 0) + Number(row.completionTokens || 0)) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时 (ms)" width="110" align="right">
          <template #default="{ row }">
            <span class="mono ai-latency" :class="`tone-${latencyTone(row.latencyMs)}`">
              {{ fmtLatency(row.latencyMs) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <span class="ai-status">
              <CheckCircle
                :size="16"
                weight="fill"
                :class="row.status === 'SUCCESS' ? 'is-ok' : 'is-bad'"
              />
              <em v-if="hasTool(row)">TOOL</em>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="预估成本 ($)" width="120" align="right">
          <template #default="{ row }">
            <span class="mono">{{ fmtCost(row.cost) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="100">
          <template #default="{ row }">
            <span class="mono">{{ fmtTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" width="90" align="right">
          <template #default="{ row }">
            <button type="button" class="ai-trace" @click="showTrace(row)">Trace</button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>
