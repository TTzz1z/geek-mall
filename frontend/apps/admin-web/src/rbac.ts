export interface AdminMenu {
  path: string
  label: string
  permission: string
  group?: 'main' | 'settings'
}

/** 平台侧账号（非商家工作台） */
export const PLATFORM_ROLES = ['SYSTEM_ADMIN', 'PRODUCT_ADMIN', 'OPS'] as const

/** 商家工作台可见菜单（后端未下发时的兜底） */
export const merchantMenus: AdminMenu[] = [
  { path: '/dashboard', label: '经营总览', permission: 'dashboard:view' },
  { path: '/products', label: '我的商品', permission: 'merchant:product:view' },
  { path: '/inventory', label: '仓库库存', permission: 'merchant:product:view' },
  { path: '/orders', label: '订单履约', permission: 'merchant:order:view' },
  { path: '/aftersales', label: '售后处理', permission: 'merchant:order:view' },
  { path: '/settlements', label: '商家结算', permission: 'merchant:settlement:view' }
]

/** 仅平台账号可进的路由（商家即使权限码重合也不可进） */
export const platformOnlyPaths = new Set([
  '/merchants',
  '/merchant-auth',
  '/flagship-certs',
  '/page-decoration',
  '/catalog-config',
  '/search-operations',
  '/marketing',
  '/arbitrations',
  '/service-tickets',
  '/ab-tests',
  '/ai-ops',
  '/system/users',
  '/system/roles',
  '/system/logs'
])

export const adminMenus: AdminMenu[] = [
  { path: '/dashboard', label: '经营总览', permission: 'dashboard:view' },
  { path: '/page-decoration', label: '首页装修', permission: 'cms:page:edit' },
  // 入驻审核 / 全站仓库仅系统管理员；勿与 merchant:product:view 共用，否则商品管理员静态兜底菜单会误展示
  { path: '/merchants', label: '商家与店铺', permission: 'system:user:view' },
  { path: '/merchant-auth', label: '商家授权治理', permission: 'governance:merchant:auth' },
  { path: '/flagship-certs', label: '旗舰认证', permission: 'governance:flagship' },
  { path: '/products', label: '商品中心', permission: 'merchant:product:view' },
  { path: '/catalog-config', label: '分类品牌属性', permission: 'catalog:manage' },
  { path: '/inventory', label: '仓库库存', permission: 'system:user:view' },
  { path: '/search-operations', label: '搜索运维', permission: 'system:user:view' },
  { path: '/orders', label: '订单履约', permission: 'merchant:order:view' },
  { path: '/marketing', label: '营销与秒杀', permission: 'cms:page:edit' },
  { path: '/settlements', label: '商家结算', permission: 'merchant:settlement:view' },
  { path: '/service-tickets', label: '客服工单', permission: 'system:user:view' },
  { path: '/aftersales', label: '售后审核', permission: 'merchant:order:view' },
  { path: '/arbitrations', label: '平台仲裁', permission: 'system:user:view' },
  { path: '/ab-tests', label: 'A/B 测试', permission: 'cms:page:edit' },
  { path: '/ai-ops', label: 'AI 调用与评测', permission: 'system:user:view' },
  { path: '/system/users', label: '系统用户', permission: 'system:user:view', group: 'settings' },
  { path: '/system/roles', label: '角色权限', permission: 'system:user:view', group: 'settings' },
  { path: '/system/logs', label: '审计日志', permission: 'system:user:view', group: 'settings' }
]

export function isPlatformStaff(roles: string[] | undefined | null) {
  return (roles ?? []).some(r => (PLATFORM_ROLES as readonly string[]).includes(r))
}

/** 商家工作台：MERCHANT 角色，且不是平台员工（避免 product_admin 绑了 merchantId 被误判） */
export function isMerchantWorkspace(principal: { roles?: string[]; merchantId?: number | null } | null | undefined) {
  if (!principal) return false
  if (isPlatformStaff(principal.roles)) return false
  return (principal.roles ?? []).includes('MERCHANT') || !!principal.merchantId
}

export function hasPermission(permissions: string[], code: string) {
  if (!code) return true
  return permissions.includes('*') || permissions.includes(code)
}

export function visibleMenus(permissions: string[]) {
  return adminMenus.filter(menu => hasPermission(permissions, menu.permission))
}

export function menusByGroup(permissions: string[]) {
  return groupMenus(visibleMenus(permissions))
}

export function groupMenus(all: AdminMenu[]) {
  return {
    main: all.filter(m => m.group !== 'settings'),
    settings: all.filter(m => m.group === 'settings')
  }
}

export function canAccessPath(
  path: string,
  principal: { roles?: string[]; permissions?: string[]; merchantId?: number | null } | null | undefined,
  permission?: string
) {
  if (!principal) return false
  const perms = principal.permissions ?? []
  // 仓库库存：商家凭商品权限进入；平台侧需系统管理权限（与 API PreAuthorize 对齐）
  if (path === '/inventory' || path.startsWith('/inventory/')) {
    if (isMerchantWorkspace(principal)) return hasPermission(perms, 'merchant:product:view')
    return hasPermission(perms, 'system:user:view')
  }
  if (permission && !hasPermission(perms, permission)) return false
  if (isMerchantWorkspace(principal) && platformOnlyPaths.has(path)) return false
  return true
}

/** 侧栏：优先用后端按角色下发的菜单；仅系统管理员（*）补全静态扩展项 */
export function resolveNavMenus(
  principal: { roles?: string[]; permissions?: string[]; merchantId?: number | null } | null | undefined,
  remote: AdminMenu[] | null | undefined
) {
  const perms = principal?.permissions ?? []
  if (remote?.length) {
    const filtered = remote.filter(m =>
      hasPermission(perms, m.permission) &&
      !(isMerchantWorkspace(principal) && platformOnlyPaths.has(m.path))
    )
    return groupMenus(filtered)
  }
  if (isMerchantWorkspace(principal)) {
    return groupMenus(merchantMenus.filter(m => hasPermission(perms, m.permission)))
  }
  return menusByGroup(perms)
}
