<template>
  <div class="vibration-console">
    <section class="command-bar">
      <div class="asset-title">
        <div class="eyebrow">八通道振动分析工作台</div>
        <h2>{{ device.deviceName || '主扇风机' }}</h2>
        <div class="asset-meta">
          <span>{{ device.deviceCode || deviceCode }}</span>
          <span>{{ device.deviceType || '旋转机械' }}</span>
          <span>最近采样 {{ formatTime(summary.latestSampleTime) }}</span>
        </div>
      </div>
      <div class="command-actions">
        <el-input v-model="deviceCode" size="small" class="device-input" placeholder="设备编码" @keyup.enter.native="reloadAll" />
        <el-select v-model="windowMinutes" size="small" class="window-select" @change="reloadAll">
          <el-option label="近 10 分钟" :value="10" />
          <el-option label="近 30 分钟" :value="30" />
          <el-option label="近 60 分钟" :value="60" />
        </el-select>
        <el-button size="small" type="primary" icon="el-icon-refresh" @click="reloadAll">刷新</el-button>
      </div>
    </section>

    <section class="status-ribbon">
      <div class="status-tile">
        <span>连接状态</span>
        <strong :class="['state-text', connectionState]">{{ connectionText }}</strong>
      </div>
      <div class="status-tile">
        <span>在线通道</span>
        <strong>{{ summary.onlineCount || 0 }}/{{ summary.channelCount || 8 }}</strong>
      </div>
      <div class="status-tile">
        <span>最高风险</span>
        <strong :class="riskClass(summary.maxAlarmLevel)">{{ alarmText(summary.maxAlarmLevel) }}</strong>
      </div>
      <div class="status-tile">
        <span>重点通道</span>
        <strong>CH{{ summary.highestRiskChannel || activeChannelId }}</strong>
      </div>
    </section>

    <main class="console-layout">
      <aside class="channel-panel">
        <div class="panel-head">
          <div>
            <div class="panel-title">测点矩阵</div>
            <div class="panel-subtitle">按物理位置和风险定位异常</div>
          </div>
          <el-select v-model="sortMode" size="mini" class="sort-select">
            <el-option label="物理顺序" value="physical" />
            <el-option label="告警优先" value="risk" />
            <el-option label="RMS 降序" value="rms" />
          </el-select>
        </div>

        <div class="channel-grid">
          <button
            v-for="item in sortedChannels"
            :key="item.channelId"
            type="button"
            :class="['channel-card', statusClass(item), { active: item.channelId === activeChannelId }]"
            @click="selectChannel(item.channelId)"
          >
            <div class="channel-card__top">
              <span class="channel-code">CH{{ item.channelId }}</span>
              <span class="channel-state">{{ statusText(item) }}</span>
            </div>
            <div class="channel-name">{{ item.channelName }}</div>
            <div class="channel-value">
              <strong>{{ formatMetric(item.rms) }}</strong>
              <span>mm/s · RMS 有效值</span>
            </div>
            <div class="channel-mini">
              <span>峰值 Peak {{ formatMetric(item.peak) }}</span>
              <span>{{ formatMetric(item.temperature) }} °C</span>
            </div>
            <div class="health-bar">
              <i :style="{ width: healthWidth(item.health) }"></i>
            </div>
          </button>
        </div>
      </aside>

      <section class="analysis-panel">
        <div class="analysis-head">
          <div>
            <div class="panel-title">{{ activeChannel.channelName || '通道分析' }}</div>
            <div class="panel-subtitle">CH{{ activeChannelId }} · {{ statusText(activeChannel) }} · {{ activeChannel.diagnosis || '暂无诊断' }}</div>
          </div>
          <div class="metric-strip">
            <div><span>RMS</span><strong>{{ formatMetric(activeChannel.rms) }}</strong></div>
            <div><span>峰值 Peak</span><strong>{{ formatMetric(activeChannel.peak) }}</strong></div>
            <div><span>健康度</span><strong>{{ activeChannel.health || 0 }}%</strong></div>
          </div>
        </div>

        <el-tabs v-model="analysisTab" class="analysis-tabs" @tab-click="renderAnalysisCharts">
          <el-tab-pane label="趋势" name="trend">
            <div ref="trendChart" class="analysis-chart"></div>
          </el-tab-pane>
          <el-tab-pane label="波形" name="waveform">
            <div v-if="hasSeries(analysis.waveform)" ref="waveformChart" class="analysis-chart"></div>
            <div v-else class="empty-chart">当前通道暂无高频波形数据。完成采样帧入库后将在此展示。</div>
          </el-tab-pane>
          <el-tab-pane label="频谱" name="spectrum">
            <div v-if="hasSeries(analysis.spectrum)" ref="spectrumChart" class="analysis-chart"></div>
            <div v-else class="empty-chart">当前通道暂无 FFT 频谱数据。完成采样帧解析后将在此展示特征频率。</div>
          </el-tab-pane>
          <el-tab-pane label="瀑布图" name="waterfall">
            <div v-if="hasSeries(analysis.waterfall)" ref="waterfallChart" class="analysis-chart"></div>
            <div v-else class="empty-chart">当前通道暂无瀑布图数据。连续频谱归档后将在此追踪频带演化。</div>
          </el-tab-pane>
        </el-tabs>
      </section>

      <aside class="insight-panel">
        <div class="panel-title">诊断与处置</div>
        <div class="diagnosis-card" :class="statusClass(activeChannel)">
          <span>当前结论</span>
          <strong>{{ activeChannel.diagnosis || analysis.message || '暂无诊断结论' }}</strong>
        </div>

        <div class="side-section">
          <div class="side-title">阈值规则</div>
          <div class="kv-row"><span>规则</span><strong>{{ thresholds.ruleName || '--' }}</strong></div>
          <div class="kv-row"><span>高报</span><strong>{{ formatMetric(thresholds.highLimit) }} mm/s</strong></div>
          <div class="kv-row"><span>高高报</span><strong>{{ formatMetric(thresholds.highHighLimit) }} mm/s</strong></div>
        </div>

        <div class="side-section">
          <div class="side-title">处置建议</div>
          <p class="advice">{{ thresholds.actionAdvice || defaultAdvice }}</p>
        </div>

        <div class="side-section">
          <div class="side-title">最近事件</div>
          <div v-if="analysis.events && analysis.events.length" class="event-list">
            <div v-for="event in analysis.events" :key="event.alarmNo" class="event-item">
              <b>{{ event.alarmNo }}</b>
              <span>{{ event.diagnosisResult || '告警事件' }}</span>
              <small>{{ formatTime(event.alarmTime) }}</small>
            </div>
          </div>
          <div v-else class="muted">暂无未闭环事件。</div>
        </div>
      </aside>
    </main>
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { getMultiChannelOverview, getChannelAnalysis } from '@/api/system/vibration'
import { industrialChartTheme } from '@/utils/industrialTheme'
import sensorWebSocket from '@/utils/sensor-websocket'

