import { ApiClient } from '@geek-mall/api-client'
import type { TokenPair } from '@geek-mall/api-types'
import { useSessionStore } from './session'
const tokenStore={getAccessToken:()=>localStorage.getItem('geek.admin.accessToken'),getRefreshToken:()=>localStorage.getItem('geek.admin.refreshToken'),setTokens:(t:TokenPair)=>useSessionStore().save(t),clear:()=>useSessionStore().clear()}
export const adminApi=new ApiClient(import.meta.env.VITE_API_BASE_URL??'http://localhost:8080',tokenStore)
