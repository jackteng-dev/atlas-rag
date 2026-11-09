import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
const apiOrigin = process.env.AKA_API_ORIGIN ?? 'http://127.0.0.1:18081'
const parsed = new URL(apiOrigin)
if (!['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)) throw new Error('Preview API origin must be loopback')
const proxy = { '/api': apiOrigin }
export default defineConfig({ plugins: [vue()], server: { proxy }, preview: { proxy } })
