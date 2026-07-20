<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { statusTone } from '../status'

type MenuItem = { id: number; parentId: number | null; name: string; path: string; icon: string; permission: string; type: string }

const rows = ref<any[]>([])
const catalog = ref<MenuItem[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const currentRole = ref<any>(null)
const checkedMenuIds = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    rows.value = await adminApi.request('/api/v1/admin/roles')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function loadCatalog() {
  catalog.value = await adminApi.request<MenuItem[]>('/api/v1/admin/menus/catalog')
}

function scopeText(scope?: string) {
  const s = String(scope || '').toUpperCase()
  if (!s) return '—'
  if (s === 'ALL' || s === 'GLOBAL' || s === 'PLATFORM') return 'PLATFORM'
  return s
}

async function configure(row: any) {
  currentRole.value = row
  dialogVisible.value = true
  saving.value = true
  try {
    if (!catalog.value.length) await loadCatalog()
    checkedMenuIds.value = await adminApi.request<number[]>(`/api/v1/admin/roles/${row.id}/menus`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载角色菜单失败')
    dialogVisible.value = false
  } finally {
    saving.value = false
  }
}

async function saveMenus() {
  if (!currentRole.value) return
  saving.value = true
  try {
    await adminApi.request(`/api/v1/admin/roles/${currentRole.value.id}/menus`, {
      method: 'PUT',
      body: JSON.stringify(checkedMenuIds.value)
    })
    ElMessage.success('角色菜单已更新')
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function toggleMenu(id: number, on: boolean) {
  if (on) {
    if (!checkedMenuIds.value.includes(id)) checkedMenuIds.value = [...checkedMenuIds.value, id]
  } else {
    checkedMenuIds.value = checkedMenuIds.value.filter(x => x !== id)
  }
}

onMounted(load)
</script>

<template>
  <section class="commerce-page iam-page" v-loading="loading">
    <header class="iam-heading">
      <h2>角色权限</h2>
      <el-button class="iam-refresh" @click="load">
        <ArrowsClockwise :size="15" weight="bold" />
        刷新数据
      </el-button>
    </header>

    <div class="ops-card iam-card">
      <header class="iam-card-head">
        <div>
          <p>RBAC</p>
          <h3>角色权限</h3>
        </div>
      </header>

      <el-table :data="rows" class="workspace-table iam-table role-table" empty-text="暂无角色">
        <el-table-column label="名称 / 编码" min-width="220">
          <template #default="{ row }">
            <div class="role-identity">
              <strong>{{ row.roleName || '—' }}</strong>
              <small class="mono">{{ row.roleCode || '—' }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="数据范围" width="140">
          <template #default="{ row }">
            <span class="role-scope">{{ scopeText(row.dataScope) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="140" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ row.status || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="菜单数" width="100" align="center">
          <template #default="{ row }"><span class="mono">{{ row.menuCount ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column label="用户数" width="100" align="center">
          <template #default="{ row }"><span class="mono">{{ row.userCount ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="right">
          <template #default="{ row }">
            <button type="button" class="iam-link is-edit" @click="configure(row)">配置权限</button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="`配置权限 · ${currentRole?.roleName || ''}`" width="560px" destroy-on-close>
      <div v-loading="saving" class="role-menu-picker">
        <label v-for="menu in catalog" :key="menu.id" class="role-menu-row">
          <input
            type="checkbox"
            :checked="checkedMenuIds.includes(menu.id)"
            @change="toggleMenu(menu.id, ($event.target as HTMLInputElement).checked)"
          />
          <span>
            <strong>{{ menu.name }}</strong>
            <small>{{ menu.path || '—' }} · {{ menu.permission || '无权限码' }}</small>
          </span>
        </label>
        <p v-if="!catalog.length" class="muted">暂无菜单目录</p>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMenus">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.role-menu-picker { max-height: 420px; overflow: auto; display: grid; gap: 8px; }
.role-menu-row { display: grid; grid-template-columns: 20px 1fr; gap: 10px; align-items: start; padding: 8px 4px; }
.role-menu-row strong { display: block; font-size: 14px; }
.role-menu-row small { display: block; color: #6b7280; margin-top: 2px; font-size: 12px; }
.muted { color: #6b7280; }
</style>
