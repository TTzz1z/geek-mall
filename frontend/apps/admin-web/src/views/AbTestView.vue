<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise, PhPlus as Plus } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { statusTone } from '../status'

const rows = ref<any[]>([])
const loading = ref(false)
const dialog = ref(false)
const form = reactive({
  experimentCode: '',
  name: '',
  trafficPercent: 100,
  variantA: 50,
  variantB: 50
})

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.request('/api/v1/admin/marketing/ab')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

function split(row: any) {
  const a = Math.max(0, Number(row.baselineWeight ?? 50))
  const b = Math.max(0, Number(row.variantWeight ?? 50))
  const total = a + b || 100
  const baseline = Math.round((a / total) * 100)
  return { baseline, variant: 100 - baseline }
}

function fmtUsers(n: unknown) {
  const v = Number(n ?? 0)
  return Number.isFinite(v) ? v.toLocaleString('en-US') : '0'
}

async function create() {
  try {
    await adminApi.request('/api/v1/admin/marketing/ab', {
      method: 'POST',
      body: JSON.stringify({
        experimentCode: form.experimentCode,
        name: form.name,
        trafficPercent: form.trafficPercent,
        variants: [
          { code: 'A', weight: form.variantA, config: { label: '基准' } },
          { code: 'B', weight: form.variantB, config: { label: '变体' } }
        ]
      })
    })
    ElMessage.success('实验已创建')
    dialog.value = false
    form.experimentCode = ''
    form.name = ''
    form.variantA = 50
    form.variantB = 50
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  }
}

async function pause(code: string) {
  try {
    await adminApi.request(`/api/v1/admin/marketing/ab/${encodeURIComponent(code)}/pause`, { method: 'POST' })
    ElMessage.success('已暂停实验')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '暂停失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="commerce-page ab-page" v-loading="loading">
    <header class="ab-heading">
      <h2>A/B 测试</h2>
      <el-button class="ab-refresh" @click="load">
        <ArrowsClockwise :size="15" weight="bold" />
        刷新数据
      </el-button>
    </header>

    <div class="ops-card ab-card">
      <header class="ab-card-head">
        <div>
          <p>EXPERIMENTATION</p>
          <h3>A/B 测试分流引擎</h3>
        </div>
        <el-button class="ab-create" @click="dialog = true">
          <Plus :size="15" weight="bold" />
          新建实验
        </el-button>
      </header>

      <el-table :data="rows" class="workspace-table ab-table" empty-text="暂无实验">
        <el-table-column label="实验标识" min-width="140">
          <template #default="{ row }">
            <span class="mono ab-code">{{ row.experimentCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="实验名称" min-width="180">
          <template #default="{ row }">
            <span class="ab-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ row.status || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="流量分配 (基准 VS 变体)" min-width="220">
          <template #default="{ row }">
            <div class="ab-split">
              <span class="mono">{{ split(row).baseline }}%</span>
              <div class="ab-split-track" aria-hidden="true">
                <i class="is-base" :style="{ width: `${split(row).baseline}%` }" />
                <i class="is-variant" :style="{ width: `${split(row).variant}%` }" />
              </div>
              <span class="mono">{{ split(row).variant }}%</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="已触达用户" width="130" align="right">
          <template #default="{ row }">
            <strong class="mono">{{ fmtUsers(row.assignmentCount) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <button
              v-if="row.status === 'RUNNING'"
              type="button"
              class="ab-pause"
              @click="pause(row.experimentCode)"
            >
              暂停实验
            </button>
            <span v-else class="ab-paused">已暂停</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建实验" width="520px">
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item label="实验标识">
          <el-input v-model="form.experimentCode" placeholder="如 EXP-HOME-UI" />
        </el-form-item>
        <el-form-item label="实验名称">
          <el-input v-model="form.name" placeholder="如 首页视觉 2.0 转化测试" />
        </el-form-item>
        <div class="ab-form-row">
          <el-form-item label="总流量 %">
            <el-input-number v-model="form.trafficPercent" :min="1" :max="100" controls-position="right" />
          </el-form-item>
          <el-form-item label="基准 A 权重">
            <el-input-number v-model="form.variantA" :min="1" :max="100" controls-position="right" />
          </el-form-item>
          <el-form-item label="变体 B 权重">
            <el-input-number v-model="form.variantB" :min="1" :max="100" controls-position="right" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!form.experimentCode || !form.name"
          @click="create"
        >
          创建并启动
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
