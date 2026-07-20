<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import type { ProductSummary } from '@geek-mall/api-types'
import { mallApi } from '../api'
import { addressApi, marketingApi } from '../api/phase4'
import { authPath, isLoggedIn } from '../auth'
import { sellerLabelOf } from '../catalog'

type SeckillItem = {
  id: number
  sessionCode: string
  skuId: number
  price: number
  stockQty: number
  warmStatus?: string
  startAt?: string
  endAt?: string
}
type SeckillCard = SeckillItem & {
  product?: ProductSummary
  coverUrl: string
  title: string
  subtitle: string
}

/** Demo SKU → SPU map (public catalog has no SKU lookup). Keep in sync with HomeView. */
const SECKILL_SKU_SPU: Record<number, number> = {
  11001: 10001,
  11002: 10001,
  13001: 10003,
  14001: 10004,
  15001: 10005,
  18001: 10008,
  21001: 10011
}

function coverOf(spuId: number, fallback?: string) {
  if (spuId > 0) return `/covers/geek-spu-${spuId}.jpg`
  return fallback || '/covers/geek-spu-10001.jpg'
}

const router = useRouter()
const sessions = ref<SeckillCard[]>([])
const addresses = ref<Array<{ id: number; receiver: string; regionName: string; detail: string; isDefault?: boolean }>>([])
const addressId = ref<number | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const buying = ref(false)
const guest = ref(!isLoggedIn())

async function enrich(rows: SeckillItem[]): Promise<SeckillCard[]> {
  const list = rows || []
  const spuIds = [...new Set(list.map(s => SECKILL_SKU_SPU[s.skuId]).filter(Boolean))]
  const products = spuIds.length
    ? await mallApi.request<ProductSummary[]>(`/api/v1/products/batch?ids=${spuIds.join(',')}`)
    : []
  const bySpu = new Map(products.map(p => [p.id, p]))
  return list.map(s => {
    const spuId = SECKILL_SKU_SPU[s.skuId]
    const product = spuId ? bySpu.get(spuId) : undefined
    return {
      ...s,
      product,
      coverUrl: coverOf(spuId || product?.id || 0, product?.coverUrl),
      title: product?.title || `秒杀 SKU ${s.skuId}`,
      subtitle: product?.subtitle || s.sessionCode
    }
  })
}

async function load() {
  loading.value = true
  error.value = ''
  guest.value = !isLoggedIn()
  try {
    const raw = await marketingApi.seckillSessions()
    sessions.value = await enrich(raw)
    if (isLoggedIn()) {
      try {
        addresses.value = await addressApi.list()
        const d = addresses.value.find(a => a.isDefault) || addresses.value[0]
        if (d) addressId.value = d.id
      } catch {
        addresses.value = []
      }
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function buy(row: SeckillCard) {
  notice.value = ''
  if (!isLoggedIn()) {
    router.push(authPath('login', { reason: 'seckill', redirect: '/seckill' }))
    return
  }
  if (!addressId.value) {
    notice.value = '请先选择收货地址，或到「地址簿」新增地址'
    return
  }
  buying.value = true
  try {
    const result = await marketingApi.seckillOrder(row.skuId, row.id, 1, addressId.value, crypto.randomUUID())
    notice.value = `秒杀请求已提交：${result.status}${result.tradeOrderNo ? '，订单号 ' + result.tradeOrderNo : ''}。稍后可在订单中心查看。`
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '秒杀失败'
  } finally {
    buying.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="seckill-page deals-section">
    <div class="section-heading">
      <div>
        <p>SECKILL</p>
        <h2>限时特惠</h2>
        <span class="floor-sub">与首页「限时秒杀」同一场次 · 库存实时同步</span>
      </div>
    </div>

    <p v-if="loading" class="seckill-status">加载中…</p>
    <p v-else-if="error" class="seckill-status error">{{ error }}</p>

    <template v-else>
      <div v-if="!guest" class="seckill-toolbar">
        <label>
          收货地址
          <select v-model="addressId">
            <option :value="null" disabled>请选择地址</option>
            <option v-for="a in addresses" :key="a.id" :value="a.id">
              {{ a.receiver }} · {{ a.regionName }}{{ a.detail }}
            </option>
          </select>
        </label>
        <RouterLink v-if="!addresses.length" class="seckill-link" to="/user/addresses">去地址簿添加</RouterLink>
      </div>
      <p v-else class="seckill-status">
        浏览秒杀商品无需登录；下单请先
        <RouterLink :to="authPath('login', { reason: 'seckill', redirect: '/seckill' })">登录</RouterLink>
      </p>
      <p v-if="notice" class="seckill-notice">{{ notice }}</p>

      <div class="product-grid home-grid">
        <article v-for="s in sessions" :key="`${s.id}-${s.skuId}`" class="product-tile seckill-product">
          <RouterLink class="seckill-cover" :to="s.product?.id ? `/product/${s.product.id}` : '/seckill'">
            <div><img :src="s.coverUrl" :alt="s.title" loading="lazy" /></div>
          </RouterLink>
          <small>{{ s.product?.brandName || '限时秒杀' }} · 库存 {{ s.stockQty }}</small>
          <h2>{{ s.title }}</h2>
          <p>{{ s.subtitle }}</p>
          <div class="seller-tags">
            <span class="seller-tag">限时秒杀</span>
            <span v-if="s.product" class="seller-tag mute">{{ sellerLabelOf(s.product) }}</span>
            <span v-if="s.sessionCode" class="seller-tag mute">{{ s.sessionCode }}</span>
          </div>
          <footer>
            <strong>¥{{ Number(s.price).toLocaleString() }}</strong>
            <span v-if="s.product">原价 ¥{{ Number(s.product.minPrice).toLocaleString() }}</span>
          </footer>
          <button type="button" class="seckill-buy" :disabled="buying" @click="buy(s)">
            {{ guest ? '登录后秒杀' : '立即秒杀' }}
          </button>
        </article>
        <div v-if="!sessions.length" class="empty-catalog"><p>当前暂无启用中的秒杀场次</p></div>
      </div>
    </template>
  </section>
</template>
