import type { AuthPrincipal } from '@geek-mall/api-types'
import { mallApi } from '../api'

export interface MemberProfile {
  userId: number
  levelCode: string
  growthValue: number
  pointBalance: number
  frozenPoints: number
}

export interface PointLog {
  changeType: string
  points: number
  balance: number
  frozenBalance: number
  sourceType: string
  sourceId: string
  createdAt: string
}

export interface UserCoupon {
  id: number
  name: string
  thresholdAmount: number
  discountAmount: number
  status: string
  receivedAt: string
}

export interface AvailableCoupon {
  id: number
  name: string
  thresholdAmount: number
  discountAmount: number
  endAt: string
}

export interface UserMessage {
  taskNo: string
  scene: string
  channel: string
  status: string
  payload: string
  createdAt: string
}

export interface InvoiceTitle {
  titleId: number
  titleType: string
  title: string
  taxNo: string | null
  status: string
  createdAt: string
}

export interface InvoiceApplication {
  applicationNo: string
  orderNo: string
  amount: number
  status: string
  createdAt: string
}

export const userApi = {
  session: () => mallApi.request<AuthPrincipal>('/api/v1/auth/session'),
  logout: (refreshToken: string) =>
    mallApi.request<null>('/api/v1/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken })
    }),
  member: () => mallApi.request<MemberProfile>('/api/v1/user/member'),
  pointLogs: () => mallApi.request<PointLog[]>('/api/v1/user/points/logs'),
  myCoupons: () => mallApi.request<UserCoupon[]>('/api/v1/marketing/coupons/mine'),
  availableCoupons: () => mallApi.request<AvailableCoupon[]>('/api/v1/marketing/coupons/available'),
  receiveCoupon: (id: number) =>
    mallApi.request<{ templateId: number; status: string }>(`/api/v1/marketing/coupons/${id}/receive`, { method: 'POST' }),
  favorites: () => mallApi.request<any[]>('/api/v1/user/favorites'),
  followedShops: () => mallApi.request<any[]>('/api/v1/user/shops'),
  history: () => mallApi.request<any[]>('/api/v1/user/history'),
  messages: () => mallApi.request<UserMessage[]>('/api/v1/user/messages'),
  invoiceTitles: () => mallApi.request<InvoiceTitle[]>('/api/v1/user/invoices/titles'),
  invoices: () => mallApi.request<InvoiceApplication[]>('/api/v1/user/invoices'),
  saveInvoiceTitle: (body: { titleType: string; title: string; taxNo?: string }) =>
    mallApi.request<{ titleId: number; title: string }>('/api/v1/user/invoices/titles', {
      method: 'POST',
      body: JSON.stringify(body)
    }),
  applyInvoice: (body: { orderNo: string; titleId: number; amount: number }) =>
    mallApi.request<{ applicationNo: string; status: string }>('/api/v1/user/invoices', {
      method: 'POST',
      body: JSON.stringify(body)
    }),
  createTicket: (body: {
    category: string
    title: string
    content: string
    priority?: string
    merchantId?: number | null
  }) =>
    mallApi.request<any>('/api/v1/service/tickets', {
      method: 'POST',
      body: JSON.stringify(body)
    })
}

export function clearMallAuth() {
  localStorage.removeItem('geek.accessToken')
  localStorage.removeItem('geek.refreshToken')
}
