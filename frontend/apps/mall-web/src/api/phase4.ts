import { mallApi } from '../api'

export interface UserAddress {
  id: number
  receiver: string
  mobile: string
  regionCode: string
  regionName: string
  detail: string
  isDefault: boolean
}

export const addressApi = {
  list: () => mallApi.request<UserAddress[]>('/api/v1/user/addresses'),
  create: (body: Omit<UserAddress, 'id' | 'isDefault'> & { isDefault?: boolean }) =>
    mallApi.request<UserAddress>('/api/v1/user/addresses', { method: 'POST', body: JSON.stringify({ ...body, isDefault: !!body.isDefault }) }),
  update: (id: number, body: Omit<UserAddress, 'id'>) =>
    mallApi.request<UserAddress>(`/api/v1/user/addresses/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  remove: (id: number) => mallApi.request<{ deleted: boolean }>(`/api/v1/user/addresses/${id}`, { method: 'DELETE' }),
  setDefault: (id: number) => mallApi.request<UserAddress>(`/api/v1/user/addresses/${id}/default`, { method: 'POST' })
}

export const marketingApi = {
  seckillSessions: () => mallApi.request<any[]>('/api/v1/marketing/seckill/sessions'),
  seckillOrder: (skuId: number, sessionId: number, quantity: number, addressId: number, idempotencyKey: string) =>
    mallApi.request<any>(`/api/v1/marketing/seckill/${skuId}/orders`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ sessionId, quantity, addressId })
    }),
  myCoupons: () => mallApi.request<any[]>('/api/v1/marketing/coupons/mine'),
  availableCoupons: () => mallApi.request<any[]>('/api/v1/marketing/coupons/available'),
  receiveCoupon: (id: number) => mallApi.request<any>(`/api/v1/marketing/coupons/${id}/receive`, { method: 'POST' })
}

export const aftersaleApi = {
  list: () => mallApi.request<any[]>('/api/v1/aftersales'),
  detail: (no: string) => mallApi.request<any>(`/api/v1/aftersales/${no}`),
  apply: (body: { orderNo: string; orderItemId: number; merchantId: number; type: string; quantity: number; reason: string; amount?: number }) =>
    mallApi.request<any>('/api/v1/aftersales', { method: 'POST', body: JSON.stringify(body) }),
  cancel: (no: string) => mallApi.request<any>(`/api/v1/aftersales/${no}/cancel`, { method: 'POST' }),
  returnLogistics: (no: string, trackingNo: string) =>
    mallApi.request<any>(`/api/v1/aftersales/${no}/return-logistics?trackingNo=${encodeURIComponent(trackingNo)}`, { method: 'POST' }),
  arbitration: (no: string, reason: string) =>
    mallApi.request<any>(`/api/v1/aftersales/${no}/arbitration?reason=${encodeURIComponent(reason)}`, { method: 'POST' })
}

export const logisticsApi = {
  packageDetail: (no: string) => mallApi.request<any>(`/api/v1/logistics/packages/${no}`),
  receive: (no: string) => mallApi.request<any>(`/api/v1/logistics/packages/${no}/receive`, { method: 'POST' })
}