const DEFAULT_CHANNELS = [
  '驱动端水平振动',
  '驱动端垂直振动',
  '驱动端轴向振动',
  '非驱动端水平振动',
  '非驱动端垂直振动',
  '非驱动端轴向振动',
  '减速机输入端',
  '减速机输出端'
]

export default {
  name: 'MultiChannelMonitor',
  props: {
    wsUrl: { type: String, default: '/ws/sensor' }
  },
  data() {
    return {
      deviceCode: this.$route.query.deviceCode || '',
      windowMinutes: 30,
      sortMode: 'physical',
      activeChannelId: 1,
      analysisTab: 'trend',
      device: {},
      summary: {},
      channels: DEFAULT_CHANNELS.map((name, index) => this.createEmptyChannel(index + 1, name)),
      analysis: { trend: [], waveform: [], spectrum: [], waterfall: [], thresholds: {}, events: [] },
      connectionState: 'disconnected',
      unsubscribeWs: null,
      trendChart: null,
      waveformChart: null,
      spectrumChart: null,
      waterfallChart: null,
      defaultAdvice: '保持趋势观察；若 RMS 连续升高，优先检查轴承润滑、地脚松动、联轴器不对中。'
    }
  },
  computed: {
    sortedChannels() {
      const list = this.channels.slice()
      if (this.sortMode === 'risk') {
        return list.sort((a, b) => Number(b.alarmLevel || 0) - Number(a.alarmLevel || 0))
      }
      if (this.sortMode === 'rms') {
        return list.sort((a, b) => Number(b.rms || 0) - Number(a.rms || 0))
      }
      return list.sort((a, b) => a.channelId - b.channelId)
    },
    activeChannel() {
      return this.channels.find(item => item.channelId === this.activeChannelId) || this.createEmptyChannel(this.activeChannelId)
    },
    thresholds() {
      return this.analysis.thresholds || this.activeChannel.thresholds || {}
    },
    connectionText() {
      const map = {
        online: '实时在线',
        connecting: '连接中',
        reconnecting: '重连中',
        disconnected: '未连接'
      }
      return map[this.connectionState] || '未知'
    }
  },
  mounted() {
    this.reloadAll()
    this.connectWebSocket()
    window.addEventListener('resize', this.resizeCharts)
    window.addEventListener('appearance-mode-change', this.renderAnalysisCharts)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeCharts)
    window.removeEventListener('appearance-mode-change', this.renderAnalysisCharts)
    this.closeWebSocket()
    this.disposeCharts()
  },
  methods: {
    createEmptyChannel(channelId, name) {
      return {
        channelId,
        channelName: name || DEFAULT_CHANNELS[channelId - 1] || `通道 ${channelId}`,
        rms: null,
        peak: null,
        peakToPeak: null,
        temperature: null,
        health: 0,
        alarmLevel: 0,
        status: 'offline',
        freshness: 'offline',
        trend: [],
        thresholds: {},
        diagnosis: '暂无最近采样'
      }
    },
    async reloadAll() {
      await this.loadOverview()
      await this.loadAnalysis(this.activeChannelId)
    },
    async loadOverview() {
      const res = await getMultiChannelOverview({ deviceCode: this.deviceCode, windowMinutes: this.windowMinutes })
      const data = res.data || {}
      this.device = data.device || {}
      this.summary = data.summary || {}
      const incoming = Array.isArray(data.channels) ? data.channels : []
      this.channels = DEFAULT_CHANNELS.map((name, index) => {
        const channelId = index + 1
        const found = incoming.find(item => Number(item.channelId) === channelId)
        return Object.assign(this.createEmptyChannel(channelId, name), found || {})
      })
      this.$nextTick(this.renderAnalysisCharts)
    },
    async loadAnalysis(channelId) {
      const res = await getChannelAnalysis(channelId, { deviceCode: this.deviceCode })
      this.analysis = Object.assign({ trend: [], waveform: [], spectrum: [], waterfall: [], thresholds: {}, events: [] }, res.data || {})
      this.$nextTick(this.renderAnalysisCharts)
    },
    selectChannel(channelId) {
      this.activeChannelId = channelId
      this.loadAnalysis(channelId)
    },
    connectWebSocket() {
      this.closeWebSocket()
      this.connectionState = 'connecting'
      this.unsubscribeWs = sensorWebSocket.subscribe((event, payload) => {
        if (event === 'open') {
          this.connectionState = 'online'
          sensorWebSocket.send({ type: 'subscribe', channel: 'overview' })
          sensorWebSocket.send({ type: 'subscribe', channel: 'phm_alarm' })
        } else if (event === 'message') {
          this.handleRealtimePayload(payload)
        } else if (event === 'error' || event === 'close') {
          this.connectionState = 'reconnecting'
        }
      })
      sensorWebSocket.connect(this.wsUrl).catch(() => {})
    },
    closeWebSocket() {
      if (this.unsubscribeWs) {
        this.unsubscribeWs()
        this.unsubscribeWs = null
      }
    },
    handleRealtimePayload(payload) {
      const records = this.normalizeRealtimePayload(payload)
      if (!records.length) {
        if (payload && payload.type === 'phm_alarm') this.loadOverview()
        return
      }
      records.forEach(record => this.applyRealtimeRecord(record))
      this.$nextTick(this.renderAnalysisCharts)
    },
    normalizeRealtimePayload(payload) {
      if (!payload) return []
      if (Array.isArray(payload)) return payload.flatMap(item => this.normalizeRealtimePayload(item))
      let records = []
      const nested = payload.data || payload.payload || payload.body
      if (nested && typeof nested === 'object') records = records.concat(this.normalizeRealtimePayload(nested))
      if (typeof payload.message === 'string' && /^[\[{]/.test(payload.message.trim())) {
        try {
          records = records.concat(this.normalizeRealtimePayload(JSON.parse(payload.message)))
        } catch (e) {}
      }
      const record = this.normalizeRealtimeRecord(payload)
      if (record) records.push(record)
      return records
    },
    normalizeRealtimeRecord(msg) {
      const channelId = Number(this.pickFirst(msg, ['channelId', 'channel_id', 'channelNo', 'channel', 'ch']))
      if (!channelId || channelId < 1 || channelId > 8) return null
      return {
        channelId,
        time: this.pickFirst(msg, ['sampleTime', 'collectionTime', 'time', 'timestamp', 'ts']) || new Date().toISOString(),
        rms: this.pickFirst(msg, ['vibrationValue', 'vibration_value', 'rms', 'value']),
        temperature: this.pickFirst(msg, ['temperatureValue', 'temperature_value', 'temp']),
        peak: this.pickFirst(msg, ['peak', 'peakValue'])
      }
    },
    applyRealtimeRecord(record) {
      const index = this.channels.findIndex(item => item.channelId === record.channelId)
      if (index < 0) return
      const old = this.channels[index]
      const rms = record.rms == null ? old.rms : Number(record.rms)
      const temperature = record.temperature == null ? old.temperature : Number(record.temperature)
      const alarmLevel = rms >= 6 ? 3 : rms >= 4 ? 2 : Number(old.alarmLevel || 0)
      const trend = (old.trend || []).slice()
      if (rms != null) {
        trend.push({ time: record.time, rms, temperature, peak: record.peak == null ? rms * 1.25 : Number(record.peak) })
        if (trend.length > 120) trend.splice(0, trend.length - 120)
      }
      const next = Object.assign({}, old, {
        rms,
        temperature,
        peak: record.peak == null ? (rms == null ? null : rms * 1.25) : Number(record.peak),
        peakToPeak: rms == null ? null : rms * 1.85,
        health: this.calcHealth(rms, temperature, alarmLevel),
        alarmLevel,
        status: alarmLevel >= 3 ? 'alarm' : alarmLevel >= 2 ? 'warning' : 'normal',
        freshness: 'realtime',
        sampleTime: record.time,
        trend,
        diagnosis: this.resolveDiagnosis(rms)
      })
      this.$set(this.channels, index, next)
      if (record.channelId === this.activeChannelId) {
        this.analysis.trend = trend
      }
    },
    pickFirst(source, keys) {
      for (const key of keys) {
        if (source && source[key] !== undefined && source[key] !== null && source[key] !== '') return source[key]
      }
      return null
    },
    renderAnalysisCharts() {
      if (this.analysisTab === 'trend') this.renderTrendChart()
      if (this.analysisTab === 'waveform') this.renderSimpleLineChart('waveformChart', 'waveform', '波形幅值')
      if (this.analysisTab === 'spectrum') this.renderSimpleLineChart('spectrumChart', 'spectrum', '频谱幅值')
      if (this.analysisTab === 'waterfall') this.renderSimpleLineChart('waterfallChart', 'waterfall', '频带演化')
    },
    renderTrendChart() {
      const el = this.$refs.trendChart
      if (!el) return
      if (!this.trendChart) this.trendChart = echarts.init(el)
      const series = this.analysis.trend && this.analysis.trend.length ? this.analysis.trend : (this.activeChannel.trend || [])
      const thresholds = this.thresholds
      const theme = industrialChartTheme
      this.trendChart.setOption({
        animation: false,
        backgroundColor: 'transparent',
        color: [theme.vibration, theme.temperature],
        tooltip: {
          trigger: 'axis',
          backgroundColor: theme.tooltipBg,
          borderColor: theme.tooltipBorder,
          textStyle: { color: theme.text }
        },
        legend: { top: 10, textStyle: { color: theme.text }, data: ['RMS', '温度'] },
        grid: { left: 54, right: 42, top: 52, bottom: 38 },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: series.map(item => this.formatTime(item.time)),
          axisLabel: { color: theme.muted },
          axisLine: { lineStyle: { color: theme.border } }
        },
        yAxis: [
          {
            type: 'value',
            name: 'mm/s',
            scale: true,
            splitLine: { lineStyle: { color: theme.grid } },
            axisLabel: { color: theme.muted }
          },
          {
            type: 'value',
            name: '°C',
            scale: true,
            splitLine: { show: false },
            axisLabel: { color: theme.muted }
          }
        ],
        series: [
          {
            name: 'RMS',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: series.map(item => this.toNumber(item.rms)),
            lineStyle: { color: theme.vibration, width: 2.5 },
            itemStyle: { color: theme.vibration },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(56, 189, 248, 0.26)' },
                { offset: 1, color: 'rgba(56, 189, 248, 0.02)' }
              ])
            },
            markLine: {
              symbol: 'none',
              data: [
                { yAxis: thresholds.highLimit || 4, label: { formatter: '高报' } },
                { yAxis: thresholds.highHighLimit || 6, label: { formatter: '高高报' } }
              ],
              lineStyle: { color: theme.warning, type: 'dashed' },
              label: { color: theme.warning }
            }
          },
          {
            name: '温度',
            type: 'line',
            yAxisIndex: 1,
            smooth: true,
            showSymbol: false,
            data: series.map(item => this.toNumber(item.temperature)),
            lineStyle: { color: theme.temperature, width: 2 },
            itemStyle: { color: theme.temperature }
          }
        ]
      }, true)
    },
    renderSimpleLineChart(refName, chartName, label) {
      const el = this.$refs[refName]
      const data = this.analysis[chartName] || []
      if (!el || !data.length) return
      if (!this[refName]) this[refName] = echarts.init(el)
      const theme = industrialChartTheme
      const isSpectrum = chartName === 'spectrum'
      const primaryColor = isSpectrum ? theme.event : theme.vibration
      this[refName].setOption({
        animation: false,
        backgroundColor: 'transparent',
        color: [primaryColor],
        tooltip: {
          trigger: 'axis',
          backgroundColor: theme.tooltipBg,
          borderColor: theme.tooltipBorder,
          textStyle: { color: theme.text }
        },
        grid: { left: 52, right: 24, top: 36, bottom: 34 },
        xAxis: {
          type: 'category',
          data: data.map((item, index) => item.time || item.freq || index),
          axisLabel: { color: theme.muted },
          axisLine: { lineStyle: { color: theme.border } }
        },
        yAxis: {
          type: 'value',
          scale: true,
          axisLabel: { color: theme.muted },
          splitLine: { lineStyle: { color: theme.grid } }
        },
        series: [{
          name: label,
          type: isSpectrum ? 'bar' : 'line',
          showSymbol: false,
          smooth: !isSpectrum,
          data: data.map(item => item.value || item.amplitude || 0),
          itemStyle: { color: primaryColor },
          lineStyle: { color: primaryColor, width: 2 },
          areaStyle: isSpectrum ? undefined : {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(56, 189, 248, 0.24)' },
              { offset: 1, color: 'rgba(56, 189, 248, 0.02)' }
            ])
          }
        }]
      }, true)
    },
    resizeCharts() {
      ;[this.trendChart, this.waveformChart, this.spectrumChart, this.waterfallChart].forEach(chart => chart && chart.resize())
    },
    disposeCharts() {
      ;['trendChart', 'waveformChart', 'spectrumChart', 'waterfallChart'].forEach(name => {
        if (this[name]) {
          this[name].dispose()
          this[name] = null
        }
      })
    },
    hasSeries(list) {
      return Array.isArray(list) && list.length > 0
    },
    statusClass(item) {
      const status = item && item.status ? item.status : 'offline'
      return `is-${status}`
    },
    statusText(item) {
      const status = item && item.status ? item.status : 'offline'
      const map = { normal: '正常', warning: '预警', alarm: '告警', offline: '离线' }
      return map[status] || '未知'
    },
    alarmText(level) {
      const value = Number(level || 0)
      if (value >= 3) return '告警'
      if (value >= 2) return '预警'
      return '正常'
    },
    riskClass(level) {
      const value = Number(level || 0)
      return value >= 3 ? 'risk-danger' : value >= 2 ? 'risk-warning' : 'risk-normal'
    },
    healthWidth(value) {
      return `${Math.max(0, Math.min(100, Number(value || 0)))}%`
    },
    calcHealth(rms, temp, alarmLevel) {
      const rmsScore = rms == null ? 0.5 : Math.max(0, Math.min(1, 1 - Number(rms) / 8))
      const tempScore = temp == null ? 0.5 : Math.max(0, Math.min(1, 1 - Math.max(0, Number(temp) - 60) / 70))
      const score = Math.round((rmsScore * 0.65 + tempScore * 0.35) * 100)
      if (alarmLevel >= 3) return Math.min(score, 45)
      if (alarmLevel >= 2) return Math.min(score, 70)
      return score
    },
    resolveDiagnosis(rms) {
      if (rms == null) return '暂无最近采样'
      if (Number(rms) >= 6) return '振动超过高高限，建议立即复核轴承、联轴器和基础松动。'
      if (Number(rms) >= 4) return '振动接近告警区间，建议观察趋势并安排点检。'
      return '振动处于可接受范围。'
    },
    formatMetric(value) {
      return value === null || value === undefined || Number.isNaN(Number(value)) ? '--' : Number(value).toFixed(2)
    },
    toNumber(value) {
      return value === null || value === undefined || Number.isNaN(Number(value)) ? null : Number(value)
    },
    formatTime(value) {
      if (!value) return '--'
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return String(value)
      const pad = n => String(n).padStart(2, '0')
      return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    }
  }
}
</script>

