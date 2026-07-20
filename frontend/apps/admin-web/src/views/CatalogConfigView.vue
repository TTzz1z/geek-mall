<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '../api'

const categories = ref<any[]>([])
const brands = ref<any[]>([])
const groups = ref<any[]>([])
const loading = ref(false)
const selectedCategoryId = ref<number>()
const categoryForm = reactive({ parentId: undefined as number | undefined, name: '', sortOrder: 0 })
const brandForm = reactive({ name: '', logoUrl: '', description: '', categoryIds: [] as number[] })
const groupForm = reactive({ name: '', sortOrder: 0 })
const attributeDrafts = reactive<Record<string, { name: string; type: string; unit: string; searchable: boolean; comparable: boolean; saleAttribute: boolean }>>({})
const optionDrafts = reactive<Record<string, string>>({})

const flatCategories = computed(() => {
  const result: Array<{ id: number; name: string; depth: number }> = []
  const visit = (items: any[], depth: number) => items.forEach(item => {
    result.push({ id: item.id, name: item.name, depth })
    visit(item.children ?? [], depth + 1)
  })
  visit(categories.value, 0)
  return result
})

async function loadCatalog() {
  loading.value = true
  try {
    const [categoryRows, brandRows] = await Promise.all([
      adminApi.request<any[]>('/api/v1/products/categories/tree'),
      adminApi.request<any[]>('/api/v1/products/brands')
    ])
    categories.value = categoryRows
    brands.value = brandRows
    if (!selectedCategoryId.value && flatCategories.value.length) selectedCategoryId.value = flatCategories.value.at(-1)?.id
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '目录加载失败')
  } finally { loading.value = false }
}

async function loadAttributes() {
  if (!selectedCategoryId.value) { groups.value = []; return }
  groups.value = await adminApi.request<any[]>(`/api/v1/products/categories/${selectedCategoryId.value}/attributes`)
}

async function createCategory() {
  await adminApi.request('/api/v1/admin/catalog/categories', { method: 'POST', body: JSON.stringify(categoryForm) })
  Object.assign(categoryForm, { parentId: undefined, name: '', sortOrder: 0 }); await loadCatalog(); ElMessage.success('分类已创建')
}
async function createBrand() {
  await adminApi.request('/api/v1/admin/catalog/brands', { method: 'POST', body: JSON.stringify(brandForm) })
  Object.assign(brandForm, { name: '', logoUrl: '', description: '', categoryIds: [] }); await loadCatalog(); ElMessage.success('品牌已创建并完成类目关联')
}
async function createGroup() {
  if (!selectedCategoryId.value) return
  await adminApi.request(`/api/v1/admin/catalog/categories/${selectedCategoryId.value}/attribute-groups`, { method: 'POST', body: JSON.stringify(groupForm) })
  Object.assign(groupForm, { name: '', sortOrder: 0 }); await loadAttributes(); ElMessage.success('属性组已创建')
}
function draft(groupId: number) {
  return attributeDrafts[groupId] ??= { name: '', type: 'SELECT', unit: '', searchable: true, comparable: true, saleAttribute: false }
}
async function createAttribute(groupId: number) {
  await adminApi.request(`/api/v1/admin/catalog/attribute-groups/${groupId}/attributes`, { method: 'POST', body: JSON.stringify(draft(groupId)) })
  delete attributeDrafts[groupId]; await loadAttributes(); ElMessage.success('属性已创建')
}
async function createOption(attributeId: number) {
  const value = optionDrafts[attributeId]?.trim(); if (!value) return
  await adminApi.request(`/api/v1/admin/catalog/attributes/${attributeId}/options`, { method: 'POST', body: JSON.stringify({ value, sortOrder: 0 }) })
  optionDrafts[attributeId] = ''; await loadAttributes(); ElMessage.success('可选值已添加')
}

watch(selectedCategoryId, loadAttributes)
onMounted(async () => { await loadCatalog(); await loadAttributes() })
</script>

