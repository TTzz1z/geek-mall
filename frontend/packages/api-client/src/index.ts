import type { ApiResponse, TokenPair } from '@geek-mall/api-types'

export interface TokenStore {
  getAccessToken(): string | null
  getRefreshToken(): string | null
  setTokens(tokens: TokenPair): void
  clear(): void
}

export class ApiClient {
  private refreshPromise: Promise<TokenPair> | null = null

  constructor(private readonly baseUrl: string, private readonly tokens: TokenStore) {}

  async request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
    const headers = new Headers(init.headers)
    if (!(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    headers.set('X-Request-Id', crypto.randomUUID())
    headers.set('X-Client-Type', 'WEB')
    const accessToken = this.tokens.getAccessToken()
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)

    const response = await fetch(`${this.baseUrl}${path}`, { ...init, headers })
    if (response.status === 401 && retry && this.tokens.getRefreshToken()) {
      await this.refreshOnce()
      return this.request<T>(path, init, false)
    }
    const envelope = (await response.json()) as ApiResponse<T>
    if (!response.ok || envelope.code !== '0') throw new Error(envelope.message)
    return envelope.data
  }

  private refreshOnce(): Promise<TokenPair> {
    if (!this.refreshPromise) {
      this.refreshPromise = fetch(`${this.baseUrl}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Request-Id': crypto.randomUUID() },
        body: JSON.stringify({ refreshToken: this.tokens.getRefreshToken() })
      })
        .then(async response => {
          const envelope = (await response.json()) as ApiResponse<TokenPair>
          if (!response.ok || envelope.code !== '0') throw new Error(envelope.message)
          this.tokens.setTokens(envelope.data)
          return envelope.data
        })
        .catch(error => {
          this.tokens.clear()
          throw error
        })
        .finally(() => { this.refreshPromise = null })
    }
    return this.refreshPromise
  }
}
