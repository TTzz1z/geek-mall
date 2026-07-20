import { ApiClient } from '@geek-mall/api-client'
import type { TokenPair } from '@geek-mall/api-types'

const tokenStore = {
  getAccessToken: () => localStorage.getItem('geek.accessToken'),
  getRefreshToken: () => localStorage.getItem('geek.refreshToken'),
  setTokens: (tokens: TokenPair) => {
    localStorage.setItem('geek.accessToken', tokens.accessToken)
    localStorage.setItem('geek.refreshToken', tokens.refreshToken)
  },
  clear: () => {
    localStorage.removeItem('geek.accessToken')
    localStorage.removeItem('geek.refreshToken')
  }
}

export const mallApi = new ApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080', tokenStore)
