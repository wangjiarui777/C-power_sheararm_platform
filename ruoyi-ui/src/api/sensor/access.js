import request from '@/utils/request'

export function listAcquisitionChannels(params) { return request({ url: '/sensor/access/channels/list', method: 'get', params }) }
export function getAcquisitionOptions() { return request({ url: '/sensor/access/channels/options', method: 'get' }) }
export function addAcquisitionChannel(data) { return request({ url: '/sensor/access/channels', method: 'post', data }) }
export function updateAcquisitionChannel(data) { return request({ url: '/sensor/access/channels', method: 'put', data }) }
export function removeAcquisitionChannel(ids) { return request({ url: `/sensor/access/channels/${ids}`, method: 'delete' }) }

export function listIngestFiles(params) { return request({ url: '/sensor/ingest/files/list', method: 'get', params }) }
export function associateIngestFile(id, data) { return request({ url: `/sensor/ingest/files/${id}/point`, method: 'put', data }) }
export function retryIngestFile(id) { return request({ url: `/sensor/ingest/files/${id}/retry`, method: 'post' }) }
