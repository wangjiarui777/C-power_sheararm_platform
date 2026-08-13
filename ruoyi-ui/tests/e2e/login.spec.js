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
  await page.goto('/analysis-toolkit/bearing-diagnosis')
  await expect(page.locator('body')).toContainText('测点诊断总览')
  await expect(page.getByPlaceholder('搜索部门、设备或测点')).toBeVisible()

  const pointCards = page.locator('.point-card')
  if (await pointCards.count()) {
    await pointCards.first().click()
    await expect(page).toHaveURL(/view=detail/)
    await expect(page.locator('body')).toContainText('诊断上下文')
    await expect(page.getByRole('button', { name: /测点总览/ })).toBeVisible()
    await page.getByRole('button', { name: /测点总览/ }).click()
    await expect(page.locator('body')).toContainText('测点诊断总览')
  } else {
    await expect(page.locator('body')).toContainText('暂无可诊断的振动测点')
  }
})

test('authenticated oil monitoring reserved page', async ({ page }) => {
  test.skip(!process.env.PHM_E2E_USER || !process.env.PHM_E2E_PASSWORD,
    'Set PHM_E2E_USER and PHM_E2E_PASSWORD for the full environment smoke test')
  await page.goto('/login')
  await page.getByPlaceholder('账号').fill(process.env.PHM_E2E_USER)
  await page.getByPlaceholder('密码').fill(process.env.PHM_E2E_PASSWORD)
  await page.getByRole('button', { name: /登录/ }).click()
  await expect(page).not.toHaveURL(/\/login/)

  await page.goto('/monitoring-center/oil')
  await expect(page.locator('body')).toContainText('在线油液监测')
  await expect(page.locator('body')).toContainText('油液数据服务尚未接入')
  await expect(page.locator('body')).toContainText('污染度')
  await expect(page.locator('body')).toContainText('铁磁颗粒')
  await expect(page.locator('body')).toContainText('设备状态')

  await page.getByRole('tab', { name: '历史趋势' }).click()
  await expect(page.locator('body')).toContainText('历史接口尚未启用')
  await expect(page.getByText('24小时', { exact: true })).toBeVisible()
  await expect(page.getByText('监测指标', { exact: true })).toBeVisible()
})
