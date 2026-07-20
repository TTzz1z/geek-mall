<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import {
  PhGridFour as GridFour, PhHouse as House, PhList as List, PhMagnifyingGlass as MagnifyingGlass,
  PhShoppingBag as ShoppingBag, PhUserCircle as UserCircle, PhX as X, PhCaretRight as CaretRight,
  PhSparkle as Sparkle, PhDeviceMobile as DeviceMobile, PhLaptop as Laptop, PhHeadphones as Headphones,
  PhWatch as Watch, PhBatteryCharging as Battery, PhMonitor as Monitor,
  PhKeyboard as Keyboard, PhSpeakerHigh as Speaker, PhCamera as Camera, PhHardDrives as HardDrives,
  PhPlug as Plug, PhArrowRight as ArrowRight
} from '@phosphor-icons/vue'
import productImage from '../assets/product-ultrabook.png'
import { useCartSummaryStore } from '../stores/cart-summary'
import { mallApi } from '../api'
import { clearMallAuth, userApi } from '../api/user'
import { authPath, isLoggedIn } from '../auth'
import PageBackButton from './PageBackButton.vue'

const COVER = (id: number) => `/covers/geek-spu-${id}.jpg`

type MegaTile = {
  name: string
  tip: string
  to: string
  cover?: string
  featured?: boolean
  more?: boolean
}
type MegaSub = {
  id: string
  label: string
  icon: unknown
  to: string
  badge?: string
  group?: string
  tiles: MegaTile[]
}
type MegaCatalog = {
  id: string
  label: string
  accent: string
  categoryId: number
  /** 旗舰类：右侧用左侧大卡 + 小格；其余等高密铺 */
  hero: boolean
  allTo: string
  subs: MegaSub[]
}

const router = useRouter()
const query = ref('')
const activeMenu = ref<string | null>(null)
const activeSub = ref<string | null>(null)
const drawerOpen = ref(false)
const accountOpen = ref(false)
const loggedIn = ref(isLoggedIn())
const cartSummary = useCartSummaryStore()
const platformNav = ref<Array<{ id: number; name: string }>>([])

function syncAuth() {
  loggedIn.value = isLoggedIn()
}

function closeAccountMenu() {
  accountOpen.value = false
}

function onDocumentPointerDown(event: PointerEvent) {
  const target = event.target as Node | null
  const root = document.querySelector('.account-menu')
  if (root && target && !root.contains(target)) closeAccountMenu()
}

function toggleAccountMenu() {
  if (!loggedIn.value) {
    router.push(authPath('login'))
    return
  }
  accountOpen.value = !accountOpen.value
  activeMenu.value = null
  activeSub.value = null
}

async function logout() {
  closeAccountMenu()
  drawerOpen.value = false
  const refresh = localStorage.getItem('geek.refreshToken')
  try {
    if (refresh) await userApi.logout(refresh)
  } catch { /* ignore network errors on logout */ }
  clearMallAuth()
  loggedIn.value = false
  await router.push('/auth/login')
}

