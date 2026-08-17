const QUALITY_LABELS = {
  GOOD: '数据正常',
  STALE: '数据延迟',
  BAD: '质量异常',
  OFFLINE: '数据离线',
  UNKNOWN: '等待接入'
}

const DEVICE_STATUS_LABELS = {
  NORMAL: '运行正常',
  RUNNING: '运行中',
  ONLINE: '在线',
  WARNING: '预警',
  ALARM: '故障告警',
  FAULT: '故障',
  OFFLINE: '离线',
  UNKNOWN: '待接入'
}

const FILE_STATUS_LABELS = {
  RECEIVING: '接收中',
  UNMAPPED: '待映射',
  VALIDATING: '校验中',
  ACCEPTED: '已接收',
  DUPLICATE: '重复文件',
  REJECTED: '校验拒绝',
  FAILED: '接收失败'
}

const WORKFLOW_STATUS_LABELS = {
  NEW: '待确认',
  ACKNOWLEDGED: '已确认',
  ASSIGNED: '已指派',
  HANDLED: '已处理',
  IGNORED: '已忽略',
  CLOSED: '已关闭'
}

const CONDITION_STATUS_LABELS = {
  ACTIVE: '告警中',
  RECOVERED: '已恢复',
  RECOVERING: '恢复中',
  CLEARED: '已解除'
}

const MODEL_STATUS_LABELS = {
  DRAFT: '草稿',
  VALIDATED: '已验证',
  ACTIVE: '已发布',
  PUBLISHED: '已发布',
  RETIRED: '已退役'
}

const OIL_STATUS_LABELS = {
  NORMAL: '正常',
  GOOD: '正常',
  WARNING: '建议复核',
  ALARM: '异常告警',
  OFFLINE: '设备离线',
  UNKNOWN: '等待接入'
}

function keyOf(value, fallback) {
  const key = String(value == null || value === '' ? fallback : value).trim().toUpperCase()
  return key || fallback
}

export function qualityText(value) {
  const key = keyOf(value, 'OFFLINE')
  return QUALITY_LABELS[key] || String(value || '数据离线')
}

export function qualityClass(value) {
  return keyOf(value, 'OFFLINE').toLowerCase()
}

export function deviceStatusText(value) {
  const key = keyOf(value, 'UNKNOWN')
  return DEVICE_STATUS_LABELS[key] || String(value || '待接入')
}

export function fileStatusText(value) {
  const key = keyOf(value, 'UNKNOWN')
  return FILE_STATUS_LABELS[key] || String(value || '未知状态')
}

export function workflowStatusText(value) {
  const key = keyOf(value, 'NEW')
  return WORKFLOW_STATUS_LABELS[key] || String(value || '待确认')
}

export function conditionStatusText(value) {
  const key = keyOf(value, 'ACTIVE')
  return CONDITION_STATUS_LABELS[key] || String(value || '告警中')
}

export function modelStatusText(value) {
  const key = keyOf(value, 'DRAFT')
  return MODEL_STATUS_LABELS[key] || String(value || '未标注')
}

export function oilStatusText(value) {
  const key = keyOf(value, 'UNKNOWN')
  return OIL_STATUS_LABELS[key] || String(value || '等待接入')
}

export function riskLevelText(value) {
  const key = keyOf(value, '')
  return {
    LOW: '低风险',
    MEDIUM: '中风险',
    HIGH: '高风险',
    DANGER: '高风险',
    WARNING: '中风险',
    NORMAL: '正常'
  }[key] || String(value || '--')
}

export const industrialLabelMaps = {
  quality: QUALITY_LABELS,
  deviceStatus: DEVICE_STATUS_LABELS,
  fileStatus: FILE_STATUS_LABELS,
  workflowStatus: WORKFLOW_STATUS_LABELS,
  conditionStatus: CONDITION_STATUS_LABELS,
  modelStatus: MODEL_STATUS_LABELS,
  oilStatus: OIL_STATUS_LABELS
}
