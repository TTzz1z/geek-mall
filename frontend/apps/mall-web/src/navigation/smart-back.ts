import type { Router, RouteLocationNormalizedLoaded } from 'vue-router'

/** Last in-app path before the current one (excludes auth). */
let previousFullPath: string | null = null

export function trackNavigation(
  to: RouteLocationNormalizedLoaded,
  from: RouteLocationNormalizedLoaded
) {
  if (!from.matched.length) return
  if (from.path.startsWith('/auth')) return
  if (from.fullPath === to.fullPath) return
  previousFullPath = from.fullPath
}

export type BackTarget = {
  mode: 'history' | 'push'
  to: string
  label: string
}

type Fallback = { to: string; label: string }

function fallbackFor(route: RouteLocationNormalizedLoaded): Fallback {
  const p = route.path
  const orderNo = route.params.orderNo ? String(route.params.orderNo) : ''

  if (p.startsWith('/product/')) {
    const from = typeof route.query.from === 'string' ? route.query.from : ''
    if (from.startsWith('/category/') || from.startsWith('/search') || from.startsWith('/shop/')) {
      return { to: from, label: '返回上一级' }
    }
    if (typeof route.query.keyword === 'string' && route.query.keyword) {
      return { to: `/search?keyword=${encodeURIComponent(route.query.keyword)}`, label: '返回搜索' }
    }
    return { to: '/search', label: '返回搜索' }
  }
  if (p === '/checkout') return { to: '/cart', label: '返回购物车' }
  if (p.startsWith('/cashier/') && orderNo) {
    return { to: `/user/orders/${orderNo}`, label: '返回订单' }
  }
  if (p === '/payment/result') {
    const qOrder = typeof route.query.orderNo === 'string' ? route.query.orderNo : ''
    if (qOrder) return { to: `/user/orders/${qOrder}`, label: '查看订单' }
    return { to: '/user/orders', label: '我的订单' }
  }
  if (p === '/ai-shopping') return { to: '/', label: '返回首页' }
  if (p === '/cart' || p === '/search' || p === '/seckill' || p === '/compare') {
    return { to: '/', label: '返回首页' }
  }
  if (p.startsWith('/category/') || p.startsWith('/shop/') || p.startsWith('/brand/') || p.startsWith('/topic/')) {
    return { to: '/', label: '返回首页' }
  }
  if (/^\/user\/orders\/[^/]+$/.test(p)) return { to: '/user/orders', label: '返回订单列表' }
  if (/^\/user\/aftersales\/[^/]+$/.test(p)) return { to: '/user/aftersales', label: '返回售后列表' }
  if (p.startsWith('/user/logistics/')) return { to: '/user/orders', label: '返回订单' }
  if (p.startsWith('/user/') && p !== '/user') return { to: '/user', label: '返回个人中心' }
  if (p === '/user' || p === '/service') return { to: '/', label: '返回首页' }
  if (p.startsWith('/legal/')) return { to: '/auth/login', label: '返回登录' }
  return { to: '/', label: '返回上一级' }
}

export function shouldShowBack(route: RouteLocationNormalizedLoaded): boolean {
  if (route.meta.hideBack) return false
  if (route.path === '/') return false
  // Auth screens keep their own flows; legal is immersive with dedicated link.
  if (route.path.startsWith('/auth')) return false
  return true
}

export function resolveBackTarget(route: RouteLocationNormalizedLoaded): BackTarget {
  const fallback = fallbackFor(route)
  const prev = previousFullPath
  if (
    prev &&
    prev !== route.fullPath &&
    !prev.startsWith('/auth') &&
    typeof window !== 'undefined' &&
    window.history.length > 1
  ) {
    return { mode: 'history', to: prev, label: '返回上一级' }
  }
  return { mode: 'push', to: fallback.to, label: fallback.label }
}

export function goSmartBack(router: Router, route: RouteLocationNormalizedLoaded) {
  const target = resolveBackTarget(route)
  if (target.mode === 'history') {
    router.back()
    return
  }
  void router.push(target.to)
}

export function peekPreviousPath() {
  return previousFullPath
}
