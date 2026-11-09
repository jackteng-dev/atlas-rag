import { defineConfig } from '@playwright/test'
export default defineConfig({ testDir: './e2e', fullyParallel: false, workers: 1, retries: 0, reporter: 'list',
  use: { baseURL: process.env.AKA_BROWSER_BASE ?? 'http://127.0.0.1:4175', ...(process.env.AKA_CHROMIUM_PATH ? { launchOptions: { executablePath: process.env.AKA_CHROMIUM_PATH } } : {}), viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure' } })
