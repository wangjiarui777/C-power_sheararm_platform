import request from '@/utils/request'

// Query temperature data list
export function listTemperature(query) {
  return request({
    url: '/system/temperature/list',
    method: 'get',
    params: query
  })
}

// Query recent temperature data for chart
export function getRecentTemperature() {
  return request({
    url: '/system/temperature/recent',
    method: 'get'
  })
}

// Query temperature data details
export function getTemperature(dataId) {
  return request({
    url: '/system/temperature/' + dataId,
    method: 'get'
  })
}

// Add temperature data
export function addTemperature(data) {
  return request({
    url: '/system/temperature',
    method: 'post',
    data: data
  })
}

// Update temperature data
export function updateTemperature(data) {
  return request({
    url: '/system/temperature',
    method: 'put',
    data: data
  })
}

// Delete temperature data
export function delTemperature(dataId) {
  return request({
    url: '/system/temperature/' + dataId,
    method: 'delete'
  })
}
