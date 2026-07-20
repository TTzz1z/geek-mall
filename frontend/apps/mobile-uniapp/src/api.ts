import type { ApiResponse, TokenPair } from '@geek-mall/api-types'
import { apiBase } from './config'

const ACCESS = 'geek.mobile.accessToken'
const REFRESH = 'geek.mobile.refreshToken'

export function getAccessToken(): string {
  return uni.getStorageSync(ACCESS) || ''
}

export function getRefreshToken(): string {
  return uni.getStorageSync(REFRESH) || ''
}

export function setTokens(tokens: Pick<TokenPair, 'accessToken' | 'refreshToken'>) {
  uni.setStorageSync(ACCESS, tokens.accessToken)
  uni.setStorageSync(REFRESH, tokens.refreshToken)
}

export function clearTokens() {
  uni.removeStorageSync(ACCESS)
  uni.removeStorageSync(REFRESH)
}

export function isLoggedIn(): boolean {
  return Boolean(getAccessToken())
}

function requestId(): string {
  return `m-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function refreshOnce(): Promise<boolean> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return false
  try {
    const data = await rawRequest<TokenPair>('/api/v1/auth/refresh', {
      method: 'POST',
      body: { refreshToken },
      auth: false
    })
    setTokens(data)
    return true
  } catch {
    clearTokens()
    return false
  }
}

async function rawRequest<T>(
  path: string,
  init: { method?: string; body?: unknown; auth?: boolean; headers?: Record<string, string> } = {},
  retried = false
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Request-Id': requestId(),
    'X-Client-Type': 'MOBILE',
    ...(init.headers || {})
  }
  if (init.auth !== false) {
    const token = getAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  const result = await new Promise<UniApp.RequestSuccessCallbackResult>((resolve, reject) => {
    uni.request({
      url: `${apiBase()}${path}`,
      method: (init.method || 'GET') as UniNamespace.RequestOptions['method'],
      data: init.body as AnyObject | undefined,
      header: headers,
      success: resolve,
      fail: reject
    })
  })

  const status = result.statusCode || 0
  const envelope = result.data as ApiResponse<T>
  if (status === 401 && !retried && init.auth !== false && getRefreshToken()) {
    const ok = await refreshOnce()
    if (ok) return rawRequest<T>(path, init, true)
  }
  if (status < 200 || status >= 300 || !envelope || envelope.code !== '0') {
    throw new Error((envelope && envelope.message) || `请求失败(${status})`)
  }
  return envelope.data
}

export const mobileApi = {
  request: <T>(path: string, init?: { method?: string; body?: unknown; headers?: Record<string, string> }) =>
    rawRequest<T>(path, { ...init, body: init?.body })
}

/** 微信登录扩展位（展示项目未接真实微信凭证） */
export async function loginWechatPlaceholder(): Promise<never> {
  throw new Error('当前请使用账号密码登录')
}

/** 微信支付扩展位（展示项目使用模拟支付） */
export async function payWechatPlaceholder(): Promise<never> {
  throw new Error('当前请使用模拟支付')
}
