import request from '@/utils/request'

export function getDeviceCluster(params) {
  return request({ url: '/phm/devices/cluster', method: 'get', params })
}

export function getDeviceBrain(deviceId) {
  return request({ url: `/phm/devices/${deviceId}/brain`, method: 'get' })
}

export function toggleDeviceFavorite(deviceId) {
  return request({ url: `/phm/devices/${deviceId}/favorite`, method: 'post' })
}

export function listPhmDevices(params) {
  return request({ url: '/phm/devices', method: 'get', params })
}

export function savePhmDevice(data) {
  return request({ url: '/phm/devices', method: data.id ? 'put' : 'post', data })
}

export function deletePhmDevice(deviceId) {
  return request({ url: `/phm/devices/${deviceId}`, method: 'delete' })
}

export function listMeasurePoints(params) {
  return request({ url: '/phm/points', method: 'get', params })
}

export function saveMeasurePoint(data) {
  return request({ url: '/phm/points', method: data.id ? 'put' : 'post', data })
}

export function deleteMeasurePoint(pointId) {
  return request({ url: `/phm/points/${pointId}`, method: 'delete' })
}

export function getFeatureTrend(pointId, params) {
  return request({ url: `/phm/points/${pointId}/features/trend`, method: 'get', params })
}

export function listFeatures() {
  return request({ url: '/phm/features', method: 'get' })
}

export function saveFeature(data) {
  return request({ url: '/phm/features', method: data.id ? 'put' : 'post', data })
}

export function deleteFeature(featureId) {
  return request({ url: `/phm/features/${featureId}`, method: 'delete' })
}

export function listAlarms(params) {
  return request({ url: '/phm/alarms', method: 'get', params })
}

export function getAlarm(alarmId) {
  return request({ url: `/phm/alarms/${alarmId}`, method: 'get' })
}

export function handleAlarm(alarmId, data) {
  return request({ url: `/phm/alarms/${alarmId}/handle`, method: 'post', data })
}

export function ignoreAlarm(alarmId, data) {
  return request({ url: `/phm/alarms/${alarmId}/ignore`, method: 'post', data })
}

export function acknowledgeAlarm(alarmId, data) {
  return request({ url: `/phm/alarms/${alarmId}/acknowledge`, method: 'post', data })
}

export function assignAlarm(alarmId, data) {
  return request({ url: `/phm/alarms/${alarmId}/assign`, method: 'post', data })
}

export function closeAlarm(alarmId, data) {
  return request({ url: `/phm/alarms/${alarmId}/close`, method: 'post', data })
}

export function getAlarmTimeline(alarmId) {
  return request({ url: `/phm/alarms/${alarmId}/timeline`, method: 'get' })
}

export function listAlarmRules() {
  return request({ url: '/phm/alarm-rules', method: 'get' })
}

export function saveAlarmRule(data) {
  return request({ url: '/phm/alarm-rules', method: data.id ? 'put' : 'post', data })
}

export function deleteAlarmRule(ruleId) {
  return request({ url: `/phm/alarm-rules/${ruleId}`, method: 'delete' })
}

export function listDeviceEvents(params) {
  return request({ url: '/phm/device-events', method: 'get', params })
}

export function saveDeviceEvent(data) {
  return request({ url: '/phm/device-events', method: data.id ? 'put' : 'post', data })
}

export function deleteDeviceEvent(eventId) {
  return request({ url: `/phm/device-events/${eventId}`, method: 'delete' })
}

export function getRealtimeReport(params) {
  return request({ url: '/phm/reports/realtime', method: 'get', params })
}

export function getHistoryReport(params) {
  return request({ url: '/phm/reports/history', method: 'get', params })
}

export function listServiceReports(params) {
  return request({ url: '/phm/reports/service', method: 'get', params })
}

export function saveServiceReport(data) {
  return request({ url: '/phm/reports/service', method: 'post', data })
}

export function listAttachments(params) {
  return request({ url: '/phm/attachments', method: 'get', params })
}

export function getAttachmentContent(attachmentId) {
  return request({
    url: `/phm/attachments/${attachmentId}/content`,
    method: 'get',
    responseType: 'blob'
  })
}

export function deleteAttachment(attachmentId) {
  return request({ url: `/phm/attachments/${attachmentId}`, method: 'delete' })
}

export function listSystemConfig() {
  return request({ url: '/phm/system-config', method: 'get' })
}

export function saveSystemConfig(data) {
  return request({ url: '/phm/system-config', method: data.id ? 'put' : 'post', data })
}
