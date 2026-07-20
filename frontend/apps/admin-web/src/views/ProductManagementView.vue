<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { PhArrowsClockwise as ArrowsClockwise, PhPlus as Plus, PhTrash as Trash } from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { resolveCover } from '../covers'
import { isMerchantWorkspace } from '../rbac'
import { useSessionStore } from '../session'
import { statusTone } from '../status'

const session = useSessionStore()
const isAdmin = computed(() =>
  !!session.principal?.roles.includes('SYSTEM_ADMIN') ||
  !!session.principal?.roles.includes('PRODUCT_ADMIN')
)
const isMerchantMode = computed(() => isMerchantWorkspace(session.principal))
/** 平台运营代运营自营店：侧栏「我的商品」，走本店 merchant 商品接口 */
const isOpsSelfOp = computed(() =>
  !!session.principal?.roles.includes('OPS') && !!session.principal?.merchantId
)
/** 本店商品视图：商家工作台，或运营的自营「我的商品」 */
const shopProductMode = computed(() => isMerchantMode.value || isOpsSelfOp.value)
/** 商家工作台，或商品管理员/运营代运营自营店（JWT 含 merchantId）时可创建/上架 */
const canCreateProduct = computed(() =>
  isMerchantMode.value ||
  (!!session.principal?.roles.includes('PRODUCT_ADMIN') && !!session.principal?.merchantId) ||
  isOpsSelfOp.value
)
const pageTitle = computed(() => (isOpsSelfOp.value ? '我的商品' : '商品中心'))

const adminTab = ref<'all' | 'pending'>('all')
const saleFilter = ref<'ALL' | 'ON_SALE' | 'OFF_SALE'>('ALL')
const items = ref<any[]>([])
const loading = ref(false)
const saving = ref(false)
const coverUploading = ref(false)
const pendingCoverFile = ref<File | null>(null)
const coverPreviewUrl = ref('')
const offSaleAllLoading = ref(false)
const dialog = ref(false)
const editingId = ref<number | null>(null)
const categories = ref<any[]>([])
const brands = ref<any[]>([])
const violationSpuId = ref<number | null>(10009)

/** Relative path stored in DB; display via resolveCover(). */
const COVER_PLACEHOLDER = '/covers/geek-spu-10004.jpg'

const emptyForm = () => ({
  title: '',
  subtitle: '',
  categoryId: 410 as number,
  brandId: 500 as number,
  coverUrl: '',
  descriptionHtml: '<h2>GEEK SELECT</h2><p>自营代运营新品。</p>',
  packageList: '主机、说明书',
  serviceDesc: '一年质保',
  skuCode: '',
  salePrice: 259,
  marketPrice: 299,
  costPrice: 120,
  weight: 1000,
  volume: 1800
})
const form = reactive(emptyForm())

const dialogTitle = computed(() => (editingId.value ? `编辑商品 #${editingId.value}` : '创建 SPU 与首个 SKU'))
const displayCover = computed(() => {
  if (coverPreviewUrl.value) return coverPreviewUrl.value
  return resolveCover(editingId.value, form.coverUrl)
})
const hasCover = computed(() => !!pendingCoverFile.value || (!!form.coverUrl && !form.coverUrl.startsWith('blob:')))

function rowCover(row: { id?: number; coverUrl?: string }) {
  return resolveCover(row.id, row.coverUrl)
}

function clearLocalCover() {
  if (coverPreviewUrl.value.startsWith('blob:')) URL.revokeObjectURL(coverPreviewUrl.value)
  coverPreviewUrl.value = ''
  pendingCoverFile.value = null
}

