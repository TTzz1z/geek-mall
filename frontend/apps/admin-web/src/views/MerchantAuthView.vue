<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'
import { statusLabel, statusTone } from '../status'

const merchantId = ref(2002)
const categoryAuths = ref<any[]>([])
const brandAuths = ref<any[]>([])
const categories = ref<any[]>([])
const brands = ref<any[]>([])
const loading = ref(false)
const catForm = ref({ categoryId: 200 as number | null, validUntil: '' })
const brandForm = ref({ brandId: 501 as number | null, fileUrl: '', validUntil: '' })

function flattenCategories(nodes: any[], out: any[] = []) {
  for (const n of nodes || []) {
    out.push({ id: n.id, name: n.name })
    if (n.children?.length) flattenCategories(n.children, out)
  }
  return out
}

async function load() {
  loading.value = true
  try {
    categoryAuths.value = await adminApi.request(`/api/v1/admin/merchants/${merchantId.value}/category-auths`)
    brandAuths.value = await adminApi.request(`/api/v1/admin/merchants/${merchantId.value}/brand-auths`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function meta() {
  try {
    const tree = await adminApi.request<any[]>('/api/v1/products/categories/tree')
    categories.value = flattenCategories(tree)
    brands.value = await adminApi.request('/api/v1/products/brands')
  } catch { /* ignore */ }
}

async function grantCategory() {
  try {
    await adminApi.request(`/api/v1/admin/merchants/${merchantId.value}/category-auths`, {
      method: 'POST',
      body: JSON.stringify({
        categoryId: catForm.value.categoryId,
        validUntil: catForm.value.validUntil || null
      })
    })
    ElMessage.success('类目授权已保存')
    await load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '授权失败') }
}

async function grantBrand() {
  try {
    await adminApi.request(`/api/v1/admin/merchants/${merchantId.value}/brand-auths`, {
      method: 'POST',
      body: JSON.stringify({
        brandId: brandForm.value.brandId,
        fileUrl: brandForm.value.fileUrl,
        validUntil: brandForm.value.validUntil || null
      })
    })
    ElMessage.success('品牌授权已保存')
    await load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '授权失败') }
}

async function revokeCategory(id: number) {
  await adminApi.request(`/api/v1/admin/merchants/category-auths/${id}/revoke`, { method: 'POST' })
  ElMessage.success('已停用')
  await load()
}
async function revokeBrand(id: number) {
  await adminApi.request(`/api/v1/admin/merchants/brand-auths/${id}/revoke`, { method: 'POST' })
  ElMessage.success('已停用')
  await load()
}

onMounted(() => { meta(); load() })
</script>

<template>
  <section class="commerce-page" v-loading="loading">
    <header class="workspace-heading">
      <div>
        <p>MERCHANT AUTHORIZATION</p>
        <h2>商家类目 / 品牌授权</h2>
        <span>上架与审核前校验类目 / 品牌授权。未授权的类目或品牌将无法完成上架。</span>
      </div>
      <div class="heading-actions">
        <el-input-number v-model="merchantId" :min="2000" controls-position="right" />
        <el-button type="primary" @click="load">切换商家</el-button>
      </div>
    </header>

    <p class="auth-summary">
      当前商家 <strong>{{ merchantId }}</strong>
      · 类目授权 <strong>{{ categoryAuths.filter(a => a.status === 'ENABLED').length }}</strong>
      · 品牌授权 <strong>{{ brandAuths.filter(a => a.status === 'ENABLED').length }}</strong>
    </p>

    <div class="merchant-layout">
      <article class="edit-panel">
        <h3>类目授权</h3>
        <div class="inline-create">
          <el-select v-model="catForm.categoryId" filterable placeholder="类目" style="flex:1;min-width:0">
            <el-option v-for="c in categories" :key="c.id" :label="`${c.name} (${c.id})`" :value="c.id" />
          </el-select>
          <el-button type="primary" @click="grantCategory">授予</el-button>
        </div>
        <el-table :data="categoryAuths" size="small">
          <el-table-column prop="categoryId" label="类目ID" width="90">
            <template #default="{ row }"><span class="mono">{{ row.categoryId }}</span></template>
          </el-table-column>
          <el-table-column prop="categoryName" label="类目" min-width="100" />
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ENABLED'" type="danger" link @click="revokeCategory(row.id)">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </article>
      <article class="edit-panel">
        <h3>品牌授权</h3>
        <div class="inline-create">
          <el-select v-model="brandForm.brandId" filterable placeholder="品牌" style="flex:1;min-width:0">
            <el-option v-for="b in brands" :key="b.id" :label="`${b.name} (${b.id})`" :value="b.id" />
          </el-select>
          <el-button type="primary" @click="grantBrand">授予</el-button>
        </div>
        <el-table :data="brandAuths" size="small">
          <el-table-column prop="brandId" label="品牌ID" width="90">
            <template #default="{ row }"><span class="mono">{{ row.brandId }}</span></template>
          </el-table-column>
          <el-table-column prop="brandName" label="品牌" min-width="100" />
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ENABLED'" type="danger" link @click="revokeBrand(row.id)">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </article>
    </div>
  </section>
</template>
