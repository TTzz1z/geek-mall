<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  PhCaretDown as CaretDown,
  PhCaretUp as CaretUp,
  PhDotsSixVertical as DotsSixVertical,
  PhEye as Eye,
  PhMagnifyingGlass as MagnifyingGlass,
  PhPlus as Plus,
  PhX as X
} from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { resolveCover } from '../covers'
import { formatAdminTime, statusLabel, statusTone } from '../status'

type VersionRow = { versionNo: number; title: string; status: string; publishedAt?: string; createdAt?: string }
type FloorBlock = {
  type: string
  code?: string
  title?: string
  subtitle?: string
  cta?: string
  spuId?: number
  spuIds?: number[]
  imageUrl?: string
}
type ProductCard = {
  id: number
  title: string
  coverUrl?: string
  minPrice?: number
  brandName?: string
}

const MALL_HOME = `${String(import.meta.env.VITE_MALL_HOME_URL || 'http://127.0.0.1:8088').replace(/\/$/, '')}/`
const pageCode = 'HOME'
const TYPE_META: Record<string, { label: string; cls: string }> = {
  HERO: { label: '头图', cls: 'hero' },
  PRODUCT_FLOOR: { label: '楼层', cls: 'product_floor' },
  SECKILL: { label: '秒杀', cls: 'seckill' }
}

const versions = ref<VersionRow[]>([])
const currentVersion = ref<number | null>(null)
const currentStatus = ref('')
const title = ref('首页草稿')
const blocks = ref<FloorBlock[]>([])
const loading = ref(false)
const dirty = ref(false)
const expandedIndex = ref(0)
const dragFrom = ref<number | null>(null)
const productMap = ref<Record<number, ProductCard>>({})
const productKeyword = ref('')
const productOptions = ref<ProductCard[]>([])
const searching = ref(false)

const editable = computed(() => currentStatus.value === 'DRAFT' || currentStatus.value === '')
const statusText = computed(() => {
  if (currentStatus.value === 'PUBLISHED') return '已发布'
  if (currentStatus.value === 'DRAFT') return '草稿'
  if (currentStatus.value === 'ARCHIVED') return '已下线'
  return statusLabel(currentStatus.value)
})

function markDirty() {
  dirty.value = true
}

function typeMeta(type: string) {
  return TYPE_META[type] || { label: type, cls: '' }
}

function collectSpuIds(list: FloorBlock[]) {
  const ids = new Set<number>()
  for (const block of list) {
    if (block.spuId) ids.add(Number(block.spuId))
    for (const id of block.spuIds || []) ids.add(Number(id))
  }
  return [...ids].filter(n => Number.isFinite(n) && n > 0)
}

async function loadProducts(ids: number[]) {
  const missing = ids.filter(id => !productMap.value[id])
  if (!missing.length) return
  try {
    const rows = await adminApi.request<ProductCard[]>(`/api/v1/products/batch?ids=${missing.join(',')}`)
    const next = { ...productMap.value }
    for (const row of rows || []) next[row.id] = row
    productMap.value = next
  } catch { /* ignore */ }
}

watch(blocks, list => { loadProducts(collectSpuIds(list)) }, { deep: true })

async function searchProducts(keyword = productKeyword.value) {
  searching.value = true
  try {
    const q = keyword.trim() || '笔记本'
    const page = await adminApi.request<{ items: ProductCard[] }>(
      `/api/v1/search/products?keyword=${encodeURIComponent(q)}&page=1&pageSize=12`
    )
    productOptions.value = page.items || []
    for (const row of productOptions.value) {
      productMap.value[row.id] = row
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '商品搜索失败')
  } finally {
    searching.value = false
  }
}

async function loadVersions(preferVersion?: number) {
  versions.value = await adminApi.request<VersionRow[]>(`/api/v1/admin/cms/pages/${pageCode}/versions`)
  const published = versions.value.find(v => v.status === 'PUBLISHED')
  const draft = versions.value.find(v => v.status === 'DRAFT')
  const pickNo = preferVersion ?? draft?.versionNo ?? published?.versionNo ?? versions.value[0]?.versionNo
  if (pickNo != null) await openVersion(pickNo, true)
}

