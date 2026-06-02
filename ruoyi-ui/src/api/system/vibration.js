import request from '@/utils/request'
import { listBearingDiagnosisHistory, getBearingDiagnosisFftData } from './bearingDiagnosis'

/**
 * @deprecated Use `@/api/system/bearingDiagnosis` instead.
 * This file is kept as a backward-compatible shim during migration.
 */
export function listVibration(query) {
  return listBearingDiagnosisHistory(query)
}

/**
 * @deprecated Use `@/api/system/bearingDiagnosis` instead.
 */
export function getRecentVibration(params) {
  return getBearingDiagnosisFftData(params)
}

/**
 * @deprecated Legacy vibration CRUD endpoint retained for compatibility.
 */
export function uploadVibration(data) {
  return request({
    url: '/system/vibration/upload',
    method: 'post',
    data
  })
}

/**
 * @deprecated Legacy vibration detail endpoint retained for compatibility.
 */
export function getVibration(dataId) {
  return request({
    url: '/system/vibration/' + dataId,
    method: 'get'
  })
}

/**
 * @deprecated Legacy vibration create endpoint retained for compatibility.
 */
export function addVibration(data) {
  return request({
    url: '/system/vibration',
    method: 'post',
    data
  })
}

/**
 * @deprecated Legacy vibration update endpoint retained for compatibility.
 */
export function updateVibration(data) {
  return request({
    url: '/system/vibration',
    method: 'put',
    data
  })
}

/**
 * @deprecated Legacy vibration delete endpoint retained for compatibility.
 */
export function delVibration(dataId) {
  return request({
    url: '/system/vibration/' + dataId,
    method: 'delete'
  })
}
