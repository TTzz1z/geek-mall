<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import { PhBell as Bell, PhMagnifyingGlass as MagnifyingGlass, PhSignOut as SignOut } from '@phosphor-icons/vue'
import { adminApi } from './api'
import { adminMenus, isMerchantWorkspace, resolveNavMenus, type AdminMenu } from './rbac'
import { useSessionStore } from './session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const remoteMenus = ref<AdminMenu[] | null>(null)

const groups = computed(() => resolveNavMenus(session.principal, remoteMenus.value))

const roleLabel = computed(() => {
  const roles = session.principal?.roles ?? []
  if (roles.includes('SYSTEM_ADMIN')) return '系统管理员'
  if (roles.includes('PRODUCT_ADMIN')) return '商品管理员'
  if (roles.includes('OPS')) return '平台运营'
  if (isMerchantWorkspace(session.principal)) return '商家工作台'
  return '工作台'
})

function logout() {
  session.clear()
  router.push('/login')
}

function openSearch() {
  if (isMerchantWorkspace(session.principal)) {
    router.push('/products')
    return
  }
  router.push('/search-operations')
}

function openMessages() {
  if (isMerchantWorkspace(session.principal)) {
    router.push('/aftersales')
    return
  }
  router.push('/service-tickets')
}

function mapApiMenus(items: Array<{ name: string; path: string; permission: string | null; type: string }>): AdminMenu[] {
  const byPath = new Map(adminMenus.map(m => [m.path, m]))
  const mapped: AdminMenu[] = []
  for (const item of items) {
    if (!item.path || item.type === 'BUTTON') continue
    const known = byPath.get(item.path)
    const label = (isMerchantWorkspace(session.principal) || (session.principal?.roles ?? []).includes('OPS')) && item.path === '/products'
      ? '我的商品'
      : (item.name || known?.label || item.path)
    mapped.push({
      path: item.path,
      label,
      permission: item.permission || known?.permission || '',
      group: known?.group || (item.path.startsWith('/system') ? 'settings' : 'main')
    })
  }
  // 仅系统管理员（*）补全静态扩展菜单；其它角色严格按后端角色菜单，避免权限码重合导致侧栏趋同
  if (session.permissions.includes('*')) {
    for (const extra of adminMenus) {
      if (!mapped.some(m => m.path === extra.path)) mapped.push(extra)
    }
  } else if (isMerchantWorkspace(session.principal)) {
    for (const extra of [
      { path: '/inventory', label: '仓库库存', permission: 'merchant:product:view' },
      { path: '/aftersales', label: '售后处理', permission: 'merchant:order:view' }
    ]) {
      if (session.permissions.includes(extra.permission) && !mapped.some(m => m.path === extra.path)) {
        mapped.push(extra)
      }
    }
  }
  return mapped
}

async function loadMenus() {
  if (route.meta.public || !session.principal) {
    remoteMenus.value = null
    return
  }
  try {
    const items = await adminApi.request<Array<{ name: string; path: string; permission: string | null; type: string }>>('/api/v1/admin/menus')
    remoteMenus.value = mapApiMenus(items || [])
  } catch {
    remoteMenus.value = null
  }
}

onMounted(loadMenus)
watch(() => session.principal?.userId, loadMenus)
</script>

<template>
  <RouterView v-if="route.meta.public" />
  <div v-else class="admin-shell">
    <aside class="admin-side">
      <a class="admin-brand" href="/dashboard">
        <b>GEEK</b><span>OPS</span>
      </a>
      <div class="admin-side-meta">
        <p class="admin-side-tag">CONSOLE</p>
        <p class="admin-side-desc">{{ roleLabel }}</p>
      </div>

      <nav class="admin-nav custom-scroll">
        <RouterLink
          v-for="menu in groups.main"
          :key="menu.path"
          :to="menu.path"
          class="admin-nav-item"
          :class="{ active: route.path === menu.path || route.path.startsWith(menu.path + '/') }"
        >
          <i class="admin-nav-bar" aria-hidden="true" />
          {{ menu.label }}
        </RouterLink>

        <template v-if="groups.settings.length">
          <p class="admin-nav-group">Settings</p>
          <RouterLink
            v-for="menu in groups.settings"
            :key="menu.path"
            :to="menu.path"
            class="admin-nav-item"
            :class="{ active: route.path === menu.path }"
          >
            <i class="admin-nav-bar" aria-hidden="true" />
            {{ menu.label }}
          </RouterLink>
        </template>
      </nav>

      <div class="tenant-chip-wrap">
        <div class="tenant-chip">
          <small>当前数据范围</small>
          <strong>{{ isMerchantWorkspace(session.principal) ? `商家 ${session.principal?.merchantId}` : '平台全局' }}</strong>
          <span>{{ session.principal?.username }}</span>
        </div>
      </div>
    </aside>

    <section class="admin-main">
      <header class="admin-top" :class="{ 'is-compact': route.meta.pageHero }">
        <div>
          <p class="admin-crumb">GEEK MALL <span>/</span> {{ route.meta.title }}</p>
          <h1 v-if="!route.meta.pageHero">{{ route.meta.title }}</h1>
        </div>
        <nav class="admin-tools" aria-label="快捷操作">
          <button type="button" aria-label="全局搜索" @click="openSearch"><MagnifyingGlass :size="18" weight="regular" /></button>
          <button type="button" class="has-dot" aria-label="消息" @click="openMessages"><Bell :size="18" weight="regular" /></button>
          <button type="button" class="logout" aria-label="退出登录" @click="logout"><SignOut :size="18" weight="bold" /></button>
        </nav>
      </header>
      <main class="admin-content">
        <div class="admin-content-inner">
          <RouterView />
        </div>
      </main>
    </section>
  </div>
</template>
