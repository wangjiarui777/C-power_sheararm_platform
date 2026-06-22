import request from '@/utils/request'

export function getAssetTree() {
  return request({ url: '/sensor/monitoring/assets/tree', method: 'get' })
}

export function getWorkbench(params) {
  return request({ url: '/sensor/monitoring/workbench', method: 'get', params })
}

export function getPointTrend(pointId, params) {
  return request({ url: `/sensor/monitoring/points/${pointId}/trend`, method: 'get', params })
}

export function getVibrationAnalysis(pointId, params) {
  return request({ url: `/sensor/monitoring/points/${pointId}/vibration-analysis`, method: 'get', params })
}

export function getTemperatureAnalysis(pointId, params) {
  return request({ url: `/sensor/monitoring/points/${pointId}/temperature-analysis`, method: 'get', params })
}
