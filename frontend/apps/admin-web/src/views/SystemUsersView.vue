<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { statusTone } from '../status'

const rows = ref<any[]>([])
const loading = ref(false)
const dialog = ref(false)
const editing = reactive({ id: 0, username: '', userType: '', status: '' })

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.request('/api/v1/admin/users')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

function initials(username?: string) {
  const s = String(username || '').trim()
  if (!s) return 'U'
  const letters = s.replace(/[^a-zA-Z0-9\u4e00-\u9fa5]/g, '')
  if (/[\u4e00-\u9fa5]/.test(letters)) return letters.slice(0, 2)
  return letters.slice(0, 2).toUpperCase() || s.slice(0, 2).toUpperCase()
}

function avatarTone(type?: string) {
  const t = String(type || '').toUpperCase()
  if (t.includes('ADMIN')) return 'violet'
  if (t.includes('MERCHANT')) return 'orange'
  if (t.includes('OPS') || t.includes('CS')) return 'blue'
  return 'slate'
}

function typeText(type?: string) {
  return String(type || '—').toUpperCase()
}

function openEdit(row: any) {
  editing.id = row.id
  editing.username = row.username
  editing.userType = row.userType
  editing.status = row.status
  dialog.value = true
}

async function toggleStatus(row: any) {
  const next = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  const action = next === 'DISABLED' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`确认${action}用户「${row.username}」？`, `${action}用户`, {
      type: 'warning',
      confirmButtonText: action,
      cancelButtonText: '取消'
    })
    await adminApi.request(`/api/v1/admin/users/${row.id}/status?status=${next}`, { method: 'POST' })
    ElMessage.success(`已${action}`)
    await load()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e instanceof Error ? e.message : `${action}失败`)
  }
}

onMounted(load)
</script>

<template>
  <section class="commerce-page iam-page" v-loading="loading">
    <header class="iam-heading">
      <h2>系统用户</h2>
      <el-button class="iam-refresh" @click="load">
        <ArrowsClockwise :size="15" weight="bold" />
        刷新数据
      </el-button>
    </header>

    <div class="ops-card iam-card">
      <header class="iam-card-head">
        <div>
          <p>IAM</p>
          <h3>系统用户</h3>
        </div>
      </header>

      <el-table :data="rows" class="workspace-table iam-table" empty-text="暂无用户">
        <el-table-column prop="id" label="ID" width="100">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="iam-user">
              <span class="iam-avatar" :class="`tone-${avatarTone(row.userType)}`">{{ initials(row.username) }}</span>
              <strong>{{ row.username }}</strong>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <span class="iam-type" :class="`tone-${avatarTone(row.userType)}`">{{ typeText(row.userType) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="140" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ row.status || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="right">
          <template #default="{ row }">
            <button type="button" class="iam-link is-edit" @click="openEdit(row)">编辑</button>
            <button type="button" class="iam-link" @click="toggleStatus(row)">
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="编辑用户" width="420px">
      <el-form label-position="top">
        <el-form-item label="用户 ID">
          <el-input :model-value="String(editing.id)" disabled />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="editing.username" disabled />
        </el-form-item>
        <el-form-item label="类型">
          <el-input :model-value="typeText(editing.userType)" disabled />
        </el-form-item>
        <el-form-item label="状态">
          <span class="status-badge" :class="`tone-${statusTone(editing.status)}`">{{ editing.status || '—' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">关闭</el-button>
        <el-button
          type="primary"
          @click="toggleStatus({ id: editing.id, username: editing.username, status: editing.status }); dialog = false"
        >
          {{ editing.status === 'ENABLED' ? '停用账号' : '启用账号' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