async function openVersion(versionNo: number, force = false) {
  if (!force && dirty.value) {
    try {
      await ElMessageBox.confirm('当前有未保存修改，切换将丢弃。', '切换版本', {
        confirmButtonText: '继续',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch { return }
  }
  loading.value = true
  try {
    const detail = await adminApi.request<{ versionNo: number; title: string; status: string; blocks: FloorBlock[] }>(
      `/api/v1/admin/cms/pages/${pageCode}/versions/${versionNo}`
    )
    currentVersion.value = detail.versionNo
    currentStatus.value = detail.status
    title.value = detail.title
    blocks.value = Array.isArray(detail.blocks) ? structuredClone(detail.blocks) : []
    expandedIndex.value = blocks.value.length ? 0 : -1
    dirty.value = false
    await loadProducts(collectSpuIds(blocks.value))
  } finally {
    loading.value = false
  }
}

function addBlock(type: 'HERO' | 'PRODUCT_FLOOR' | 'SECKILL') {
  if (type === 'SECKILL' && blocks.value.some(b => b.type === 'SECKILL')) {
    ElMessage.warning('已有秒杀模块')
    return
  }
  if (type === 'HERO' && blocks.value.some(b => b.type === 'HERO')) {
    ElMessage.warning('已有头图，请直接编辑')
    return
  }
  const next: FloorBlock =
    type === 'HERO'
      ? { type: 'HERO', code: 'hero_banner', title: '新品头图', subtitle: '副标题', cta: '立即选购', spuId: undefined, imageUrl: '' }
      : type === 'SECKILL'
        ? { type: 'SECKILL', code: 'seckill', title: '限时秒杀', subtitle: '场次与库存实时同步' }
        : { type: 'PRODUCT_FLOOR', code: `floor_${blocks.value.length + 1}`, title: '新楼层', subtitle: '', spuIds: [] }
  blocks.value.push(next)
  expandedIndex.value = blocks.value.length - 1
  markDirty()
}

function removeBlock(index: number) {
  blocks.value.splice(index, 1)
  if (expandedIndex.value === index) expandedIndex.value = Math.min(index, blocks.value.length - 1)
  else if (expandedIndex.value > index) expandedIndex.value -= 1
  markDirty()
}

function moveBlock(from: number, to: number) {
  if (to < 0 || to >= blocks.value.length || from === to) return
  const [item] = blocks.value.splice(from, 1)
  blocks.value.splice(to, 0, item)
  if (expandedIndex.value === from) expandedIndex.value = to
  else if (from < expandedIndex.value && to >= expandedIndex.value) expandedIndex.value -= 1
  else if (from > expandedIndex.value && to <= expandedIndex.value) expandedIndex.value += 1
  markDirty()
}

function onDragStart(index: number, event: DragEvent) {
  dragFrom.value = index
  event.dataTransfer?.setData('text/plain', String(index))
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDrop(index: number) {
  if (dragFrom.value == null) return
  moveBlock(dragFrom.value, index)
  dragFrom.value = null
}

function productsOf(ids: number[] = []) {
  return ids.map(id => productMap.value[id]).filter(Boolean) as ProductCard[]
}

function heroCover(block?: FloorBlock) {
  if (!block) return ''
  if (block.imageUrl) return resolveCover(block.spuId, block.imageUrl)
  const linked = block.spuId ? productMap.value[Number(block.spuId)] : undefined
  return resolveCover(block.spuId, linked?.coverUrl)
}

function setHeroProduct(block: FloorBlock, product: ProductCard) {
  block.spuId = product.id
  if (!block.title) block.title = product.title
  markDirty()
  loadProducts([product.id])
}

function addFloorProduct(block: FloorBlock, product: ProductCard) {
  const ids = [...(block.spuIds || [])]
  if (ids.includes(product.id)) {
    ElMessage.info('该商品已在楼层中')
    return
  }
  ids.push(product.id)
  block.spuIds = ids
  markDirty()
  loadProducts([product.id])
}

function removeFloorProduct(block: FloorBlock, id: number) {
  block.spuIds = (block.spuIds || []).filter(x => x !== id)
  markDirty()
}

async function saveDraft() {
  loading.value = true
  try {
    const body = { title: title.value, blocks: blocks.value }
    const current = versions.value.find(v => v.versionNo === currentVersion.value)
    if (current?.status === 'DRAFT' && currentVersion.value != null) {
      await adminApi.request(`/api/v1/admin/cms/pages/${pageCode}/versions/${currentVersion.value}`, {
        method: 'PUT',
        body: JSON.stringify(body)
      })
      ElMessage.success(`草稿 v${currentVersion.value} 已保存`)
      dirty.value = false
      await loadVersions(currentVersion.value!)
    } else {
      const created = await adminApi.request<{ versionNo: number }>(`/api/v1/admin/cms/pages/${pageCode}/versions`, {
        method: 'POST',
        body: JSON.stringify(body)
      })
      ElMessage.success(`已创建草稿 v${created.versionNo}`)
      dirty.value = false
      await loadVersions(created.versionNo)
    }
    return true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    loading.value = false
  }
}

async function publish() {
  if (currentVersion.value == null) return
  if (dirty.value) {
    const ok = await saveDraft()
    if (!ok) return
  }
  if (currentVersion.value == null) return
  loading.value = true
  try {
    await adminApi.request(`/api/v1/admin/cms/pages/${pageCode}/publish?versionNo=${currentVersion.value}`, { method: 'POST' })
    ElMessage.success(`已发布 v${currentVersion.value}`)
    await loadVersions(currentVersion.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    loading.value = false
  }
}

async function rollback(versionNo: number) {
  try {
    await ElMessageBox.confirm(`确认回滚到 v${versionNo}？`, '回滚发布', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch { return }
  loading.value = true
  try {
    await adminApi.request(`/api/v1/admin/cms/pages/${pageCode}/rollback?versionNo=${versionNo}`, { method: 'POST' })
    ElMessage.success(`已回滚到 v${versionNo}`)
    await loadVersions(versionNo)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '回滚失败')
  } finally {
    loading.value = false
  }
}

function openMallPreview() {
  window.open(MALL_HOME, '_blank', 'noopener,noreferrer')
}

onMounted(async () => {
  try {
    await loadVersions()
    await searchProducts('数码')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})
</script>

<template>
  <section class="commerce-page cms-page" v-loading="loading">
    <header class="cms-hero">
      <div>
        <p>首页装修</p>
        <h2>编辑商城首页</h2>
        <span>
          商品图来自商品库：搜索并添加商品即可，无需在此上传图片。改完保存草稿 → 发布 → 商城首页生效。
        </span>
      </div>
      <div class="heading-actions">
        <el-button @click="addBlock('PRODUCT_FLOOR')">加楼层</el-button>
        <el-button @click="addBlock('SECKILL')">加秒杀</el-button>
        <el-button class="cms-btn-draft" :loading="loading" @click="saveDraft">
          保存草稿{{ dirty ? ' *' : '' }}
        </el-button>
        <el-button class="cms-btn-publish" :loading="loading" @click="publish">发布</el-button>
        <el-button @click="openMallPreview"><Eye :size="16" style="margin-right:6px" />打开商城</el-button>
      </div>
    </header>

    <section class="cms-versions-bar edit-panel">
      <div class="cms-versions-head">
        <h3>版本</h3>
        <small>点选切换；已发布版本只读，保存会新建草稿</small>
      </div>
      <div class="cms-versions-scroll">
        <button
          v-for="row in versions"
          :key="row.versionNo"
          type="button"
          class="cms-version-chip"
          :class="{ active: row.versionNo === currentVersion }"
          @click="openVersion(row.versionNo)"
        >
          <strong>v{{ row.versionNo }}</strong>
          <span>{{ row.title }}</span>
          <em class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ statusLabel(row.status) }}</em>
          <small>{{ formatAdminTime(row.publishedAt || row.createdAt) }}</small>
          <el-button
            v-if="row.status !== 'PUBLISHED'"
            type="primary"
            link
            @click.stop="rollback(row.versionNo)"
          >
            回滚
          </el-button>
        </button>
      </div>
    </section>

    <div class="cms-workspace">
      <article class="cms-editor edit-panel">
        <header class="cms-editor-head">
          <div>
            <h3>编辑 v{{ currentVersion ?? '—' }}</h3>
            <small v-if="!editable">只读版本 · 保存将创建新草稿</small>
          </div>
          <span class="status-badge" :class="`tone-${statusTone(currentStatus)}`">{{ statusText }}</span>
        </header>

        <label class="cms-field">
          <span>页面标题</span>
          <el-input v-model="title" @input="markDirty" />
        </label>

        <div class="cms-section-title">
          <h4>楼层</h4>
          <small>拖拽排序 · {{ blocks.length }}</small>
        </div>

        <div
          v-for="(block, idx) in blocks"
          :key="`${block.type}-${block.code || idx}`"
          class="cms-floor"
          :class="{ open: expandedIndex === idx, dragging: dragFrom === idx }"
          draggable="true"
          @dragstart="onDragStart(idx, $event)"
          @dragover.prevent
          @drop.prevent="onDrop(idx)"
        >
          <header class="cms-floor-head" @click="expandedIndex = expandedIndex === idx ? -1 : idx">
            <span class="cms-drag" @click.stop><DotsSixVertical :size="16" /></span>
            <span class="cms-type-tag" :class="typeMeta(block.type).cls">{{ typeMeta(block.type).label }}</span>
            <strong>{{ block.title || typeMeta(block.type).label }}</strong>
            <div class="cms-floor-actions" @click.stop>
              <button type="button" aria-label="上移" @click="moveBlock(idx, idx - 1)"><CaretUp :size="14" /></button>
              <button type="button" aria-label="下移" @click="moveBlock(idx, idx + 1)"><CaretDown :size="14" /></button>
              <el-button type="danger" link @click="removeBlock(idx)">删</el-button>
            </div>
          </header>

          <div v-if="expandedIndex === idx" class="cms-floor-body">
            <div class="cms-field-grid">
              <label class="cms-field">
                <span>标题</span>
                <el-input v-model="block.title" @input="markDirty" />
              </label>
              <label class="cms-field">
                <span>副标题</span>
                <el-input v-model="block.subtitle" @input="markDirty" />
              </label>
              <label v-if="block.type === 'HERO'" class="cms-field">
                <span>按钮文案</span>
                <el-input v-model="block.cta" @input="markDirty" />
              </label>
              <label v-if="block.type === 'HERO'" class="cms-field">
                <span>头图链接（可选）</span>
                <el-input
                  v-model="block.imageUrl"
                  placeholder="不填则用所选商品封面"
                  @input="markDirty"
                />
              </label>
            </div>

            <div v-if="block.type === 'HERO'" class="cms-pick-box">
              <p>关联商品（决定跳转与默认封面）</p>
              <div v-if="block.spuId && productMap[block.spuId]" class="cms-picked">
                <img v-if="resolveCover(block.spuId, productMap[block.spuId].coverUrl)" :src="resolveCover(block.spuId, productMap[block.spuId].coverUrl)" alt="" />
                <div>
                  <strong>{{ productMap[block.spuId].title }}</strong>
                  <small>SPU {{ block.spuId }}</small>
                </div>
                <el-button link type="danger" @click="block.spuId = undefined; markDirty()">清除</el-button>
              </div>
              <div v-else class="cms-pick-empty">尚未选择商品，可在下方搜索添加</div>
            </div>

            <div v-if="block.type === 'PRODUCT_FLOOR'" class="cms-pick-box">
              <p>楼层商品（图来自商品封面，点 × 可移除）</p>
              <div class="cms-picked-grid">
                <figure v-for="p in productsOf(block.spuIds)" :key="p.id">
                  <img v-if="resolveCover(p.id, p.coverUrl)" :src="resolveCover(p.id, p.coverUrl)" :alt="p.title" />
                  <figcaption>{{ p.title }}</figcaption>
                  <button type="button" class="cms-remove" aria-label="移除" @click="removeFloorProduct(block, p.id)">
                    <X :size="12" />
                  </button>
                </figure>
                <div v-if="!(block.spuIds || []).length" class="cms-pick-empty">还没有商品，请下方搜索添加</div>
              </div>
            </div>

            <div v-if="block.type !== 'SECKILL'" class="cms-search-box">
              <div class="cms-search-bar">
                <el-input
                  v-model="productKeyword"
                  placeholder="搜索商品名称，如 笔记本 / 耳机"
                  clearable
                  @keyup.enter="searchProducts()"
                >
                  <template #prefix><MagnifyingGlass :size="14" /></template>
                </el-input>
                <el-button :loading="searching" @click="searchProducts()">搜索</el-button>
              </div>
              <div class="cms-search-results">
                <button
                  v-for="p in productOptions"
                  :key="p.id"
                  type="button"
                  class="cms-search-item"
                  @click="block.type === 'HERO' ? setHeroProduct(block, p) : addFloorProduct(block, p)"
                >
                  <img v-if="resolveCover(p.id, p.coverUrl)" :src="resolveCover(p.id, p.coverUrl)" :alt="p.title" />
                  <span>
                    <strong>{{ p.title }}</strong>
                    <small>SPU {{ p.id }}{{ p.minPrice != null ? ` · ¥${Number(p.minPrice).toLocaleString()}` : '' }}</small>
                  </span>
                  <em>{{ block.type === 'HERO' ? '选用' : '加入' }}</em>
                </button>
              </div>
            </div>

            <p v-if="block.type === 'SECKILL'" class="cms-hint">秒杀模块读取营销场次，发布后在首页展示。</p>
          </div>
        </div>

        <button type="button" class="cms-add-slot" @click="addBlock('PRODUCT_FLOOR')">
          <Plus :size="16" /> 添加楼层
        </button>
        <div class="cms-add-row">
          <el-button link type="primary" @click="addBlock('HERO')">+ 头图</el-button>
          <el-button link type="primary" @click="addBlock('SECKILL')">+ 秒杀</el-button>
        </div>
      </article>

      <aside class="cms-preview" aria-label="PC 端结构预览">
        <header>
          <h3>PC 结构预览</h3>
          <span class="cms-live-dot">随编辑更新</span>
        </header>
        <div class="cms-pc-frame">
          <div class="cms-pc-chrome">
            <i /><i /><i />
            <span>geekmall.local /</span>
          </div>
          <div v-if="!blocks.length" class="cms-preview-empty">暂无楼层</div>
          <div v-else class="cms-pc-canvas">
            <template v-for="(block, idx) in blocks" :key="`pv-${idx}-${block.code}`">
              <div
                v-if="block.type === 'HERO'"
                class="cms-pc-hero"
                :style="heroCover(block)
                  ? { backgroundImage: `linear-gradient(100deg, rgba(18,22,28,.66), rgba(18,22,28,.18)), url(${heroCover(block)})` }
                  : undefined"
              >
                <p>GEEK MALL</p>
                <h4>{{ block.title || '头图标题' }}</h4>
                <span>{{ block.subtitle || '副标题' }}</span>
                <div class="cms-preview-cta">{{ block.cta || '立即选购' }}</div>
              </div>

              <div v-else-if="block.type === 'PRODUCT_FLOOR'" class="cms-pc-floor">
                <header>
                  <div>
                    <h5>{{ block.title || '商品楼层' }}</h5>
                    <p>{{ block.subtitle || `${block.spuIds?.length || 0} 件商品` }}</p>
                  </div>
                </header>
                <div class="cms-pc-tiles">
                  <article v-for="p in productsOf(block.spuIds).slice(0, 6)" :key="p.id">
                    <img v-if="resolveCover(p.id, p.coverUrl)" :src="resolveCover(p.id, p.coverUrl)" :alt="p.title" />
                    <strong>{{ p.title }}</strong>
                    <small v-if="p.minPrice != null">¥{{ Number(p.minPrice).toLocaleString() }}</small>
                  </article>
                  <article v-for="n in Math.max(0, 3 - productsOf(block.spuIds).length)" :key="`ph-${n}`" class="is-placeholder">
                    <i />
                    <strong>待添加商品</strong>
                  </article>
                </div>
              </div>

              <div v-else-if="block.type === 'SECKILL'" class="cms-pc-seckill">
                <strong>{{ block.title || '限时秒杀' }}</strong>
                <span>{{ block.subtitle || '秒杀楼层' }}</span>
              </div>
            </template>
          </div>
        </div>
        <footer class="cms-preview-foot">
          <small>此处为 PC 结构预览；点「打开商城」查看线上完整首页（需先发布）。</small>
        </footer>
      </aside>
    </div>
  </section>
</template>
