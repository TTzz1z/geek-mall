/** 后台状态码 → 中文展示 */
const STATUS_LABELS: Record<string, string> = {
  ENABLED: '已启用',
  DISABLED: '已停用',
  PENDING: '待处理',
  PENDING_REVIEW: '审核中',
  REVIEWING: '审核中',
  CONFIRMED: '已确认',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  ON_SALE: '在售',
  OFF_SALE: '下架',
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已下线',
  SUCCESS: '成功',
  FAILED: '失败',
  RUNNING: '进行中',
  PAUSED: '已暂停'
}

const STATUS_TONE: Record<string, 'neutral' | 'success' | 'warning' | 'danger' | 'info'> = {
  ENABLED: 'success',
  DISABLED: 'neutral',
  DRAFT: 'warning',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
  SUCCESS: 'success',
  FAILED: 'danger',
  RUNNING: 'success',
  PAUSED: 'warning',
  PENDING: 'warning',
  PENDING_REVIEW: 'info',
  CONFIRMED: 'success',
  REVIEWING: 'info',
  APPROVED: 'success',
  REJECTED: 'danger',
  ON_SALE: 'success',
  OFF_SALE: 'neutral'
}

export function statusLabel(code?: string | null) {
  if (!code) return '—'
  return STATUS_LABELS[code] || code
}

export function statusTone(code?: string | null) {
  if (!code) return 'neutral'
  return STATUS_TONE[code] || 'neutral'
}

export function formatAdminTime(value?: string | null) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
