<template>
  <div class="multi-channel-panel" :class="{ 'focus-mode': focusedChannel }">
    <div class="radar-scan"></div>
    <div class="toolbar">
      <el-select v-model="selectedChannel" placeholder="选择通道" style="width: 220px" @change="renderCharts">
        <el-option label="全部通道" :value="0" />
        <el-option
          v-for="item in channels"
          :key="item.channelId"
          :label="`通道 ${item.channelId}`"
          :value="item.channelId"
        />
      </el-select>
      <el-button v-if="focusedChannel" type="primary" size="mini" @click="exitFocusMode">退出聚焦</el-button>
    </div>

    <div class="sparkline-strip">
      <div v-for="item in channels" :key="`spark-${item.channelId}`" class="spark-card" @click="toggleFocus(item.channelId)">
        <div class="spark-card__head">
          <span>{{ item.title }}</span>
          <span :class="['spark-state', getHealthClass(item.channelId)]">{{ getHealthText(item.channelId) }}</span>
        </div>
        <div class="spark-card__value">{{ getLatestValue(item.channelId) }}</div>
        <div :ref="el => setSparkRef(el, item.channelId)" class="sparkline"></div>
      </div>
    </div>

    <div class="dashboard-grid">
      <div class="center-slot">
        <div class="center-core">
          <div class="center-core__title">主扇风机核心态势</div>
          <div class="center-core__value">8 通道实时监控</div>
          <div class="center-core__meta">点击任意通道进入聚焦模式</div>
        </div>
      </div>

      <div
        v-for="item in visibleChannels"
        :key="item.channelId"
        class="chart-card"
        :class="{
          'is-left': item.channelId <= 4,
          'is-right': item.channelId > 4,
          'is-focused': focusedChannel === item.channelId,
          'is-faded': focusedChannel && focusedChannel !== item.channelId
        }"
        @click="toggleFocus(item.channelId)"
      >
        <div class="card-title">
          <span>{{ item.title }}</span>
          <span class="card-title__hint">点击聚焦</span>
        </div>

        <div class="card-body">
          <div class="metric-zone">
            <div class="metric-main">
              <div class="metric-label">RMS</div>
              <div class="metric-value">{{ formatMetric(getChannelMetrics(item.channelId).rms) }}</div>
              <div class="metric-unit">mm/s</div>
            </div>
            <div class="metric-sub">
              <div class="metric-sub__item">
                <span class="metric-sub__label">Temp</span>
                <span class="metric-sub__value">{{ formatMetric(getChannelMetrics(item.channelId).temp) }} <small>°C</small></span>
              </div>
              <div class="metric-sub__item">
                <span class="metric-sub__label">健康度</span>
                <span class="metric-sub__value">{{ getHealthPercent(item.channelId) }}%</span>
              </div>
            </div>
          </div>

          <div class="detail-zone">
            <div class="detail-table">
              <div class="detail-row">
                <span>Peak-Peak</span>
                <strong>{{ formatMetric(getChannelMetrics(item.channelId).peakToPeak) }}</strong>
              </div>
              <div class="detail-row">
                <span>Peak</span>
                <strong>{{ formatMetric(getChannelMetrics(item.channelId).peak) }}</strong>
              </div>
              <div class="detail-row">
                <span>频率峰值</span>
                <strong>{{ formatMetric(getChannelMetrics(item.channelId).freqPeak) }}</strong>
              </div>
            </div>
            <div :ref="el => setGaugeRef(el, item.channelId)" class="health-gauge"></div>
          </div>
        </div>

        <div :ref="el => setChartRef(el, item.channelId)" class="chart-container"></div>
      </div>
    </div>
  </div>
</template>

<script>
import * as echarts from 'echarts'

