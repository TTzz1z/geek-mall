import { describe, expect, it } from 'vitest'
import {
  adminMenus,
  canAccessPath,
  isMerchantWorkspace,
  resolveNavMenus,
  visibleMenus
} from './rbac'

describe('admin RBAC menu', () => {
  it('hides platform settings from merchants by permission', () => {
    const labels = visibleMenus(['dashboard:view', 'merchant:order:view']).map(i => i.label)
    expect(labels).toContain('订单履约')
    expect(labels).not.toContain('系统用户')
  })

  it('lets wildcard admin see every menu', () => {
    expect(visibleMenus(['*'])).toHaveLength(adminMenus.length)
  })

  it('does not treat product_admin with merchantId as merchant workspace', () => {
    expect(isMerchantWorkspace({ roles: ['PRODUCT_ADMIN'], merchantId: 2000 })).toBe(false)
    expect(isMerchantWorkspace({ roles: ['OPS'], merchantId: 2000 })).toBe(false)
    expect(isMerchantWorkspace({ roles: ['MERCHANT'], merchantId: 2001 })).toBe(true)
  })

  it('blocks merchant from platform-only paths even with shared permission codes', () => {
    const merchant = {
      roles: ['MERCHANT'],
      permissions: ['merchant:product:view', 'merchant:order:view', 'dashboard:view'],
      merchantId: 2001
    }
    expect(canAccessPath('/products', merchant, 'merchant:product:view')).toBe(true)
    expect(canAccessPath('/merchants', merchant, 'merchant:product:view')).toBe(false)
    expect(canAccessPath('/system/users', merchant, 'system:user:view')).toBe(false)
  })

  it('hides merchants and platform inventory from product_admin static menus', () => {
    const paths = visibleMenus([
      'dashboard:view',
      'merchant:product:view',
      'catalog:manage',
      'governance:merchant:auth',
      'governance:flagship'
    ]).map(i => i.path)
    expect(paths).toContain('/products')
    expect(paths).not.toContain('/merchants')
    expect(paths).not.toContain('/inventory')
  })

  it('allows merchant inventory but blocks product_admin inventory by role scope', () => {
    const merchant = {
      roles: ['MERCHANT'],
      permissions: ['merchant:product:view'],
      merchantId: 2001
    }
    const productAdmin = {
      roles: ['PRODUCT_ADMIN'],
      permissions: ['merchant:product:view', 'dashboard:view'],
      merchantId: 2000
    }
    expect(canAccessPath('/inventory', merchant, 'merchant:product:view')).toBe(true)
    expect(canAccessPath('/inventory', productAdmin, 'merchant:product:view')).toBe(false)
    expect(canAccessPath('/merchants', productAdmin, 'system:user:view')).toBe(false)
  })

  it('keeps remote menus strict for non-admin and does not inflate to full catalog', () => {
    const merchant = {
      roles: ['MERCHANT'],
      permissions: ['dashboard:view', 'merchant:product:view', 'merchant:order:view', 'merchant:settlement:view'],
      merchantId: 2001
    }
    const remote = [
      { path: '/dashboard', label: '经营总览', permission: 'dashboard:view' },
      { path: '/products', label: '商品中心', permission: 'merchant:product:view' },
      { path: '/orders', label: '订单履约', permission: 'merchant:order:view' },
      { path: '/settlements', label: '商家结算', permission: 'merchant:settlement:view' }
    ]
    const { main, settings } = resolveNavMenus(merchant, remote)
    expect(settings).toHaveLength(0)
    expect(main.map(m => m.path)).toEqual(['/dashboard', '/products', '/orders', '/settlements'])
    expect(main).toHaveLength(4)
  })
})
