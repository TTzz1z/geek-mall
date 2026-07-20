<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { useSessionStore } from '../session'
import { statusLabel, statusTone } from '../status'

const session = useSessionStore()
const isAdmin = computed(() => session.principal?.roles.includes('SYSTEM_ADMIN'))
const items = ref<any[]>([])
const shop = reactive({ shopName: '', logoUrl: '', announcement: '' })
const accounts = ref<any[]>([])
const account = reactive({ username: '', roleCode: 'PRODUCT_OPERATOR' })
const pendingCount = computed(() => items.value.filter(i => i.status === 'PENDING').length)
const denied = ref(false)

async function load() {
  denied.value = false
  try {
    if (isAdmin.value) {
      items.value = await adminApi.request<any[]>('/api/v1/admin/merchant/applications')
    } else if (session.principal?.roles.includes('MERCHANT')) {
      Object.assign(shop, await adminApi.request<any>('/api/v1/merchant/shop'))
      accounts.value = await adminApi.request<any[]>('/api/v1/merchant/subaccounts')
    } else {
      denied.value = true
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
}

async function decide(id: number, approve: boolean) {
  let reason = '平台审核通过'
  if (!approve) {
    const r = await ElMessageBox.prompt('请输入驳回原因', '驳回入驻申请')
    reason = r.value
  }
  await adminApi.request(`/api/v1/admin/merchant/applications/${id}/${approve ? 'approve' : 'reject'}`, {
    method: 'POST',
    body: approve ? undefined : JSON.stringify({ reason })
  })
  ElMessage.success('审核结果已保存')
  load()
}

async function saveShop() {
  await adminApi.request('/api/v1/merchant/shop', { method: 'PATCH', body: JSON.stringify(shop) })
  ElMessage.success('店铺资料已更新')
}

async function add() {
  await adminApi.request('/api/v1/merchant/subaccounts', { method: 'POST', body: JSON.stringify(account) })
  account.username = ''
  ElMessage.success('子账号已添加')
  load()
}

onMounted(load)
</script>

<template>
  <section class="commerce-page">
    <header class="workspace-heading">
      <div>
        <p>MERCHANT & STORE</p>
        <h2>{{ isAdmin ? '商家入驻审核' : '店铺与子账号' }}</h2>
        <span>{{ isAdmin ? '审核企业主体、经营类目与联系人信息。' : '店铺数据严格绑定当前 Token 中的商家范围。' }}</span>
      </div>
    </header>

    <div v-if="denied" class="ops-card">
      <p style="margin:0;color:var(--text-secondary, #6b7280);">商家入驻审核仅系统管理员可操作；商品管理员请使用「商品中心」与「商家授权治理」。</p>
    </div>

    <div v-else-if="isAdmin" class="ops-card">
      <div class="ops-tabs">
        <button type="button" class="active">
          待审核入驻
          <span v-if="pendingCount" class="count">{{ pendingCount }}</span>
        </button>
      </div>
      <el-table :data="items" class="workspace-table" empty-text="暂无入驻申请">
        <el-table-column prop="applicationNo" label="申请编号" width="180">
          <template #default="{ row }"><span class="mono">{{ row.applicationNo }}</span></template>
        </el-table-column>
        <el-table-column label="企业与资质">
          <template #default="{ row }">
            <strong style="display:block;font-size:13px;">{{ row.companyName }}</strong>
            <small class="mono" style="color:#9ca3af;font-size:11px;">{{ row.creditCode }}</small>
          </template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="120" />
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="right">
          <template #default="{ row }">
            <el-button type="danger" link @click="decide(row.id, false)">驳回</el-button>
            <el-button type="primary" @click="decide(row.id, true)">通过入驻</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-else class="merchant-layout">
      <article class="edit-panel">
        <h3>店铺资料</h3>
        <el-form label-position="top">
          <el-form-item label="店铺名称"><el-input v-model="shop.shopName" /></el-form-item>
          <el-form-item label="Logo URL"><el-input v-model="shop.logoUrl" /></el-form-item>
          <el-form-item label="店铺公告"><el-input v-model="shop.announcement" type="textarea" /></el-form-item>
          <el-button type="primary" @click="saveShop">保存店铺</el-button>
        </el-form>
      </article>
      <article class="edit-panel">
        <h3>新增子账号</h3>
        <el-form label-position="top">
          <el-form-item label="账号名"><el-input v-model="account.username" /></el-form-item>
          <el-form-item label="岗位角色">
            <el-select v-model="account.roleCode">
              <el-option label="商品运营" value="PRODUCT_OPERATOR" />
              <el-option label="库存专员" value="STOCK_OPERATOR" />
              <el-option label="店铺管理员" value="SHOP_ADMIN" />
            </el-select>
          </el-form-item>
          <el-button :disabled="!account.username" @click="add">添加账号</el-button>
        </el-form>
        <el-table :data="accounts">
          <el-table-column prop="username" label="账号" />
          <el-table-column prop="roleCode" label="岗位" />
          <el-table-column prop="status" label="状态" />
        </el-table>
      </article>
    </div>
  </section>
</template>
