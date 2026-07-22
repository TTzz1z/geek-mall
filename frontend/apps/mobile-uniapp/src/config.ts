/** H5 / 微信开发者工具默认 Gateway；可用 VITE_API_BASE_URL 覆盖 */
export function apiBase(): string {
  return (import.meta as ImportMeta & { env: Record<string, string> }).env?.VITE_API_BASE_URL || ''
}

/** 商品封面所在商城静态站；可用 VITE_MALL_ASSET_BASE 覆盖 */
export function mallAssetBase(): string {
  const env = (import.meta as ImportMeta & { env: Record<string, string> }).env || {}
  return String(env.VITE_MALL_ASSET_BASE || env.VITE_MALL_HOME_URL || 'http://127.0.0.1:8088').replace(/\/$/, '')
}
