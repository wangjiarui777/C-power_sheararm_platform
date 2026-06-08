import request from '@/utils/request'
import axios from 'axios'
import { listBearingDiagnosisHistory, getBearingDiagnosisFftData } from './bearingDiagnosis'

const SIDE_CAR_BASE_URL = process.env.VUE_APP_VIBRATION_SIDECAR_URL || 'http://127.0.0.1:5001'

export function fetchMatFiles() {
  return axios({
    url: `${SIDE_CAR_BASE_URL}/mat-files`,
    method: 'get'
  })
}

export function analyzeVibrationFromSidecar(fileName) {
  return axios({
    url: `${SIDE_CAR_BASE_URL}/analyze`,
    method: 'get',
    params: { file_name: fileName }
  })
}

/**
 * Analyze an uploaded `.mat` file directly through the Python sidecar.
 * @param {File} file
 */
export function analyzeUploadedMatFromSidecar(file) {
  const formData = new FormData()
  formData.append('file', file)
  return axios({
    url: `${SIDE_CAR_BASE_URL}/analyze/upload`,
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
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
