import request from '@/utils/request'

const inferenceBaseURL = process.env.VUE_APP_INFERENCE_SERVICE_URL || 'http://127.0.0.1:5000'

// 推理接口超时时间（毫秒）：模型推理 + 频谱计算 + 指标提取 在 CPU 上约 6-8s，
// 上传额外需要传输时间，设置 120s 以保证充裕余量。
const INFER_TIMEOUT = 120_000
const UPLOAD_TIMEOUT = 180_000  // 上传比纯推理多出文件传输时间

export function getInferenceHealth() {
  return request({
    baseURL: inferenceBaseURL,
    url: '/health',
    method: 'get',
    timeout: 5000  // 健康检查 5s 足够
  })
}

export function listMatFiles() {
  return request({
    baseURL: inferenceBaseURL,
    url: '/mat-files',
    method: 'get'
  })
}

export function analyzeLatestFile(fileName, modelType = 'gear') {
  return request({
    baseURL: inferenceBaseURL,
    url: '/analyze',
    method: 'get',
    timeout: INFER_TIMEOUT,
    params: Object.assign({ _t: Date.now(), model_type: modelType }, fileName ? { file_name: fileName } : {})
  })
}

export function uploadDiagnosisToInferenceService(formData) {
  return request({
    baseURL: inferenceBaseURL,
    url: '/analyze/upload',
    method: 'post',
    timeout: UPLOAD_TIMEOUT,
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function inferWithFilePath(data) {
  return request({
    baseURL: inferenceBaseURL,
    url: '/infer',
    method: 'post',
    timeout: INFER_TIMEOUT,
    data
  })
}

export function fetchHistory(params) {
  return request({
    baseURL: inferenceBaseURL,
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
