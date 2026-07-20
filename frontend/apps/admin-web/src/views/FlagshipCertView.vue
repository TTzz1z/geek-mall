<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  PhArrowsClockwise as ArrowsClockwise,
  PhFilePdf as FilePdf,
  PhMagnifyingGlass as MagnifyingGlass,
  PhStorefront as Storefront
} from '@phosphor-icons/vue'
import { adminApi } from '../api'
import { statusTone } from '../status'

const items = ref<any[]>([])
const loading = ref(false)
const tab = ref('')
const keyword = ref('')
const keywordInput = ref<{ focus?: () => void } | null>(null)
const page = ref(1)
const pageSize = 10

const tabs = [
  { label: '全部申请', value: '' },
  { label: '待审核', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' }
]

const pendingCount = computed(() => items.value.filter((i) => i.status === 'PENDING').length)

const filtered = computed(() => {
  let list = items.value
  if (tab.value) list = list.filter((i) => i.status === tab.value)
  const q = keyword.value.trim().toLowerCase()
  if (q) {
    list = list.filter((i) =>
      String(i.shopName || '').toLowerCase().includes(q) ||
      String(i.brandName || '').toLowerCase().includes(q)
    )
  }
  return list
})

const total = computed(() => filtered.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const pageStart = computed(() => (total.value === 0 ? 0 : (page.value - 1) * pageSize + 1))
const pageEnd = computed(() => Math.min(page.value * pageSize, total.value))
const paged = computed(() => {
  const start = (page.value - 1) * pageSize
  return filtered.value.slice(start, start + pageSize)
})

const pageList = computed(() => {
  const totalP = totalPages.value
  const cur = page.value
  if (totalP <= 5) return Array.from({ length: totalP }, (_, i) => i + 1)
  const pages: (number | '…')[] = [1]
  const left = Math.max(2, cur - 1)
  const right = Math.min(totalP - 1, cur + 1)
  if (left > 2) pages.push('…')
  for (let i = left; i <= right; i++) pages.push(i)
  if (right < totalP - 1) pages.push('…')
  pages.push(totalP)
  return pages
})

async function load() {
  loading.value = true
  try {
    items.value = await adminApi.request('/api/v1/admin/shop/flagship-certs')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function switchTab(next: string) {
  if (tab.value === next) return
  tab.value = next
  page.value = 1
}

function fileName(url?: string) {
  if (!url) return '资质文件.pdf'
  try {
    const path = decodeURIComponent(String(url).split('?')[0])
    const name = path.split('/').pop() || path
    return name.includes('.') ? name : `${name}.pdf`
  } catch {
    return '资质文件.pdf'
  }
}

function formatTime(value?: string) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function review(row: any) {
  try {
    await ElMessageBox.confirm(
      `店铺「${row.shopName}」· 品牌 ${row.brandName || '—'}`,
      '审核旗舰认证',
      {
        distinguishCancelAndClose: true,
        confirmButtonText: '通过',
        cancelButtonText: '驳回',
        type: 'warning'
      }
    )
    await adminApi.request(`/api/v1/admin/shop/flagship-certs/${row.id}/approve`, { method: 'POST' })
    ElMessage.success('已认证为官方旗舰店')
    await load()
  } catch (action) {
    if (action === 'cancel') {
      try {
        const { value } = await ElMessageBox.prompt('驳回原因', '驳回旗舰认证', { inputPattern: /\S+/ })
        await adminApi.request(`/api/v1/admin/shop/flagship-certs/${row.id}/reject`, {
          method: 'POST',
          body: JSON.stringify({ reason: value })
        })
        ElMessage.success('已驳回')
        await load()
      } catch {
        /* dismissed */
      }
    }
  }
}

function showDetail(row: any) {
  const lines = [
    `申请 ID：${row.id}`,
    `店铺：${row.shopName || '—'}`,
    `商家 ID：${row.merchantId ?? '—'}`,
    `品牌：${row.brandName || '—'}`,
    `状态：${row.status || '—'}`,
    `资质：${row.evidenceUrl || '—'}`,
    `申请时间：${formatTime(row.createdAt)}`,
    row.rejectReason ? `驳回原因：${row.rejectReason}` : ''
  ].filter(Boolean)
  ElMessageBox.alert(lines.join('\n'), '申请详情', { confirmButtonText: '关闭' })
}

watch(keyword, () => { page.value = 1 })
watch(filtered, () => {
  if (page.value > totalPages.value) page.value = totalPages.value
})

onMounted(load)
</script>

<template>
  <section class="commerce-page flagship-page" v-loading="loading">
    <header class="flagship-heading">
      <div>
        <p>FLAGSHIP CERTIFICATION</p>
        <h2>官方旗舰店认证</h2>
        <span>审核通过后，店铺类型将自动切换为 OFFICIAL_FLAGSHIP 并下发证书状态。</span>
      </div>
      <div class="flagship-heading-actions">
        <el-button class="flagship-refresh" @click="load">
          <ArrowsClockwise :size="15" weight="bold" />
          刷新数据
        </el-button>
        <button type="button" class="flagship-icon-btn" aria-label="搜索" @click="keywordInput?.focus?.()">
          <MagnifyingGlass :size="18" />
        </button>
      </div>
    </header>

    <div class="ops-card flagship-card">
      <div class="flagship-toolbar">
        <div class="ops-tabs">
          <button
            v-for="t in tabs"
            :key="t.value || 'all'"
            type="button"
            :class="{ active: tab === t.value }"
            @click="switchTab(t.value)"
          >
            {{ t.label }}
            <span v-if="t.value === 'PENDING' && pendingCount" class="count">{{ pendingCount }}</span>
          </button>
        </div>
        <el-input
          ref="keywordInput"
          v-model="keyword"
          class="flagship-search"
          clearable
          placeholder="搜索店铺名称 / 品牌"
        >
          <template #prefix>
            <MagnifyingGlass :size="15" />
          </template>
        </el-input>
      </div>

      <el-table :data="paged" class="workspace-table" empty-text="暂无认证申请">
        <el-table-column prop="id" label="申请 ID" width="110">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="申请店铺" min-width="200">
          <template #default="{ row }">
            <div class="flagship-shop">
              <span class="flagship-shop-icon" aria-hidden="true"><Storefront :size="16" weight="fill" /></span>
              <div>
                <strong>{{ row.shopName || '—' }}</strong>
                <small>商家 ID: {{ row.merchantId ?? '—' }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="关联品牌" min-width="120">
          <template #default="{ row }">
            <span class="flagship-brand">{{ row.brandName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="`tone-${statusTone(row.status)}`">{{ row.status || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="资质文件" min-width="200">
          <template #default="{ row }">
            <a
              v-if="row.evidenceUrl"
              class="flagship-file"
              :href="row.evidenceUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              <FilePdf :size="16" weight="fill" />
              <span>{{ fileName(row.evidenceUrl) }}</span>
            </a>
            <span v-else class="flagship-file is-empty">—</span>
          </template>
        </el-table-column>
        <el-table-column label="申请时间" width="160">
          <template #default="{ row }">
            <span class="mono">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <button
              v-if="row.status === 'PENDING'"
              type="button"
              class="flagship-link is-primary"
              @click="review(row)"
            >
              去审核
            </button>
            <button v-else type="button" class="flagship-link" @click="showDetail(row)">详情</button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="flagship-pager">
        <p>显示第 {{ pageStart }} 到 {{ pageEnd }} 条，共 {{ total }} 条记录</p>
        <div class="flagship-pager-nav">
          <button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
          <template v-for="(p, idx) in pageList" :key="`${p}-${idx}`">
            <span v-if="p === '…'" class="ellipsis">…</span>
            <button
              v-else
              type="button"
              class="page-num"
              :class="{ active: page === p }"
              @click="page = p"
            >
              {{ p }}
            </button>
          </template>
          <button type="button" :disabled="page >= totalPages" @click="page += 1">下一页</button>
        </div>
      </footer>
    </div>
  </section>
</template>
