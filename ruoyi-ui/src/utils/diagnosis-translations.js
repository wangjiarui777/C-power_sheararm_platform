/**
 * 诊断分析界面 — 英文 → 中文翻译映射表
 * 用于将后端返回的英文字段值翻译为用户可读的中文
 */

// =============================================================================
// 诊断结果标签映射（后端可能返回 snake_case / camelCase / 全小写）
// =============================================================================
export const DIAGNOSIS_LABEL_MAP = {
  // 正常
  healthy: '正常',
  normal: '正常',
  health: '健康',

  // 轴承故障
  inner_race: '轴承内圈故障',
  outer_race: '轴承外圈故障',
  ball_fault: '滚珠故障',
  cage_fault: '保持架故障',
  bearing_fault: '轴承故障',
  bearing_inner: '轴承内圈故障',
  bearing_outer: '轴承外圈故障',
  bearing_ball: '轴承滚珠故障',

  // 齿轮故障
  gear_wear: '齿轮磨损',
  gear_broken: '齿轮断裂',
  gear_crack: '齿轮裂纹',
  gear_fault: '齿轮故障',
  gear_pitting: '齿轮点蚀',
  gear_scuffing: '齿轮胶合',

  // 点蚀/剥落
  single_pitting: '单点蚀',
  multi_pitting: '多点蚀',
  pitting: '点蚀',
  spalling: '剥落',

  // 不平衡/不对中/松动
  unbalance: '不平衡',
  imbalance: '不平衡',
  misalignment: '不对中',
  looseness: '松动',
  mechanical_looseness: '机械松动',

  // 共振/摩擦
  resonance: '共振',
  rub: '摩擦',
  rotor_rub: '转子摩擦',

  // 其他
  cracked_tooth: '断齿',
  wear: '磨损',
  corrosion: '腐蚀',
  fatigue: '疲劳',
  overload: '过载',
  unknown: '未知类别',
  other: '其他异常'
}

// =============================================================================
// 告警等级映射
// =============================================================================
export const ALARM_LEVEL_MAP = {
  normal: '正常',
  attention: '注意',
  warning: '预警',
  alarm: '告警',
  critical: '严重',
  high: '高',
  medium: '中',
  low: '低'
}

// =============================================================================
// 风险等级映射
// =============================================================================
export const RISK_LEVEL_MAP = {
  high: '高',
  medium: '中',
  low: '低',
  none: '无'
}

// =============================================================================
// 设备状态映射
// =============================================================================
export const DEVICE_STATUS_MAP = {
  success: '正常',
  normal: '正常',
  warning: '预警',
  danger: '告警',
  alarm: '告警',
  offline: '离线',
  online: '在线',
  running: '运行中',
  idle: '待机',
  stopped: '已停止',
  error: '异常',
  fault: '故障',
  maintenance: '维护中'
}

// =============================================================================
// 诊断模型名称映射
// =============================================================================
export const MODEL_TYPE_MAP = {
  gear: '齿轮诊断模型',
  bearing: '轴承诊断模型',
  motor: '电机诊断模型',
  pump: '泵诊断模型',
  fan: '风机诊断模型'
}

// =============================================================================
// 翻译工具函数
// =============================================================================

/**
 * 翻译诊断标签
 * @param {string} label - 后端返回的诊断标签
 * @returns {string} 中文翻译，如果无匹配则返回原值或"未知"
 */
export function translateDiagnosisLabel(label) {
  if (!label || label === '--') return '--'
  const key = String(label).trim().toLowerCase()
  // 如果已经是中文则直接返回
  if (/[一-龥]/.test(key)) return label
  return DIAGNOSIS_LABEL_MAP[key] || label
}

/**
 * 翻译告警等级
 * @param {string} level - 后端返回的告警等级
 * @returns {string} 中文翻译
 */
export function translateAlarmLevel(level) {
  if (!level || level === '--') return '--'
  const key = String(level).trim().toLowerCase()
  if (key === '高' || key === '中' || key === '低') return key
  return ALARM_LEVEL_MAP[key] || level
}

/**
 * 翻译风险等级
 * @param {string} level - 后端返回的风险等级
 * @returns {string} 中文翻译
 */
export function translateRiskLevel(level) {
  if (!level || level === '--') return '--'
  const key = String(level).trim().toLowerCase()
  if (key === '高' || key === '中' || key === '低') return key
  return RISK_LEVEL_MAP[key] || level
}

/**
 * 翻译设备状态
 * @param {string} status - 后端返回的设备状态
 * @returns {string} 中文翻译
 */
export function translateDeviceStatus(status) {
  if (!status || status === '--') return '--'
  const key = String(status).trim().toLowerCase()
  if (/[一-龥]/.test(key)) return status
  return DEVICE_STATUS_MAP[key] || status
}

/**
 * 翻译诊断模型类型
 * @param {string} type - 模型类型
 * @returns {string} 中文名称
 */
export function translateModelType(type) {
  if (!type) return '--'
  const key = String(type).trim().toLowerCase()
  return MODEL_TYPE_MAP[key] || type
}

/**
 * 通用翻译：尝试所有映射表找到匹配
 * @param {string} value - 待翻译的原始值
 * @returns {string} 翻译后的值，无匹配则返回原值
 */
export function translateAll(value) {
  if (!value || value === '--') return '--'
  const key = String(value).trim().toLowerCase()
  if (/[一-龥]/.test(key)) return value
  return DIAGNOSIS_LABEL_MAP[key]
    || ALARM_LEVEL_MAP[key]
    || RISK_LEVEL_MAP[key]
    || DEVICE_STATUS_MAP[key]
    || value
}

export default {
  DIAGNOSIS_LABEL_MAP,
  ALARM_LEVEL_MAP,
  RISK_LEVEL_MAP,
  DEVICE_STATUS_MAP,
  MODEL_TYPE_MAP,
  translateDiagnosisLabel,
  translateAlarmLevel,
  translateRiskLevel,
  translateDeviceStatus,
  translateModelType,
  translateAll
}
