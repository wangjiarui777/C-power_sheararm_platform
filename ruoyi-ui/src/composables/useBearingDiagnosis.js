import { computed, onBeforeUnmount, ref } from 'vue'

export function useBearingDiagnosis(options = {}) {
  const { maxPoints = 60, tickMs = 16.6667 } = options
  const sourceQueue = ref([])
  const seriesData = ref([])
  const isRunning = ref(false)
  const pendingBatchCount = ref(0)
  let rafId = null
  let lastFrame = 0

  function normalizePoints(payload) {
    if (payload == null) return []
    if (Array.isArray(payload)) return payload.flatMap(normalizePoints)
    if (typeof payload === 'number') return [payload]
    if (typeof payload === 'string') {
      const parsed = Number(payload)
      return Number.isFinite(parsed) ? [parsed] : []
    }
    if (typeof payload === 'object') {
      if (Array.isArray(payload.values)) return payload.values.flatMap(normalizePoints)
      if (Array.isArray(payload.data)) return payload.data.flatMap(normalizePoints)
      if (Array.isArray(payload.rms)) return payload.rms.flatMap(normalizePoints)
      if (typeof payload.value === 'number') return [payload.value]
      if (typeof payload.rms === 'number') return [payload.rms]
      if (typeof payload.x === 'number') return [payload.x]
      if (typeof payload.y === 'number') return [payload.y]
    }
    return []
  }

  function pushPoint(point) {
    const numeric = Number(point)
    if (!Number.isFinite(numeric)) return
    seriesData.value.push(numeric)
    if (seriesData.value.length > maxPoints) {
      seriesData.value.splice(0, seriesData.value.length - maxPoints)
    }
  }

  function enqueueBatch(batch) {
    const points = normalizePoints(batch)
    if (!points.length) return
    sourceQueue.value.push(...points)
    pendingBatchCount.value += 1
    start()
  }

  function frame(now) {
    if (!isRunning.value) return
    if (!lastFrame) lastFrame = now
    if (now - lastFrame >= tickMs && sourceQueue.value.length) {
      pushPoint(sourceQueue.value.shift())
      lastFrame = now
    }
    if (sourceQueue.value.length) {
      rafId = requestAnimationFrame(frame)
    } else {
      isRunning.value = false
      rafId = null
    }
  }

  function start() {
    if (isRunning.value) return
    isRunning.value = true
    lastFrame = 0
    rafId = requestAnimationFrame(frame)
  }

  function stop() {
    isRunning.value = false
    if (rafId) cancelAnimationFrame(rafId)
    rafId = null
    lastFrame = 0
  }

  function clear() {
    sourceQueue.value = []
    seriesData.value = []
    pendingBatchCount.value = 0
  }

  const latestValue = computed(() => {
    const latest = seriesData.value[seriesData.value.length - 1]
    return latest == null ? 0 : latest
  })

  onBeforeUnmount(() => stop())

  return {
    sourceQueue,
    seriesData,
    isRunning,
    pendingBatchCount,
    latestValue,
    enqueueBatch,
    pushPoint,
    clear,
    start,
    stop
  }
}
