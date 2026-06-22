function themeColor(name, fallback) {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export const industrialChartTheme = {
  get text() { return themeColor('--chart-text', '#d8e4ef') },
  get muted() { return themeColor('--chart-muted', '#8ea4ba') },
  get axis() { return themeColor('--chart-muted', '#7f95ac') },
  get border() { return themeColor('--chart-border', '#31465b') },
  get grid() { return themeColor('--chart-border', 'rgba(94, 122, 150, 0.22)') },
  tooltipBg: '#09111d',
  get tooltipBorder() { return themeColor('--chart-border', '#31465b') },
  vibration: '#38bdf8',
  temperature: '#14b8a6',
  warning: '#f59e0b',
  danger: '#ef4444',
  event: '#8b5cf6'
}
