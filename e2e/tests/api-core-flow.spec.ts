import { test, expect } from '@playwright/test'

/**
 * API-level Playwright smoke (Stage 4 / P2-10).
 * Uses isolated e2e_tester account. Aligns with scripts/e2e-core-flow.ps1.
 */
test('core trade API flow', async ({ request }) => {
  const login = await request.post('/api/v1/auth/login/password', {
    data: {
      username: process.env.E2E_USERNAME || 'e2e_tester',
      password: process.env.E2E_PASSWORD || 'Geek@123'
    },
    headers: { 'X-Client-Type': 'WEB', 'X-Request-Id': `pw-login-${Date.now()}` }
  })
  expect(login.ok(), await login.text()).toBeTruthy()
  const token = (await login.json()).data.accessToken as string
  expect(token).toBeTruthy()
  const auth = {
    Authorization: `Bearer ${token}`,
    'X-Client-Type': 'WEB',
    'X-Request-Id': `pw-${Date.now()}`
  }

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
})
