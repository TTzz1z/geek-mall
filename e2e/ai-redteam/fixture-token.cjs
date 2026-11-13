const { createHmac } = require('node:crypto')

const secret = process.env.JWT_SECRET
if (!secret || Buffer.byteLength(secret) < 32) throw new Error('Set a test JWT_SECRET of at least 32 bytes')
const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url')
const now = Math.floor(Date.now() / 1000)
const header = encode({ alg: 'HS256', typ: 'JWT' })
const claims = encode({
  iss: 'geek-mall', sub: '4', aud: ['USER'], iat: now, exp: now + 1800,
  username: 'redteam-fixture', merchantId: 0, shopId: 0, roles: ['USER'], permissions: []
})
const signature = createHmac('sha256', secret).update(`${header}.${claims}`).digest('base64url')
process.stdout.write(`${header}.${claims}.${signature}`)
