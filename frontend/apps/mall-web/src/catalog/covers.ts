/** Prefer local mall covers so cart/detail never depend on picsum/unsplash. */
export function productCover(spuId?: number | null, fallbackUrl?: string | null) {
  const id = Number(spuId || 0)
  if (id > 0 && id < 100000) return `/covers/geek-spu-${id}.jpg`
  if (fallbackUrl && /^https?:\/\//.test(fallbackUrl) && !/picsum\.photos|unsplash\.com/.test(fallbackUrl)) {
    return fallbackUrl
  }
  return fallbackUrl || '/covers/geek-spu-10001.jpg'
}
