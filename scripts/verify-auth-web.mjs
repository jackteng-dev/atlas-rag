// Real packaged frontend/API, disposable synthetic accounts, no inference or browser API stubs.
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
import { chromium, expect } from '../frontend/node_modules/@playwright/test/index.mjs'
const instance = JSON.parse(await fs.readFile(process.argv[2], 'utf8'))
const profiles = JSON.parse(await fs.readFile(instance.profilesFile, 'utf8'))
if (!['127.0.0.1', 'localhost', '[::1]'].includes(new URL(instance.base).hostname)) throw new Error('Local QA instance only')
const browser = await chromium.launch({ executablePath: process.env.AKA_CHROMIUM_PATH ?? '/usr/bin/chromium', headless: true })
const checked = []
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const response = await page.goto(instance.base, { waitUntil: 'networkidle' })
  assert.equal(response.status(), 200)
  await expect(page.getByRole('heading', { name: '登入文件知識庫' })).toBeVisible()
  assert.equal((await page.request.get(instance.base+'/api/documents')).status(), 401)
  for (const profile of ['reader', 'hr', 'outsider']) {
    const account = profiles[profile]
    await page.getByLabel('帳號', { exact: true }).fill(account.username)
    await page.getByLabel('密碼', { exact: true }).fill(account.password)
    await page.getByRole('button', { name: '登入', exact: true }).click()
    await expect(page.locator('.identity')).toContainText(account.username)
    const list = page.locator('.document-list')
    await expect(list).toContainText('Atlas 混合檢索 RAG 架構')
    const text = await list.innerText()
    assert.equal(text.includes('HR 補助規範'), profile === 'hr')
    assert.ok(!text.includes('過期'))
    const authorization = 'Basic '+Buffer.from(`${account.username}:${account.password}`).toString('base64')
    const privateDoc = await page.request.get(instance.base+'/api/documents/hr-benefits', { headers: { Authorization: authorization } })
    assert.equal(privateDoc.status(), profile === 'hr' ? 200 : 404)
    checked.push({ profile, packagedHtmlStatus: 200, privateDocumentStatus: privateDoc.status(), privateListVisible: profile === 'hr' })
    await page.getByRole('button', { name: /登出/ }).click()
    await expect(page.getByRole('heading', { name: '登入文件知識庫' })).toBeVisible()
    await expect(page.locator('.document-choice')).toHaveCount(0)
  }
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  assert.deepEqual(errors, [])
  const output = `work/auth-web-verification-${Date.now()}.json`
  await fs.writeFile(output, JSON.stringify({ checkedAt: new Date().toISOString(), packagedFrontend: true, anonymousApiStatus: 401,
    checked, logoutClearsDocuments: true, mobileOverflow: false, pageErrors: errors, noBrowserApiStubs: true }, null, 2)+'\n', { flag: 'wx' })
  console.log(JSON.stringify({ result: 'PASS', output, checked }))
} finally { await browser.close() }