function formatPrice(value: unknown) {
  const n = Number(value || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function statusCode(row: any) {
  const sale = String(row.saleStatus || '')
  if (sale === 'ON_SALE') return 'ON_SALE'
  if (sale === 'OFF_SALE') return 'OFF_SALE'
  if (sale === 'VIOLATION_OFF_SALE') return 'VIOLATION'
  const code = String(row.auditStatus || '')
  if (code === 'PENDING_REVIEW') return 'REVIEWING'
  return code || '—'
}

function isSelfOperated(row: any) {
  return row?.selfOperated === true
    || row?.sourceType === 'SELF_OPERATED'
    || row?.shopType === 'SELF_OPERATED'
}

function canAdminEdit(row: any) {
  return isSelfOperated(row)
}

function canAdminOffSale(row: any) {
  return String(row.saleStatus) === 'ON_SALE'
}

function shopTypeLabel(row: any) {
  if (isSelfOperated(row)) return '自营'
  if (row.shopType === 'OFFICIAL_FLAGSHIP') return '旗舰'
  return '第三方'
}

async function load() {
  loading.value = true
  try {
    if (shopProductMode.value) {
      items.value = await adminApi.request<any[]>('/api/v1/merchant/products/spus')
    } else if (adminTab.value === 'pending') {
      items.value = await adminApi.request<any[]>('/api/v1/admin/products/spus/pending')
    } else {
      const q = saleFilter.value === 'ALL' ? '' : `?saleStatus=${saleFilter.value}`
      items.value = await adminApi.request<any[]>(`/api/v1/admin/products/spus${q}`)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

watch([adminTab, saleFilter], () => { if (!shopProductMode.value) load() })

function productPayload() {
  return {
    title: form.title,
    subtitle: form.subtitle,
    categoryId: form.categoryId,
    brandId: form.brandId,
    coverUrl: form.coverUrl,
    descriptionHtml: form.descriptionHtml,
    packageList: form.packageList,
    serviceDesc: form.serviceDesc,
    skus: [{
      skuCode: form.skuCode,
      barcode: '',
      salePrice: form.salePrice,
      marketPrice: form.marketPrice,
      costPrice: form.costPrice,
      weight: form.weight,
      volume: form.volume,
      attributes: []
    }],
    attributes: []
  }
}

function resetForm() {
  clearLocalCover()
  Object.assign(form, emptyForm())
}

async function meta() {
  try {
    categories.value = await adminApi.request<any[]>('/api/v1/products/categories/tree')
    brands.value = await adminApi.request<any[]>('/api/v1/products/brands')
  } catch { /* ignore */ }
}

function openCreate() {
  editingId.value = null
  resetForm()
  dialog.value = true
}

async function openEdit(row: any) {
  if (!shopProductMode.value && !canAdminEdit(row)) {
    ElMessage.warning('仅可编辑平台自营商品；第三方商品请使用下架')
    return
  }
  loading.value = true
  try {
    const detail = await adminApi.request<any>(`/api/v1/products/spus/${row.id}`)
    const sku = Array.isArray(detail.skus) && detail.skus.length ? detail.skus[0] : {}
    clearLocalCover()
    editingId.value = Number(detail.id)
    Object.assign(form, {
      title: detail.title || '',
      subtitle: detail.subtitle || '',
      categoryId: Number(detail.categoryId || 410),
      brandId: Number(detail.brandId || 500),
      coverUrl: detail.coverUrl || '',
      descriptionHtml: detail.descriptionHtml || '',
      packageList: detail.packageList || '',
      serviceDesc: detail.serviceDesc || '',
      skuCode: sku.skuCode || '',
      salePrice: Number(sku.salePrice ?? detail.minPrice ?? 0),
      marketPrice: Number(sku.marketPrice ?? sku.salePrice ?? 0),
      costPrice: Number(sku.costPrice ?? 0),
      weight: Number(sku.weight ?? 1000),
      volume: Number(sku.volume ?? 1800)
    })
    dialog.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载商品失败')
  } finally {
    loading.value = false
  }
}

async function uploadCoverFile(spuId: number, file: File) {
  // 商家工作台 / 商品管理员自营代运营走 merchant 写接口；平台全局编辑走 admin
  const path = isMerchantMode.value || canCreateProduct.value
    ? `/api/v1/merchant/products/${spuId}/cover`
    : `/api/v1/admin/products/spus/${spuId}/cover`
  const body = new FormData()
  body.append('file', file)
  const result = await adminApi.request<{ coverUrl: string }>(path, { method: 'POST', body })
  form.coverUrl = result.coverUrl
  clearLocalCover()
  return result.coverUrl
}

async function save() {
  if (!form.title || !form.skuCode) {
    ElMessage.warning('请填写标题和 SKU 编码')
    return
  }
  if (!hasCover.value) {
    ElMessage.warning('请上传商品封面图')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      if (pendingCoverFile.value) {
        await uploadCoverFile(editingId.value, pendingCoverFile.value)
      }
      const path = isMerchantMode.value || canCreateProduct.value
        ? `/api/v1/merchant/products/spus/${editingId.value}`
        : `/api/v1/admin/products/spus/${editingId.value}`
      await adminApi.request(path, { method: 'PUT', body: JSON.stringify(productPayload()) })
      ElMessage.success(`商品 ${editingId.value} 已保存`)
    } else {
      if (!form.coverUrl || form.coverUrl.startsWith('blob:')) {
        form.coverUrl = COVER_PLACEHOLDER
      }
      const result = await adminApi.request<{ id: number }>('/api/v1/merchant/products/spus', {
        method: 'POST',
        body: JSON.stringify(productPayload())
      })
      editingId.value = Number(result.id)
      if (pendingCoverFile.value) {
        await uploadCoverFile(editingId.value, pendingCoverFile.value)
      }
      ElMessage.success(`商品 ${result.id} 已保存为草稿`)
    }
    dialog.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function action(id: number, name: string, reason?: string) {
  try {
    const prefix = shopProductMode.value ? 'merchant' : 'admin'
    await adminApi.request(`/api/v1/${prefix}/products/spus/${id}/${name}`, {
      method: 'POST',
      body: reason ? JSON.stringify({ reason }) : undefined
    })
    ElMessage.success('操作成功')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function reject(id: number) {
  const { value } = await ElMessageBox.prompt('请输入明确的驳回原因', '拒绝提交', {
    inputPattern: /\S+/,
    inputErrorMessage: '原因不能为空'
  })
  await action(id, 'reject', value)
}

async function showDetail(row: any) {
  const lines = [
    `SPU ID：${row.id}`,
    `标题：${row.title || '—'}`,
    `商家：${row.shopName || '—'}`,
    `品牌：${row.brandName || '—'}`,
    `类目：${row.categoryName || '—'}`,
    `价格：¥${formatPrice(row.minPrice)}`,
    `状态：${statusCode(row)}`
  ]
  try {
    await ElMessageBox.confirm(lines.join('\n'), '商品详情', {
      distinguishCancelAndClose: true,
      confirmButtonText: '拒绝提交',
      cancelButtonText: '关闭',
      confirmButtonClass: 'el-button--danger'
    })
    await reject(row.id)
  } catch {
    /* dismissed */
  }
}

async function violationOffSale() {
  if (!violationSpuId.value) return
  const { value } = await ElMessageBox.prompt('违规原因', '违规下架', { inputPattern: /\S+/ })
  try {
    await adminApi.request(`/api/v1/admin/products/spus/${violationSpuId.value}/violation-off-sale`, {
      method: 'POST',
      body: JSON.stringify({ reason: value })
    })
    ElMessage.success(`SPU ${violationSpuId.value} 已违规下架`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function clearViolation() {
  if (!violationSpuId.value) return
  await adminApi.request(`/api/v1/admin/products/spus/${violationSpuId.value}/clear-violation`, { method: 'POST' })
  ElMessage.success('已解除违规')
}

async function adminOffSaleOne(id: number) {
  try {
    await ElMessageBox.confirm('确认下架该商品？第三方与自营均可下架。', '平台下架', { type: 'warning' })
    await adminApi.request(`/api/v1/admin/products/spus/${id}/off-sale`, { method: 'POST' })
    ElMessage.success('已下架')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '下架失败')
  }
}

async function adminOffSaleAll() {
  try {
    await ElMessageBox.confirm(
      '将下架当前所有「在售」商品（含第三方），此操作影响面大，是否继续？',
      '一键下架全部',
      { type: 'warning', confirmButtonText: '确认全部下架', confirmButtonClass: 'el-button--danger' }
    )
    offSaleAllLoading.value = true
    const result = await adminApi.request<{ offSaleCount: number }>('/api/v1/admin/products/spus/off-sale-all', { method: 'POST' })
    ElMessage.success(`已下架 ${result.offSaleCount} 个商品`)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '批量下架失败')
  } finally {
    offSaleAllLoading.value = false
  }
}

async function uploadDocument(id: number, options: { file: File }) {
  const body = new FormData()
  body.append('type', 'PRODUCT_SPEC')
  body.append('file', options.file)
  try {
    await adminApi.request(`/api/v1/merchant/products/${id}/documents/upload`, { method: 'POST', body })
    ElMessage.success('商品资料已上传到对象存储')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '资料上传失败')
  }
}

async function uploadCoverInDialog(options: UploadRequestOptions) {
  const file = options.file as File
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    return
  }
  coverUploading.value = true
  try {
    if (editingId.value) {
      await uploadCoverFile(editingId.value, file)
      ElMessage.success('封面已更新')
      return
    }
    clearLocalCover()
    pendingCoverFile.value = file
    coverPreviewUrl.value = URL.createObjectURL(file)
    form.coverUrl = coverPreviewUrl.value
    ElMessage.success('已选择封面，保存草稿时会自动上传')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '封面上传失败')
  } finally {
    coverUploading.value = false
  }
}

function removeCover() {
  clearLocalCover()
  form.coverUrl = ''
}

onMounted(() => { load(); meta() })
</script>

<template>
  <section class="commerce-page product-page" v-loading="loading">
    <header class="product-heading">
      <div>
        <h2>{{ pageTitle }}</h2>
      </div>
      <div class="product-heading-actions">
        <el-radio-group v-if="isAdmin && !shopProductMode" v-model="adminTab" size="small">
          <el-radio-button label="all">全部商品</el-radio-button>
          <el-radio-button label="pending">审核队列</el-radio-button>
        </el-radio-group>
        <el-select
          v-if="isAdmin && !shopProductMode && adminTab === 'all'"
          v-model="saleFilter"
          size="small"
          style="width: 120px"
        >
          <el-option label="全部状态" value="ALL" />
          <el-option label="在售" value="ON_SALE" />
          <el-option label="已下架" value="OFF_SALE" />
        </el-select>
        <el-button
          v-if="isAdmin && !shopProductMode && adminTab === 'all'"
          type="danger"
          plain
          size="small"
          :loading="offSaleAllLoading"
          @click="adminOffSaleAll"
        >
          一键下架全部
        </el-button>
        <el-button class="product-refresh" @click="load">
          <ArrowsClockwise :size="15" weight="bold" />
          刷新数据
        </el-button>
        <el-button v-if="canCreateProduct" type="primary" @click="openCreate">创建商品</el-button>
      </div>
    </header>

    <div class="ops-card product-card">
      <header class="product-card-head">
        <p>{{ shopProductMode ? (isOpsSelfOp ? 'SELF-OPERATED STUDIO' : 'MERCHANT PRODUCT STUDIO') : (adminTab === 'all' ? 'PLATFORM CATALOG' : 'PRODUCT REVIEW') }}</p>
        <h3>
          {{ shopProductMode
            ? (isOpsSelfOp ? '自营商品管理' : '商家商品管理')
            : (adminTab === 'all' ? '全平台商品（自营可编辑 / 任意可下架）' : '商品上架审核队列') }}
        </h3>
        <span>
          {{ shopProductMode
            ? (isOpsSelfOp
              ? '管理平台自营店商品：创建、编辑、提交审核、上架与下架。'
              : '仅管理本店商品：编辑、提交审核、上架与下架。')
            : (adminTab === 'all'
              ? (canCreateProduct
                ? '可查看全部商品；自营店可创建与编辑；任意在售商品可下架。'
                : '可查看全部商品；仅自营商品可编辑；任意在售商品可下架；支持一键下架全部。')
              : '核验类目、品牌授权、定价与图文资料，拦截违规商品。') }}
        </span>
      </header>

      <el-table
        :data="items"
        class="workspace-table product-ops-table"
        :empty-text="shopProductMode || adminTab === 'all' ? '暂无商品' : '当前没有待处理商品'"
      >
        <el-table-column prop="id" label="SPU ID" width="110">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="商品标题" min-width="240">
          <template #default="{ row }">
            <div class="product-cell">
              <img v-if="rowCover(row)" :src="rowCover(row)" alt="" />
              <span v-else class="product-thumb-fallback" aria-hidden="true">IMG</span>
              <div>
                <strong>{{ row.title || '—' }}</strong>
                <small>商家: {{ row.shopName || row.subtitle || '—' }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="!shopProductMode" label="类型" width="90" align="center">
          <template #default="{ row }">
            <span class="product-brand">{{ shopTypeLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!shopProductMode" label="品牌" width="120">
          <template #default="{ row }">
            <span class="product-brand">{{ row.brandName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="价格 (¥)" width="120" align="right">
          <template #default="{ row }">
            <strong class="mono product-price">{{ formatPrice(row.minPrice) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.saleStatus || row.auditStatus)}`">{{ statusCode(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" :min-width="shopProductMode || adminTab === 'all' ? 240 : 200" fixed="right" align="right">
          <template #default="{ row }">
            <div class="product-row-actions">
              <template v-if="!shopProductMode && adminTab === 'pending'">
                <button type="button" class="product-link is-danger" @click="showDetail(row)">拒绝提交</button>
                <el-button type="primary" @click="action(row.id, 'approve', '审核通过')">通过上架</el-button>
              </template>
              <template v-else-if="!shopProductMode && adminTab === 'all'">
                <el-button v-if="canAdminEdit(row)" type="primary" link @click="openEdit(row)">编辑</el-button>
                <el-button v-if="canAdminOffSale(row)" type="danger" link @click="adminOffSaleOne(row.id)">下架</el-button>
                <span v-if="!canAdminEdit(row) && !canAdminOffSale(row)" class="cover-hint">仅可查看</span>
              </template>
              <template v-else>
                <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
                <el-upload
                  class="cover-upload"
                  :show-file-list="false"
                  accept=".pdf,.doc,.docx,.xls,.xlsx,.jpg,.jpeg,.png"
                  :http-request="(opt: UploadRequestOptions) => uploadDocument(row.id, { file: opt.file as File })"
                >
                  <el-button type="primary" link>上传资料</el-button>
                </el-upload>
                <el-button v-if="['DRAFT', 'REJECTED'].includes(row.auditStatus)" type="primary" link @click="action(row.id, 'submit')">提交审核</el-button>
                <el-button v-if="row.auditStatus === 'APPROVED' && row.saleStatus === 'OFF_SALE'" type="success" link @click="action(row.id, 'on-sale')">上架</el-button>
                <el-button v-if="row.saleStatus === 'ON_SALE'" type="danger" link @click="action(row.id, 'off-sale')">下架</el-button>
              </template>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <footer v-if="isAdmin && !shopProductMode" class="product-admin-tools">
        <span>违规处置</span>
        <el-input-number v-model="violationSpuId" :min="1" controls-position="right" size="small" />
        <el-button size="small" type="danger" plain @click="violationOffSale">违规下架</el-button>
        <el-button size="small" plain @click="clearViolation">解除违规</el-button>
      </footer>
    </div>

    <el-dialog
      v-model="dialog"
      :title="dialogTitle"
      width="780px"
      top="8vh"
      :align-center="false"
      :close-on-click-modal="false"
      destroy-on-close
      class="product-edit-dialog"
      append-to-body
    >
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="商品标题"><el-input v-model="form.title" /></el-form-item>
          <el-form-item label="副标题"><el-input v-model="form.subtitle" /></el-form-item>
          <el-form-item label="分类">
            <el-select v-model="form.categoryId" filterable>
              <template v-for="root in categories" :key="root.id">
                <el-option
                  v-for="child in root.children?.length ? root.children : [root]"
                  :key="child.id"
                  :label="child.name"
                  :value="child.id"
                />
              </template>
            </el-select>
          </el-form-item>
          <el-form-item label="品牌">
            <el-select v-model="form.brandId" filterable>
              <el-option v-for="brand in brands" :key="brand.id" :label="brand.name" :value="brand.id" />
            </el-select>
          </el-form-item>

          <el-form-item class="span-2" label="封面">
            <div class="cover-uploader-wrap">
              <el-upload
                class="cover-uploader"
                :show-file-list="false"
                :disabled="coverUploading"
                accept="image/jpeg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif"
                :http-request="uploadCoverInDialog"
              >
                <img v-if="displayCover" class="cover-preview" :src="displayCover" alt="cover" />
                <div v-else class="cover-plus">
                  <Plus :size="28" weight="bold" />
                  <span>{{ coverUploading ? '上传中…' : '上传封面' }}</span>
                </div>
              </el-upload>
              <el-button
                v-if="displayCover"
                class="cover-remove"
                text
                type="danger"
                :disabled="coverUploading"
                @click="removeCover"
              >
                <Trash :size="14" />
                移除
              </el-button>
              <small class="cover-hint">支持 jpg / png / webp；新建时选图后点「保存草稿」即可自动上传</small>
            </div>
          </el-form-item>

          <el-form-item class="span-2" label="详情 HTML"><el-input v-model="form.descriptionHtml" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="包装清单"><el-input v-model="form.packageList" /></el-form-item>
          <el-form-item label="服务说明"><el-input v-model="form.serviceDesc" /></el-form-item>
          <el-form-item label="SKU 编码"><el-input v-model="form.skuCode" /></el-form-item>
          <el-form-item label="销售价"><el-input-number v-model="form.salePrice" :min="0.01" :precision="2" /></el-form-item>
          <el-form-item label="市场价"><el-input-number v-model="form.marketPrice" :min="0.01" :precision="2" /></el-form-item>
          <el-form-item label="成本价"><el-input-number v-model="form.costPrice" :min="0" :precision="2" /></el-form-item>
          <el-form-item label="重量(g)"><el-input-number v-model="form.weight" :min="0" /></el-form-item>
          <el-form-item label="体积"><el-input-number v-model="form.volume" :min="0" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!form.title || !form.skuCode || !hasCover" @click="save">
          {{ editingId ? '保存修改' : '保存草稿' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.cover-uploader-wrap {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
  width: 100%;
}
.cover-uploader :deep(.el-upload) {
  display: block;
  border: 0;
  background: transparent;
}
.cover-preview {
  width: 112px;
  height: 112px;
  object-fit: cover;
  border-radius: 10px;
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  display: block;
}
.cover-plus {
  width: 112px;
  height: 112px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
  background: #f9fafb;
  color: #6b7280;
  font-size: 12px;
  transition: border-color .15s ease, color .15s ease, background .15s ease;
}
.cover-plus:hover {
  border-color: #ff6a2b;
  color: #ff6a2b;
  background: #fff7f2;
}
.cover-remove {
  padding: 0 4px;
}
.cover-hint {
  flex-basis: 100%;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}
</style>

<style>
/* Teleported dialog: keep a stable top offset; scroll inside the body. */
.product-edit-dialog.el-dialog {
  margin-top: 8vh !important;
  margin-bottom: 0 !important;
}
.product-edit-dialog .el-dialog__body {
  max-height: min(68vh, calc(100vh - 200px));
  overflow-y: auto;
}
</style>
