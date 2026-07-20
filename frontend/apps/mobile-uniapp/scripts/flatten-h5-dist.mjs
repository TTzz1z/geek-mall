import { existsSync, cpSync, rmSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

function rmSafe(path) {
  try {
    rmSync(path, { recursive: true, force: true })
  } catch (err) {
    // Windows may lock dist/dev while HBuilder / antivirus holds handles.
    if (err && (err.code === 'EPERM' || err.code === 'EBUSY' || err.code === 'ENOTEMPTY')) {
      console.warn('skip remove (locked):', path, err.code)
      return
    }
    throw err
  }
}

const h5 = join(process.cwd(), 'dist', 'build', 'h5')
if (!existsSync(h5)) {
  console.error('H5 build missing:', h5)
  process.exit(1)
}

const staging = join(process.cwd(), '.h5-nginx-staging')
rmSafe(staging)
cpSync(h5, staging, { recursive: true })

const dist = join(process.cwd(), 'dist')
for (const name of readdirSync(dist)) {
  if (name === 'build') continue
  rmSafe(join(dist, name))
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
rmSafe(staging)
console.log('Flattened H5 assets into dist/ for Docker nginx (kept dist/build for mp-weixin).')
