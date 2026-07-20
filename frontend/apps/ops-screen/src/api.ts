import { ApiClient } from '@geek-mall/api-client'
import type { TokenPair } from '@geek-mall/api-types'

const tokenStore = {
  getAccessToken: () => localStorage.getItem('geek.ops.accessToken'),
  getRefreshToken: () => localStorage.getItem('geek.ops.refreshToken'),
  setTokens: (tokens: TokenPair) => {
    localStorage.setItem('geek.ops.accessToken', tokens.accessToken)
    localStorage.setItem('geek.ops.refreshToken', tokens.refreshToken)
  },
  clear: () => {
    localStorage.removeItem('geek.ops.accessToken')
    localStorage.removeItem('geek.ops.refreshToken')
  }
}

export const opsApi = new ApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080', tokenStore)
