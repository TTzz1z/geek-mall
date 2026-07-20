import { describe, expect, it } from 'vitest'
import { routes } from './router'
import GenericView from './views/GenericView.vue'
import UserAccountLayout from './components/UserAccountLayout.vue'

function flatten(list = routes, prefix = ''): Array<{ path: string; component?: unknown }> {
  const out: Array<{ path: string; component?: unknown }> = []
  for (const route of list) {
    const full = route.path.startsWith('/')
      ? route.path
      : `${prefix.replace(/\/$/, '')}/${route.path}`.replace(/\/+/g, '/').replace(/\/$/, '') || '/'
    if (route.component) out.push({ path: full === '' ? '/' : full, component: route.component })
    if (route.children) out.push(...flatten(route.children, full === '/' ? '' : full))
  }
  return out
}

describe('mall routes', () => {
  it('keeps every phase-one entry on a real route', () => {
    const paths = flatten().map(route => route.path)
    expect(paths).toContain('/product/:spuId')
    expect(paths).toContain('/ai-shopping')
    expect(paths).toContain('/checkout')
    expect(paths).toContain('/user/aftersales/:no')
    expect(paths).toContain('/auth/login')
  })
  it('binds phase-two catalog routes to real page components', () => {
    const flat = flatten()
    for (const path of ['/search', '/product/:spuId', '/shop/:shopId', '/user/favorites', '/user/history']) {
      expect(flat.find(route => route.path === path)?.component).not.toBe(GenericView)
    }
  })
  it('binds phase-three transaction routes to real page components', () => {
    const flat = flatten()
    for (const path of ['/cart', '/checkout', '/cashier/:orderNo', '/payment/result', '/user/orders', '/user/orders/:orderNo']) {
      expect(flat.find(route => route.path === path)?.component).not.toBe(GenericView)
    }
  })
  it('binds personal center routes to account layout and real pages', () => {
    expect(routes.find(r => r.path === '/user')?.component).toBe(UserAccountLayout)
    const flat = flatten()
    for (const path of [
      '/user', '/user/coupons', '/user/member', '/user/points',
      '/user/messages', '/user/invoices', '/user/security', '/service'
    ]) {
      expect(flat.find(route => route.path === path)?.component).not.toBe(GenericView)
    }
  })
  it('exposes immersive auth login and register pages', () => {
    const login = routes.find(route => route.path === '/auth/login')
    const register = routes.find(route => route.path === '/auth/register')
    expect(login?.meta).toMatchObject({ immersive: true })
    expect(register?.meta).toMatchObject({ immersive: true })
    expect(login?.component).not.toBe(GenericView)
    expect(register?.component).not.toBe(GenericView)
  })
})