export default {
  name: 'MultiChannelMonitor',
  props: {
    vibrationData: { type: Array, default: () => [] },
    temperatureData: { type: Array, default: () => [] },
    wsUrl: { type: String, default: '/ws/sensor' }
  },
  data() {
    return {
      selectedChannel: 0,
      focusedChannel: null,
      channels: Array.from({ length: 8 }, (_, i) => ({ channelId: i + 1, title: `通道 ${i + 1}` })),
      chartRefs: {},
      sparkRefs: {},
      gaugeRefs: {},
      chartInstances: {},
      sparkInstances: {},
      gaugeInstances: {},
      syncZooming: false,
      ws: null,
      realtimeBuffers: {},
      maxRealtimePoints: 2000
    }
  },
  computed: {
    visibleChannels() {
      return this.selectedChannel === 0 ? this.channels : this.channels.filter(i => i.channelId === this.selectedChannel)
    },
    displayedChannels() {
      return this.focusedChannel ? this.channels.filter(item => item.channelId === this.focusedChannel) : this.visibleChannels
    },
    channelStatusMap() {
      const map = {}
      this.channels.forEach(item => {
        const series = this.realtimeBuffers[item.channelId]?.vibration || this.getChannelSeries(this.vibrationData, item.channelId)
        const latest = series.length ? series[series.length - 1].value : null
        map[item.channelId] = this.calcHealth(latest)
      })
      return map
    }
  },
  watch: {
    vibrationData: { deep: true, handler() { this.renderCharts() } },
    temperatureData: { deep: true, handler() { this.renderCharts() } }
  },
  mounted() {
    this.initRealtimeBuffers()
    this.connectWebSocket()
    this.$nextTick(() => {
      this.initCharts()
      this.renderCharts()
      window.addEventListener('resize', this.resizeCharts)
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeCharts)
    this.closeWebSocket()
    Object.values(this.chartInstances).forEach(chart => chart && chart.dispose())
  },
  methods: {
    initRealtimeBuffers() {
      this.channels.forEach(item => {
        this.$set(this.realtimeBuffers, item.channelId, { vibration: [], temperature: [] })
      })
    },
    calcHealth(value) {
      if (value == null) return 'warning'
      if (Number(value) >= 6) return 'danger'
      if (Number(value) >= 4) return 'warning'
      return 'success'
    },
    getHealthClass(channelId) {
      return this.channelStatusMap[channelId] || 'warning'
    },
    getHealthText(channelId) {
      const cls = this.getHealthClass(channelId)
      return cls === 'success' ? '正常' : cls === 'warning' ? '预警' : '告警'
    },
    getLatestValue(channelId) {
      const series = this.realtimeBuffers[channelId]?.vibration || this.getChannelSeries(this.vibrationData, channelId)
      const latest = series.length ? series[series.length - 1].value : null
      return latest == null ? '--' : Number(latest).toFixed(2)
    },
    getChannelMetrics(channelId) {
      const vibSeries = this.realtimeBuffers[channelId]?.vibration || this.getChannelSeries(this.vibrationData, channelId)
      const tempSeries = this.realtimeBuffers[channelId]?.temperature || this.getChannelSeries(this.temperatureData, channelId)
      const latestVib = vibSeries.length ? Number(vibSeries[vibSeries.length - 1].value) : null
      const latestTemp = tempSeries.length ? Number(tempSeries[tempSeries.length - 1].value) : null
      return {
        rms: latestVib,
        temp: latestTemp,
        peakToPeak: latestVib == null ? null : latestVib * 1.85,
        peak: latestVib == null ? null : latestVib * 1.25,
        freqPeak: latestVib == null ? null : Math.max(0.1, latestVib * 8.6)
      }
    },
    getHealthPercent(channelId) {
      const { rms, temp } = this.getChannelMetrics(channelId)
      if (rms == null && temp == null) return 0
      const rmsScore = rms == null ? 0.5 : Math.max(0, Math.min(1, 1 - rms / 8))
      const tempScore = temp == null ? 0.5 : Math.max(0, Math.min(1, 1 - Math.max(0, temp - 60) / 70))
      return Math.round((rmsScore * 0.6 + tempScore * 0.4) * 100)
    },
    formatMetric(value) {
      return value == null || Number.isNaN(Number(value)) ? '--' : Number(value).toFixed(2)
    },
    connectWebSocket() {
      this.closeWebSocket()
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const base = this.wsUrl.startsWith('ws') ? this.wsUrl : `${protocol}//${window.location.host}${this.wsUrl}`
      this.ws = new WebSocket(base)
      this.ws.onopen = () => {
        this.ws && this.ws.send(JSON.stringify({ type: 'subscribe', channels: this.channels.map(i => i.channelId) }))
      }
      this.ws.onmessage = (evt) => {
        try {
          const msg = JSON.parse(evt.data)
          this.handleRealtimeMessage(msg)
        } catch (e) {
          console.warn('WebSocket 消息解析失败', e)
        }
      }
      this.ws.onerror = () => {}
      this.ws.onclose = () => {}
    },
    closeWebSocket() {
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
    },
    handleRealtimeMessage(msg) {
      if (!msg || msg.channelId == null) return
      const channelId = Number(msg.channelId)
      if (!this.realtimeBuffers[channelId]) {
        this.$set(this.realtimeBuffers, channelId, { vibration: [], temperature: [] })
      }

      const time = msg.sampleTime || new Date().toISOString()
      const vibrationValue = msg.vibrationValue != null ? msg.vibrationValue : msg.rms
      const temperatureValue = msg.temperatureValue

      if (vibrationValue != null) {
        this.appendSeriesPoint(channelId, 'vibration', { time, value: vibrationValue })
      }
      if (temperatureValue != null) {
        this.appendSeriesPoint(channelId, 'temperature', { time, value: temperatureValue })
      }
      if (vibrationValue != null || temperatureValue != null) {
        this.updateSparkline(channelId)
        this.updateChart(channelId)
        this.syncZoom(channelId)
      }
    },
    appendSeriesPoint(channelId, type, point) {
      const bucket = this.realtimeBuffers[channelId][type]
      bucket.push(point)
      if (bucket.length > this.maxRealtimePoints) {
        bucket.splice(0, bucket.length - this.maxRealtimePoints)
      }
    },
    setChartRef(el, channelId) {
      if (el) this.chartRefs[channelId] = el
    },
    setSparkRef(el, channelId) {
      if (el) this.sparkRefs[channelId] = el
    },
    setGaugeRef(el, channelId) {
      if (el) this.gaugeRefs[channelId] = el
    },
    initCharts() {
      this.visibleChannels.forEach(item => {
        const el = this.chartRefs[item.channelId]
        if (el && !this.chartInstances[item.channelId]) {
          this.chartInstances[item.channelId] = echarts.init(el)
        }
        const gaugeEl = this.gaugeRefs[item.channelId]
        if (gaugeEl && !this.gaugeInstances[item.channelId]) {
          this.gaugeInstances[item.channelId] = echarts.init(gaugeEl)
        }
      })
      this.channels.forEach(item => {
        const el = this.sparkRefs[item.channelId]
        if (el && !this.sparkInstances[item.channelId]) {
          this.sparkInstances[item.channelId] = echarts.init(el)
        }
      })
    },
    renderCharts() {
      this.$nextTick(() => {
        this.initCharts()
        this.channels.forEach(item => {
          this.updateSparkline(item.channelId)
          this.updateGauge(item.channelId)
        })
        this.visibleChannels.forEach(item => this.updateChart(item.channelId))
      })
    },
    toggleFocus(channelId) {
      if (this.focusedChannel === channelId) {
        this.exitFocusMode()
        return
      }
      this.focusedChannel = channelId
      this.$nextTick(() => {
        this.resizeCharts()
        this.updateChart(channelId)
      })
    },
    exitFocusMode() {
      this.focusedChannel = null
      this.$nextTick(() => {
        this.renderCharts()
        this.resizeCharts()
      })
    },
    updateSparkline(channelId) {
      const chart = this.sparkInstances[channelId]
      if (!chart) return
      const vibSource = this.realtimeBuffers[channelId]?.vibration || this.getChannelSeries(this.vibrationData, channelId)
      const vib = this.lttbDownsample(vibSource, 36)
      chart.setOption(this.buildSparklineOption(vib), true)
    },
    updateGauge(channelId) {
      const chart = this.gaugeInstances[channelId]
      if (!chart) return
      const health = this.getHealthPercent(channelId)
      chart.setOption(this.buildGaugeOption(health), true)
    },
    updateChart(channelId) {
      this.$nextTick(() => {
        this.initCharts()
        const chart = this.chartInstances[channelId]
        if (!chart) return
        const vibSource = this.realtimeBuffers[channelId]?.vibration || this.getChannelSeries(this.vibrationData, channelId)
        const tempSource = this.realtimeBuffers[channelId]?.temperature || this.getChannelSeries(this.temperatureData, channelId)
        const vib = this.lttbDownsample(vibSource, 1200)
        const temp = this.lttbDownsample(tempSource, 1200)
        chart.setOption(this.buildOption(channelId, vib, temp), true)
      })
    },
    resizeCharts() {
      Object.values(this.chartInstances).forEach(chart => chart && chart.resize())
      Object.values(this.sparkInstances).forEach(chart => chart && chart.resize())
    },
    syncZoom(sourceChannelId) {
      if (this.syncZooming) return
      const source = this.chartInstances[sourceChannelId]
      if (!source) return
      const option = source.getOption()
      const zoom = option.dataZoom && option.dataZoom[0]
      if (!zoom) return
      const startValue = zoom.startValue
      const endValue = zoom.endValue
      this.syncZooming = true
      Object.entries(this.chartInstances).forEach(([id, chart]) => {
        if (Number(id) !== Number(sourceChannelId) && chart) {
          chart.dispatchAction({ type: 'dataZoom', startValue, endValue })
        }
      })
      this.$nextTick(() => { this.syncZooming = false })
    },
    getChannelSeries(sourceList, channelId) {
      return (sourceList || []).filter(x => x.channelId === channelId)
    },
    downsample(list, maxPoints) {
      if (!Array.isArray(list) || list.length <= maxPoints) return list || []
      const step = Math.ceil(list.length / maxPoints)
      const result = []
      for (let i = 0; i < list.length; i += step) result.push(list[i])
      return result
    },
    lttbDownsample(data, threshold) {
      if (!Array.isArray(data) || data.length <= threshold || threshold < 3) return data || []
      const sampled = []
      const bucketSize = (data.length - 2) / (threshold - 2)
      let a = 0
      sampled.push(data[a])
      for (let i = 0; i < threshold - 2; i++) {
        const rangeStart = Math.floor((i + 1) * bucketSize) + 1
        const rangeEnd = Math.floor((i + 2) * bucketSize) + 1
        const rangeEndLimited = Math.min(rangeEnd, data.length)
        let avgX = 0
        let avgY = 0
        let avgCount = 0
        for (let j = rangeStart; j < rangeEndLimited; j++) {
          const point = data[j]
          const x = point.time != null ? new Date(point.time).getTime() : j
          const y = Number(point.value != null ? point.value : 0)
          avgX += x
          avgY += y
          avgCount++
        }
        avgX = avgCount ? avgX / avgCount : 0
        avgY = avgCount ? avgY / avgCount : 0
        const rangeOffs = Math.floor(i * bucketSize) + 1
        const rangeTo = Math.floor((i + 1) * bucketSize) + 1
        let maxArea = -1
        let nextA = rangeOffs
        for (let j = rangeOffs; j < Math.min(rangeTo, data.length - 1); j++) {
          const point = data[j]
          const x = point.time != null ? new Date(point.time).getTime() : j
          const y = Number(point.value != null ? point.value : 0)
          const area = Math.abs((data[a].time != null ? new Date(data[a].time).getTime() : a - avgX) * (y - (data[a].value != null ? data[a].value : 0)) - (data[a].time != null ? new Date(data[a].time).getTime() : a - x) * (avgY - (data[a].value != null ? data[a].value : 0)))
          if (area > maxArea) {
            maxArea = area
            nextA = j
          }
        }
        sampled.push(data[nextA])
        a = nextA
      }
      sampled.push(data[data.length - 1])
      return sampled
    },
    buildSparklineOption(series) {
      return {
        animation: false,
        grid: { left: 0, right: 0, top: 0, bottom: 0 },
        xAxis: { type: 'category', show: false, boundaryGap: false, data: series.map(i => i.time) },
        yAxis: { type: 'value', show: false, scale: true },
        tooltip: { show: false },
        series: [{
          type: 'line',
          data: series.map(i => i.value),
          showSymbol: false,
          smooth: true,
          lineStyle: { width: 1.5, color: '#00FFFF' },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(0,255,255,0.38)' },
                { offset: 1, color: 'rgba(0,255,255,0)' }
              ]
            }
          }
        }]
      }
    },
    buildGaugeOption(health) {
      return {
        series: [{
          type: 'gauge',
          startAngle: 220,
          endAngle: -40,
          min: 0,
          max: 100,
          radius: '100%',
          progress: { show: true, width: 10, itemStyle: { color: health >= 80 ? '#67c23a' : health >= 60 ? '#e6a23c' : '#f56c6c' } },
          axisLine: { lineStyle: { width: 10, color: [[1, 'rgba(0,255,255,0.15)']] } },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { show: false },
          pointer: { show: false },
          anchor: { show: false },
          title: { show: false },
          detail: { valueAnimation: true, formatter: '{value}%', color: '#eaffff', fontSize: 14, offsetCenter: [0, '45%'] },
          data: [{ value: health }]
        }]
      }
    },
    getIso10816Lines() {
      return [
        { yAxis: 2, label: { formatter: '优' } },
        { yAxis: 4, label: { formatter: '良' } },
        { yAxis: 6, label: { formatter: '限' } },
        { yAxis: 8, label: { formatter: '强' } }
      ]
    },
    buildOption(channelId, vibrationSeries, temperatureSeries) {
      const vibX = vibrationSeries.map(i => i.time)
      const vibY = vibrationSeries.map(i => i.value)
      const tempX = temperatureSeries.map(i => i.time)
      const tempY = temperatureSeries.map(i => i.value)
      return {
        animation: true,
        animationDuration: 450,
        animationDurationUpdate: 350,
        animationEasing: 'cubicOut',
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        legend: { data: ['振动', '温度'], top: 5, textStyle: { color: 'rgba(235,255,255,0.85)' } },
        dataZoom: [
          { type: 'inside', xAxisIndex: [0, 1], filterMode: 'none' },
          { type: 'slider', xAxisIndex: [0, 1], bottom: 0, height: 18, textStyle: { color: 'rgba(235,255,255,0.7)' }, brushSelect: false }
        ],
        grid: [
          { left: 45, right: 20, top: 40, height: '40%' },
          { left: 45, right: 20, top: '62%', height: '28%' }
        ],
        xAxis: [
          { type: 'category', data: vibX, boundaryGap: false, gridIndex: 0, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: 'rgba(235,255,255,0.72)' } },
          { type: 'category', data: tempX, boundaryGap: false, gridIndex: 1, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: 'rgba(235,255,255,0.72)' } }
        ],
        yAxis: [
          { type: 'value', name: `CH${channelId} 振动`, gridIndex: 0, splitLine: { show: false }, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: 'rgba(235,255,255,0.72)' } },
          { type: 'value', name: `CH${channelId} 温度`, gridIndex: 1, splitLine: { show: false }, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: 'rgba(235,255,255,0.72)' } }
        ],
        series: [
          {
            name: '振动',
            type: 'line',
            xAxisIndex: 0,
            yAxisIndex: 0,
            showSymbol: false,
            smooth: true,
            lineStyle: { width: 3, color: '#00FFFF' },
            markLine: {
              symbol: 'none',
              label: { color: '#ffffff' },
              lineStyle: { color: 'rgba(0,255,255,0.45)', type: 'dashed' },
              data: this.getIso10816Lines()
            },
            areaStyle: {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: 'rgba(0,255,255,0.42)' },
                  { offset: 1, color: 'rgba(0,255,255,0)' }
                ]
              }
            },
            data: vibY
          },
          {
            name: '温度',
            type: 'line',
            xAxisIndex: 1,
            yAxisIndex: 1,
            showSymbol: false,
            smooth: true,
            lineStyle: { width: 3, color: '#00FFFF' },
            areaStyle: {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: 'rgba(0,255,255,0.42)' },
                  { offset: 1, color: 'rgba(0,255,255,0)' }
                ]
              }
            },
            data: tempY
          }
        ]
      }
    }
  }
}
</script>

