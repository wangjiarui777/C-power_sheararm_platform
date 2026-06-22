import request from '@/utils/request'

/**
 * 查询振动批次分页列表 → VibrationBatchController.page()
 */
export function listVibrationBatch(params) {
  return request({
    url: '/sensor/diagnosis/batch/page',
    method: 'get',
    params
  })
}

/**
 * 查询单个批次详情 → VibrationBatchController.detail()
 */
export function getVibrationBatch(batchId) {
  return request({
    url: '/sensor/diagnosis/batch/detail/' + batchId,
    method: 'get'
  })
}

/**
 * 新增振动批次 → VibrationBatchController.add()
 */
export function addVibrationBatch(data) {
  return request({
    url: '/sensor/diagnosis/batch',
    method: 'post',
    data
  })
}

/**
 * 修改振动批次 → VibrationBatchController.edit()
 */
export function updateVibrationBatch(data) {
  return request({
    url: '/sensor/diagnosis/batch',
    method: 'put',
    data
  })
}

/**
 * 删除振动批次 → VibrationBatchController.remove()
 */
export function delVibrationBatch(batchId) {
  return request({
    url: '/sensor/diagnosis/batch/' + batchId,
    method: 'delete'
  })
}
