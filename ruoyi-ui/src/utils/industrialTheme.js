function themeColor(name, fallback) {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export const industrialChartTheme = {
  get text() { return themeColor('--chart-text', '#e6edf3') },
  get muted() { return themeColor('--chart-muted', '#8ea0b5') },
  get axis() { return themeColor('--chart-muted', '#8ea0b5') },
  get border() { return themeColor('--chart-border', '#263645') },
  get grid() { return themeColor('--chart-border', '#263645') },
  tooltipBg: '#08111d',
  get tooltipBorder() { return themeColor('--chart-border', '#263645') },
  vibration: '#38bdf8',
  temperature: '#14b8a6',
  oil: '#f0b44d',
  moisture: '#38bdf8',
  particle: '#8b5cf6',
  warning: '#f59e0b',
  danger: '#ef4444',
  event: '#8b5cf6'
}
