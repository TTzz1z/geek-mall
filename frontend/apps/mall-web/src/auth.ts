export function isLoggedIn() {
  return !!localStorage.getItem('geek.accessToken')
}

export function authPath(
  mode: 'login' | 'register' = 'login',
  opts?: { redirect?: string; reason?: string }
) {
  const q = new URLSearchParams()
  if (opts?.redirect) q.set('redirect', opts.redirect)
  if (opts?.reason) q.set('reason', opts.reason)
  const qs = q.toString()
  return `/auth/${mode}${qs ? `?${qs}` : ''}`
}

export function requireAuth(
  router: { push: (to: string) => unknown },
  opts?: { mode?: 'login' | 'register'; redirect?: string; reason?: string }
) {
  if (isLoggedIn()) return true
  router.push(authPath(opts?.mode ?? 'register', {
    redirect: opts?.redirect ?? (typeof location !== 'undefined' ? location.pathname + location.search : '/'),
    reason: opts?.reason
  }))
  return false
}
