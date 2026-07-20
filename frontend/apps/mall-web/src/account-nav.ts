import {
  PhUserCircle as UserCircle,
  PhPackage as Package,
  PhArrowCounterClockwise as Aftersale,
  PhTicket as Ticket,
  PhCoin as Coin,
  PhHeart as Heart,
  PhStorefront as Storefront,
  PhClockCounterClockwise as History,
  PhMapPin as MapPin,
  PhFileText as FileText,
  PhBell as Bell,
  PhShieldCheck as Shield,
  PhHeadset as Headset,
  PhCrown as Crown
} from '@phosphor-icons/vue'

export type AccountNavItem = {
  to: string
  label: string
  icon: unknown
  match?: RegExp
  valueKey?: 'points' | 'coupons' | 'messages'
}

export type AccountNavGroup = {
  title: string
  items: AccountNavItem[]
}

export const accountNavGroups: AccountNavGroup[] = [
  {
    title: '账户',
    items: [
      { to: '/user', label: '账户信息', icon: UserCircle, match: /^\/user$/ }
    ]
  },
  {
    title: '订单',
    items: [
      { to: '/user/orders', label: '我的订单', icon: Package, match: /^\/user\/orders/ },
      { to: '/user/aftersales', label: '售后服务', icon: Aftersale, match: /^\/user\/aftersales/ }
    ]
  },
  {
    title: '资产',
    items: [
      { to: '/user/coupons', label: '我的优惠券', icon: Ticket, match: /^\/user\/coupons$/, valueKey: 'coupons' },
      { to: '/user/points', label: '我的积分', icon: Coin, match: /^\/user\/points$/, valueKey: 'points' },
      { to: '/user/member', label: '会员中心', icon: Crown, match: /^\/user\/member$/ },
      { to: '/user/favorites', label: '我的收藏', icon: Heart, match: /^\/user\/favorites$/ },
      { to: '/user/shops', label: '关注店铺', icon: Storefront, match: /^\/user\/shops$/ },
      { to: '/user/history', label: '浏览历史', icon: History, match: /^\/user\/history$/ }
    ]
  },
  {
    title: '设置',
    items: [
      { to: '/user/addresses', label: '收货地址', icon: MapPin, match: /^\/user\/addresses$/ },
      { to: '/user/invoices', label: '发票管理', icon: FileText, match: /^\/user\/invoices$/ },
      { to: '/user/messages', label: '消息中心', icon: Bell, match: /^\/user\/messages$/, valueKey: 'messages' },
      { to: '/user/security', label: '账户安全', icon: Shield, match: /^\/user\/security$/ },
      { to: '/service', label: '客户服务', icon: Headset, match: /^\/service$/ }
    ]
  }
]

export function accountTitle(path: string) {
  for (const g of accountNavGroups) {
    for (const item of g.items) {
      if (item.match?.test(path) || item.to === path) return item.label
    }
  }
  if (path.startsWith('/user/orders/')) return '订单详情'
  if (path.startsWith('/user/aftersales/')) return '售后详情'
  if (path.startsWith('/user/logistics/')) return '物流详情'
  return '个人中心'
}
