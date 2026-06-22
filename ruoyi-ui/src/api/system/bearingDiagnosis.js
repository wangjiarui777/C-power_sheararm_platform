import request from '@/utils/request'

const gearBaseURL = process.env.VUE_APP_INFERENCE_SERVICE_URL || ''
const bearingBaseURL = process.env.VUE_APP_BEARING_SERVICE_URL || ''

// 推理接口超时时间（毫秒）：模型推理 + 频谱计算 + 指标提取
// GPU 上 <1s，CPU 上约 6-8s。设置 120s 以保证充裕余量。
const INFER_TIMEOUT = 120_000
const UPLOAD_TIMEOUT = 180_000

/**
 * 根据模型类型获取对应的服务 URL。
 * @param {'gear'|'bearing'} modelType
 * @returns {string}
 */
export function getServiceURL(modelType) {
  return modelType === 'bearing' ? bearingBaseURL : gearBaseURL
}

function requireServiceURL(serviceBaseURL) {
  const baseURL = serviceBaseURL || gearBaseURL
  if (!baseURL) {
    throw new Error('未配置推理服务地址，请设置 VUE_APP_INFERENCE_SERVICE_URL 或 VUE_APP_BEARING_SERVICE_URL')
  }
  return baseURL
}

export function getInferenceHealth(serviceBaseURL) {
  return request({
    baseURL: requireServiceURL(serviceBaseURL),
    url: '/health',
    method: 'get',
    timeout: 5000
  })
}

export function listMatFiles(serviceBaseURL) {
  return request({
    baseURL: requireServiceURL(serviceBaseURL),
    url: '/mat-files',
    method: 'get'
  })
}

/**
 * @param {string} [fileName]
 * @param {Object} [context] - { deviceCode, channelId, pointId }
 * @param {string} [serviceBaseURL] - 可选，覆盖默认齿轮服务 URL
 */
export function analyzeLatestFile(fileName, context = {}, serviceBaseURL) {
  const params = Object.assign(
    { _t: Date.now() },
    fileName ? { file_name: fileName } : {},
    context.deviceCode ? { device_code: context.deviceCode } : {},
    context.channelId ? { channel_id: context.channelId } : {},
    context.pointId ? { point_id: context.pointId } : {}
  )
  return request({
    baseURL: requireServiceURL(serviceBaseURL),
    url: '/analyze',
    method: 'get',
    timeout: INFER_TIMEOUT,
    params
  })
}

/**
 * @param {FormData} formData
 * @param {string} [serviceBaseURL]
 */
export function uploadDiagnosisToInferenceService(formData, serviceBaseURL) {
  return request({
    baseURL: requireServiceURL(serviceBaseURL),
    url: '/analyze/upload',
    method: 'post',
    timeout: UPLOAD_TIMEOUT,
    data: formData,
    // 必须清除全局默认的 application/json header，否则 FastAPI 返回 422
    transformRequest: [function(data, headers) {
      delete headers['Content-Type']
      return data
    }]
  })
}

/**
 * @param {Object} data - { filePath, analysisMode, filename, ... }
 * @param {string} [serviceBaseURL]
 */
export function inferWithFilePath(data, serviceBaseURL) {
  return request({
    baseURL: requireServiceURL(serviceBaseURL),
    url: '/infer',
    method: 'post',
    timeout: INFER_TIMEOUT,
    data
  })
}

export function fetchHistory(params, serviceBaseURL) {
  return request({
    baseURL: requireServiceURL(serviceBaseURL),
    url: '/history',
    method: 'get',
    params
  })
}

/**
 * @deprecated Compatibility alias for legacy vibration APIs.
 */
export function listBearingDiagnosisHistory(params) {
  return fetchHistory(params)
}

/**
 * @deprecated Compatibility alias for legacy vibration APIs.
 */
export function getBearingDiagnosisFftData(params) {
  return request({
    url: '/system/bearing/fft/latest',
    method: 'get',
    params
  })
}
