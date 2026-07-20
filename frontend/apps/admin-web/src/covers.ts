/** Mall JPEG covers are served by mall-web (:8088), not admin-web (:8089). */
export function mallAssetOrigin(): string {
  return String(import.meta.env.VITE_MALL_HOME_URL || 'http://127.0.0.1:8088').replace(/\/$/, '')
}

/**
 * Resolve a product cover URL for display in the admin console.
 * Relative `/covers/...` paths must be rewritten to the mall origin.
 */
export function resolveCover(spuId?: number | null, coverUrl?: string | null): string {
  const id = Number(spuId || 0)
  const origin = mallAssetOrigin()
  if (id > 0 && id < 100000) return `${origin}/covers/geek-spu-${id}.jpg`
  const raw = String(coverUrl || '').trim()
  if (!raw) return ''
  if (raw.startsWith('blob:')) return raw
  if (/^https?:\/\//i.test(raw)) return raw
  if (raw.startsWith('/')) return `${origin}${raw}`
  return raw
}

export function coverPlaceholder(): string {
  return resolveCover(10004, '/covers/geek-spu-10004.jpg')
}
