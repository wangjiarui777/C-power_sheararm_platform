import { getAssetTree, getWorkbench } from '@/api/monitoring'
import sensorWebSocket from '@/utils/sensor-websocket'

function parseEnvelope(payload) {
  if (!payload) return null
  if (payload.event !== 'metric.changed') return null
  if (payload.message && typeof payload.message === 'string') {
    try {
      return { ...payload, ...JSON.parse(payload.message) }
    } catch (error) {
      return payload
    }
  }
  return payload
}

const state = {
  assets: [],
  workbench: { device: null, points: [], alarms: [], stateRail: [], summary: {} },
  deviceCode: '',
  pointId: null,
  range: '15m',
  connectionState: 'offline',
  lastMessageTime: null,
  unsubscribe: null,
  consumers: 0,
  subscribedDeviceCode: ''
}

const mutations = {
  SET_ASSETS(state, assets) {
    state.assets = assets || []
  },
  SET_WORKBENCH(state, payload) {
    state.workbench = payload || { device: null, points: [], alarms: [], stateRail: [], summary: {} }
    if (!state.deviceCode && payload && payload.device) state.deviceCode = payload.device.deviceCode
    if (!state.pointId && payload && payload.points && payload.points.length) state.pointId = payload.points[0].pointId
  },
  SET_CONTEXT(state, context) {
    if (context.deviceCode !== undefined) state.deviceCode = context.deviceCode
    if (context.pointId !== undefined) state.pointId = context.pointId
    if (context.range !== undefined) state.range = context.range
  },
  SET_CONNECTION(state, value) {
    state.connectionState = value
  },
  SET_UNSUBSCRIBE(state, value) {
    state.unsubscribe = value
  },
  CHANGE_CONSUMERS(state, delta) {
    state.consumers = Math.max(0, state.consumers + delta)
  },
  SET_SUBSCRIBED_DEVICE(state, value) {
    state.subscribedDeviceCode = value || ''
  },
  APPLY_METRIC(state, payload) {
    const envelope = parseEnvelope(payload)
    if (!envelope || envelope.deviceCode !== state.deviceCode) return
    const index = state.workbench.points.findIndex(point =>
      (envelope.pointId && Number(point.pointId) === Number(envelope.pointId)) ||
      (!envelope.pointId && Number(point.channelId) === Number(envelope.channelId))
    )
    if (index < 0) return
    const old = state.workbench.points[index]
    const next = {
      ...old,
      value: envelope.value !== undefined
        ? envelope.value
        : envelope.metricCode === 'temperature' ? envelope.temperatureValue : envelope.vibrationValue,
      quality: envelope.quality || old.quality,
      sampleTime: envelope.sampleTime || old.sampleTime
    }
    state.workbench.points.splice(index, 1, next)
    state.lastMessageTime = Date.now()
  }
}

const actions = {
  async loadAssets({ commit }) {
    const response = await getAssetTree()
    commit('SET_ASSETS', response.data || [])
    return response.data || []
  },
  async loadWorkbench({ state, commit }, params = {}) {
    const response = await getWorkbench({ deviceCode: params.deviceCode || state.deviceCode, ...params })
    commit('SET_WORKBENCH', response.data || {})
    return response.data || {}
  },
  setContext({ state, commit }, context) {
    const previousDevice = state.deviceCode
    commit('SET_CONTEXT', context)
    if (context.deviceCode !== undefined && state.unsubscribe && state.deviceCode !== previousDevice) {
      if (state.subscribedDeviceCode) sensorWebSocket.send({ type: 'unsubscribe', channel: `device:${state.subscribedDeviceCode}` })
      if (state.deviceCode) sensorWebSocket.send({ type: 'subscribe', channel: `device:${state.deviceCode}` })
      commit('SET_SUBSCRIBED_DEVICE', state.deviceCode)
    }
  },
  connect({ state, commit, dispatch }) {
    commit('CHANGE_CONSUMERS', 1)
    if (state.unsubscribe) {
      if (state.deviceCode && state.deviceCode !== state.subscribedDeviceCode) {
        if (state.subscribedDeviceCode) sensorWebSocket.send({ type: 'unsubscribe', channel: `device:${state.subscribedDeviceCode}` })
        sensorWebSocket.send({ type: 'subscribe', channel: `device:${state.deviceCode}` })
        commit('SET_SUBSCRIBED_DEVICE', state.deviceCode)
      }
      return
    }
    const unsubscribe = sensorWebSocket.subscribe((event, payload) => {
      if (event === 'open') {
        commit('SET_CONNECTION', 'online')
        sensorWebSocket.send({ type: 'subscribe', channel: 'monitoring' })
        if (state.deviceCode) {
          sensorWebSocket.send({ type: 'subscribe', channel: `device:${state.deviceCode}` })
          commit('SET_SUBSCRIBED_DEVICE', state.deviceCode)
        }
        dispatch('loadWorkbench').catch(() => {})
      } else if (event === 'message') {
        if (payload && (payload.event === 'alarm.changed' || (payload.type === 'phm_alarm' && payload.event === 'changed'))) {
          dispatch('loadWorkbench').catch(() => {})
        }
        commit('APPLY_METRIC', payload)
      } else if (event === 'close' || event === 'error') {
        commit('SET_CONNECTION', 'offline')
      }
    })
    commit('SET_UNSUBSCRIBE', unsubscribe)
    sensorWebSocket.connect('/ws/monitoring').catch(() => {})
  },
  disconnect({ state, commit }) {
    commit('CHANGE_CONSUMERS', -1)
    if (state.consumers > 0) return
    if (state.unsubscribe) state.unsubscribe()
    commit('SET_UNSUBSCRIBE', null)
    commit('SET_SUBSCRIBED_DEVICE', '')
    sensorWebSocket.close()
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}
