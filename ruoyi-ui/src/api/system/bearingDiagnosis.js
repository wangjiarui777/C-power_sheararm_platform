import request from '@/utils/request'

export function listBearingDiagnosisHistory(query) {
  return request({
    url: '/sensor/vibration/batch/page',
    method: 'get',
    params: query
  })
}

export function getBearingDiagnosisFftData(params) {
  return request({
    url: '/sensor/vibration/analysis/tdengine',
    method: 'get',
    params
  })
}

export function getLatestBearingDiagnosis(params) {
  return request({
    url: '/sensor/vibration/diagnosis/latest',
    method: 'get',
    params
  })
}

export function listBearingDevices(params) {
  return request({
    url: '/sensor/vibration/device/list',
    method: 'get',
    params
  })
}

export function getBearingDiagnosisTrend(params) {
  return request({
    url: '/sensor/vibration/diagnosis/trend',
    method: 'get',
    params
  })
}

export function analyzeReceiverFile(data) {
  return request({
    url: '/sensor/vibration/receiver/analyze',
    method: 'post',
    data
  })
}
