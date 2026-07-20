import { defineStore } from 'pinia'
import { ref } from 'vue'
import { tradeApi } from '../api/trade'

export const useCartSummaryStore = defineStore('cart-summary', () => {
  const count = ref(0)
  async function refresh() { if (!localStorage.getItem('geek.accessToken')) { count.value = 0; return } try { count.value = (await tradeApi.cart()).itemCount } catch { count.value = 0 } }
  return { count, refresh }
})
