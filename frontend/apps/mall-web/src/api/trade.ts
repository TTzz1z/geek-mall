import type { CartSummary, CheckoutPayload, PaymentInfo, TradeOrderDetail, TradeOrderSummary } from '@geek-mall/api-types'
import { mallApi } from '../api'

export const tradeApi = {
  cart: () => mallApi.request<CartSummary>('/api/v1/trade/cart'),
  addCart: (skuId: number, quantity = 1) => mallApi.request<CartSummary>('/api/v1/trade/cart/items', { method: 'POST', body: JSON.stringify({ skuId, quantity }) }),
  updateCart: (id: number, body: { quantity?: number; selected?: boolean }) => mallApi.request<CartSummary>(`/api/v1/trade/cart/items/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  deleteCart: (id: number) => mallApi.request<CartSummary>(`/api/v1/trade/cart/items/${id}`, { method: 'DELETE' }),
  mergeCart: (items: Array<{ skuId: number; quantity: number }>) => mallApi.request<CartSummary>('/api/v1/trade/cart/merge', { method: 'POST', body: JSON.stringify({ items }) }),
  preview: (body: { cartItemIds: number[]; addressId: number | null; receiver: string; mobile: string; regionCode: string; addressDetail: string; deliveryMethod: string; userCouponId?: number | null; pointsToUse?: number | null }) => mallApi.request<CheckoutPayload>('/api/v1/trade/checkout/preview', { method: 'POST', body: JSON.stringify(body) }),
  confirm: (checkoutToken: string) => mallApi.request<CheckoutPayload>('/api/v1/trade/checkout/confirm', { method: 'POST', body: JSON.stringify({ checkoutToken }) }),
  createOrder: (body: { checkoutToken: string; addressId: number | null; remarkByShop: Record<string, string> }, idempotencyKey: string) => mallApi.request<TradeOrderDetail>('/api/v1/trade/orders', { method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body) }),
  orders: (status = '') => mallApi.request<TradeOrderSummary[]>(`/api/v1/trade/orders${status ? `?status=${encodeURIComponent(status)}` : ''}`),
  order: (orderNo: string) => mallApi.request<TradeOrderDetail>(`/api/v1/trade/orders/${orderNo}`),
  cancel: (orderNo: string) => mallApi.request<TradeOrderDetail>(`/api/v1/trade/orders/${orderNo}/cancel`, { method: 'POST' }),
  createPayment: (orderNo: string) => mallApi.request<{ paymentNo: string; amount: number; channel: string }>('/api/v1/payments', { method: 'POST', body: JSON.stringify({ orderNo, channel: 'SIMULATED' }) }),
  payment: (paymentNo: string) => mallApi.request<PaymentInfo>(`/api/v1/payments/${paymentNo}`),
  simulatePayment: (paymentNo: string) => mallApi.request<{ paymentNo: string; orderNo: string; status: string; idempotentReplay: boolean }>(`/api/v1/payments/${paymentNo}/simulate`, { method: 'POST' })
}
