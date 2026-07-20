import { existsSync, cpSync, rmSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const h5 = join(process.cwd(), 'dist', 'build', 'h5')
if (!existsSync(h5)) {
  console.error('H5 build missing:', h5)
  process.exit(1)
}

const staging = join(process.cwd(), '.h5-nginx-staging')
rmSync(staging, { recursive: true, force: true })
cpSync(h5, staging, { recursive: true })

const dist = join(process.cwd(), 'dist')
for (const name of readdirSync(dist)) {
  if (name === 'build') continue
  rmSync(join(dist, name), { recursive: true, force: true })
}
for (const name of readdirSync(staging)) {
  const from = join(staging, name)
  const to = join(dist, name)
  if (statSync(from).isDirectory()) {
    mkdirSync(to, { recursive: true })
    cpSync(from, to, { recursive: true })
  } else {
    cpSync(from, to)
  }
}
rmSync(staging, { recursive: true, force: true })
console.log('Flattened H5 assets into dist/ for Docker nginx (kept dist/build for mp-weixin).')
