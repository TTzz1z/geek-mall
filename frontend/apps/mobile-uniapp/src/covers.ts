import { mallAssetBase } from './config'

/**
 * Resolve product cover for H5 / 微信小程序.
 * API returns relative `/covers/...` which only exists on mall-web (:8088).
 * Mini-program must use an absolute URL, otherwise images fail and fall back to logo.
 */
export function productCover(spuId?: number | null, coverUrl?: string | null): string {
  const base = mallAssetBase()
  const id = Number(spuId || 0)
  if (id > 0 && id < 100000) return `${base}/covers/geek-spu-${id}.jpg`
  const raw = String(coverUrl || '').trim()
  if (!raw) return `${base}/covers/geek-spu-10001.jpg`
  if (/^https?:\/\//i.test(raw)) return raw
  if (raw.startsWith('/')) return `${base}${raw}`
  return raw
}
