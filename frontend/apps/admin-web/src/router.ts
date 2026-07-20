import { createRouter, createWebHistory } from 'vue-router'
import AdminPage from './views/AdminPage.vue'
import LoginView from './views/LoginView.vue'
import { canAccessPath } from './rbac'
import { useSessionStore } from './session'
import ProductManagementView from './views/ProductManagementView.vue'
import MerchantManagementView from './views/MerchantManagementView.vue'
import InventoryView from './views/InventoryView.vue'
import SearchOperationsView from './views/SearchOperationsView.vue'
import CatalogConfigView from './views/CatalogConfigView.vue'
import FulfillmentAftersaleView from './views/FulfillmentAftersaleView.vue'
import ArbitrationView from './views/ArbitrationView.vue'
import CmsHomeView from './views/CmsHomeView.vue'
import MerchantAuthView from './views/MerchantAuthView.vue'
import FlagshipCertView from './views/FlagshipCertView.vue'
import DashboardView from './views/DashboardView.vue'
import MarketingSeckillView from './views/MarketingSeckillView.vue'
import SettlementAdminView from './views/SettlementAdminView.vue'
import ServiceTicketView from './views/ServiceTicketView.vue'
import AbTestView from './views/AbTestView.vue'
import AiOpsView from './views/AiOpsView.vue'
import SystemUsersView from './views/SystemUsersView.vue'
import SystemRolesView from './views/SystemRolesView.vue'
import SystemLogsView from './views/SystemLogsView.vue'

const page = (path: string, title: string, permission: string) => ({ path, component: AdminPage, meta: { title, permission } })
export const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', component: LoginView, meta: { title: '后台登录', public: true } },
  { path: '/dashboard', component: DashboardView, meta: { title: '经营总览', permission: 'dashboard:view', pageHero: true } },
  { path: '/analytics', redirect: '/dashboard' },
  { path: '/page-decoration', component: CmsHomeView, meta: { title: '首页装修', permission: 'cms:page:edit', pageHero: true } },
  { path: '/merchants', component: MerchantManagementView, meta: { title: '商家与店铺', permission: 'system:user:view' } },
  { path: '/merchant-auth', component: MerchantAuthView, meta: { title: '商家授权治理', permission: 'governance:merchant:auth' } },
  { path: '/flagship-certs', component: FlagshipCertView, meta: { title: '旗舰认证', permission: 'governance:flagship' } },
  { path: '/products', component: ProductManagementView, meta: { title: '商品中心', permission: 'merchant:product:view' } },
  { path: '/catalog-config', component: CatalogConfigView, meta: { title: '分类品牌属性', permission: 'catalog:manage' } },
  { path: '/inventory', component: InventoryView, meta: { title: '仓库库存', permission: 'merchant:product:view' } },
  { path: '/search-operations', component: SearchOperationsView, meta: { title: '搜索索引', permission: 'system:user:view' } },
  { path: '/orders', component: FulfillmentAftersaleView, meta: { title: '订单履约', permission: 'merchant:order:view' } },
  { path: '/fulfillments', component: FulfillmentAftersaleView, meta: { title: '仓储发货', permission: 'merchant:order:view' } },
  { path: '/marketing', component: MarketingSeckillView, meta: { title: '营销与秒杀', permission: 'cms:page:edit' } },
  { path: '/aftersales', component: FulfillmentAftersaleView, meta: { title: '售后审核', permission: 'merchant:order:view' } },
  { path: '/arbitrations', component: ArbitrationView, meta: { title: '平台仲裁', permission: 'system:user:view' } },
  { path: '/settlements', component: SettlementAdminView, meta: { title: '商家结算', permission: 'merchant:settlement:view' } },
  { path: '/service-tickets', component: ServiceTicketView, meta: { title: '客服工单', permission: 'system:user:view' } },
  { path: '/ab-tests', component: AbTestView, meta: { title: 'A/B 测试', permission: 'cms:page:edit' } },
  { path: '/ai-ops', component: AiOpsView, meta: { title: 'AI 调用与评测', permission: 'system:user:view' } },
  { path: '/system/users', component: SystemUsersView, meta: { title: '系统用户', permission: 'system:user:view' } },
  { path: '/system/roles', component: SystemRolesView, meta: { title: '角色权限', permission: 'system:user:view' } },
  { path: '/system/logs', component: SystemLogsView, meta: { title: '审计日志', permission: 'system:user:view' } },
  page('/forbidden', '无权限', ''),
  page('/:pathMatch(.*)*', '页面未找到', '')
] })
router.beforeEach(to => {
  const store = useSessionStore()
  if (to.meta.public) return true
  if (!store.principal) return { path: '/login', query: { redirect: to.fullPath } }
  const permission = String(to.meta.permission ?? '')
  const principal = {
    roles: store.principal.roles,
    permissions: store.permissions,
    merchantId: store.principal.merchantId
  }
  if (!canAccessPath(to.path, principal, permission)) return '/forbidden'
  return true
})
