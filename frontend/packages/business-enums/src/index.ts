export const ORDER_STATUS = {
  PENDING_PAYMENT: '待支付',
  PAID: '已支付',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
} as const

export const CLIENT_TYPE = ['MALL_WEB', 'ADMIN_WEB', 'MOBILE_H5', 'WECHAT_MINI', 'OPS_SCREEN'] as const
