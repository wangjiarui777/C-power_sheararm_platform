import request from '@/utils/request'

// Query overview data for monitoring dashboard
export function getMonitoringOverview() {
  return request({
    url: '/sensor/monitoring/overview',
    method: 'get'
  })
}
