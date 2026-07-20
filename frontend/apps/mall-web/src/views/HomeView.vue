<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { PhCaretRight as CaretRight, PhCpu as Cpu, PhDeviceMobile as DeviceMobile, PhHeadphones as Headphones, PhKeyboard as Keyboard, PhLaptop as Laptop, PhMonitor as Monitor, PhWatch as Watch } from '@phosphor-icons/vue'
import type { ProductSummary } from '@geek-mall/api-types'
import heroImage from '../assets/hero-geek-alpine.png'
import { mallApi } from '../api'
import { sellerLabelOf } from '../catalog'

type Floor = { code: string; title: string; subtitle: string; items: ProductSummary[]; kind?: string }
type SeckillItem = { id: number; sessionCode: string; skuId: number; price: number; stockQty: number; startAt: string; endAt: string }
type SeckillCard = SeckillItem & { product?: ProductSummary; coverUrl: string; title: string; subtitle: string }
type HeroSlide = {
  id?: number
  title: string
  subtitle: string
  cta?: string
  coverUrl: string
}
type HomeState = {
  source: 'cms' | 'showcase'
  versionNo?: number
  heroes: HeroSlide[]
  floors: Floor[]
  seckill?: { title: string; subtitle: string; sessions: SeckillCard[] }
}

/** Demo SKU → SPU map for seckill product cards (public catalog has no SKU lookup). */
const SECKILL_SKU_SPU: Record<number, number> = {
  11001: 10001,
  11002: 10001,
  13001: 10003,
  14001: 10004,
  15001: 10005,
  18001: 10008,
  21001: 10011
}

const channelCards = [
  { to: '/shop/3000', title: 'GEEK 自营', subtitle: '平台自营 · 多品牌精选', cover: '/covers/geek-spu-10003.jpg' },
  { to: '/search?keyword=GEEK%20SELECT', title: 'GEEK SELECT', subtitle: '自有配件品牌', cover: '/covers/geek-spu-10005.jpg' },
  { to: '/shop/3001', title: '品牌旗舰店', subtitle: 'Nova / Orion 官方直营', cover: '/covers/geek-spu-10008.jpg' },
  { to: '/shop/3002', title: '第三方优选', subtitle: '授权专营与外设', cover: '/covers/geek-spu-10002.jpg' }
]

function coverOf(spuId: number, fallback?: string) {
  if (spuId > 0) return `/covers/geek-spu-${spuId}.jpg`
  return fallback || '/covers/geek-spu-10001.jpg'
}

const categoryRail = [
  { label: '旗舰手机', icon: DeviceMobile, to: '/category/200' },
  { label: '轻薄本', icon: Laptop, to: '/category/110' },
  { label: '显示器', icon: Monitor, to: '/category/300' },
  { label: '耳机音频', icon: Headphones, to: '/category/510' },
  { label: '键盘鼠标', icon: Keyboard, to: '/category/420' },
  { label: '配件中心', icon: Cpu, to: '/category/400' },
  { label: '智能穿戴', icon: Watch, to: '/category/600' }
]

const home = ref<HomeState>({ source: 'showcase', heroes: [], floors: [] })
const loading = ref(true)
const error = ref('')
const heroIndex = ref(0)
let heroTimer: ReturnType<typeof setInterval> | null = null

const currentHero = computed(() => home.value.heroes[heroIndex.value] || {
  title: '多品牌数码精选',
  subtitle: '平台自营、官方旗舰与第三方店铺，统一分类与正品保障',
  coverUrl: heroImage,
  cta: '立即选购'
})

function toSlide(product: ProductSummary, overrides: Partial<HeroSlide> = {}): HeroSlide {
  return {
    id: product.id,
    title: overrides.title || product.title,
    subtitle: overrides.subtitle || product.subtitle,
    cta: overrides.cta || '立即选购',
    coverUrl: overrides.coverUrl || product.coverUrl || heroImage
  }
}

