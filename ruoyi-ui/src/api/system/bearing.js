import request from '@/utils/request'

export function listHistory(query) {
  return request({
    url: '/system/bearing/history/list',
    method: 'get',
    params: query
  })
}

export function getFftData(params) {
  return request({
    url: '/system/bearing/fft/latest',
    method: 'get',
    params
  })
}