<template>
  <section class="commerce-page" v-loading="loading">
    <header class="workspace-heading"><div><p>CATALOG GOVERNANCE</p><h2>分类 · 品牌 · 属性模板</h2><span>平台统一维护商品目录，商家创建 SPU/SKU 时复用同一套结构化标准。</span></div></header>
    <div class="catalog-columns">
      <article class="edit-panel"><h3>分类树</h3><el-tree :data="categories" node-key="id" :props="{ label: 'name', children: 'children' }" default-expand-all />
        <el-divider content-position="left">新增分类</el-divider><el-form label-position="top"><el-form-item label="上级分类"><el-select v-model="categoryForm.parentId" clearable placeholder="无，创建一级分类"><el-option v-for="item in flatCategories" :key="item.id" :value="item.id" :label="`${'— '.repeat(item.depth)}${item.name}`" /></el-select></el-form-item><el-form-item label="分类名称"><el-input v-model="categoryForm.name" /></el-form-item><el-button type="primary" :disabled="!categoryForm.name" @click="createCategory">创建分类</el-button></el-form>
      </article>
      <article class="edit-panel"><h3>品牌库</h3><div class="brand-cloud"><el-tag v-for="brand in brands" :key="brand.id" effect="plain">{{ brand.name }}</el-tag></div>
        <el-divider content-position="left">新增品牌</el-divider><el-form label-position="top"><el-form-item label="品牌名称"><el-input v-model="brandForm.name" /></el-form-item><el-form-item label="关联分类"><el-select v-model="brandForm.categoryIds" multiple><el-option v-for="item in flatCategories" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item><el-form-item label="简介"><el-input v-model="brandForm.description" type="textarea" /></el-form-item><el-button type="primary" :disabled="!brandForm.name" @click="createBrand">创建品牌</el-button></el-form>
      </article>
    </div>
    <article class="log-panel catalog-attributes"><header><div><h3>属性模板</h3><p>搜索、对比和销售属性均由模板显式标记。</p></div><el-select v-model="selectedCategoryId" placeholder="选择分类"><el-option v-for="item in flatCategories" :key="item.id" :value="item.id" :label="item.name" /></el-select></header>
      <div class="inline-create"><el-input v-model="groupForm.name" placeholder="新属性组，如：核心参数" /><el-button type="primary" :disabled="!selectedCategoryId || !groupForm.name" @click="createGroup">添加属性组</el-button></div>
      <el-empty v-if="!groups.length" description="当前分类还没有属性模板" />
      <section v-for="group in groups" :key="group.id" class="attribute-group"><h4>{{ group.name }}</h4><div v-for="attribute in group.attributes" :key="attribute.id" class="attribute-row"><div><strong>{{ attribute.name }}</strong><small>{{ attribute.type }} · {{ attribute.saleAttribute ? '销售属性' : '基础属性' }} · {{ attribute.searchable ? '可搜索' : '不搜索' }}</small></div><div class="option-list"><el-tag v-for="option in attribute.options" :key="option.id" size="small">{{ option.value }}</el-tag><el-input v-model="optionDrafts[attribute.id]" size="small" placeholder="新增可选值" @keyup.enter="createOption(attribute.id)" /><el-button size="small" @click="createOption(attribute.id)">添加</el-button></div></div>
        <div class="attribute-create"><el-input v-model="draft(group.id).name" placeholder="属性名称" /><el-select v-model="draft(group.id).type"><el-option label="单选" value="SELECT" /><el-option label="文本" value="TEXT" /><el-option label="数值" value="NUMBER" /></el-select><el-checkbox v-model="draft(group.id).searchable">可搜索</el-checkbox><el-checkbox v-model="draft(group.id).comparable">可对比</el-checkbox><el-checkbox v-model="draft(group.id).saleAttribute">销售属性</el-checkbox><el-button type="primary" plain :disabled="!draft(group.id).name" @click="createAttribute(group.id)">添加属性</el-button></div>
      </section>
    </article>
  </section>
</template>
