/** Map marketing slugs used in nav to platform category ids (product_category). */
export const CATEGORY_SLUG_TO_ID: Record<string, number> = {
  phones: 200,
  computers: 100,
  audio: 500,
  wearables: 600,
  components: 400,
  charging: 410,
  peripherals: 420,
  monitors: 300,
  accessories: 400
}

export function resolveCategoryId(raw: string | string[] | undefined | null): number | null {
  if (raw == null) return null
  const key = String(Array.isArray(raw) ? raw[0] : raw).trim()
  if (!key || key === 'all') return null
  if (/^\d+$/.test(key)) return Number(key)
  return CATEGORY_SLUG_TO_ID[key] ?? null
}

export function sellerLabelOf(p: { shopType?: string | null; sellerLabel?: string | null }): string {
  if (p.sellerLabel) return p.sellerLabel
  switch (p.shopType) {
    case 'SELF_OPERATED':
      return '平台自营'
    case 'OFFICIAL_FLAGSHIP':
      return '官方旗舰店'
    default:
      return '第三方店铺'
  }
}

export function shopTypeLabel(shopType?: string | null): string {
  switch (shopType) {
    case 'SELF_OPERATED':
      return '平台自营'
    case 'OFFICIAL_FLAGSHIP':
      return '官方旗舰店'
    default:
      return '第三方店铺'
  }
}
