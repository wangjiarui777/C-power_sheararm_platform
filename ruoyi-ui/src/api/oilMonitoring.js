import request from '@/utils/request'

export function getOilMonitoringDevices() {
  return request({
    url: '/sensor/oil-monitoring/devices',
    method: 'get'
  })
}

export function getOilMonitoringRealtime(deviceCode) {
  return request({
    url: `/sensor/oil-monitoring/realtime/${encodeURIComponent(deviceCode)}`,
    method: 'get'
  })
}

export function getOilMonitoringTrend(deviceCode, params) {
  return request({
    url: `/sensor/oil-monitoring/trend/${encodeURIComponent(deviceCode)}`,
    method: 'get',
    params
  })
}
