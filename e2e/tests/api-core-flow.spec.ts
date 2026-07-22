import { test, expect, type APIRequestContext } from '@playwright/test'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * API-level acceptance. No browser is launched.
 * Uses isolated e2e_tester account. Aligns with scripts/e2e-core-flow.ps1.
 */
const password = process.env.E2E_PASSWORD || 'Geek@123'

async function login(request: APIRequestContext, username: string, accountPassword = password) {
  const login = await request.post('/api/v1/auth/login/password', {
    data: {
      username,
      password: accountPassword
    },
    headers: { 'X-Client-Type': 'WEB', 'X-Request-Id': `pw-login-${username}-${Date.now()}` }
  })
  expect(login.ok(), await login.text()).toBeTruthy()
  const token = (await login.json()).data.accessToken as string
  expect(token).toBeTruthy()
  return {
    Authorization: `Bearer ${token}`,
    'X-Client-Type': 'WEB',
    'X-Request-Id': `pw-${username}-${Date.now()}`
  }
}

function localEnvValue(name: string): string | undefined {
  if (process.env[name] !== undefined) return process.env[name]?.trim()
  for (const file of [resolve(process.cwd(), '.env'), resolve(process.cwd(), '..', '.env')]) {
    if (!existsSync(file)) continue
    const match = readFileSync(file, 'utf8').match(new RegExp(`^\\s*${name}\\s*=\\s*([^\\r\\n#]*)`, 'm'))
    if (match) return match[1].trim().replace(/^['"]|['"]$/g, '')
  }
  return undefined
}

function remoteLlmConfigured(): boolean {
  const key = localEnvValue('LLM_API_KEY') || ''
  const enabled = (localEnvValue('LLM_ENABLED') || 'true').toLowerCase() !== 'false'
  return enabled && key.length > 0 && !/CHANGE_ME|YOUR_|example|placeholder/i.test(key)
}

test('core trade payment states and minimal aftersale loop', async ({ request }) => {
  const auth = await login(request, process.env.E2E_USERNAME || 'e2e_tester')

  const search = await request.get('/api/v1/search/products?keyword=Nova&page=1&pageSize=5', { headers: auth })
  expect(search.ok()).toBeTruthy()

  const cart = await request.post('/api/v1/trade/cart/items', {
    headers: auth,
    data: { skuId: 11001, quantity: 1 }
  })
  expect(cart.ok(), await cart.text()).toBeTruthy()

  const addresses = await request.get('/api/v1/user/addresses', { headers: auth })
  expect(addresses.ok()).toBeTruthy()
  const addrJson = await addresses.json()
  let addressId = addrJson.data?.[0]?.id as number | undefined
  if (!addressId) {
    const created = await request.post('/api/v1/user/addresses', {
      headers: auth,
      data: {
        receiver: 'E2E PW',
        mobile: '13900000008',
        regionCode: '310000',
        regionName: 'Shanghai',
        detail: 'Playwright Road 1',
        isDefault: true
      }
    })
    expect(created.ok(), await created.text()).toBeTruthy()
    addressId = (await created.json()).data.id
  }

  const preview = await request.post('/api/v1/trade/checkout/preview', {
    headers: auth,
    data: { addressId, deliveryMethod: 'EXPRESS' }
  })
  expect(preview.ok(), await preview.text()).toBeTruthy()
  const checkoutToken = (await preview.json()).data.checkoutToken as string
  expect(checkoutToken).toBeTruthy()

  const idem = `pw-${Date.now()}`
  const order = await request.post('/api/v1/trade/orders', {
    headers: { ...auth, 'Idempotency-Key': idem },
    data: { checkoutToken }
  })
  expect(order.ok(), await order.text()).toBeTruthy()
  const orderNo = (await order.json()).data.orderNo as string
  expect(orderNo).toBeTruthy()

  const pay = await request.post('/api/v1/payments', {
    headers: auth,
    data: { orderNo, channel: 'SIMULATED' }
  })
  expect(pay.ok(), await pay.text()).toBeTruthy()
  const paymentNo = (await pay.json()).data.paymentNo as string

  const sim = await request.post(`/api/v1/payments/${paymentNo}/simulate`, {
    headers: auth,
    data: {}
  })
  expect(sim.ok(), await sim.text()).toBeTruthy()

  // Re-query after the callback. A transport-level 200 from /simulate is not payment acceptance.
  const orderDetailResponse = await request.get(`/api/v1/trade/orders/${orderNo}`, { headers: auth })
  expect(orderDetailResponse.ok(), await orderDetailResponse.text()).toBeTruthy()
  const orderDetail = (await orderDetailResponse.json()).data
  expect(orderDetail.status).toBe('PAID')

  const paymentDetailResponse = await request.get(`/api/v1/payments/${paymentNo}`, { headers: auth })
  expect(paymentDetailResponse.ok(), await paymentDetailResponse.text()).toBeTruthy()
  const paymentDetail = (await paymentDetailResponse.json()).data
  expect(paymentDetail.status).toBe('SUCCESS')
  expect(orderDetail.payments.find((item: any) => item.paymentNo === paymentNo)?.status).toBe('SUCCESS')

  // DEDUCTED is the normal synchronous result. RESERVED is an explicitly accepted compensation
  // state: the order remains paid while DEDUCT_STOCK retry owns the pending deduction.
  expect(['DEDUCTED', 'RESERVED']).toContain(orderDetail.inventoryStatus)

  const subOrder = orderDetail.subOrders?.[0]
  const orderItem = subOrder?.items?.[0]
  expect(subOrder?.merchantId).toBeTruthy()
  expect(orderItem?.id).toBeTruthy()

  const apply = await request.post('/api/v1/aftersales', {
    headers: auth,
    data: {
      orderNo,
      orderItemId: orderItem.id,
      merchantId: subOrder.merchantId,
      type: 'RETURN_REFUND',
      quantity: 1,
      reason: 'Playwright API minimal aftersale loop'
    }
  })
  expect(apply.ok(), await apply.text()).toBeTruthy()
  const applied = (await apply.json()).data
  expect(applied.aftersaleNo).toBeTruthy()
  expect(applied.status).toBe('APPLIED')

  const merchantUsername = process.env.E2E_MERCHANT_USERNAME || 'merchant_a'
  const merchantAuth = await login(
    request,
    merchantUsername,
    process.env.E2E_MERCHANT_PASSWORD || password
  )

  const merchantOrdersResponse = await request.get('/api/v1/merchant/orders', { headers: merchantAuth })
  expect(merchantOrdersResponse.ok(), await merchantOrdersResponse.text()).toBeTruthy()
  const merchantOrders = (await merchantOrdersResponse.json()).data
  const ownedSubOrder = merchantOrders.find((item: any) => item.subOrderNo === subOrder.subOrderNo)
  expect(ownedSubOrder, 'owning merchant must see the real trade sub-order').toBeTruthy()
  expect(ownedSubOrder.items?.some((item: any) => item.id === orderItem.id)).toBe(true)

  const otherMerchantUsername = merchantUsername === 'merchant_b' ? 'merchant_a' : 'merchant_b'
  const otherMerchantAuth = await login(request, otherMerchantUsername, process.env.E2E_MERCHANT_PASSWORD || password)
  const otherOrdersResponse = await request.get('/api/v1/merchant/orders', { headers: otherMerchantAuth })
  expect(otherOrdersResponse.ok(), await otherOrdersResponse.text()).toBeTruthy()
  const otherOrders = (await otherOrdersResponse.json()).data
  expect(otherOrders.some((item: any) => item.subOrderNo === subOrder.subOrderNo)).toBe(false)

  const crossTenantDetail = await request.get(`/api/v1/merchant/orders/${subOrder.subOrderNo}`, {
    headers: otherMerchantAuth
  })
  expect(crossTenantDetail.status()).toBe(404)
  expect((await crossTenantDetail.json()).code).toBe('TR40410')

  const approve = await request.post(`/api/v1/merchant/aftersales/${applied.aftersaleNo}/approve`, {
    headers: merchantAuth
  })
  expect(approve.ok(), await approve.text()).toBeTruthy()
  expect((await approve.json()).data.status).toBe('APPROVED')

  const aftersaleDetail = await request.get(`/api/v1/aftersales/${applied.aftersaleNo}`, { headers: auth })
  expect(aftersaleDetail.ok(), await aftersaleDetail.text()).toBeTruthy()
  const aftersale = (await aftersaleDetail.json()).data
  expect(aftersale.status).toBe('APPROVED')
  expect(aftersale.logs.map((item: any) => item.toStatus)).toEqual(expect.arrayContaining(['APPLIED', 'APPROVED']))
})

test('AI acceptance validates tool result, model path, and grounded citation', async ({ request }) => {
  const auth = await login(request, process.env.E2E_USERNAME || 'e2e_tester')

  const ask = async (scene: string, message: string) => {
    const conversation = await request.post('/api/v1/ai/conversations', {
      headers: auth,
      data: { scene }
    })
    expect(conversation.ok(), await conversation.text()).toBeTruthy()
    const conversationNo = (await conversation.json()).data.conversationNo as string
    expect(conversationNo).toBeTruthy()
    const response = await request.post(`/api/v1/ai/conversations/${conversationNo}/messages`, {
      headers: auth,
      data: { scene, message }
    })
    expect(response.ok(), await response.text()).toBeTruthy()
    return (await response.json()).data
  }

  const guide = await ask('GUIDE', 'search products for an office laptop under 8000')
  const searchTool = guide.tools?.find(
    (tool: any) => tool.toolName === 'search_products' && tool.status === 'SUCCESS' && tool.result !== undefined
  )
  expect(searchTool, 'AI must expose a successful search_products tool.result').toBeTruthy()
  expect(searchTool.result).toBeDefined()

  if (remoteLlmConfigured()) {
    expect(guide.degraded, 'real LLM key is configured, so remote completion must succeed').toBe(false)
    expect(guide.model?.degraded).toBe(false)
    expect(guide.plannerMode).toBe('LLM')
  } else {
    expect(guide.degraded, 'without a real LLM key the local fallback must be observable').toBe(true)
    expect(guide.model?.degraded).toBe(true)
    expect(guide.plannerMode).toBe('RULE')
    expect(guide.fallbackReason).toBe('LLM_DISABLED')
  }

  const knowledge = await ask('PRODUCT_KNOWLEDGE', '极客本 Air 14 的内存是多少')
  expect(knowledge.noEvidence).toBe(false)
  expect(knowledge.citations?.length, 'knowledge-backed answer must retain citation evidence').toBeGreaterThan(0)
})
