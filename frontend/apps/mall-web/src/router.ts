import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import HomeView from './views/HomeView.vue'
import GenericView from './views/GenericView.vue'
import AuthView from './views/AuthView.vue'
import SearchView from './views/SearchView.vue'
import ProductView from './views/ProductView.vue'
import ShopView from './views/ShopView.vue'
import CategoryView from './views/CategoryView.vue'
import CollectionView from './views/CollectionView.vue'
import CartView from './views/CartView.vue'
import CheckoutView from './views/CheckoutView.vue'
import CashierView from './views/CashierView.vue'
import PaymentResultView from './views/PaymentResultView.vue'
import OrderListView from './views/OrderListView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import AiShoppingView from './views/AiShoppingView.vue'
import AddressView from './views/AddressView.vue'
import SeckillView from './views/SeckillView.vue'
import AftersaleListView from './views/AftersaleListView.vue'
import AftersaleDetailView from './views/AftersaleDetailView.vue'
import LogisticsView from './views/LogisticsView.vue'
import UserCenterView from './views/UserCenterView.vue'
import UserCouponsView from './views/UserCouponsView.vue'
import UserMemberView from './views/UserMemberView.vue'
import UserPointsView from './views/UserPointsView.vue'
import UserMessagesView from './views/UserMessagesView.vue'
import UserInvoicesView from './views/UserInvoicesView.vue'
import UserSecurityView from './views/UserSecurityView.vue'
import ServiceView from './views/ServiceView.vue'
import CollectionLiteView from './views/CollectionLiteView.vue'
import LegalView from './views/LegalView.vue'
import UserAccountLayout from './components/UserAccountLayout.vue'
import { trackNavigation } from './navigation/smart-back'

const page = (path: string, title: string): RouteRecordRaw => ({ path, component: GenericView, meta: { title } })

const accountChildren: RouteRecordRaw[] = [
  { path: '', component: UserCenterView, meta: { title: '账户信息' } },
  { path: 'orders', component: OrderListView, meta: { title: '我的订单' } },
  { path: 'orders/:orderNo', component: OrderDetailView, meta: { title: '订单详情' } },
  { path: 'logistics/:packageNo', component: LogisticsView, meta: { title: '物流详情' } },
  { path: 'aftersales', component: AftersaleListView, meta: { title: '售后服务' } },
  { path: 'aftersales/:no', component: AftersaleDetailView, meta: { title: '售后详情' } },
  { path: 'favorites', component: CollectionView, meta: { title: '我的收藏' } },
  { path: 'shops', component: CollectionView, meta: { title: '关注店铺' } },
  { path: 'history', component: CollectionView, meta: { title: '浏览历史' } },
  { path: 'coupons', component: UserCouponsView, meta: { title: '优惠券' } },
  { path: 'member', component: UserMemberView, meta: { title: '会员中心' } },
  { path: 'points', component: UserPointsView, meta: { title: '积分明细' } },
  { path: 'messages', component: UserMessagesView, meta: { title: '消息中心' } },
  { path: 'addresses', component: AddressView, meta: { title: '收货地址' } },
  { path: 'invoices', component: UserInvoicesView, meta: { title: '发票管理' } },
  { path: 'security', component: UserSecurityView, meta: { title: '账户安全' } }
]

export const routes: RouteRecordRaw[] = [
  { path: '/', component: HomeView, meta: { title: '首页' } },
  { path: '/category/:id', component: CategoryView, meta: { title: '分类精选' } },
  { path: '/search', component: SearchView, meta: { title: '商品搜索' } },
  { path: '/product/:spuId', component: ProductView, meta: { title: '商品详情' } },
  { path: '/compare', component: CollectionLiteView, meta: { title: '商品对比' } },
  { path: '/shop/:shopId', component: ShopView, meta: { title: '品牌店铺' } },
  { path: '/brand/:brandId', component: CollectionLiteView, meta: { title: '品牌专区' } },
  { path: '/topic/:code', component: CollectionLiteView, meta: { title: '主题活动' } },
  { path: '/legal/privacy', component: LegalView, meta: { title: '隐私政策', legal: 'privacy', immersive: true } },
  { path: '/legal/terms', component: LegalView, meta: { title: '使用条款', legal: 'terms', immersive: true } },
  { path: '/seckill', component: SeckillView, meta: { title: '限时秒杀' } },
  { path: '/ai-shopping', component: AiShoppingView, meta: { title: 'AI 导购' } },
  { path: '/cart', component: CartView, meta: { title: '购物车' } },
  { path: '/checkout', component: CheckoutView, meta: { title: '确认订单' } },
  { path: '/cashier/:orderNo', component: CashierView, meta: { title: '收银台' } },
  { path: '/payment/result', component: PaymentResultView, meta: { title: '支付结果' } },
  {
    path: '/service',
    component: UserAccountLayout,
    children: [{ path: '', component: ServiceView, meta: { title: '客户服务' } }]
  },
  {
    path: '/user',
    component: UserAccountLayout,
    children: accountChildren
  },
  { path: '/auth/login', component: AuthView, meta: { title: '登录', immersive: true } },
  { path: '/auth/register', component: AuthView, meta: { title: '注册', immersive: true } },
  { path: '/auth/:pathMatch(.*)*', redirect: '/auth/login' },
  page('/:pathMatch(.*)*', '页面未找到')
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.afterEach((to, from) => {
  const title = String(to.meta.title ?? to.matched.at(-1)?.meta.title ?? '极客商城')
  document.title = `${title} · 极客商城`
  trackNavigation(to, from)
})
