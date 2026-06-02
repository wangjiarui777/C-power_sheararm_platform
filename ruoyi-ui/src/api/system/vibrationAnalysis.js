import request from '@/utils/request'
import axios from 'axios'
import { listBearingDiagnosisHistory, getBearingDiagnosisFftData } from './bearingDiagnosis'

/**
 * @deprecated Use `@/api/system/bearingDiagnosis` instead.
 * This file is kept as a backward-compatible shim during migration.
 */
export function mockVibrationAnalysis(data) {
  return request({
    url: '/sensor/vibration/analysis/mock',
    method: 'post',
    data
  })
}

/**
 * @deprecated Use `@/api/system/bearingDiagnosis` instead.
 */
export function analyzeVibration(data) {
  return request({
    url: '/sensor/vibration/analysis/analyze',
    method: 'post',
    data
  })
}

/**
 * Call Python sidecar service directly for .mat diagnosis.
 * @param {string} fileName file name without extension
 */
export function fetchMatFiles() {
  return axios({
    url: 'http://127.0.0.1:5000/mat-files',
    method: 'get'
  })
}

export function analyzeVibrationFromSidecar(fileName) {
  return axios({
    url: 'http://127.0.0.1:5000/analyze',
    method: 'get',
    params: {
      file_name: fileName
    }
  })
}

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
