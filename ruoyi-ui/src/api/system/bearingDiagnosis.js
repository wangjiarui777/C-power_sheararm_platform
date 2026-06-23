import request from '@/utils/request'

const INFER_TIMEOUT = 120_000
const UPLOAD_TIMEOUT = 180_000

export function getServiceURL(modelType) {
  return modelType === 'bearing' ? 'bearing' : 'gear'
}

export function getInferenceHealth(modelType = 'gear') {
  return request({
    url: '/sensor/diagnosis/inference/health',
    method: 'get',
    timeout: 5000,
    params: { modelType }
  })
}

export function listMatFiles(modelType = 'gear') {
  return request({
    url: '/sensor/diagnosis/inference/files',
    method: 'get',
    params: { modelType }
  })
}

export function analyzeLatestFile(fileName, context = {}, modelType = 'gear') {
  return request({
    url: '/sensor/diagnosis/inference/analyze',
    method: 'get',
    timeout: INFER_TIMEOUT,
    params: {
      fileName: fileName || undefined,
      modelType,
      deviceCode: context.deviceCode || undefined,
      channelId: context.channelId || undefined,
      pointId: context.pointId || undefined,
      _t: Date.now()
    }
  })
}

export function uploadDiagnosisToInferenceService(formData, modelType = 'gear') {
  if (!formData.has('model_type')) {
    formData.append('model_type', modelType)
  }
  return request({
    url: '/sensor/diagnosis/inference/upload',
    method: 'post',
    timeout: UPLOAD_TIMEOUT,
    data: formData,
    transformRequest: [function(data, headers) {
      delete headers['Content-Type']
      return data
    }]
  })
}

export function inferWithFilePath(data, modelType = 'gear') {
  return request({
    url: '/sensor/diagnosis/receiver/analyze',
    method: 'post',
    timeout: INFER_TIMEOUT,
    data: Object.assign({}, data, { modelType })
  })
}

export function fetchHistory(params) {
  return request({
    url: '/sensor/diagnosis/inference/history',
    method: 'get',
    params
  })
}

export function listBearingDiagnosisHistory(params) {
  return fetchHistory(params)
}

export function getBearingDiagnosisFftData(params) {
  return request({
    url: '/system/bearing/fft/latest',
    method: 'get',
    params
  })
}
