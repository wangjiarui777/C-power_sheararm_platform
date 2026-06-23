const { test, expect } = require('@playwright/test')

test('login page renders the production platform identity', async ({ page }) => {
  await page.goto('/login')
  await expect(page.locator('body')).toContainText('振动温度监测平台')
  await expect(page.getByPlaceholder('账号')).toBeVisible()
  await expect(page.getByPlaceholder('密码')).toBeVisible()
  await expect(page.getByRole('button', { name: /登\s*录/ })).toBeVisible()
})

test('protected route redirects an anonymous browser to login', async ({ page }) => {
  await page.goto('/monitor/diagnosis')
  await expect(page).toHaveURL(/\/login/)
})

test('authenticated PHM smoke path', async ({ page }) => {
  test.skip(!process.env.PHM_E2E_USER || !process.env.PHM_E2E_PASSWORD,
    'Set PHM_E2E_USER and PHM_E2E_PASSWORD for the full environment smoke test')
  await page.goto('/login')
  await page.getByPlaceholder('账号').fill(process.env.PHM_E2E_USER)
  await page.getByPlaceholder('密码').fill(process.env.PHM_E2E_PASSWORD)
  await page.getByRole('button', { name: /登录/ }).click()
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto('/monitor/diagnosis')
  await expect(page.locator('body')).toContainText(/诊断|推理/)
})
