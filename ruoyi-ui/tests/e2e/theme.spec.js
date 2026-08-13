const { test, expect } = require('@playwright/test')

test('application exposes the fixed industrial design tokens', async ({ page }) => {
  await page.goto('/login')

  await expect(page.locator('html')).toHaveAttribute('data-theme', 'industrial')

  const theme = await page.evaluate(() => {
    const styles = getComputedStyle(document.documentElement)
    const formStyles = getComputedStyle(document.querySelector('.login-form'))
    return {
      canvas: styles.getPropertyValue('--color-canvas').trim(),
      surface: styles.getPropertyValue('--color-surface').trim(),
      text: styles.getPropertyValue('--color-text').trim(),
      accent: styles.getPropertyValue('--color-accent').trim(),
      opsAccent: styles.getPropertyValue('--ops-accent').trim(),
      chartText: styles.getPropertyValue('--chart-text').trim(),
      formRadius: formStyles.borderRadius
    }
  })

  expect(theme).toEqual({
    canvas: '#08111d',
    surface: '#111c30',
    text: '#e6edf3',
    accent: '#22d3ee',
    opsAccent: '#22d3ee',
    chartText: '#e6edf3',
    formRadius: '14px'
  })
})

test('legacy appearance settings cannot override the fixed theme', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('layout-setting', JSON.stringify({
      appearanceMode: 'light',
      sideTheme: 'theme-light',
      theme: '#F59E0B'
    }))
  })
  await page.goto('/login')

  const result = await page.evaluate(() => {
    const styles = getComputedStyle(document.documentElement)
    return {
      mode: document.documentElement.getAttribute('data-theme'),
      accent: styles.getPropertyValue('--color-accent').trim()
    }
  })

  expect(result).toEqual({ mode: 'industrial', accent: '#22d3ee' })
})

test('login remains readable without horizontal overflow on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/login')

  const layout = await page.evaluate(() => {
    const form = document.querySelector('.login-form').getBoundingClientRect()
    return {
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      left: Math.round(form.left),
      right: Math.round(innerWidth - form.right)
    }
  })

  expect(layout.overflow).toBe(false)
  expect(layout.left).toBeGreaterThanOrEqual(12)
  expect(layout.right).toBeGreaterThanOrEqual(12)
})
