import request from '@/utils/request'

let socket = null
let reconnectTimer = null
let manualClose = false
let reconnectDelay = 3000
let listeners = []
let currentPath = '/ws/sensor'
let connectionPromise = null

function getWsUrl(path = '/ws/sensor', ticket = '') {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const separator = path.includes('?') ? '&' : '?'
  // 支持通过环境变量配置独立的 WebSocket 服务器地址
  const wsHost = process.env.VUE_APP_WS_HOST || window.location.host
  return `${protocol}//${wsHost}${path}${separator}ticket=${encodeURIComponent(ticket)}`
}

function notify(event, payload) {
  listeners.forEach(cb => {
    try {
      cb(event, payload)
    } catch (error) {
      // ignore listener errors
    }
  })
}

function clearReconnectTimer() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function scheduleReconnect() {
  if (manualClose) {
    return
  }
  clearReconnectTimer()
  reconnectTimer = setTimeout(() => {
    connect(currentPath)
  }, reconnectDelay)
}

async function issueTicket() {
  const response = await request({ url: '/sensor/ws-ticket', method: 'post' })
  return response.data && response.data.ticket
}

function connect(path = '/ws/sensor') {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return Promise.resolve(socket)
  }
  if (connectionPromise) {
    return connectionPromise
  }

  manualClose = false
  currentPath = path
  connectionPromise = issueTicket().then(ticket => {
    if (!ticket) throw new Error('WebSocket ticket was not issued')
    const url = getWsUrl(path, ticket)
    socket = new WebSocket(url)

    socket.onopen = () => {
      reconnectDelay = 3000
      connectionPromise = null
      notify('open')
    }

    socket.onmessage = event => {
      let data = event.data
      try {
        data = JSON.parse(event.data)
      } catch (error) {
        // keep original text payload
      }
      notify('message', data)
    }

    socket.onerror = error => {
      connectionPromise = null
      notify('error', error)
    }

    socket.onclose = () => {
      notify('close')
      socket = null
      connectionPromise = null
      reconnectDelay = Math.min(reconnectDelay * 2, 30000)
      scheduleReconnect()
    }

    return socket
  }).catch(error => {
    connectionPromise = null
    notify('error', error)
    scheduleReconnect()
    throw error
  })
  return connectionPromise
}

function close() {
  manualClose = true
  clearReconnectTimer()
  if (socket) {
    socket.close()
    socket = null
  }
  connectionPromise = null
}

function subscribe(callback) {
  if (typeof callback === 'function') {
    listeners.push(callback)
  }
  return () => {
    listeners = listeners.filter(item => item !== callback)
    if (listeners.length === 0) {
      close()
    }
  }
}

function send(data) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(typeof data === 'string' ? data : JSON.stringify(data))
    return true
  }
  return false
}

export default {
  connect,
  close,
  subscribe,
  send,
  get socket() {
    return socket
  }
}
