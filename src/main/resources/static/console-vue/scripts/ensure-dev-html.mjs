import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(__dirname, '..')
const template = path.join(root, 'index.template.html')
const target = path.join(root, 'index.html')

fs.copyFileSync(template, target)
