import { test, expect, type APIRequestContext } from '@playwright/test'
import path from 'node:path'
import { mkdirSync } from 'node:fs'

// Runs only against an ISOLATED platform instance with real local models:
//   ATLAS_PLATFORM_E2E=1 AKA_BROWSER_BASE=http://127.0.0.1:18081 npx playwright test e2e/platform.spec.ts
test.skip(!process.env.ATLAS_PLATFORM_E2E, 'platform e2e runs only when ATLAS_PLATFORM_E2E=1 (needs a running platform profile)')
test.setTimeout(240_000)
const screenshots = path.resolve(process.cwd(), process.env.AKA_SCREENSHOTS ?? '../work/screenshots/platform')
mkdirSync(screenshots, { recursive: true })

async function csrfHeaders(api: APIRequestContext) {
  await api.get('/api/v2/auth/csrf')
  const token = (await api.storageState()).cookies.find(c => c.name === 'XSRF-TOKEN')?.value ?? ''
  return { 'X-XSRF-TOKEN': decodeURIComponent(token) }
}

test('register, upload, ask with citations, verify source, isolation and agent run through the UI', async ({ page, browser }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  const stamp = Date.now().toString(36)
  const email = `e2e-${stamp}@example.test`, password = `E2e-platform-${stamp}-Pw`

  // Seed through the real API (self-registration is enabled on the dev instance only).
  const api = page.request
  let headers = await csrfHeaders(api)
  expect((await api.post('/api/v2/auth/register', { headers, data: { email, password, displayName: 'E2E 使用者', orgName: 'E2E ' + stamp } })).ok()).toBeTruthy()
  headers = await csrfHeaders(api)
  const me = await (await api.get('/api/v2/auth/me')).json()
  const orgId = me.memberships[0].orgId
  const ws = (await (await api.get(`/api/v2/orgs/${orgId}/workspaces`)).json())[0].id
  const kb = await (await api.post(`/api/v2/orgs/${orgId}/knowledge-bases`, { headers, data: { workspaceId: ws, name: '人資規章', description: '', visibility: 'ORG' } })).json()
  await api.post('/api/v2/auth/logout', { headers })

  // Sign in through the UI.
  await page.goto('/login')
  await page.getByLabel('電子郵件').fill(email)
  await page.getByLabel('密碼').fill(password)
  await page.getByRole('button', { name: /登入/ }).click()
  await expect(page).toHaveURL(new RegExp(`/o/${orgId}/`))
  await page.screenshot({ path: path.join(screenshots, '01-overview.png'), fullPage: true })

  // Upload through the knowledge base page.
  await page.goto(`/o/${orgId}/knowledge-bases/${kb.id}`)
  const fileInput = page.locator('input[type=file]').first()
  await fileInput.setInputFiles({ name: 'leave.md', mimeType: 'text/markdown',
    buffer: Buffer.from('# 請假規定\n\n特休假須於七日前提出申請，並經直屬主管核准。\n\n## 病假\n\n病假三日以上須附醫師證明。\n') })
  await page.getByRole('button', { name: '上傳', exact: true }).click()
  await expect(page.getByText('leave', { exact: false }).first()).toBeVisible({ timeout: 30_000 })
  await expect.poll(async () => (await (await page.request.get(`/api/v2/orgs/${orgId}/documents?kbId=${kb.id}`)).json())[0]?.status, { timeout: 120_000 }).toBe('READY')
  await page.reload()
  await page.screenshot({ path: path.join(screenshots, '02-knowledge-base.png'), fullPage: true })

  // Ask in chat and open the citation.
  await page.goto(`/o/${orgId}/chat`)
  await page.getByRole('button', { name: '新增對話' }).click()
  await page.getByLabel('人資規章').check()
  await page.getByRole('button', { name: '建立對話' }).click()
  await page.getByLabel('向知識庫提問').fill('特休假要幾天前申請？')
  await page.getByRole('button', { name: '傳送問題' }).click()
  const chip = page.getByRole('button', { name: /^引用 1：/ })
  await expect(chip).toBeVisible({ timeout: 180_000 })
  await expect(page.getByLabel('對話訊息')).toContainText('七日')
  await chip.click()
  await page.getByRole('button', { name: '檢視來源原文' }).click()
  await expect(page.getByText('特休假須於七日前提出申請', { exact: false }).last()).toBeVisible()
  await page.screenshot({ path: path.join(screenshots, '03-chat-citation.png'), fullPage: true })

  // A second, unrelated user cannot open the first org's pages (404 → redirected / not found).
  const other = await browser.newContext({ baseURL: page.url().split('/o/')[0] })
  const otherPage = await other.newPage()
  const otherApi = otherPage.request
  let h2 = await csrfHeaders(otherApi)
  await otherApi.post('/api/v2/auth/register', { headers: h2, data: { email: `e2e-other-${stamp}@example.test`, password: `Second-tenant-${stamp}-Pw`, displayName: 'Other', orgName: 'Other ' + stamp } })
  expect((await otherApi.get(`/api/v2/orgs/${orgId}/documents`)).status()).toBe(404)
  await otherPage.goto(`/o/${orgId}/documents`)
  await expect(otherPage).not.toHaveURL(new RegExp(`/o/${orgId}/documents`))
  await other.close()

  // Agent run from the UI (read-only tool template).
  headers = await csrfHeaders(api)
  const tpl = (await (await api.get(`/api/v2/orgs/${orgId}/agent-templates`)).json()).find((t: { template: string }) => t.template === 'READONLY_TOOL')
  const agent = await (await api.post(`/api/v2/orgs/${orgId}/agents`, { headers, data: { name: '料號查詢', template: 'READONLY_TOOL', description: '', definition: tpl.definition } })).json()
  await page.goto(`/o/${orgId}/agents/${agent.id}`)
  await page.getByLabel('料號').selectOption('MCU-100')
  await page.getByRole('button', { name: /執行/ }).first().click()
  await expect(page).toHaveURL(/\/runs\//, { timeout: 30_000 })
  const runId = page.url().split('/runs/')[1]
  await expect.poll(async () => (await (await page.request.get(`/api/v2/orgs/${orgId}/runs/${runId}`)).json()).status, { timeout: 180_000 }).toBe('SUCCEEDED')
  await page.reload()
  await expect(page.getByText('執行中')).toHaveCount(0)
  await page.screenshot({ path: path.join(screenshots, '04-agent-run.png'), fullPage: true })

  // Mobile layout renders without horizontal scroll.
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto(`/o/${orgId}/documents`)
  await expect(page.getByText('leave').first()).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(1)
  await page.screenshot({ path: path.join(screenshots, '05-mobile-documents.png'), fullPage: true })
  expect(errors).toEqual([])
})