<style scoped>
.vibration-console {
  --iiot-bg: linear-gradient(180deg, rgba(9, 17, 29, 0.98), rgba(6, 13, 24, 0.98));
  --iiot-panel-solid: linear-gradient(180deg, rgba(20, 34, 55, 0.96), rgba(11, 21, 37, 0.98));
  --iiot-border: var(--ops-border);
  --iiot-border-active: var(--ops-accent);
  --iiot-text: var(--ops-text);
  --iiot-heading: var(--ops-heading);
  --iiot-muted: var(--ops-muted);
  --iiot-cyan: var(--ops-accent);
  --iiot-success: var(--ops-success);
  --iiot-warning: var(--ops-warning);
  --iiot-danger: var(--ops-danger);
  min-height: calc(100vh - 84px);
  padding: 14px;
  background: var(--iiot-bg);
  color: var(--iiot-text);
}
.command-bar,
.status-ribbon,
.channel-panel,
.analysis-panel,
.insight-panel {
  border: 1px solid var(--iiot-border);
  background: var(--iiot-panel-solid);
  box-shadow: var(--ops-shadow-soft), inset 0 1px 0 rgba(148, 163, 184, 0.04);
}
.command-bar {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 16px 18px;
  border-radius: 16px;
}
.eyebrow {
  color: var(--iiot-cyan);
  font-size: 12px;
  font-weight: 700;
}
.asset-title h2 {
  margin: 4px 0 8px;
  font-size: 24px;
  color: var(--iiot-heading);
}
.asset-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--iiot-muted);
  font-size: 12px;
}
.asset-meta span {
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(8, 16, 28, 0.72);
  border: 1px solid rgba(120, 153, 186, 0.18);
}
.command-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.device-input { width: 150px; }
.window-select { width: 126px; }
.status-ribbon {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  margin: 12px 0;
  border-radius: 16px;
  overflow: hidden;
}
.status-tile {
  padding: 12px 14px;
  background: rgba(8, 16, 28, 0.78);
}
.status-tile span {
  display: block;
  color: var(--iiot-muted);
  font-size: 12px;
}
.status-tile strong {
  display: block;
  margin-top: 4px;
  font-size: 20px;
}
.state-text.online,
.risk-normal { color: var(--iiot-success); }
.state-text.connecting,
.state-text.reconnecting,
.risk-warning { color: var(--iiot-warning); }
.state-text.disconnected,
.risk-danger { color: var(--iiot-danger); }
.console-layout {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: stretch;
}
.channel-panel,
.analysis-panel,
.insight-panel {
  border-radius: 16px;
  padding: 14px;
  min-height: 620px;
}
.panel-head,
.analysis-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 12px;
}
.panel-title {
  font-size: 16px;
  font-weight: 800;
  color: var(--iiot-heading);
}
.panel-subtitle {
  margin-top: 3px;
  color: var(--iiot-muted);
  font-size: 12px;
}
.sort-select { width: 104px; }
.channel-grid {
  display: grid;
  gap: 10px;
}
.channel-card {
  width: 100%;
  padding: 12px;
  text-align: left;
  border: 1px solid var(--iiot-border);
  border-left-width: 5px;
  border-radius: 12px;
  background: var(--iiot-panel-solid);
  cursor: pointer;
  transition: border-color .18s ease, transform .18s ease, box-shadow .18s ease;
}
.channel-card:hover,
.channel-card.active {
  transform: translateY(-1px);
  box-shadow: 0 16px 28px rgba(2, 8, 20, 0.28), 0 0 0 1px rgba(34, 211, 238, 0.12);
  border-color: var(--iiot-border-active);
}
.channel-card.is-normal { border-left-color: var(--iiot-success); }
.channel-card.is-warning { border-left-color: var(--iiot-warning); }
.channel-card.is-alarm { border-left-color: var(--iiot-danger); }
.channel-card.is-offline { border-left-color: var(--iiot-muted); }
.channel-card__top,
.channel-mini,
.kv-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.channel-code {
  font-weight: 800;
  color: var(--iiot-heading);
}
.channel-state {
  color: var(--iiot-muted);
  font-size: 12px;
}
.channel-name {
  margin-top: 8px;
  color: var(--iiot-text);
  font-size: 13px;
}
.channel-value {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 8px;
}
.channel-value strong {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 24px;
}
.channel-value span,
.channel-mini {
  color: var(--iiot-muted);
  font-size: 12px;
}
.health-bar {
  height: 5px;
  margin-top: 10px;
  border-radius: 999px;
  background: rgba(8, 16, 28, 0.76);
  overflow: hidden;
}
.health-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--iiot-danger), var(--iiot-warning), var(--iiot-success));
}
.metric-strip {
  display: grid;
  grid-template-columns: repeat(3, 86px);
  gap: 8px;
}
.metric-strip div {
  padding: 8px;
  border-radius: 10px;
  background: rgba(8, 16, 28, 0.74);
  border: 1px solid rgba(120, 153, 186, 0.16);
}
.metric-strip span {
  display: block;
  color: var(--iiot-muted);
  font-size: 11px;
}
.metric-strip strong {
  display: block;
  margin-top: 2px;
  font-family: Consolas, 'Courier New', monospace;
  color: var(--iiot-heading);
}
.analysis-tabs ::v-deep .el-tabs__header {
  margin-bottom: 10px;
}
.analysis-chart {
  height: 470px;
  border-radius: 14px;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(8, 16, 28, 0.96), rgba(9, 18, 31, 0.86));
  border: 1px solid rgba(120, 153, 186, 0.16);
}
.empty-chart {
  height: 470px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed rgba(34, 211, 238, 0.36);
  border-radius: 14px;
  color: var(--iiot-muted);
  background: rgba(8, 16, 28, 0.72);
  text-align: center;
  padding: 24px;
}
.diagnosis-card {
  margin-top: 12px;
  padding: 14px;
  border-radius: 14px;
  border-left: 5px solid var(--iiot-muted);
  background: rgba(8, 16, 28, 0.72);
}
.diagnosis-card.is-normal { border-left-color: var(--iiot-success); }
.diagnosis-card.is-warning { border-left-color: var(--iiot-warning); }
.diagnosis-card.is-alarm { border-left-color: var(--iiot-danger); }
.diagnosis-card span {
  display: block;
  color: var(--iiot-muted);
  font-size: 12px;
}
.diagnosis-card strong {
  display: block;
  margin-top: 6px;
  line-height: 1.5;
}
.side-section {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--iiot-border);
}
.side-title {
  margin-bottom: 10px;
  font-weight: 800;
  color: var(--iiot-heading);
}
.kv-row {
  padding: 7px 0;
  border-bottom: 1px solid var(--iiot-border);
  color: var(--iiot-muted);
  font-size: 13px;
}
.kv-row strong {
  color: var(--iiot-heading);
  text-align: right;
}
.advice {
  margin: 0;
  color: var(--iiot-text);
  line-height: 1.6;
}
.event-list {
  display: grid;
  gap: 8px;
}
.event-item {
  padding: 10px;
  border-radius: 12px;
  background: rgba(8, 16, 28, 0.72);
  border: 1px solid rgba(120, 153, 186, 0.14);
}
.event-item b,
.event-item span,
.event-item small {
  display: block;
}
.event-item span {
  margin-top: 4px;
  color: var(--iiot-text);
}
.event-item small,
.muted {
  color: var(--iiot-muted);
}
@media (max-width: 1280px) {
  .console-layout {
    grid-template-columns: 320px minmax(0, 1fr);
  }
  .insight-panel {
    grid-column: 1 / -1;
    min-height: auto;
  }
}
@media (max-width: 900px) {
  .command-bar,
  .analysis-head {
    flex-direction: column;
  }
  .status-ribbon,
  .console-layout {
    grid-template-columns: 1fr;
  }
  .channel-panel,
  .analysis-panel {
    min-height: auto;
  }
}
</style>
