import request from '@/utils/request'

export function listRealtimeDiagnosisPolicies(params) {
  return request({ url: '/sensor/diagnosis/realtime/policies', method: 'get', params })
}

export function getRealtimeDiagnosisStatus() {
  return request({ url: '/sensor/diagnosis/realtime/status', method: 'get' })
}

export function addRealtimeDiagnosisPolicy(data) {
  return request({ url: '/sensor/diagnosis/realtime/policies', method: 'post', data })
}

export function updateRealtimeDiagnosisPolicy(data) {
  return request({ url: '/sensor/diagnosis/realtime/policies', method: 'put', data })
}

export function removeRealtimeDiagnosisPolicies(ids) {
  return request({ url: `/sensor/diagnosis/realtime/policies/${ids}`, method: 'delete' })
}
