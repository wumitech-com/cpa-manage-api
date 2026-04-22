import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { execSync } from 'child_process'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(__dirname, '..')

// Vite 需要带 /src/main.ts 的入口 index；若上次已同步生产 index，先恢复模板
const template = path.join(root, 'index.template.html')
const indexHtml = path.join(root, 'index.html')
fs.copyFileSync(template, indexHtml)

execSync('npm run build', { cwd: root, stdio: 'inherit' })
execSync('node ./scripts/sync-dist-to-root.mjs', { cwd: root, stdio: 'inherit' })
