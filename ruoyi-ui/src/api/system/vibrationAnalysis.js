import request from '@/utils/request'
import { listBearingDiagnosisHistory, getBearingDiagnosisFftData } from './bearingDiagnosis'

/**
 * @deprecated Use `@/api/system/bearingDiagnosis` instead.
 */
export function listVibrationBatch(params) {
  return listBearingDiagnosisHistory(params)
}

/**
 * @deprecated Use `@/api/system/bearingDiagnosis` instead.
 */
export function getVibrationBatch(batchId) {
  return getBearingDiagnosisFftData({ batchId })
}

/**
 * @deprecated Legacy vibration batch create endpoint retained for compatibility.
 */
export function addVibrationBatch(data) {
  return request({
    url: '/sensor/vibration/batch',
    method: 'post',
    data
  })
}

/**
 * @deprecated Legacy vibration batch update endpoint retained for compatibility.
 */
export function updateVibrationBatch(data) {
  return request({
    url: '/sensor/vibration/batch',
    method: 'put',
    data
  })
}

/**
 * @deprecated Legacy vibration batch delete endpoint retained for compatibility.
 */
export function delVibrationBatch(batchId) {
  return request({
    url: '/sensor/vibration/batch/' + batchId,
    method: 'delete'
  })
}
