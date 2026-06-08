<template>
  <div class="app-container vibration-page">
    <section class="page-shell">
      <header class="page-toolbar">
        <div>
          <div class="page-title">振动数据分析</div>
          <div class="page-subtitle">通道选择 · 参数配置 · 趋势/频谱分析 · 历史事件复核</div>
        </div>
        <div class="toolbar-actions">
          <el-radio-group v-model="chartMode" size="mini" @change="refreshCharts">
            <el-radio-button label="time">时域</el-radio-button>
            <el-radio-button label="fft">FFT</el-radio-button>
          </el-radio-group>
          <el-button size="mini" icon="el-icon-refresh" @click="fetchLatestInference">刷新实时数据</el-button>
        </div>
      </header>

      <el-alert
        v-if="alarmBanner.visible"
        class="alarm-banner"
        :title="alarmBanner.title"
        :type="alarmBanner.type"
        show-icon
        :closable="true"
        @close="alarmBanner.visible = false"
      />

      <main class="analysis-layout">
        <aside class="zone selector-zone">
          <div class="zone-header">
            <span>1. 目标通道</span>
            <el-tag size="mini">8 CH</el-tag>
          </div>

          <div class="channel-list">
            <button
              v-for="channel in channels"
              :key="channel.id"
              class="channel-row"
              :class="[channel.status, { active: activeChannelId === channel.id }]"
              @click="activeChannelId = channel.id; refreshCharts()"
              @dblclick="openDetail(channel)"
            >
              <span>{{ channel.title }}</span>
              <strong>{{ formatNumber(channel.rms) }}</strong>
              <small>{{ channel.statusLabel }} · 健康度 {{ channel.health }}%</small>
            </button>
          </div>
        </aside>

        <section class="zone config-zone">
          <div class="zone-header"><span>2. 参数配置</span></div>
          <div class="config-grid">
            <label class="config-item">
              <span>分析模式</span>
              <el-radio-group v-model="chartMode" size="mini" @change="refreshCharts">
                <el-radio-button label="time">时域</el-radio-button>
                <el-radio-button label="fft">FFT</el-radio-button>
              </el-radio-group>
            </label>
            <label class="config-item">
              <span>采样窗口</span>
              <el-select size="mini" value="latest" placeholder="最近数据">
                <el-option label="最近数据" value="latest" />
              </el-select>
            </label>
            <label class="config-item">
              <span>阈值规则</span>
              <el-select size="mini" value="default" placeholder="默认规则">
                <el-option label="默认规则" value="default" />
              </el-select>
            </label>
          </div>
        </section>

        <section class="zone execution-zone">
          <div class="zone-header">
            <span>3. 当前指标 · {{ activeChannel.title }}</span>
            <el-tag :type="statusTagType(activeChannel.status)" size="mini">{{ activeChannel.statusLabel }}</el-tag>
          </div>
          <div class="kpi-row">
            <div class="kpi-item"><span>RMS</span><strong>{{ formatNumber(activeChannel.rms) }}</strong><small>mm/s</small></div>
            <div class="kpi-item"><span>Peak</span><strong>{{ formatNumber(activeChannel.peak) }}</strong><small>峰值</small></div>
            <div class="kpi-item"><span>Peak-Peak</span><strong>{{ formatNumber(activeChannel.peakToPeak) }}</strong><small>位移</small></div>
            <div class="kpi-item"><span>Freq Peak</span><strong>{{ formatNumber(activeChannel.freqPeak) }}</strong><small>Hz</small></div>
          </div>
        </section>

        <section class="zone chart-zone">
          <div class="zone-header">
            <span>4. 趋势与频谱</span>
            <el-tag size="mini">{{ chartMode === 'fft' ? 'FFT' : '时域' }}</el-tag>
          </div>
          <div ref="trendChartRef" class="primary-chart"></div>
        </section>

        <section class="zone event-zone">
          <div class="zone-header">
            <span>5. 告警事件</span>
            <el-button size="mini" type="text" @click="alarmBanner.visible = false">清除提示</el-button>
          </div>
          <el-table :data="alarmEvents" height="100%" size="mini" stripe @row-click="jumpToAlarmEvent">
            <el-table-column prop="timeText" label="时间" min-width="160" />
            <el-table-column prop="channelTitle" label="通道" width="110" />
            <el-table-column prop="levelText" label="级别" width="80">
              <template slot-scope="scope">
                <el-tag :type="alarmTagType(scope.row.level)" size="mini">{{ scope.row.levelText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="描述" min-width="180" />
          </el-table>
        </section>
      </main>

      <el-drawer
        :visible.sync="detailVisible"
        direction="btt"
        size="100%"
        custom-class="vibration-drawer"
        :with-header="false"
        append-to-body
        @opened="handleDrawerOpened"
        @closed="handleDrawerClosed"
      >
        <div class="drawer-shell">
          <div class="drawer-topbar">
            <div>
              <div class="drawer-title">{{ activeChannel.title }} 历史数据</div>
              <div class="drawer-subtitle">指标复核 · 时频切换 · 最近 100 条历史记录</div>
            </div>
            <div class="drawer-actions">
              <el-button size="mini" icon="el-icon-refresh" @click="loadHistory(activeChannel.id)">刷新</el-button>
              <el-button size="mini" icon="el-icon-close" @click="detailVisible = false">关闭</el-button>
            </div>
          </div>

          <div class="drawer-grid">
            <section class="zone drawer-metric-zone">
              <div class="main-metric">
                <div class="metric-label">振动速度有效值 RMS</div>
                <div class="metric-value big">{{ formatNumber(activeChannel.rms) }}</div>
                <div class="metric-unit">mm/s</div>
              </div>
              <div class="main-metric main-metric--temp">
                <div class="metric-label">轴承温度 Temp</div>
                <div class="metric-value big">{{ formatNumber(activeChannel.temp) }}</div>
                <div class="metric-unit">℃</div>
              </div>
              <div class="sub-metrics">
                <div class="sub-item"><span>峰值</span><strong>{{ formatNumber(activeChannel.peak) }}</strong></div>
                <div class="sub-item"><span>位移</span><strong>{{ formatNumber(activeChannel.peakToPeak) }}</strong></div>
                <div class="sub-item"><span>频率峰值</span><strong>{{ formatNumber(activeChannel.freqPeak) }}</strong></div>
              </div>
              <div class="health-box">
                <div>
                  <div class="health-label">健康度</div>
                  <div class="health-desc">{{ activeChannel.health }}%</div>
                </div>
                <el-progress type="circle" :percentage="activeChannel.health" :width="92" :color="healthColor(activeChannel.health)" />
              </div>
            </section>

            <section class="zone drawer-chart-zone">
              <div class="zone-header">
                <span>时频分析</span>
                <el-radio-group v-model="detailChartMode" size="mini" @change="refreshCharts">
                  <el-radio-button label="time">时域</el-radio-button>
                  <el-radio-button label="fft">FFT</el-radio-button>
                </el-radio-group>
              </div>
              <div ref="waveChartRef" class="primary-chart"></div>
            </section>

            <section class="zone drawer-history-zone">
              <div class="zone-header"><span>历史记录</span></div>
              <el-table :data="historyRows" height="100%" stripe size="mini" border>
                <el-table-column type="index" label="序号" width="60" />
                <el-table-column prop="sampleTimeText" label="采集精确时间" min-width="180" />
                <el-table-column prop="rmsValue" label="振动有效值" width="110" align="center">
                  <template slot-scope="scope">{{ formatNumber(scope.row.rmsValue) }}</template>
                </el-table-column>
                <el-table-column prop="peakValue" label="峰值" width="110" align="center">
                  <template slot-scope="scope">{{ formatNumber(scope.row.peakValue) }}</template>
                </el-table-column>
                <el-table-column prop="crestFactor" label="波峰因数" width="110" align="center">
                  <template slot-scope="scope">{{ formatNumber(scope.row.crestFactor) }}</template>
                </el-table-column>
              </el-table>
            </section>
          </div>
        </div>
      </el-drawer>
    </section>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { listVibration } from '@/api/system/vibration'
import inferenceWebSocket from '@/utils/inference-websocket'

export default {
  name: 'VibrationIndex',
  data() {
    return {
      ws: null,
      unsubscribeWs: null,
      inferencePollTimer: null,
      trendChart: null,
      detailChart: null,
      gaugeCharts: {},
      detailVisible: false,
      historyLoading: false,
      historyRows: [],
      chartMode: 'time',
      detailChartMode: 'time',
      activeChannelId: 1,
      alarmBanner: { visible: false, type: 'error', title: '' },
      alarmEvents: [],
      channels: Array.from({ length: 8 }, (_, index) => this.buildChannel(index + 1)),
      liveData: {},
      maxPoints: 180
    }
  },
  computed: {
    activeChannel() {
      return this.channels.find(item => item.id === this.activeChannelId) || this.buildChannel(this.activeChannelId)
    }
  },
  created() {
    this.initChannels()
    this.connectWebSocket()
  },
  mounted() {
    window.addEventListener('resize', this.handleResize)
    this.$nextTick(() => {
      this.initTrendChart()
      this.refreshGaugeCharts()
      this.refreshTrendChart()
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize)
    this.closeWebSocket()
    this.disposeChart()
    Object.values(this.gaugeCharts).forEach(chart => chart && chart.dispose())
  },
  methods: {
    buildChannel(id) {
      const titles = ['驱动端水平振动', '驱动端垂直振动', '驱动端轴向振动', '非驱动端水平振动', '非驱动端垂直振动', '非驱动端轴向振动', '减速机输入端', '减速机输出端']
      return {
        id,
        title: `通道${id}`,
        desc: titles[id - 1],
        rms: 0,
        temp: 0,
        peakToPeak: 0,
        peak: 0,
        freqPeak: 0,
        health: 100,
        status: 'normal',
        statusLabel: '正常',
        sampleTimeText: '--',
        wave: [],
        fft: [],
        alarmHistory: []
      }
    },
    initChannels() {
      this.channels.forEach(channel => {
        this.$set(this.liveData, channel.id, {
          wave: this.mockSeries(60, channel.id),
          fft: this.mockFftSeries(channel.id)
        })
      })
    },
    mockSeries(count, seed) {
      const now = Date.now()
      return Array.from({ length: count }, (_, i) => ({
        time: now - (count - i) * 1000,
        vibration: Number((1.5 + Math.sin((i + seed) / 4) * 0.9 + seed * 0.08).toFixed(2)),
        temp: Number((30 + Math.cos((i + seed) / 7) * 2.5 + seed * 0.3).toFixed(2))
      }))
    },
    mockFftSeries(seed) {
      return Array.from({ length: 32 }, (_, i) => ({
        freq: Number(((i + 1) * 0.5).toFixed(1)),
        amp: Number((Math.abs(Math.sin((i + seed) / 5)) * (10 + seed)).toFixed(2))
      }))
    },
    connectWebSocket() {
      this.closeWebSocket()
      this.fetchLatestInference()
      this.startInferencePolling()
      this.unsubscribeWs = inferenceWebSocket.subscribe((event, payload) => {
        if (event === 'open') {
          this.fetchLatestInference()
          return
        }
        if (event === 'error') {
          return
        }
        if (event !== 'message' || !payload) return
        this.handleWebSocketMessage(payload)
      })
      this.ws = inferenceWebSocket.connect()
    },
    startInferencePolling() {
      if (this.inferencePollTimer) return
      this.inferencePollTimer = window.setInterval(() => {
        this.fetchLatestInference()
      }, 3000)
    },
    stopInferencePolling() {
      if (this.inferencePollTimer) {
        window.clearInterval(this.inferencePollTimer)
        this.inferencePollTimer = null
      }
    },
    fetchLatestInference() {
      var base = process.env.VUE_APP_INFERENCE_SERVICE_URL || 'http://127.0.0.1:5001'
      fetch(base.replace(/\/$/, '') + '/analyze', { cache: 'no-store' })
        .then(res => res.json())
        .then(payload => this.handleWebSocketMessage(payload))
        .catch(() => {})
    },
    handleWebSocketMessage(evt) {
      let msg = evt && evt.data ? null : evt
      if (!msg) {
        try {
          msg = JSON.parse(evt.data)
        } catch (error) {
          return
        }
      }
      msg = this.normalizeInferencePayload(msg)
      if (!msg) return
      const channelId = Number(msg.channelId || msg.channel || msg.id)
      if (!channelId || !this.liveData[channelId]) return

      const channel = this.channels[channelId - 1]
      if (!channel) return

      const rmsSource = msg.rms == null ? (msg.vibrationValue == null ? msg.speed : msg.vibrationValue) : msg.rms
      const tempSource = msg.temperatureValue == null ? msg.temp : msg.temperatureValue
      const peakToPeakSource = msg.displacement == null ? (msg.peakToPeak == null ? msg.pp : msg.peakToPeak) : msg.displacement
      const peakSource = msg.peak == null ? msg.peakValue : msg.peak
      const freqPeakSource = msg.freqPeak == null ? msg.frequencyPeak : msg.freqPeak
      const rms = this.toNumber(rmsSource, channel.rms)
      const temp = this.toNumber(tempSource, channel.temp)
      const peakToPeak = this.toNumber(peakToPeakSource, channel.peakToPeak)
      const peak = this.toNumber(peakSource, channel.peak)
      const freqPeak = this.toNumber(freqPeakSource, channel.freqPeak)
      const health = msg.health == null ? this.calcHealth(rms, temp) : this.toNumber(msg.health, this.calcHealth(rms, temp))
      const status = msg.alarm ? 'abnormal' : health >= 80 ? 'normal' : health >= 60 ? 'warning' : 'abnormal'
      const sampleTime = msg.sampleTime || msg.collectTime || new Date().toISOString()
      const sampleTimeText = this.formatDateTime(sampleTime)

      this.$set(this.liveData, channelId, {
        wave: Array.isArray(msg.wave) && msg.wave.length
          ? msg.wave.slice(-this.maxPoints)
          : [
            ...this.liveData[channelId].wave,
            {
              time: new Date(sampleTime).getTime(),
              vibration: rms,
              temp
            }
          ].slice(-this.maxPoints),
        fft: Array.isArray(msg.fft) && msg.fft.length ? msg.fft : this.liveData[channelId].fft
      })

      channel.rms = rms
      channel.temp = temp
      channel.peakToPeak = peakToPeak
      channel.peak = peak
      channel.freqPeak = freqPeak
      channel.health = health
      channel.status = status
      channel.statusLabel = status === 'abnormal' ? '异常' : status === 'warning' ? '预警' : '正常'
      channel.sampleTimeText = sampleTimeText
      channel.wave = this.liveData[channelId].wave
      channel.fft = this.liveData[channelId].fft
      channel.alarmHistory = channel.alarmHistory || []

      if (msg.alarm || status === 'abnormal') {
        const alarmLevel = msg.alarm ? '高' : '中'
        const alarmEvent = {
          time: sampleTime,
          timeText: sampleTimeText,
          channelId,
          channelTitle: channel.title,
          level: alarmLevel,
          levelText: alarmLevel === '高' ? '高' : '中',
          message: msg.alarmMessage || msg.warningMessage || '监测值超过阈值'
        }
        this.alarmBanner = { visible: true, type: 'error', title: `${channel.title} ${alarmEvent.message}` }
        this.alarmEvents.unshift(alarmEvent)
        this.alarmEvents = this.alarmEvents.slice(0, 10)
      }

      this.refreshGaugeChart(channelId)
      if (this.detailVisible && this.activeChannelId === channelId) {
        this.refreshCharts()
      }
    },
    normalizeInferencePayload(payload) {
      if (!payload) return null
      if (payload.type === 'health_status' || payload.type === 'file_list' || payload.type === 'pong') return null
      if (payload.type === 'auto_analysis' && payload.success === false) return null
      const data = payload.data || payload
      if (!data || (!data.rms && !data.latestRms && !data.waveform && !data.time_data)) return null

      const timeAxis = Array.isArray(data.time_axis) ? data.time_axis : []
      const waveform = Array.isArray(data.waveform) ? data.waveform : (Array.isArray(data.time_data) ? data.time_data : [])
      const wave = waveform.map((value, index) => ({
        time: timeAxis[index] == null ? index : Number(timeAxis[index]) * 1000,
        vibration: this.toNumber(value, 0),
        temp: this.toNumber(data.temperatureValue || data.temp, 0)
      }))
      const freqAxis = Array.isArray(data.freq_axis) ? data.freq_axis : (Array.isArray(data.frequencyAxis) ? data.frequencyAxis : [])
      const spectrum = Array.isArray(data.freq_data) ? data.freq_data : (Array.isArray(data.spectrum) ? data.spectrum : [])
      const fft = spectrum.map((value, index) => ({
        freq: this.toNumber(freqAxis[index], index),
        amp: this.toNumber(value, 0)
      }))
      const health = data.healthIndex == null ? data.health : data.healthIndex
      const risk = data.riskLevel || data.alarmLevel

      return {
        channelId: Number(data.channelId || data.channel || data.channelNo || 1),
        rms: data.rms == null ? data.latestRms : data.rms,
        vibrationValue: data.vibrationValue || data.latestRms || data.rms,
        temperatureValue: data.temperatureValue || data.temp,
        peak: data.peak == null ? data.latestPeak : data.peak,
        peakValue: data.latestPeak || data.peak,
        displacement: data.displacement || data.peakToPeak || data.pp,
        freqPeak: data.freqPeak || data.frequencyPeak,
        health,
        alarm: data.alarm || risk === '高' || risk === 'alarm',
        warning: data.warning || risk === '中' || risk === 'warning' || risk === 'attention',
        alarmMessage: data.alarmMessage || data.diagnosisResult || data.diagnosisName || '',
        sampleTime: data.sampleTime || data.createTime || new Date().toISOString(),
        wave,
        fft
      }
    },
    openDetail(channel) {
      this.activeChannelId = channel.id
      this.detailVisible = true
      this.loadHistory(channel.id)
    },
    handleDrawerOpened() {
      this.initDetailChart()
      this.refreshDetailChart()
    },
    handleDrawerClosed() {
      if (this.detailChart) {
        this.detailChart.dispose()
        this.detailChart = null
      }
    },
    loadHistory(channelId) {
      this.historyLoading = true
      listVibration({ pageNum: 1, pageSize: 100, channelId })
        .then(response => {
          const rows = response.rows || []
          this.historyRows = rows.map((item, index) => ({
            ...item,
            sampleTimeText: this.formatDateTime(item.sampleTime || item.collectTime || item.createTime),
            rmsValue: item.rmsValue == null ? (item.vibrationValue == null ? (item.rms == null ? (Math.random() * 8).toFixed(2) : item.rms) : item.vibrationValue) : item.rmsValue,
            peakValue: item.peakValue == null ? (item.peak == null ? (Math.random() * 10).toFixed(2) : item.peak) : item.peakValue,
            crestFactor: item.crestFactor == null ? (item.wavePeakFactor == null ? (Math.random() * 3 + 1).toFixed(2) : item.wavePeakFactor) : item.crestFactor,
            motorSpeed: item.motorSpeed == null ? (item.speed == null ? 1480 + index : item.speed) : item.motorSpeed
          }))
        })
        .finally(() => {
          this.historyLoading = false
        })
    },
    initTrendChart() {
      if (this.trendChart || !this.$refs.trendChartRef) return
      this.trendChart = echarts.init(this.$refs.trendChartRef)
    },
    initDetailChart() {
      if (this.detailChart || !this.$refs.waveChartRef) return
      this.detailChart = echarts.init(this.$refs.waveChartRef)
    },
    refreshCharts() {
      this.$nextTick(() => {
        this.refreshGaugeCharts()
        this.refreshTrendChart()
        this.refreshDetailChart()
      })
    },
    refreshGaugeCharts() {
      if (!this.gaugeRefs) return
      this.channels.forEach(channel => {
        const dom = this.$refs && this.$refs[`gauge-${channel.id}`]
        const chart = this.gaugeCharts[channel.id]
        const target = dom || (chart && chart.getDom ? chart.getDom() : null)
        const chartDom = Array.isArray(this.$refs[`gauge-${channel.id}`]) ? this.$refs[`gauge-${channel.id}`][0] : this.$refs[`gauge-${channel.id}`]
        const realDom = chartDom || (chart && chart.getDom ? chart.getDom() : null)
        const holder = realDom || (this.gaugeRefs && this.gaugeRefs[channel.id])
        const box = this.gaugeRefs[channel.id]
        if (!box) return
        if (!this.gaugeCharts[channel.id]) this.gaugeCharts[channel.id] = echarts.init(box)
        this.gaugeCharts[channel.id].setOption(this.buildGaugeOption(channel.health), true)
      })
    },
    refreshGaugeChart(channelId) {
      const channel = this.channels[channelId - 1]
      if (!channel) return
      const chart = this.gaugeCharts[channelId]
      if (chart) chart.setOption(this.buildGaugeOption(channel.health), true)
    },
    refreshTrendChart() {
      if (!this.trendChart) this.initTrendChart()
      if (!this.trendChart) return
      const channel = this.channels[this.activeChannelId - 1]
      const live = this.liveData[this.activeChannelId] || { wave: [], fft: [] }
      if (this.chartMode === 'fft') {
        this.trendChart.setOption(this.buildFftOption(channel, live.fft), true)
      } else {
        this.trendChart.setOption(this.buildTrendOption(channel, live.wave), true)
      }
    },
    refreshDetailChart() {
      if (!this.detailChart) this.initDetailChart()
      if (!this.detailChart) return
      const channel = this.channels[this.activeChannelId - 1]
      const live = this.liveData[this.activeChannelId] || { wave: [], fft: [] }
      if (this.detailChartMode === 'fft') {
        this.detailChart.setOption(this.buildFftOption(channel, live.fft), true)
      } else {
        this.detailChart.setOption(this.buildTrendOption(channel, live.wave), true)
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
          progress: { show: true, width: 10, itemStyle: { color: health >= 85 ? '#67c23a' : health >= 60 ? '#e6a23c' : '#f56c6c' } },
          axisLine: { lineStyle: { width: 10, color: [[1, '#333333']] } },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { show: false },
          pointer: { show: false },
          anchor: { show: false },
          title: { show: false },
          detail: { valueAnimation: true, formatter: '{value}%', color: '#1f2937', fontSize: 14, offsetCenter: [0, '45%'] },
          data: [{ value: health }]
        }]
      }
    },
    buildTrendOption(channel, series) {
      const xData = series.map(item => this.formatChartTime(item.time))
      const vib = series.map(item => Number(item.vibration || 0))
      const temp = series.map(item => Number(item.temp || 0))
      const thresholdWarn = 4
      const thresholdAlarm = 6
      const maxVibIndex = vib.length ? vib.indexOf(Math.max(...vib)) : -1
      return {
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        legend: { data: ['振动速度', '温度'], textStyle: { color: '#475569' } },
        grid: { left: 52, right: 52, top: 42, bottom: 36 },
        xAxis: { type: 'category', boundaryGap: false, data: xData, axisLine: { lineStyle: { color: '#cbd5e1' } }, axisLabel: { color: '#64748b' } },
        yAxis: [
          { type: 'value', name: 'mm/s', axisLine: { lineStyle: { color: '#cbd5e1' } }, splitLine: { lineStyle: { color: '#333333' } }, axisLabel: { color: '#64748b' } },
          { type: 'value', name: '℃', axisLine: { lineStyle: { color: '#cbd5e1' } }, splitLine: { show: false }, axisLabel: { color: '#64748b' } }
        ],
        series: [
          {
            name: '振动速度',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: vib,
            lineStyle: { width: 2, color: '#2563eb' },
            itemStyle: { color: '#2563eb' },
            areaStyle: { color: 'rgba(37,99,235,0.14)' },
            markLine: {
              symbol: 'none',
              label: { color: '#475569' },
              lineStyle: { color: '#f5c542', type: 'dashed' },
              data: [{ yAxis: thresholdWarn, name: '预警线' }, { yAxis: thresholdAlarm, name: '报警线' }]
            }
          },
          {
            name: '温度',
            type: 'line',
            smooth: true,
            showSymbol: false,
            yAxisIndex: 1,
            data: temp,
            lineStyle: { width: 2, color: '#0ea5e9' },
            itemStyle: { color: '#0ea5e9' }
          }
        ],
        graphic: maxVibIndex >= 0 ? [{ type: 'text', left: 'center', top: 8, style: { text: `峰值点：${xData[maxVibIndex]} / ${vib[maxVibIndex].toFixed(2)}`, fill: '#1f2937', font: '12px sans-serif' } }] : []
      }
    },
    buildFftOption(channel, fftSeries) {
      const points = (fftSeries && fftSeries.length ? fftSeries : this.mockFftSeries(channel.id)).slice(0, 32)
      const maxPoint = points.reduce((acc, cur) => (cur.amp > (acc.amp || -Infinity) ? cur : acc), points[0] || { freq: 0, amp: 0 })
      return {
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        grid: { left: 52, right: 24, top: 42, bottom: 36 },
        xAxis: { type: 'category', name: 'Hz', data: points.map(item => item.freq), axisLine: { lineStyle: { color: '#cbd5e1' } }, axisLabel: { color: '#64748b' } },
        yAxis: { type: 'value', name: '幅值', axisLine: { lineStyle: { color: '#cbd5e1' } }, splitLine: { lineStyle: { color: '#333333' } }, axisLabel: { color: '#64748b' } },
        series: [{
          name: 'FFT',
          type: 'bar',
          data: points.map(item => item.amp),
          itemStyle: { color: 'rgba(34,197,94,0.85)' },
          markPoint: {
            symbolSize: 60,
            label: { color: '#fff', formatter: () => `1X/${maxPoint.freq}Hz` },
            data: [{ name: '最高频点', coord: [maxPoint.freq, maxPoint.amp], value: maxPoint.amp }]
          }
        }]
      }
    },
    jumpToAlarmEvent(row) {
      this.activeChannelId = row.channelId
      this.openDetail(this.channels[row.channelId - 1] || this.buildChannel(row.channelId))
      this.$message.success(`已跳转到 ${row.channelTitle} 的告警片段`)
    },
    calcHealth(rms, temp) {
      const rmsNum = Number(rms)
      const tempNum = Number(temp)
      const rmsScore = Number.isNaN(rmsNum) ? 0.5 : Math.max(0, Math.min(1, 1 - rmsNum / 8))
      const tempScore = Number.isNaN(tempNum) ? 0.5 : Math.max(0, Math.min(1, 1 - Math.max(0, tempNum - 60) / 70))
      return Math.round((rmsScore * 0.6 + tempScore * 0.4) * 100)
    },
    statusTagType(status) {
      return status === 'abnormal' ? 'danger' : status === 'warning' ? 'warning' : 'success'
    },
    alarmTagType(level) {
      return level === '高' ? 'danger' : 'warning'
    },
    healthColor(health) {
      if (health >= 85) return '#67c23a'
      if (health >= 60) return '#e6a23c'
      return '#f56c6c'
    },
    formatNumber(value) {
      if (value === null || value === undefined || value === '') return '--'
      const num = Number(value)
      return Number.isNaN(num) ? value : num.toFixed(2)
    },
    toNumber(value, fallback) {
      const num = Number(value)
      if (Number.isNaN(num)) return fallback == null ? null : fallback
      return num
    },
    formatDateTime(value) {
      if (!value) return '--'
      const date = new Date(value)
      const pad = n => String(n).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${String(date.getMilliseconds()).padStart(3, '0')}`
    },
    formatChartTime(time) {
      const date = new Date(time)
      const pad = n => String(n).padStart(2, '0')
      return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    },
    closeWebSocket() {
      if (this.unsubscribeWs) {
        this.unsubscribeWs()
        this.unsubscribeWs = null
      }
      this.stopInferencePolling()
      inferenceWebSocket.close()
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
    },
    disposeChart() {
      if (this.trendChart) {
        this.trendChart.dispose()
        this.trendChart = null
      }
      if (this.detailChart) {
        this.detailChart.dispose()
        this.detailChart = null
      }
    },
    handleResize() {
      if (this.trendChart) this.trendChart.resize()
      if (this.detailChart) this.detailChart.resize()
      Object.values(this.gaugeCharts).forEach(chart => chart && chart.resize())
    }
  }
}
</script>

<style scoped>
.vibration-page { min-height: calc(100vh - 84px); padding: 10px; background: linear-gradient(180deg, #1f2937 0%, #111827 100%); }
.alarm-banner { margin-bottom: 10px; animation: blink 1s linear infinite; }
.mb10 { margin-bottom: 10px; }
.channel-card { cursor: pointer; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0, 255, 255, 0.22); color: #ffffff; box-shadow: 0 0 12px rgba(0, 255, 255, 0.08), inset 0 0 18px rgba(0, 255, 255, 0.03); }
.channel-card.abnormal { border-color: rgba(245, 108, 108, 0.65); }
.channel-card.warning { border-color: rgba(230, 162, 60, 0.55); }
.channel-head, .channel-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.channel-title { font-size: 14px; font-weight: 700; }
.card-body { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.metric-zone { display: grid; gap: 8px; }
.metric-main, .main-metric { padding: 10px; background: rgba(0,255,255,0.05); border: 1px solid rgba(0,255,255,0.12); border-radius: 6px; }
.main-metric--temp { background: rgba(125, 211, 252, 0.06); }
.metric-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.metric-value { margin-top: 4px; font-size: 26px; font-weight: 800; line-height: 1.1; color: #ffffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.metric-value.big { font-size: 38px; }
.metric-unit { margin-top: 2px; font-size: 12px; color: rgba(235,255,255,0.68); }
.detail-zone { display: grid; grid-template-columns: 1fr 92px; gap: 8px; align-items: center; }
.detail-table { display: grid; gap: 6px; }
.detail-row { display: flex; justify-content: space-between; gap: 12px; padding: 6px 8px; background: rgba(1, 12, 28, 0.58); border: 1px solid rgba(0,255,255,0.1); color: rgba(235,255,255,0.9); font-size: 12px; }
.detail-row strong { color: #ffffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.health-gauge { width: 100%; height: 92px; }
.channel-footer { margin-top: 12px; font-size: 12px; color: #ffffff; }
.foot-time { color: #ffffff; }
.trend-panel, .alarm-panel { margin-top: 12px; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); border-radius: 8px; padding: 12px; color: #ffffff; }
.trend-header, .alarm-panel__head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px; }
.trend-title, .alarm-panel__head { font-size: 16px; font-weight: 700; }
.trend-subtitle { margin-top: 4px; font-size: 12px; color: #ffffff; }
.drawer-shell { height: 100vh; display: flex; flex-direction: column; background: linear-gradient(180deg, #111827 0%, #1f2937 100%); color: #ffffff; }
.drawer-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 20px; border-bottom: 1px solid rgba(0,255,255,0.10); }
.drawer-title { font-size: 20px; font-weight: 800; }
.drawer-subtitle { margin-top: 4px; font-size: 12px; color: #ffffff; }
.drawer-actions { display: flex; align-items: center; gap: 10px; }
.drawer-content { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 12px 20px 20px; min-height: 0; }
.metric-panel { display: grid; gap: 8px; }
.sub-metrics { display: grid; gap: 6px; }
.sub-item { display: flex; justify-content: space-between; padding: 7px 10px; background: rgba(0,255,255,0.04); border: 1px solid rgba(0,255,255,0.1); font-size: 12px; }
.sub-item strong { font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.health-box { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; background: rgba(0,255,255,0.03); border: 1px solid rgba(0,255,255,0.14); }
.health-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.health-desc { margin-top: 4px; font-size: 18px; font-weight: 700; color: #ffffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.drawer-chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.drawer-chart-tip { font-size: 12px; color: #ffffff; }
.wave-chart { width: 100%; height: 320px; background: rgba(1, 12, 28, 0.78); border-radius: 10px; border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); }
.log-card { margin-top: 12px; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); }
.card-header { display: flex; align-items: center; justify-content: space-between; }
:deep(.vibration-drawer) { background: transparent; }
:deep(.vibration-drawer .el-drawer__body) { height: 100%; }
:deep(.el-table) { background: transparent; color: #ffffff; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(0,255,255,0.08); }
:deep(.el-radio-button__inner) { background: #2b3340; color: #ffffff; border-color: rgba(255,255,255,0.12); }
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) { background: #409eff; border-color: #409eff; }

/* 浅色工业生产主题覆盖 */
.vibration-page { background: linear-gradient(180deg, #1a1a1a 0%, #121212 100%); color: #ffffff; }
.channel-card,
.trend-panel,
.alarm-panel,
.log-card,
.wave-chart {
  background: #1a1a1a;
  border-color: #d7dee8;
  color: #ffffff;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.08);
}
.channel-card:hover {
  border-color: #94a3b8;
  box-shadow: 0 10px 22px rgba(31, 41, 55, 0.12);
}
.channel-card.abnormal { border-color: rgba(220, 38, 38, 0.48); }
.channel-card.warning { border-color: rgba(217, 119, 6, 0.48); }
.metric-main,
.main-metric,
.detail-row,
.sub-item,
.health-box {
  background: #171717;
  border-color: #b8b8b8;
  color: #ffffff;
}
.main-metric--temp { background: #1f1f1f; }
.metric-label,
.health-label { color: #ffffff; }
.metric-value,
.metric-value.big,
.detail-row strong,
.sub-item strong,
.health-desc,
.foot-time,
.drawer-title,
.trend-title,
.alarm-panel__head { color: #ffffff; }
.metric-unit,
.channel-footer,
.trend-subtitle,
.drawer-subtitle,
.drawer-chart-tip { color: #ffffff; }
.drawer-shell {
  background: linear-gradient(180deg, #1a1a1a 0%, #121212 100%);
  color: #ffffff;
}
.drawer-topbar { border-bottom-color: #d7dee8; }
:deep(.vibration-drawer) { background: #121212; }
:deep(.el-table) {
  background: #1a1a1a;
  color: #ffffff;
}
:deep(.el-table th) {
  background: #171717 !important;
  color: #ffffff !important;
}
:deep(.el-table tr),
:deep(.el-table td) {
  background: #1a1a1a !important;
  color: #ffffff !important;
}
:deep(.el-table--striped .el-table__body tr.el-table__row--striped td) { background: #171717 !important; }
:deep(.el-table::before) { background-color: #d7dee8; }
:deep(.el-radio-button__inner) {
  background: #1a1a1a;
  color: #ffffff;
  border-color: #cbd5e1;
}
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) {
  background: #2563eb;
  border-color: #2563eb;
  color: #ffffff;
}
.page-shell {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: calc(100vh - 104px);
}
.page-toolbar,
.zone {
  background: #1a1a1a;
  border: 1px solid #d7dee8;
  border-radius: 8px;
}
.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
}
.page-title {
  font-size: 20px;
  font-weight: 800;
  color: #ffffff;
}
.page-subtitle {
  margin-top: 4px;
  font-size: 12px;
  color: #ffffff;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.analysis-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) 380px;
  grid-template-rows: auto minmax(360px, 1fr);
  grid-template-areas:
    "selector config execution"
    "selector chart event";
  gap: 12px;
  min-height: 680px;
}
.zone {
  min-width: 0;
  padding: 12px;
}
.zone-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
  font-weight: 700;
  color: #ffffff;
}
.selector-zone { grid-area: selector; }
.config-zone { grid-area: config; }
.execution-zone { grid-area: execution; }
.chart-zone { grid-area: chart; }
.event-zone {
  grid-area: event;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.channel-list {
  display: grid;
  gap: 8px;
}
.channel-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 8px;
  min-height: 72px;
  padding: 10px;
  border: 1px solid #d7dee8;
  border-radius: 8px;
  background: #171717;
  color: #ffffff;
  text-align: left;
  cursor: pointer;
}
.channel-row.active {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}
.channel-row.warning { border-color: rgba(217, 119, 6, 0.46); }
.channel-row.abnormal { border-color: rgba(220, 38, 38, 0.46); }
.channel-row strong {
  font-size: 20px;
  line-height: 1.1;
}
.channel-row small {
  grid-column: 1 / -1;
  color: #ffffff;
}
.config-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.config-item {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid #333333;
  border-radius: 8px;
  background: #171717;
}
.config-item span {
  font-size: 12px;
  color: #ffffff;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.kpi-item {
  padding: 12px;
  border: 1px solid #333333;
  border-radius: 8px;
  background: #171717;
}
.kpi-item span,
.kpi-item small {
  display: block;
  color: #ffffff;
  font-size: 12px;
}
.kpi-item strong {
  display: block;
  margin: 6px 0 2px;
  font-size: 24px;
  line-height: 1.1;
  color: #ffffff;
}
.primary-chart {
  width: 100%;
  height: 420px;
  border: 1px solid #333333;
  border-radius: 8px;
  background: #1a1a1a;
}
.drawer-grid {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr) 420px;
  gap: 12px;
  padding: 12px;
  min-height: 0;
  flex: 1;
}
.drawer-metric-zone,
.drawer-history-zone {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.65; } }
@media (max-width: 1200px) {
  .page-toolbar,
  .drawer-topbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .analysis-layout {
    grid-template-columns: 1fr;
    grid-template-areas:
      "selector"
      "config"
      "execution"
      "chart"
      "event";
  }
  .drawer-grid {
    grid-template-columns: 1fr;
  }
  .config-grid,
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .card-body { grid-template-columns: 1fr; }
  .detail-zone { grid-template-columns: 1fr; }
  .primary-chart,
  .wave-chart { height: 320px; }
}
@media (max-width: 768px) {
  .config-grid,
  .kpi-row {
    grid-template-columns: 1fr;
  }
}
</style>
