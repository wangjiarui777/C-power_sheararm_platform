import request from '@/utils/request'

export function listProjects() { return request({ url: '/tool/lowcode/projects', method: 'get' }) }
export function getProject(id) { return request({ url: `/tool/lowcode/projects/${id}`, method: 'get' }) }
export function createProject(data) { return request({ url: '/tool/lowcode/projects', method: 'post', data }) }
export function saveDraft(id, data) { return request({ url: `/tool/lowcode/projects/${id}/draft`, method: 'put', data }) }
export function validateProject(id) { return request({ url: `/tool/lowcode/projects/${id}/validate`, method: 'post' }) }
export function diffProject(id) { return request({ url: `/tool/lowcode/projects/${id}/diff`, method: 'get' }) }
export function publishProject(id) { return request({ url: `/tool/lowcode/projects/${id}/publish`, method: 'post' }) }
export function rollbackProject(id, versionId) { return request({ url: `/tool/lowcode/projects/${id}/rollback/${versionId}`, method: 'post' }) }
export function inspectDatabase(id) { return request({ url: `/tool/lowcode/projects/${id}/database/inspect`, method: 'get' }) }
export function previewDdl(id, data) { return request({ url: `/tool/lowcode/projects/${id}/database/ddl-preview`, method: 'post', data }) }
export function exportUrl(id) { return `${process.env.VUE_APP_BASE_API}/tool/lowcode/projects/${id}/export` }
export function listConnectors() { return request({ url: '/tool/lowcode/connectors', method: 'get' }) }
export function saveConnector(data) { return request({ url: '/tool/lowcode/connectors', method: 'put', data }) }

export function getRuntimeSchema(appCode) { return request({ url: `/lowcode/runtime/${appCode}/schema`, method: 'get' }) }
export function listRuntimeRecords(appCode, params) { return request({ url: `/lowcode/runtime/${appCode}/records`, method: 'get', params }) }
export function addRuntimeRecord(appCode, data) { return request({ url: `/lowcode/runtime/${appCode}/records`, method: 'post', data }) }
export function updateRuntimeRecord(appCode, id, data) { return request({ url: `/lowcode/runtime/${appCode}/records/${id}`, method: 'put', data }) }
export function removeRuntimeRecord(appCode, id) { return request({ url: `/lowcode/runtime/${appCode}/records/${id}`, method: 'delete' }) }
export function runRuntimeAction(appCode, actionCode, data) { return request({ url: `/lowcode/runtime/${appCode}/actions/${actionCode}`, method: 'post', data }) }