function uniqueSlides(slides: HeroSlide[]) {
  const seen = new Set<string>()
  return slides.filter(slide => {
    const key = `${slide.id || 0}:${slide.coverUrl}:${slide.title}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function enrichSlides(primary: HeroSlide[], floors: Floor[]) {
  const slides = [...primary]
  for (const floor of floors) {
    for (const item of floor.items || []) {
      if (slides.length >= 5) break
      slides.push(toSlide(item, { title: item.title, subtitle: floor.title }))
    }
    if (slides.length >= 5) break
  }
  if (!slides.length) {
    slides.push({
      title: '多品牌数码精选',
      subtitle: '平台自营、官方旗舰与第三方店铺，统一分类与正品保障',
      coverUrl: heroImage,
      cta: '立即选购'
    })
  }
  return uniqueSlides(slides).slice(0, 5)
}

async function loadBatch(ids: number[]) {
  if (!ids.length) return [] as ProductSummary[]
  return mallApi.request<ProductSummary[]>(`/api/v1/products/batch?ids=${ids.join(',')}`)
}

async function enrichSeckillSessions(sessions: SeckillItem[]): Promise<SeckillCard[]> {
  const rows = (sessions || []).slice(0, 3)
  const spuIds = [...new Set(rows.map(s => SECKILL_SKU_SPU[s.skuId]).filter(Boolean))]
  const products = await loadBatch(spuIds)
  const bySpu = new Map(products.map(p => [p.id, p]))
  return rows.map(s => {
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

async function loadFromCms(): Promise<HomeState> {
  const page = await mallApi.request<{ versionNo: number; blocks: any[] }>('/api/v1/cms/pages/HOME')
  const floors: Floor[] = []
  const heroes: HeroSlide[] = []
  let seckill: HomeState['seckill']
  for (const block of page.blocks || []) {
    if (block.type === 'HERO') {
      const ids = [
        ...(block.spuId ? [Number(block.spuId)] : []),
        ...((block.spuIds || []).map(Number))
      ].filter(Boolean)
      const items = await loadBatch([...new Set(ids)])
      if (items.length) {
        heroes.push(...items.map((item, index) => toSlide(item, {
          title: index === 0 ? (block.title || item.title) : item.title,
          subtitle: index === 0 ? (block.subtitle || item.subtitle) : item.subtitle,
          cta: block.cta,
          coverUrl: coverOf(item.id, index === 0 && block.imageUrl ? block.imageUrl : item.coverUrl)
        })))
      } else {
        heroes.push({
          id: Number(block.spuId || 0) || undefined,
          title: block.title || 'GEEK MALL',
          subtitle: block.subtitle || '',
          coverUrl: block.imageUrl || heroImage,
          cta: block.cta
        })
      }
    } else if (block.type === 'PRODUCT_FLOOR') {
      const code = block.code || `floor_${floors.length}`
      if (code === 'self_operated') continue
      const ids = (block.spuIds || []).map(Number).filter(Boolean)
      const items = (await loadBatch(ids)).map(item => ({
        ...item,
        coverUrl: coverOf(item.id, item.coverUrl)
      }))
      if (!items.length) continue
      floors.push({
        code,
        title: block.title || '精选楼层',
        subtitle: block.subtitle || '',
        items
      })
    } else if (block.type === 'SECKILL') {
      const sessions = await mallApi.request<SeckillItem[]>('/api/v1/marketing/seckill/sessions')
      seckill = {
        title: block.title || '限时秒杀',
        subtitle: block.subtitle || '场次与库存实时同步',
        sessions: await enrichSeckillSessions(sessions || [])
      }
    }
  }
  return { source: 'cms', versionNo: page.versionNo, heroes: enrichSlides(heroes, floors), floors, seckill }
}

async function loadShowcaseFallback(): Promise<HomeState> {
  const data = await mallApi.request<{ floors: Floor[]; hero?: ProductSummary }>('/api/v1/products/showcase')
  const floors = data.floors || []
  const heroes = data.hero ? [toSlide(data.hero)] : []
  return { source: 'showcase', heroes: enrichSlides(heroes, floors), floors }
}

function stopHeroTimer() {
  if (heroTimer) {
    clearInterval(heroTimer)
    heroTimer = null
  }
}

function startHeroTimer() {
  stopHeroTimer()
  if (home.value.heroes.length <= 1) return
  heroTimer = setInterval(() => {
    heroIndex.value = (heroIndex.value + 1) % home.value.heroes.length
  }, 3000)
}

function goHero(index: number) {
  heroIndex.value = index
  startHeroTimer()
}

watch(() => home.value.heroes.length, () => {
  heroIndex.value = 0
  startHeroTimer()
})

onMounted(async () => {
  try {
    home.value = await loadFromCms()
  } catch {
    try {
      home.value = await loadShowcaseFallback()
    } catch (e) {
      error.value = e instanceof Error ? e.message : '首页加载失败'
    }
  } finally {
    loading.value = false
    startHeroTimer()
  }
})

onUnmounted(stopHeroTimer)
</script>

<template>
  <section
    class="hero"
    :style="{ backgroundImage: `url(${currentHero.coverUrl})` }"
    aria-labelledby="hero-title"
    @mouseenter="stopHeroTimer"
    @mouseleave="startHeroTimer"
  >
    <div class="hero-copy">
      <p>GEEK MALL · {{ home.source === 'cms' ? `CMS v${home.versionNo}` : 'SHOWCASE' }}</p>
      <h1 id="hero-title">{{ currentHero.title }}</h1>
      <h2>{{ currentHero.subtitle }}</h2>
      <RouterLink class="pill-button" :to="currentHero.id ? `/product/${currentHero.id}` : '/search'">
        {{ currentHero.cta || '立即选购' }}
      </RouterLink>
    </div>
    <div class="hero-pagination" aria-label="轮播图位置">
      <button
        v-for="(slide, index) in home.heroes"
        :key="`${slide.id || slide.coverUrl}-${index}`"
        type="button"
        :class="{ active: index === heroIndex }"
        :aria-label="`第 ${index + 1} 张`"
        :aria-current="index === heroIndex ? 'true' : undefined"
        @click="goHero(index)"
      />
    </div>
  </section>

  <nav class="category-rail" aria-label="热门品类">
    <RouterLink v-for="item in categoryRail" :key="item.to" :to="item.to">
      <component :is="item.icon" :size="36" weight="thin" /><span>{{ item.label }}</span>
    </RouterLink>
  </nav>

  <div v-if="loading" class="detail-state">正在载入首页精选…</div>
  <div v-else-if="error" class="detail-state error" role="alert">{{ error }}</div>

  <template v-else>
    <section v-if="home.seckill" class="deals-section home-floor" aria-labelledby="floor-seckill">
      <div class="section-heading">
        <div>
          <p>SECKILL</p>
          <h2 id="floor-seckill">{{ home.seckill.title }}</h2>
          <span class="floor-sub">{{ home.seckill.subtitle }}</span>
        </div>
        <RouterLink class="floor-more" to="/seckill">进入秒杀 <CaretRight :size="16" /></RouterLink>
      </div>
      <div class="product-grid home-grid">
        <RouterLink
          v-for="s in home.seckill.sessions"
          :key="`${s.id}-${s.skuId}`"
          class="product-tile"
          :to="s.product?.id ? `/product/${s.product.id}` : '/seckill'"
        >
          <div><img :src="s.coverUrl" :alt="s.title" loading="lazy" /></div>
          <small>{{ s.product?.brandName || '限时秒杀' }} · 库存 {{ s.stockQty }}</small>
          <h2>{{ s.title }}</h2>
          <p>{{ s.subtitle }}</p>
          <div class="seller-tags">
            <span class="seller-tag">限时秒杀</span>
            <span v-if="s.product" class="seller-tag mute">{{ sellerLabelOf(s.product) }}</span>
          </div>
          <footer>
            <strong>¥{{ Number(s.price).toLocaleString() }}</strong>
            <span v-if="s.product">原价 ¥{{ Number(s.product.minPrice).toLocaleString() }}</span>
          </footer>
        </RouterLink>
        <div v-if="!home.seckill.sessions.length" class="empty-catalog"><p>当前暂无启用中的秒杀场次</p></div>
      </div>
    </section>

    <section v-for="floor in home.floors" :key="floor.code" class="deals-section home-floor" :aria-labelledby="`floor-${floor.code}`">
      <div class="section-heading">
        <div>
          <p>{{ floor.code.replaceAll('_', ' ').toUpperCase() }}</p>
          <h2 :id="`floor-${floor.code}`">{{ floor.title }}</h2>
          <span class="floor-sub">{{ floor.subtitle }}</span>
        </div>
        <RouterLink class="floor-more" :to="`/search?keyword=${encodeURIComponent(floor.title)}`">查看更多 <CaretRight :size="16" /></RouterLink>
      </div>
      <div class="product-grid home-grid">
        <RouterLink v-for="product in floor.items" :key="product.id" class="product-tile" :to="`/product/${product.id}`">
          <div><img :src="product.coverUrl" :alt="product.title" loading="lazy" /></div>
          <small>{{ product.brandName }} · {{ product.shopName }}</small>
          <h2>{{ product.title }}</h2>
          <p>{{ product.subtitle }}</p>
          <div class="seller-tags">
            <span class="seller-tag">{{ sellerLabelOf(product) }}</span>
            <span v-if="product.ownBrand" class="seller-tag own">自有品牌</span>
            <span v-if="product.deliveryHint" class="seller-tag mute">{{ product.deliveryHint }}</span>
          </div>
          <footer>
            <strong>¥{{ Number(product.minPrice).toLocaleString() }}</strong>
            <span>{{ product.rating }} 分</span>
          </footer>
        </RouterLink>
      </div>
    </section>
  </template>

  <section class="ai-banner">
    <div>
      <p>GEEK AI SHOPPING</p>
      <h2>参数很多，答案可以很简单</h2>
      <span>告诉我预算、用途和偏好，得到带来自多品牌、多店铺的选购建议。</span>
      <RouterLink to="/ai-shopping">开始 AI 导购 <CaretRight :size="18" /></RouterLink>
    </div>
    <img :src="currentHero.coverUrl" alt="AI 导购推荐场景" loading="lazy" width="720" height="720" />
  </section>

  <section class="floor-grid" aria-labelledby="floor-title">
    <div class="section-heading"><div><p>CHANNELS</p><h2 id="floor-title">按销售主体逛商城</h2></div></div>
    <RouterLink
      v-for="card in channelCards"
      :key="card.to"
      class="channel-card"
      :to="card.to"
      :style="{ backgroundImage: `url(${card.cover})` }"
    >
      <strong>{{ card.title }}</strong>
      <span>{{ card.subtitle }}</span>
    </RouterLink>
  </section>
</template>
