import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthPrincipal, TokenPair } from '@geek-mall/api-types'

export const useSessionStore = defineStore('session', () => {
  const principal = ref<AuthPrincipal | null>(JSON.parse(localStorage.getItem('geek.admin.principal') || 'null'))
  const permissions = computed(() => principal.value?.permissions ?? [])
  function save(tokens: TokenPair) {
    localStorage.setItem('geek.admin.accessToken', tokens.accessToken); localStorage.setItem('geek.admin.refreshToken', tokens.refreshToken)
    localStorage.setItem('geek.admin.principal', JSON.stringify(tokens.principal)); principal.value = tokens.principal
  }
  function clear() { localStorage.removeItem('geek.admin.accessToken'); localStorage.removeItem('geek.admin.refreshToken'); localStorage.removeItem('geek.admin.principal'); principal.value = null }
  return { principal, permissions, save, clear }
})
