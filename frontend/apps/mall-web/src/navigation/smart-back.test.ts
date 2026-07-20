import { describe, expect, it } from 'vitest'
import {
  peekPreviousPath,
  resolveBackTarget,
  shouldShowBack,
  trackNavigation
} from './smart-back'

function loc(path: string, extra: Record<string, unknown> = {}) {
  return {
    path,
    fullPath: path,
    query: {},
    params: {},
    matched: path === '/' ? [] : [{ path }],
    meta: {},
    name: undefined,
    hash: '',
    redirectedFrom: undefined,
    ...extra
  } as any
}

describe('smart-back', () => {
  it('hides on home', () => {
    expect(shouldShowBack(loc('/'))).toBe(false)
  })

  it('falls back checkout to cart', () => {
    const t = resolveBackTarget(loc('/checkout'))
    expect(t.to).toBe('/cart')
    expect(t.label).toContain('购物车')
  })

  it('falls back order detail to order list', () => {
    const t = resolveBackTarget(loc('/user/orders/ORD1'))
    expect(t.to).toBe('/user/orders')
  })

  it('tracks previous in-app path for history preference', () => {
    trackNavigation(loc('/product/10001'), loc('/search?keyword=Phone'))
    expect(peekPreviousPath()).toBe('/search?keyword=Phone')
    const t = resolveBackTarget(loc('/product/10001'))
    // Prefer history when browser stack allows; otherwise still knows previous path.
    if (t.mode === 'history') {
      expect(t.label).toBe('返回上一级')
    } else {
      expect(t.to).toBe('/search')
    }
  })

  it('uses product from= query as fallback', () => {
    const t = resolveBackTarget(loc('/product/10001', {
      path: '/product/10001',
      fullPath: '/product/10001?from=/category/110',
      query: { from: '/category/110' }
    }))
    if (t.mode === 'push') {
      expect(t.to).toBe('/category/110')
    }
  })
})
