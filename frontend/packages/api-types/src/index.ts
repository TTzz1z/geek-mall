export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  requestId: string
  timestamp: string
}

export interface AuthPrincipal {
  userId: number
  username: string
  audience: 'USER' | 'BACKOFFICE'
  merchantId: number | null
  shopId: number | null
  roles: string[]
  permissions: string[]
}

export interface TokenPair {
  accessToken: string
  refreshToken: string
  expiresIn: number
  principal: AuthPrincipal
}

export interface MenuItem {
  id: number
  parentId: number | null
  name: string
  path: string | null
  icon: string | null
  permission: string | null
  type: 'DIR' | 'MENU' | 'BUTTON'
}

export interface ProductSummary {
  id: number
  title: string
  subtitle: string
  minPrice: number
  maxPrice: number
  coverUrl: string
  salesCount: number
  rating: number
  shopId: number
  shopName: string
  shopType?: string
  sellerLabel?: string
  ownBrand?: boolean
  deliveryHint?: string
  sourceType?: string
  brandId: number
  brandName: string
  categoryId: number
  categoryName: string
}

export interface ProductSku {
  id: number
  skuCode: string
  salePrice: number
  marketPrice: number
  status: string
  saleAttributes: Array<{ name: string; value: string; attrId: number; optionId: number | null }>
}

export interface ProductDetail extends ProductSummary {
  descriptionHtml: string
  packageList: string
  serviceDesc: string
  shopLogo: string
  shopScore: number
  skus: ProductSku[]
  attributes: Array<{ id: number; name: string; value: string; unit: string | null; comparable: boolean }>
}

export interface PageResult<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
  hasNext: boolean
  facets?: Record<string, unknown>
}

export interface CartItem {
  id: number; shopId: number; merchantId: number; spuId: number; skuId: number; quantity: number; selected: boolean
  addedPrice: number; titleSnapshot: string; coverUrl: string; currentPrice: number; priceChanged: boolean; availableQty: number
  saleable: boolean; stockEnough: boolean; invalidReason: string | null; lineAmount: number
}
export interface CartShop { shopId: number; shopName: string; items: CartItem[] }
export interface CartSummary { shops: CartShop[]; itemCount: number; selectedCount: number; selectedAmount: number }
export interface CheckoutItem { cartItemId: number; spuId: number; skuId: number; merchantId: number; shopId: number; shopName: string; title: string; coverUrl: string; skuCode: string; unitPrice: number; quantity: number; subtotal: number; discountAmount: number; payableAmount: number }
export interface WarehousePlan { warehouseId: number; warehouseName: string; skuId: number; quantity: number }
export interface CheckoutShopGroup { shopId: number; merchantId: number; shopName: string; totalAmount: number; discountAmount: number; payableAmount: number; items: CheckoutItem[]; warehousePlans: WarehousePlan[] }
export interface PromotionLine { type: string; sourceId: string; label: string; amount: number }
export interface CheckoutPayload { checkoutToken: string; expiresAt: string; address: { addressId: number | null; receiver: string; mobile: string; regionCode: string; detail: string }; totalAmount: number; discountAmount: number; freightAmount: number; payableAmount: number; discountType: string; discountSourceId: string; userCouponId?: number | null; pointsUsed?: number | null; promotionLines?: PromotionLine[]; items: CheckoutItem[]; shopGroups: CheckoutShopGroup[] }
export interface TradeOrderSummary { orderNo: string; totalAmount: number; discountAmount: number; freightAmount: number; payableAmount: number; paidAmount: number; status: string; inventoryStatus: string; createdAt: string; paidAt: string | null; closedAt: string | null }
export interface TradeOrderDetail extends TradeOrderSummary { address: { addressId: number | null; receiver: string; mobile: string; regionCode: string; detail: string }; discounts: Array<Record<string, unknown>>; payments: Array<Record<string, unknown>>; subOrders: Array<Record<string, any>> }
export interface PaymentInfo { paymentNo: string; orderNo: string; channel: string; amount: number; status: string; channelTradeNo: string | null; createdAt: string; paidAt: string | null }
