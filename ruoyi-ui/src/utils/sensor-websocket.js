let socket = null
let reconnectTimer = null
let manualClose = false
let reconnectDelay = 3000
let listeners = []

function getWsUrl(path = '/ws/sensor') {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${path}`
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
    connect()
  }, reconnectDelay)
}

function connect(path = '/ws/sensor') {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return socket
  }

  manualClose = false
  const url = getWsUrl(path)
  socket = new WebSocket(url)

  socket.onopen = () => {
    reconnectDelay = 3000
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
    notify('error', error)
  }

  socket.onclose = () => {
    notify('close')
    socket = null
    reconnectDelay = Math.min(reconnectDelay * 2, 30000)
    scheduleReconnect()
  }

  return socket
}

function close() {
  manualClose = true
  clearReconnectTimer()
  if (socket) {
    socket.close()
    socket = null
  }
}

function subscribe(callback) {
  if (typeof callback === 'function') {
    listeners.push(callback)
  }
  return () => {
    listeners = listeners.filter(item => item !== callback)
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