onMounted(async () => {
  syncAuth()
  document.addEventListener('pointerdown', onDocumentPointerDown)
  cartSummary.refresh()
  try {
    const tree = await mallApi.request<Array<{ id: number; name: string }>>('/api/v1/products/categories/tree')
    platformNav.value = (tree || []).map(n => ({ id: n.id, name: n.name }))
  } catch {
    platformNav.value = []
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
})

watch(() => router.currentRoute.value.fullPath, () => {
  syncAuth()
  closeAccountMenu()
})

const catalogs: MegaCatalog[] = [
  {
    id: 'phones',
    label: '手机数码',
    accent: '#ff6a2b',
    categoryId: 200,
    hero: true,
    allTo: '/category/200',
    subs: [
      {
        id: 'flagship',
        label: '影像旗舰',
        icon: DeviceMobile,
        group: '创作影像',
        to: '/category/220',
        tiles: [
          { name: 'Nova Phone Ultra', tip: '官方旗舰 · 一英寸主摄', to: '/product/10008', cover: COVER(10008), featured: true },
          { name: 'Nova Phone Air', tip: '轻旗舰影像备选', to: '/product/10010', cover: COVER(10010) },
          { name: 'Orion Fold Z', tip: '折叠大屏影像', to: '/product/10007', cover: COVER(10007) },
          { name: '查看全部', tip: '影像旗舰', to: '/category/220', more: true }
        ]
      },
      {
        id: 'fold',
        label: '折叠屏',
        icon: DeviceMobile,
        group: '创作影像',
        to: '/category/210',
        tiles: [
          { name: 'Orion Fold Z', tip: '官方旗舰店 · 内折大屏', to: '/product/10007', cover: COVER(10007), featured: true },
          { name: 'Nova Phone Ultra', tip: '直板影像旗舰', to: '/product/10008', cover: COVER(10008) },
          { name: 'Nova Phone Air', tip: '轻薄直板备选', to: '/product/10010', cover: COVER(10010) },
          { name: '查看全部', tip: '折叠系列', to: '/category/210', more: true }
        ]
      },
      {
        id: 'mid',
        label: '轻旗舰',
        icon: Camera,
        group: '日常出行',
        to: '/category/220',
        tiles: [
          { name: 'Nova Phone Air', tip: 'GEEK 自营精选', to: '/product/10010', cover: COVER(10010), featured: true },
          { name: 'Nova Phone Ultra', tip: '进阶影像体验', to: '/product/10008', cover: COVER(10008) },
          { name: 'NovaBuds Pro', tip: '机侧搭配耳机', to: '/product/10011', cover: COVER(10011) },
          { name: '查看全部', tip: '轻旗舰', to: '/category/220', more: true }
        ]
      },
      {
        id: 'accessory',
        label: '手机配件',
        icon: Plug,
        group: '日常出行',
        badge: '新品',
        to: '/category/410',
        tiles: [
          { name: 'GEEK SELECT 100W', tip: '氮化镓三口快充', to: '/product/10004', cover: COVER(10004), featured: true },
          { name: 'NovaBuds Pro', tip: '无线耳机', to: '/product/10011', cover: COVER(10011) },
          { name: 'Nexus Band 7', tip: '腕上互联', to: '/product/10012', cover: COVER(10012) },
          { name: '查看全部', tip: '手机配件', to: '/category/410', more: true }
        ]
      }
    ]
  },
  {
    id: 'computers',
    label: '电脑办公',
    accent: '#1f6feb',
    categoryId: 100,
    hero: true,
    allTo: '/category/100',
    subs: [
      {
        id: 'ultrabook',
        label: '轻薄本',
        icon: Laptop,
        group: '移动办公',
        to: '/category/110',
        tiles: [
          { name: 'NovaBook Pro 14', tip: '官方旗舰店', to: '/product/10001', cover: COVER(10001), featured: true },
          { name: 'OrionBook Pro 16', tip: 'GEEK 自营采购', to: '/product/10003', cover: COVER(10003) },
          { name: 'PixelCore 32 MiniLED', tip: '外接创作屏', to: '/product/10002', cover: COVER(10002) },
          { name: '查看全部', tip: '轻薄本', to: '/category/110', more: true }
        ]
      },
      {
        id: 'creator',
        label: '创作本',
        icon: Laptop,
        group: '移动办公',
        to: '/category/110',
        tiles: [
          { name: 'OrionBook Pro 16', tip: '高性能创作本', to: '/product/10003', cover: COVER(10003), featured: true },
          { name: 'NovaBook Pro 14', tip: '轻量移动创作', to: '/product/10001', cover: COVER(10001) },
          { name: 'PixelCore 32 MiniLED', tip: '创作级显示', to: '/product/10002', cover: COVER(10002) },
          { name: '查看全部', tip: '创作本', to: '/category/110', more: true }
        ]
      },
      {
        id: 'monitor',
        label: '显示器',
        icon: Monitor,
        group: '桌面工作站',
        to: '/category/300',
        tiles: [
          { name: 'PixelCore 32 MiniLED', tip: '第三方专营 · 4K', to: '/product/10002', cover: COVER(10002), featured: true },
          { name: 'OrionBook Pro 16', tip: '本机高色域屏', to: '/product/10003', cover: COVER(10003) },
          { name: 'GEEK SELECT 扩展坞', tip: '一线接 4K 屏', to: '/product/10006', cover: COVER(10006) },
          { name: '查看全部', tip: '显示器', to: '/category/300', more: true }
        ]
      },
      {
        id: 'desk',
        label: '键鼠外设',
        icon: Keyboard,
        group: '桌面工作站',
        to: '/category/420',
        tiles: [
          { name: 'GEEK SELECT 键盘', tip: '自有品牌', to: '/product/10005', cover: COVER(10005), featured: true },
          { name: 'Aurora 键鼠套装', tip: '第三方优选', to: '/product/10009', cover: COVER(10009) },
          { name: '8 合 1 扩展坞', tip: '桌面枢纽', to: '/product/10006', cover: COVER(10006) },
          { name: '查看全部', tip: '键鼠外设', to: '/category/420', more: true }
        ]
      }
    ]
  },
  {
    id: 'audio',
    label: '影音娱乐',
    accent: '#7c3aed',
    categoryId: 500,
    hero: false,
    allTo: '/category/500',
    subs: [
      {
        id: 'anc',
        label: '降噪耳机',
        icon: Headphones,
        group: '耳机',
        to: '/category/510',
        tiles: [
          { name: 'NovaBuds Pro', tip: '官方旗舰 · 主动降噪', to: '/product/10011', cover: COVER(10011) },
          { name: 'GEEK SELECT Buds Lite', tip: '自有品牌 · 轻量降噪', to: '/product/10013', cover: COVER(10013), featured: true },
          { name: '查看全部', tip: '降噪耳机', to: '/category/510', more: true }
        ]
      },
      {
        id: 'open',
        label: '开放式耳机',
        icon: Headphones,
        group: '耳机',
        to: '/category/510',
        tiles: [
          { name: 'NovaBuds Pro', tip: '空间音频 · 通勤听感', to: '/product/10011', cover: COVER(10011) },
          { name: 'GEEK SELECT Buds Lite', tip: '轻量佩戴 · 长续航', to: '/product/10013', cover: COVER(10013) },
          { name: '查看全部', tip: '开放式耳机', to: '/category/510', more: true }
        ]
      },
      {
        id: 'speaker',
        label: '智能音箱',
        icon: Speaker,
        group: '音箱与影音',
        to: '/category/500',
        tiles: [
          { name: 'NovaBuds Pro', tip: '便携影音入门', to: '/product/10011', cover: COVER(10011) },
          { name: 'PixelCore 32 MiniLED', tip: '桌面影音大屏', to: '/product/10002', cover: COVER(10002) },
          { name: '查看全部', tip: '智能音箱', to: '/category/500', more: true }
        ]
      },
      {
        id: 'soundbar',
        label: '回音壁',
        icon: Speaker,
        group: '音箱与影音',
        to: '/category/500',
        tiles: [
          { name: 'PixelCore 32 MiniLED', tip: '家庭影院画面', to: '/product/10002', cover: COVER(10002) },
          { name: 'NovaBuds Pro', tip: '个人听感备选', to: '/product/10011', cover: COVER(10011) },
          { name: '查看全部', tip: '回音壁', to: '/category/500', more: true }
        ]
      }
    ]
  },
  {
    id: 'wearables',
    label: '智能穿戴',
    accent: '#0f766e',
    categoryId: 600,
    hero: false,
    allTo: '/category/600',
    subs: [
      {
        id: 'watch',
        label: '智能手表',
        icon: Watch,
        group: '腕上',
        to: '/category/600',
        tiles: [
          { name: 'Nexus Band 7', tip: 'GEEK 自营精选', to: '/product/10012', cover: COVER(10012) },
          { name: 'NovaBuds Pro', tip: '腕耳联动', to: '/product/10011', cover: COVER(10011) },
          { name: '查看全部', tip: '智能手表', to: '/category/600', more: true }
        ]
      },
      {
        id: 'band',
        label: '运动手环',
        icon: Watch,
        group: '腕上',
        to: '/category/600',
        tiles: [
          { name: 'Nexus Band 7', tip: '运动健康追踪', to: '/product/10012', cover: COVER(10012) },
          { name: 'NovaBuds Pro', tip: '跑步听感', to: '/product/10011', cover: COVER(10011) },
          { name: '查看全部', tip: '运动手环', to: '/category/600', more: true }
        ]
      },
      {
        id: 'glasses',
        label: '智能眼镜',
        icon: Camera,
        group: '新形态',
        badge: '敬请期待',
        to: '/category/600',
        tiles: [
          { name: 'Nexus Band 7', tip: '先看腕上穿戴', to: '/product/10012', cover: COVER(10012) },
          { name: 'NovaBuds Pro', tip: '开放听感体验', to: '/product/10011', cover: COVER(10011) },
          { name: '查看全部', tip: '智能穿戴', to: '/category/600', more: true }
        ]
      }
    ]
  },
  {
    id: 'components',
    label: '配件中心',
    accent: '#b45309',
    categoryId: 400,
    hero: false,
    allTo: '/category/400',
    subs: [
      {
        id: 'charge',
        label: '充电线材',
        icon: Battery,
        group: '供电',
        to: '/category/410',
        tiles: [
          { name: '100W 充电器', tip: '自有品牌氮化镓', to: '/product/10004', cover: COVER(10004) },
          { name: '8 合 1 扩展坞', tip: 'PD 100W 供电', to: '/product/10006', cover: COVER(10006) },
          { name: '查看全部', tip: '充电线材', to: '/category/410', more: true }
        ]
      },
      {
        id: 'kb',
        label: '键鼠外设',
        icon: Keyboard,
        group: '桌面',
        to: '/category/420',
        tiles: [
          { name: '机械键盘', tip: '自有品牌', to: '/product/10005', cover: COVER(10005) },
          { name: 'Aurora 键鼠', tip: '第三方优选', to: '/product/10009', cover: COVER(10009) },
          { name: '查看全部', tip: '键鼠外设', to: '/category/420', more: true }
        ]
      },
      {
        id: 'dock',
        label: '扩展坞支架',
        icon: Plug,
        group: '桌面',
        to: '/category/430',
        tiles: [
          { name: '8 合 1 扩展坞', tip: 'HDMI 4K / PD', to: '/product/10006', cover: COVER(10006) },
          { name: '100W 充电器', tip: '桌面供电配套', to: '/product/10004', cover: COVER(10004) },
          { name: '查看全部', tip: '扩展坞', to: '/category/430', more: true }
        ]
      },
      {
        id: 'ssd',
        label: '存储周边',
        icon: HardDrives,
        group: '扩展',
        to: '/category/400',
        tiles: [
          { name: '8 合 1 扩展坞', tip: '含存储扩展接口', to: '/product/10006', cover: COVER(10006) },
          { name: 'GEEK SELECT 键盘', tip: '桌面效率套装', to: '/product/10005', cover: COVER(10005) },
          { name: '查看全部', tip: '配件中心', to: '/category/400', more: true }
        ]
      }
    ]
  },
  {
    id: 'charging',
    label: '充电与连接',
    accent: '#c2410c',
    categoryId: 410,
    hero: false,
    allTo: '/category/410',
    subs: [
      {
        id: 'gan',
        label: '氮化镓充电',
        icon: Battery,
        group: '供电',
        to: '/category/410',
        tiles: [
          { name: 'GEEK SELECT 100W', tip: '三口快充旅行装', to: '/product/10004', cover: COVER(10004) },
          { name: '8 合 1 扩展坞', tip: 'PD 100W 供电口', to: '/product/10006', cover: COVER(10006) },
          { name: '查看全部', tip: '氮化镓充电', to: '/category/410', more: true }
        ]
      },
      {
        id: 'cable',
        label: '线材集线',
        icon: Plug,
        group: '连接',
        to: '/category/410',
        tiles: [
          { name: 'GEEK SELECT 100W', tip: '快充线材配套', to: '/product/10004', cover: COVER(10004) },
          { name: '8 合 1 扩展坞', tip: '一线多口扩展', to: '/product/10006', cover: COVER(10006) },
          { name: '查看全部', tip: '线材集线', to: '/category/410', more: true }
        ]
      },
      {
        id: 'hub',
        label: '扩展坞',
        icon: Plug,
        group: '连接',
        badge: '热卖',
        to: '/category/430',
        tiles: [
          { name: 'GEEK SELECT 扩展坞', tip: 'HDMI 4K / PD 100W', to: '/product/10006', cover: COVER(10006) },
          { name: 'GEEK SELECT 100W', tip: '供电伴侣', to: '/product/10004', cover: COVER(10004) },
          { name: '查看全部', tip: '扩展坞', to: '/category/430', more: true }
        ]
      }
    ]
  }
]

const current = computed(() => catalogs.find(c => c.id === activeMenu.value) ?? null)
const currentSubs = computed(() => current.value?.subs ?? [])
const currentPanel = computed(() => {
  const cat = current.value
  if (!cat) return null
  return cat.subs.find(s => s.id === activeSub.value) ?? cat.subs[0] ?? null
})
const panelTiles = computed(() => currentPanel.value?.tiles ?? [])
const heroTile = computed(() => {
  return panelTiles.value.find(t => t.featured && !t.more) ?? null
})
const gridTiles = computed(() => {
  const hero = heroTile.value
  if (!hero) return panelTiles.value
  return panelTiles.value.filter(t => t !== hero)
})
const useHeroLayout = computed(() => !!heroTile.value)

function openMenu(id: string) {
  activeMenu.value = id
  activeSub.value = catalogs.find(c => c.id === id)?.subs[0]?.id ?? null
}

function tileCover(tile: MegaTile) {
  return tile.cover || productImage
}

function search() {
  router.push({ path: '/search', query: query.value.trim() ? { keyword: query.value.trim() } : {} })
}
</script>

<template>
  <header class="store-header" @mouseleave="activeMenu = null; activeSub = null">
    <div class="utility-bar">
      <nav aria-label="服务导航">
        <RouterLink to="/service">极客指南</RouterLink>
        <RouterLink to="/compare">选购对比</RouterLink>
        <RouterLink to="/topic/new">新品日历</RouterLink>
      </nav>
      <span>中国大陆（简体中文 / ¥ CNY）</span>
    </div>
    <div class="main-nav">
      <PageBackButton />
      <button class="mobile-menu-button" type="button" :aria-expanded="drawerOpen" aria-controls="mobile-drawer" @click="drawerOpen = true">
        <List :size="24" /><span class="sr-only">打开菜单</span>
      </button>
      <RouterLink class="wordmark" to="/" aria-label="极客商城首页"><b>GEEK</b><span>MALL</span></RouterLink>
      <nav class="desktop-nav" aria-label="商品导航">
        <RouterLink
          v-for="item in catalogs"
          :key="item.id"
          :to="`/category/${item.categoryId}`"
          :class="{ 'is-mega-active': activeMenu === item.id }"
          @mouseenter="openMenu(item.id)"
          @focus="openMenu(item.id)"
        >{{ item.label }}</RouterLink>
        <RouterLink class="accent-link" to="/ai-shopping"><Sparkle :size="17" />AI 导购</RouterLink>
        <RouterLink to="/seckill">限时特惠</RouterLink>
      </nav>
      <div class="nav-tools">
        <form class="header-search" role="search" @submit.prevent="search">
          <label class="sr-only" for="site-search">搜索商品</label>
          <input id="site-search" v-model="query" placeholder="搜索数码好物" />
          <button type="submit" aria-label="搜索"><MagnifyingGlass :size="21" /></button>
        </form>
        <RouterLink class="cart-tool" to="/cart" :aria-label="`购物车，${cartSummary.count} 件商品`">
          <ShoppingBag :size="22" /><span v-if="cartSummary.count">{{ cartSummary.count }}</span>
        </RouterLink>
        <div class="account-menu">
          <button
            type="button"
            class="account-menu-trigger"
            :aria-expanded="accountOpen"
            :aria-label="loggedIn ? '账户菜单' : '登录或注册'"
            @click="toggleAccountMenu"
          >
            <UserCircle :size="23" />
          </button>
          <div v-if="loggedIn && accountOpen" class="account-menu-panel" role="menu">
            <RouterLink role="menuitem" to="/user" @click="closeAccountMenu">个人中心</RouterLink>
            <RouterLink role="menuitem" to="/user/orders" @click="closeAccountMenu">我的订单</RouterLink>
            <RouterLink role="menuitem" to="/user/addresses" @click="closeAccountMenu">收货地址</RouterLink>
            <button type="button" role="menuitem" class="account-menu-logout" @click="logout">退出登录</button>
          </div>
        </div>
      </div>
    </div>

    <Transition name="mega">
      <section
        v-if="current && currentPanel"
        class="mega-menu"
        :style="{ '--mega-accent': current.accent }"
        aria-label="分类展开菜单"
        @mouseenter="activeMenu = current.id"
      >
        <aside class="mega-side">
          <template v-for="(sub, idx) in currentSubs" :key="sub.id">
            <p v-if="idx === 0 || currentSubs[idx - 1]?.group !== sub.group" class="mega-side-group">{{ sub.group }}</p>
            <button
              type="button"
              class="mega-sub"
              :class="{ active: activeSub === sub.id }"
              @mouseenter="activeSub = sub.id"
              @focus="activeSub = sub.id"
              @click="router.push(sub.to)"
            >
              <component :is="sub.icon" :size="22" weight="thin" />
              <span>{{ sub.label }}</span>
              <em v-if="sub.badge">{{ sub.badge }}</em>
            </button>
          </template>
          <RouterLink class="all-link" :to="current.allTo">查看全部{{ current.label }} <CaretRight :size="16" /></RouterLink>
        </aside>

        <div class="mega-main">
          <article class="mega-distill">
            <header>
              <h2>{{ currentPanel.label }}</h2>
              <RouterLink :to="currentPanel.to">查看全部 <CaretRight :size="15" /></RouterLink>
            </header>

            <div class="distill-body" :class="{ 'is-hero': useHeroLayout }">
              <RouterLink
                v-if="heroTile"
                :to="heroTile.to"
                class="distill-tile featured"
              >
                <div class="tile-visual">
                  <img :src="tileCover(heroTile)" :alt="heroTile.name" />
                </div>
                <div class="tile-meta">
                  <b>{{ heroTile.name }}</b>
                  <small>{{ heroTile.tip }}</small>
                </div>
              </RouterLink>

              <RouterLink
                v-for="tile in gridTiles"
                :key="tile.name + tile.to"
                :to="tile.to"
                class="distill-tile"
                :class="{ more: tile.more }"
              >
                <template v-if="tile.more">
                  <span class="more-circle"><ArrowRight :size="22" /></span>
                  <b>查看全部</b>
                </template>
                <template v-else>
                  <div class="tile-visual">
                    <img :src="tileCover(tile)" :alt="tile.name" />
                  </div>
                  <div class="tile-meta">
                    <b>{{ tile.name }}</b>
                    <small>{{ tile.tip }}</small>
                  </div>
                </template>
              </RouterLink>
            </div>
          </article>
        </div>

        <button type="button" class="mega-close" aria-label="关闭分类菜单" @click="activeMenu = null; activeSub = null">
          <X :size="24" />
        </button>
      </section>
    </Transition>

    <div v-if="activeMenu" class="mega-scrim" aria-hidden="true" />

    <div v-if="drawerOpen" class="drawer-backdrop" @click.self="drawerOpen = false">
      <aside id="mobile-drawer" class="mobile-drawer" aria-label="移动导航">
        <div><strong>全部品类</strong><button type="button" aria-label="关闭菜单" @click="drawerOpen = false"><X :size="24" /></button></div>
        <RouterLink v-for="item in catalogs" :key="item.id" :to="`/category/${item.categoryId}`" @click="drawerOpen = false">
          {{ item.label }}<CaretRight :size="18" />
        </RouterLink>
        <RouterLink to="/ai-shopping" @click="drawerOpen = false">AI 导购<CaretRight :size="18" /></RouterLink>
        <RouterLink to="/seckill" @click="drawerOpen = false">限时特惠<CaretRight :size="18" /></RouterLink>
        <template v-if="loggedIn">
          <RouterLink to="/user" @click="drawerOpen = false">个人中心<CaretRight :size="18" /></RouterLink>
          <button type="button" class="drawer-logout" @click="logout">退出登录</button>
        </template>
        <RouterLink v-else :to="authPath('login')" @click="drawerOpen = false">登录 / 注册<CaretRight :size="18" /></RouterLink>
      </aside>
    </div>
  </header>

  <nav class="mobile-bottom-nav" aria-label="移动底部导航">
    <RouterLink to="/"><House :size="20" /><span>首页</span></RouterLink>
    <RouterLink to="/category/100"><GridFour :size="20" /><span>分类</span></RouterLink>
    <RouterLink to="/ai-shopping" class="mobile-ai"><Sparkle :size="20" /><span>AI 导购</span></RouterLink>
    <RouterLink to="/cart"><ShoppingBag :size="20" /><span>购物车</span></RouterLink>
    <RouterLink :to="loggedIn ? '/user' : authPath('login')"><UserCircle :size="21" /><span>我的</span></RouterLink>
  </nav>
</template>
