function themeColor(name, fallback) {
  if (typeof window === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return value || fallback
}

export const MONITOR_COLORS = {
  get bg() { return themeColor('--chart-bg', '#0F1720') },
  get panel() { return themeColor('--chart-panel', '#17212B') },
  get table() { return themeColor('--chart-table', '#101820') },
  primary: '#2F80ED',
  normal: '#2EAD6B',
  warning: '#E6A23C',
  abnormal: '#D84C4C',
  get text() { return themeColor('--chart-text', '#E6EDF3') },
  get muted() { return themeColor('--chart-muted', '#8EA0B5') },
  get border() { return themeColor('--chart-border', '#263645') }
}

export const VIBRATION_POSITIONS = [
  '驱动端水平振动',
  '驱动端垂直振动',
  '驱动端轴向振动',
  '非驱动端水平振动',
  '非驱动端垂直振动',
  '非驱动端轴向振动',
  '减速机输入端',
  '减速机输出端'
]

export const TEMPERATURE_POSITIONS = [
  '驱动端轴承温度',
  '驱动端电机温度',
  '非驱动端轴承温度',
  '非驱动端电机温度',
  '减速机输入端温度',
  '减速机输出端温度',
  '油箱温度',
  '环境温度'
]

export function pickField(obj, keys, fallback) {
  if (!obj) return fallback
  for (let i = 0; i < keys.length; i++) {
    const value = obj[keys[i]]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

export function toNumber(value, fallback) {
  const num = Number(value)
  return Number.isNaN(num) ? fallback : num
}

export function formatMetric(value, digits = 2) {
  if (value === null || value === undefined || value === '') return '--'
  const num = Number(value)
  return Number.isNaN(num) ? value : num.toFixed(digits)
}

export function formatDateTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = n => String(n).padStart(2, '0')
  const ms = String(date.getMilliseconds()).padStart(3, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${ms}`
}

export function formatChartTime(value) {
  if (!value && value !== 0) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = n => String(n).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function calcHealth(rms, temperature, roc) {
  const rmsNum = Number(rms)
  const tempNum = Number(temperature)
  const rocNum = Number(roc)
  const rmsScore = Number.isNaN(rmsNum) ? 0.72 : Math.max(0, Math.min(1, 1 - rmsNum / 8))
  const tempScore = Number.isNaN(tempNum) ? 0.78 : Math.max(0, Math.min(1, 1 - Math.max(0, tempNum - 55) / 40))
  const rocScore = Number.isNaN(rocNum) ? 0.9 : Math.max(0, Math.min(1, 1 - Math.max(0, rocNum) / 4))
  return Math.round((rmsScore * 0.52 + tempScore * 0.34 + rocScore * 0.14) * 100)
}

export function resolveStatus(record, thresholds = {}) {
  const health = toNumber(record.health, calcHealth(record.rms, record.temperature, record.roc))
  const rms = toNumber(record.rms, null)
  const temperature = toNumber(record.temperature, null)
  const risk = record.riskLevel || record.alarmLevel
  const alarm = record.alarm || risk === '高' || risk === 'alarm' || risk === 'high'
  const warning = record.warning || risk === '中' || risk === 'warning' || risk === 'attention' || risk === 'medium'

  if (alarm || (thresholds.rmsAlarm && rms >= thresholds.rmsAlarm) || (thresholds.tempAlarm && temperature >= thresholds.tempAlarm) || health < 60) {
    return { status: 'abnormal', alarmLevel: 'high', health }
  }
  if (warning || (thresholds.rmsWarn && rms >= thresholds.rmsWarn) || (thresholds.tempWarn && temperature >= thresholds.tempWarn) || health < 80) {
    return { status: 'warning', alarmLevel: 'medium', health }
  }
  return { status: 'normal', alarmLevel: 'low', health }
}

export function statusText(status) {
  if (status === 'abnormal') return '异常'
  if (status === 'warning') return '预警'
  return '正常'
}

export function alarmLevelText(level) {
  if (level === 'high') return '高'
  if (level === 'medium') return '中'
  return '低'
}

export function statusTagType(status) {
  if (status === 'abnormal') return 'danger'
  if (status === 'warning') return 'warning'
  return 'success'
}

export function healthColor(health) {
  if (health >= 80) return MONITOR_COLORS.normal
  if (health >= 60) return MONITOR_COLORS.warning
  return MONITOR_COLORS.abnormal
}

export function buildChannel(id, type = 'vibration') {
  const positionName = type === 'temperature' ? TEMPERATURE_POSITIONS[id - 1] : VIBRATION_POSITIONS[id - 1]
  return {
    channelId: id,
    deviceCode: `MOTOR-${String(id).padStart(2, '0')}`,
    positionName: positionName || `通道${id}`,
    sampleTime: '',
    status: 'normal',
    alarmLevel: 'low',
    health: 100,
    rms: null,
    peak: null,
    peakToPeak: null,
    freqPeak: null,
    temperature: null,
    ma: null,
    roc: null,
    wave: [],
    fft: [],
    records: [],
    alarms: []
  }
}

export function buildChannels(type = 'vibration') {
  return Array.from({ length: 8 }, (_, index) => buildChannel(index + 1, type))
}

export function normalizeMonitorPayload(payload, fallback = {}) {
  if (!payload) return null
  if (payload.type === 'health_status' || payload.type === 'file_list' || payload.type === 'pong') return null
  if (payload.type === 'auto_analysis' && payload.success === false) return null

  const data = payload.data || payload.message || payload
  if (!data || typeof data !== 'object') return null

  const channelId = toNumber(pickField(data, ['channelId', 'channel', 'channelNo', 'id'], fallback.channelId || 1), 1)
  const deviceCode = pickField(data, ['deviceCode', 'deviceId', 'deviceName'], fallback.deviceCode || `MOTOR-${String(channelId).padStart(2, '0')}`)
  const sampleTime = pickField(data, ['sampleTime', 'collectionTime', 'collectTime', 'createTime', 'time'], new Date().toISOString())
  const rms = toNumber(pickField(data, ['rms', 'latestRms', 'vibrationValue', 'speed', 'value'], fallback.rms), fallback.rms)
  const peak = toNumber(pickField(data, ['peak', 'latestPeak', 'peakValue'], fallback.peak), fallback.peak)
  const peakToPeak = toNumber(pickField(data, ['peakToPeak', 'displacement', 'pp'], fallback.peakToPeak), fallback.peakToPeak)
  const freqPeak = toNumber(pickField(data, ['freqPeak', 'frequencyPeak', 'primaryFrequency'], fallback.freqPeak), fallback.freqPeak)
  const temperature = toNumber(pickField(data, ['temperatureValue', 'temperature', 'temp'], fallback.temperature), fallback.temperature)
  const ma = toNumber(pickField(data, ['maValue', 'ma'], fallback.ma), fallback.ma)
  const roc = toNumber(pickField(data, ['rocValue', 'roc'], fallback.roc), fallback.roc)
  const health = toNumber(pickField(data, ['health', 'healthIndex'], fallback.health), fallback.health)
  const riskLevel = pickField(data, ['riskLevel', 'alarmLevel'], fallback.alarmLevel)

  const timeAxis = Array.isArray(data.time_axis) ? data.time_axis : []
  const waveform = Array.isArray(data.waveform) ? data.waveform : (Array.isArray(data.time_data) ? data.time_data : [])
  const wave = Array.isArray(data.wave) && data.wave.length
    ? data.wave.map((item, index) => normalizeWavePoint(item, index, temperature))
    : waveform.map((value, index) => ({
      time: timeAxis[index] == null ? index : timeAxis[index],
      vibration: toNumber(value, 0),
      temperature: temperature == null ? 0 : temperature
    }))

  const freqAxis = Array.isArray(data.freq_axis) ? data.freq_axis : (Array.isArray(data.frequencyAxis) ? data.frequencyAxis : [])
  const spectrum = Array.isArray(data.freq_data) ? data.freq_data : (Array.isArray(data.spectrum) ? data.spectrum : [])
  const fft = Array.isArray(data.fft) && data.fft.length
    ? data.fft.map((item, index) => normalizeFftPoint(item, index))
    : spectrum.map((value, index) => ({
      freq: toNumber(freqAxis[index], index + 1),
      amp: toNumber(value, 0)
    }))

  const status = resolveStatus({
    rms,
    temperature,
    roc,
    health,
    alarm: data.alarm,
    warning: data.warning,
    riskLevel
  })

  return {
    channelId,
    deviceCode,
    positionName: pickField(data, ['positionName', 'pointName', 'name'], fallback.positionName || `通道${channelId}`),
    sampleTime,
    status: status.status,
    alarmLevel: status.alarmLevel,
    health: status.health,
    rms,
    peak,
    peakToPeak,
    freqPeak,
    temperature,
    ma,
    roc,
    wave,
    fft,
    alarmMessage: pickField(data, ['alarmMessage', 'warningMessage', 'diagnosisResult', 'diagnosisName'], '')
  }
}

function normalizeWavePoint(item, index, temperature) {
  if (typeof item === 'number') {
    return { time: index, vibration: item, temperature: temperature == null ? 0 : temperature }
  }
  return {
    time: pickField(item, ['time', 'sampleTime', 'collectionTime'], index),
    vibration: toNumber(pickField(item, ['vibration', 'vibrationValue', 'rms', 'value'], 0), 0),
    temperature: toNumber(pickField(item, ['temperature', 'temperatureValue', 'temp'], temperature == null ? 0 : temperature), temperature == null ? 0 : temperature)
  }
}

function normalizeFftPoint(item, index) {
  if (typeof item === 'number') return { freq: index + 1, amp: item }
  return {
    freq: toNumber(pickField(item, ['freq', 'frequency', 'x'], index + 1), index + 1),
    amp: toNumber(pickField(item, ['amp', 'amplitude', 'value', 'y'], 0), 0)
  }
}

function baseChartOption(emptyText) {
  return {
    backgroundColor: 'transparent',
    animationDuration: 260,
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(16, 24, 32, 0.96)',
      borderColor: MONITOR_COLORS.border,
      textStyle: { color: MONITOR_COLORS.text }
    },
    grid: { left: 54, right: 36, top: 42, bottom: 36 },
    graphic: emptyText ? [{
      type: 'text',
      left: 'center',
      top: 'middle',
      style: { text: emptyText, fill: MONITOR_COLORS.muted, font: '13px sans-serif' }
    }] : []
  }
}

export function buildRealtimeTrendOption(points, options = {}) {
  const rows = points || []
  const empty = rows.length ? '' : '等待实时采样数据'
  const option = baseChartOption(empty)
  option.legend = { top: 4, right: 8, data: ['振动速度', '温度'], textStyle: { color: MONITOR_COLORS.muted } }
  option.xAxis = { type: 'category', boundaryGap: false, data: rows.map(item => formatChartTime(item.time)), axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, axisLabel: { color: MONITOR_COLORS.muted } }
  option.yAxis = [
    { type: 'value', name: 'mm/s', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { lineStyle: { color: '#22313F' } }, axisLabel: { color: MONITOR_COLORS.muted } },
    { type: 'value', name: '℃', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { show: false }, axisLabel: { color: MONITOR_COLORS.muted } }
  ]
  option.series = [
    {
      name: '振动速度',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: rows.map(item => toNumber(item.vibration, 0)),
      lineStyle: { width: 2, color: MONITOR_COLORS.primary },
      areaStyle: { color: 'rgba(47,128,237,0.14)' },
      markLine: buildThresholdLines(options.rmsWarn || 4, options.rmsAlarm || 6)
    },
    {
      name: '温度',
      type: 'line',
      smooth: true,
      showSymbol: false,
      yAxisIndex: 1,
      data: rows.map(item => toNumber(item.temperature, 0)),
      lineStyle: { width: 2, color: '#32B8C6' }
    }
  ]
  return option
}

export function buildTemperatureTrendOption(points, mode = 'trend') {
  const rows = points || []
  const empty = rows.length ? '' : '等待温度采样数据'
  const option = baseChartOption(empty)
  option.legend = { top: 4, right: 8, data: mode === 'coupling' ? ['温度', '振动速度'] : ['原始温度', 'MA 平滑', 'ROC'], textStyle: { color: MONITOR_COLORS.muted } }
  option.xAxis = { type: 'category', boundaryGap: false, data: rows.map(item => formatChartTime(item.collectionTime || item.time)), axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, axisLabel: { color: MONITOR_COLORS.muted } }
  option.yAxis = mode === 'coupling'
    ? [
      { type: 'value', name: '℃', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { lineStyle: { color: '#22313F' } }, axisLabel: { color: MONITOR_COLORS.muted } },
      { type: 'value', name: 'mm/s', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { show: false }, axisLabel: { color: MONITOR_COLORS.muted } }
    ]
    : [
      { type: 'value', name: '℃', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { lineStyle: { color: '#22313F' } }, axisLabel: { color: MONITOR_COLORS.muted } },
      { type: 'value', name: '℃/min', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { show: false }, axisLabel: { color: MONITOR_COLORS.muted } }
    ]
  option.series = mode === 'coupling'
    ? [
      { name: '温度', type: 'line', smooth: true, showSymbol: false, data: rows.map(item => toNumber(item.temperatureValue, 0)), lineStyle: { width: 2, color: '#32B8C6' }, markLine: buildThresholdLines(65, 75) },
      { name: '振动速度', type: 'line', smooth: true, showSymbol: false, yAxisIndex: 1, data: rows.map(item => toNumber(item.vibrationValue, 0)), lineStyle: { width: 2, color: MONITOR_COLORS.warning } }
    ]
    : [
      { name: '原始温度', type: 'line', smooth: true, showSymbol: false, data: rows.map(item => toNumber(item.temperatureValue, 0)), lineStyle: { width: 1.5, color: 'rgba(50,184,198,0.62)' } },
      { name: 'MA 平滑', type: 'line', smooth: true, showSymbol: false, data: rows.map(item => toNumber(item.maValue, 0)), lineStyle: { width: 2.5, color: '#32B8C6' }, markLine: buildThresholdLines(65, 75) },
      { name: 'ROC', type: 'bar', yAxisIndex: 1, data: rows.map(item => toNumber(item.rocValue, 0)), itemStyle: { color: 'rgba(230,162,60,0.78)' } }
    ]
  return option
}

export function buildFftOption(points) {
  const rows = points && points.length ? points : []
  const option = baseChartOption(rows.length ? '' : '等待频谱数据')
  option.xAxis = { type: 'category', name: 'Hz', data: rows.map(item => item.freq), axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, axisLabel: { color: MONITOR_COLORS.muted } }
  option.yAxis = { type: 'value', name: '幅值', scale: true, axisLine: { lineStyle: { color: MONITOR_COLORS.border } }, splitLine: { lineStyle: { color: '#22313F' } }, axisLabel: { color: MONITOR_COLORS.muted } }
  option.series = [{
    name: 'FFT',
    type: 'bar',
    barMaxWidth: 14,
    data: rows.map(item => item.amp),
    itemStyle: { color: 'rgba(46,173,107,0.86)' }
  }]
  return option
}

function buildThresholdLines(warn, alarm) {
  return {
    symbol: 'none',
    label: { color: MONITOR_COLORS.muted },
    lineStyle: { type: 'dashed' },
    data: [
      { yAxis: warn, name: '预警线', lineStyle: { color: MONITOR_COLORS.warning } },
      { yAxis: alarm, name: '报警线', lineStyle: { color: MONITOR_COLORS.abnormal } }
    ]
  }
}
