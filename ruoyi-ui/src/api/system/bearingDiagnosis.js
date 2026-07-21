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

export function listDiagnosisDevices() {
  return request({
    url: '/sensor/diagnosis/device/list',
    method: 'get'
  })
}

export function getDiagnosisOptions() {
  return request({
    url: '/sensor/diagnosis/options',
    method: 'get'
  })
}

export function listMatFiles(modelType = 'gear', deviceCode, pointId) {
  return request({
    url: '/sensor/diagnosis/inference/files',
    method: 'get',
    params: { modelType, deviceCode: deviceCode || undefined, pointId: pointId || undefined }
  })
}

export function createDiagnosisBatch(data) {
  return request({
    url: '/sensor/diagnosis/batches',
    method: 'post',
    timeout: 30000,
    data
  })
}

export function getDiagnosisBatch(id) {
  return request({
    url: `/sensor/diagnosis/batches/${id}`,
    method: 'get',
    timeout: 10000
  })
}

export function retryDiagnosisBatch(id) {
  return request({
    url: `/sensor/diagnosis/batches/${id}/retry`,
    method: 'post',
    timeout: 30000
  })
}

export function analyzeLatestFile(attachmentId, context = {}, modelType = 'gear', modelVersion) {
  return request({
    url: '/sensor/diagnosis/inference/analyze',
    method: 'get',
    timeout: INFER_TIMEOUT,
    params: {
      attachmentId: attachmentId || undefined,
      modelType,
      modelVersion: modelVersion || undefined,
      deviceCode: context.deviceCode || undefined,
      channelId: context.channelId || undefined,
      pointId: context.pointId || undefined,
      _t: Date.now()
    }
  })
}

export function uploadDiagnosisToInferenceService(formData, modelType = 'gear', modelVersion) {
  if (!formData.has('model_type')) {
    formData.append('model_type', modelType)
  }
  if (modelVersion && !formData.has('model_version')) {
    formData.append('model_version', modelVersion)
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

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export async function inferWithAttachment(data, modelType = 'gear') {
  const created = await request({
    url: '/sensor/diagnosis/tasks',
    method: 'post',
    timeout: INFER_TIMEOUT,
    data: Object.assign({}, data, {
      modelType,
      idempotencyKey: data.idempotencyKey ||
        `${modelType}:${data.modelVersion || ''}:${data.deviceCode || ''}:${data.pointId || ''}:${data.attachmentId || ''}:${Date.now()}`
    })
  })
  const task = created.data || created
  const deadline = Date.now() + INFER_TIMEOUT
  while (Date.now() < deadline) {
    const response = await request({
      url: `/sensor/diagnosis/tasks/${task.id}`,
      method: 'get',
      timeout: 10000
    })
    const current = response.data || response
    if (current.status === 'SUCCEEDED') {
      return current.resultJson ? JSON.parse(current.resultJson) : {}
    }
    if (current.status === 'FAILED' || current.status === 'INVALID') {
      throw new Error(current.errorMessage || `诊断任务${current.status}`)
    }
    await sleep(1000)
  }
  throw new Error('诊断任务执行超时')
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