<style scoped>
.multi-channel-panel {
  position: relative;
  padding: 12px;
  overflow: hidden;
}
.multi-channel-panel.focus-mode {
  overflow: visible;
}
.multi-channel-panel::before {
  content: '';
  position: absolute;
  inset: 50% auto auto 50%;
  width: 900px;
  height: 900px;
  transform: translate(-50%, -50%);
  border: 1px solid rgba(0, 255, 255, 0.16);
  border-radius: 50%;
  background:
    radial-gradient(circle, rgba(0,255,255,0.12) 0 1px, transparent 1px 100%),
    radial-gradient(circle, transparent 62%, rgba(0,255,255,0.12) 63%, transparent 64%);
  background-size: 50px 50px, 100% 100%;
  animation: radar-rotate 18s linear infinite;
  pointer-events: none;
  opacity: 0.55;
}
.toolbar {
  position: relative;
  z-index: 2;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 12px;
}
.sparkline-strip {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.spark-card {
  padding: 10px 12px;
  background: rgba(1, 12, 28, 0.78);
  border: 1px solid rgba(0, 255, 255, 0.22);
  cursor: pointer;
}
.spark-card__head { display: flex; justify-content: space-between; align-items: center; color: rgba(235,255,255,0.88); font-size: 12px; }
.spark-state.success { color: #67c23a; }
.spark-state.warning { color: #e6a23c; }
.spark-state.danger { color: #f56c6c; }
.spark-card__value { margin-top: 6px; font-size: 18px; font-weight: 700; font-family: 'Roboto Mono', 'Digital-7', 'Share Tech Mono', 'Courier New', monospace; color: #eaffff; }
.sparkline { width: 100%; height: 42px; margin-top: 4px; }
.dashboard-grid {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: 1fr 360px 1fr;
  grid-template-rows: repeat(4, minmax(180px, auto));
  gap: 12px;
  align-items: stretch;
}
.center-slot {
  grid-column: 2;
  grid-row: 1 / span 4;
  display: flex;
  align-items: center;
  justify-content: center;
}
.center-core {
  width: 100%;
  height: 100%;
  min-height: 760px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(0, 255, 255, 0.25);
  background: radial-gradient(circle, rgba(0,255,255,0.12), rgba(1,12,28,0.78) 70%);
  box-shadow: 0 0 18px rgba(0, 255, 255, 0.08), inset 0 0 30px rgba(0, 255, 255, 0.04);
}
.center-core__title { font-size: 14px; color: rgba(0,255,255,0.72); letter-spacing: 2px; }
.center-core__value { margin-top: 10px; font-size: 28px; font-weight: 700; color: rgba(235,255,255,0.94); }
.center-core__meta { margin-top: 8px; font-size: 12px; color: rgba(235,255,255,0.65); }
.chart-card {
  position: relative;
  overflow: hidden;
  background: rgba(1, 12, 28, 0.78);
  border: 1px solid rgba(0, 255, 255, 0.35);
  border-radius: 0;
  padding: 12px;
  box-shadow: 0 0 12px rgba(0, 255, 255, 0.08), inset 0 0 18px rgba(0, 255, 255, 0.03);
  transition: transform .45s ease, opacity .45s ease, box-shadow .45s ease, z-index .45s ease;
  transform-origin: center center;
  cursor: pointer;
}
.chart-card.is-left { grid-column: 1; }
.chart-card.is-right { grid-column: 3; }
.chart-card.is-focused {
  position: fixed;
  left: 50%;
  top: 50%;
  width: min(94vw, 1400px);
  height: min(86vh, 900px);
  transform: translate(-50%, -50%) scale(1.04);
  z-index: 9999;
  opacity: 1 !important;
  box-shadow: 0 0 24px rgba(0, 255, 255, 0.18), 0 0 48px rgba(0, 255, 255, 0.08);
}

.focus-mode .chart-card.is-faded {
  z-index: 1;
}

.focus-mode .center-slot,
.focus-mode .sparkline-strip,
.focus-mode .toolbar {
  z-index: 1;
}
.chart-card.is-faded {
  opacity: 0.16;
  transform: scale(0.96);
  pointer-events: none;
}
.card-title { display: flex; justify-content: space-between; align-items: center; font-weight: 600; margin-bottom: 8px; color: rgba(235, 255, 255, 0.92); }
.card-title__hint { font-size: 11px; color: rgba(0,255,255,0.65); }
.card-body { display: grid; grid-template-columns: 1.2fr 0.8fr; gap: 10px; align-items: stretch; }
.metric-zone { display: grid; grid-template-columns: 1fr; gap: 8px; }
.metric-main {
  padding: 10px 12px;
  border: 1px solid rgba(0, 255, 255, 0.18);
  background: rgba(0, 255, 255, 0.05);
}
.metric-label { font-size: 12px; color: rgba(0,255,255,0.72); letter-spacing: 1px; }
.metric-value { margin-top: 4px; font-size: 30px; font-weight: 800; font-family: 'Roboto Mono', 'Digital-7', 'Share Tech Mono', 'Courier New', monospace; color: #f2ffff; }
.metric-unit { margin-top: 2px; font-size: 12px; color: rgba(235,255,255,0.68); font-weight: 700; }
.metric-sub { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.metric-sub__item { padding: 8px 10px; background: rgba(1, 12, 28, 0.58); border: 1px solid rgba(0,255,255,0.12); }
.metric-sub__label { display: block; font-size: 12px; color: rgba(0,255,255,0.68); }
.metric-sub__value { display: block; margin-top: 4px; font-size: 16px; font-weight: 700; color: #eefcff; font-family: 'Roboto Mono', 'Digital-7', 'Share Tech Mono', 'Courier New', monospace; }
.metric-sub__value small { font-size: 12px; color: rgba(235,255,255,0.68); }
.detail-zone { display: grid; grid-template-columns: 1fr 96px; gap: 8px; align-items: center; }
.detail-table { display: grid; gap: 6px; }
.detail-row { display: flex; justify-content: space-between; gap: 12px; padding: 6px 8px; background: rgba(1, 12, 28, 0.58); border: 1px solid rgba(0,255,255,0.1); color: rgba(235,255,255,0.9); font-size: 12px; }
.detail-row strong { color: #eaffff; font-family: 'Roboto Mono', 'Digital-7', 'Share Tech Mono', 'Courier New', monospace; }
.health-gauge { width: 100%; height: 96px; }
.chart-container { width: 100%; height: 180px; margin-top: 10px; }
.chart-card.is-focused .chart-container { height: 280px; }
@keyframes radar-rotate {
  from { transform: translate(-50%, -50%) rotate(0deg); }
  to { transform: translate(-50%, -50%) rotate(360deg); }
}
@media (max-width: 1400px) {
  .sparkline-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .dashboard-grid { grid-template-columns: 1fr; }
  .center-slot { grid-column: auto; grid-row: auto; min-height: 240px; }
  .chart-card.is-left,
  .chart-card.is-right { grid-column: auto; }
  .card-body { grid-template-columns: 1fr; }
  .detail-zone { grid-template-columns: 1fr; }
}
</style>
