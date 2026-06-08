import request from '@/utils/request'

const inferenceBaseURL = process.env.VUE_APP_INFERENCE_SERVICE_URL || 'http://127.0.0.1:5001'

export function getInferenceHealth() {
  return request({
    baseURL: inferenceBaseURL,
    url: '/health',
    method: 'get'
  })
}

export function listMatFiles() {
  return request({
    baseURL: inferenceBaseURL,
    url: '/mat-files',
    method: 'get'
  })
}

export function analyzeLatestFile(fileName) {
  return request({
    baseURL: inferenceBaseURL,
    url: '/analyze',
    method: 'get',
    params: Object.assign({ _t: Date.now() }, fileName ? { file_name: fileName } : {})
  })
}

export function uploadDiagnosisToInferenceService(formData) {
  return request({
    baseURL: inferenceBaseURL,
    url: '/analyze/upload',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function inferWithFilePath(data) {
  return request({
    baseURL: inferenceBaseURL,
    url: '/infer',
    method: 'post',
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
