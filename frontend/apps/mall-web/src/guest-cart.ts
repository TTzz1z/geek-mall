export interface GuestCartItem { skuId: number; quantity: number }
const key = 'geek.guestCart'
export function readGuestCart(): GuestCartItem[] { try { return JSON.parse(localStorage.getItem(key) ?? '[]') as GuestCartItem[] } catch { return [] } }
export function addGuestCart(skuId: number, quantity = 1) { const items = readGuestCart(); const old = items.find(item => item.skuId === skuId); if (old) old.quantity += quantity; else items.push({ skuId, quantity }); localStorage.setItem(key, JSON.stringify(items)) }
export function clearGuestCart() { localStorage.removeItem(key) }
