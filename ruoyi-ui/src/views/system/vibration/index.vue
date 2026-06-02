<template>
  <div class="app-container vibration-page">
    <el-alert
      v-if="alarmBanner.visible"
      class="alarm-banner"
      :title="alarmBanner.title"
      :type="alarmBanner.type"
      show-icon
      :closable="true"
      @close="alarmBanner.visible = false"
    />

    <el-row :gutter="10">
      <el-col v-for="channel in channels" :key="channel.id" :span="6" class="mb10">
        <el-card shadow="hover" class="channel-card" :class="channel.status" @click.native="openDetail(channel)">
          <div class="channel-head">
            <div class="channel-title">{{ channel.title }}</div>
            <el-tag size="mini" :type="statusTagType(channel.status)">{{ channel.statusLabel }}</el-tag>
          </div>

          <div class="card-body">
            <div class="metric-zone">
              <div class="metric-main">
                <div class="metric-label">RMS</div>
                <div class="metric-value">{{ formatNumber(channel.rms) }}</div>
                <div class="metric-unit">mm/s</div>
              </div>
              <div class="metric-main metric-main--temp">
                <div class="metric-label">Temp</div>
                <div class="metric-value">{{ formatNumber(channel.temp) }}</div>
                <div class="metric-unit">℃</div>
              </div>
            </div>

            <div class="detail-zone">
              <div class="detail-table">
                <div class="detail-row"><span>Peak-Peak</span><strong>{{ formatNumber(channel.peakToPeak) }}</strong></div>
                <div class="detail-row"><span>Peak</span><strong>{{ formatNumber(channel.peak) }}</strong></div>
                <div class="detail-row"><span>频率峰值</span><strong>{{ formatNumber(channel.freqPeak) }}</strong></div>
              </div>
              <div :ref="el => setGaugeRef(el, channel.id)" class="health-gauge"></div>
            </div>
          </div>

          <div class="channel-footer">
            <span class="foot-text">{{ channel.sampleTimeText }}</span>
            <span class="foot-time">健康度 {{ channel.health }}%</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <div class="trend-panel">
      <div class="trend-header">
        <div>
          <div class="trend-title">实时趋势图 · {{ activeChannel.title }}</div>
          <div class="trend-subtitle">左轴振动速度 mm/s，右轴温度 ℃，支持 WebSocket 平滑滚动与 FFT 切换</div>
        </div>
        <el-radio-group v-model="chartMode" size="mini" @change="refreshCharts">
          <el-radio-button label="time">时域</el-radio-button>
          <el-radio-button label="fft">FFT</el-radio-button>
        </el-radio-group>
      </div>
      <div ref="trendChartRef" class="trend-chart"></div>
    </div>

    <div class="alarm-panel">
      <div class="alarm-panel__head">
        <span>近期告警事件</span>
        <el-tag size="mini" type="danger">点击跳转历史片段</el-tag>
      </div>
      <el-table :data="alarmEvents" height="220" size="mini" stripe @row-click="jumpToAlarmEvent">
        <el-table-column prop="timeText" label="时间" min-width="180" />
        <el-table-column prop="channelTitle" label="通道" width="120" />
        <el-table-column prop="levelText" label="级别" width="90">
          <template slot-scope="scope">
            <el-tag :type="alarmTagType(scope.row.level)" size="mini">{{ scope.row.levelText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="告警描述" min-width="220" />
      </el-table>
    </div>

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
            <div class="drawer-title">{{ activeChannel.title }} 详细信息</div>
            <div class="drawer-subtitle">通道 ID：{{ activeChannel.id }} · 最近 100 条历史记录 · 支持时频切换</div>
          </div>
          <div class="drawer-actions">
            <el-tag :type="statusTagType(activeChannel.status)" size="mini">{{ activeChannel.statusLabel }}</el-tag>
            <el-button size="mini" icon="el-icon-close" @click="detailVisible = false">关闭</el-button>
          </div>
        </div>

        <div class="drawer-content">
          <el-row :gutter="12" class="detail-row">
            <el-col :xs="24" :md="8">
              <div class="metric-panel">
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
              </div>
            </el-col>

            <el-col :xs="24" :md="16">
              <div class="drawer-chart-header">
                <el-radio-group v-model="detailChartMode" size="mini" @change="refreshCharts">
                  <el-radio-button label="time">时域波形</el-radio-button>
                  <el-radio-button label="fft">频谱图（FFT）</el-radio-button>
                </el-radio-group>
                <div class="drawer-chart-tip">最高峰已自动标注</div>
              </div>
              <div ref="waveChartRef" class="wave-chart"></div>
            </el-col>
          </el-row>

          <el-card class="log-card" shadow="never">
            <div slot="header" class="card-header">
              <span>最近 100 条历史记录</span>
              <el-button size="mini" type="primary" plain @click="loadHistory(activeChannel.id)">刷新</el-button>
            </div>
            <el-table :data="historyRows" height="300" stripe size="mini" border>
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
              <el-table-column prop="motorSpeed" label="当前电机转速" width="130" align="center">
                <template slot-scope="scope">{{ formatNumber(scope.row.motorSpeed) }}</template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { listVibration } from '@/api/system/vibration'

export default {
  name: 'VibrationIndex',
  data() {
    return {
      ws: null,
      chart: null,
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
    this.$nextTick(() => this.refreshGaugeCharts())
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
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const url = `${protocol}//${window.location.host}/ws/sensor`
      this.ws = new WebSocket(url)
      this.ws.onmessage = this.handleWebSocketMessage
    },
    handleWebSocketMessage(evt) {
      let msg
      try {
        msg = JSON.parse(evt.data)
      } catch (error) {
        return
      }
      const channelId = Number(msg.channelId || msg.channel || msg.id)
      if (!channelId || !this.liveData[channelId]) return

      const channel = this.channels[channelId - 1]
      if (!channel) return

      const rms = this.toNumber(msg.rms ?? msg.vibrationValue ?? msg.speed, channel.rms)
      const temp = this.toNumber(msg.temperatureValue ?? msg.temp, channel.temp)
      const peakToPeak = this.toNumber(msg.displacement ?? msg.peakToPeak ?? msg.pp, channel.peakToPeak)
      const peak = this.toNumber(msg.peak ?? msg.peakValue, channel.peak)
      const freqPeak = this.toNumber(msg.freqPeak ?? msg.frequencyPeak, channel.freqPeak)
      const health = this.calcHealth(rms, temp)
      const status = msg.alarm ? 'abnormal' : health >= 80 ? 'normal' : health >= 60 ? 'warning' : 'abnormal'
      const sampleTime = msg.sampleTime || msg.collectTime || new Date().toISOString()
      const sampleTimeText = this.formatDateTime(sampleTime)

      this.$set(this.liveData, channelId, {
        wave: [
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
    openDetail(channel) {
      this.activeChannelId = channel.id
      this.detailVisible = true
      this.loadHistory(channel.id)
    },
    handleDrawerOpened() {
      this.initChart()
      this.refreshCharts()
    },
    handleDrawerClosed() {
      this.disposeChart()
    },
    loadHistory(channelId) {
      this.historyLoading = true
      listVibration({ pageNum: 1, pageSize: 100, channelId })
        .then(response => {
          const rows = response.rows || []
          this.historyRows = rows.map((item, index) => ({
            ...item,
            sampleTimeText: this.formatDateTime(item.sampleTime || item.collectTime || item.createTime),
            rmsValue: item.rmsValue ?? item.vibrationValue ?? item.rms ?? (Math.random() * 8).toFixed(2),
            peakValue: item.peakValue ?? item.peak ?? (Math.random() * 10).toFixed(2),
            crestFactor: item.crestFactor ?? item.wavePeakFactor ?? (Math.random() * 3 + 1).toFixed(2),
            motorSpeed: item.motorSpeed ?? item.speed ?? 1480 + index
          }))
        })
        .finally(() => {
          this.historyLoading = false
        })
    },
    initChart() {
      this.$nextTick(() => {
        if (this.chart || !this.$refs.waveChartRef) return
        this.chart = echarts.init(this.$refs.waveChartRef)
      })
    },
    refreshCharts() {
      this.$nextTick(() => {
        this.refreshGaugeCharts()
        this.refreshTrendChart()
        this.refreshDetailChart()
      })
    },
    refreshGaugeCharts() {
      this.channels.forEach(channel => {
        const el = this.$refs[`gauge-${channel.id}`] || this.$refs[`gauge-${channel.id}`]
        const dom = this.$refs && this.$refs[`gauge-${channel.id}`]
        const target = dom || this.gaugeCharts[channel.id]?.getDom?.()
        const chartDom = Array.isArray(this.$refs[`gauge-${channel.id}`]) ? this.$refs[`gauge-${channel.id}`][0] : this.$refs[`gauge-${channel.id}`]
        const realDom = chartDom || this.gaugeCharts[channel.id]?.getDom?.()
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
      if (!this.chart) return
      const channel = this.channels[this.activeChannelId - 1]
      const live = this.liveData[this.activeChannelId] || { wave: [], fft: [] }
      if (this.chartMode === 'fft') {
        this.chart.setOption(this.buildFftOption(channel, live.fft), true)
      } else {
        this.chart.setOption(this.buildTrendOption(channel, live.wave), true)
      }
    },
    refreshDetailChart() {
      if (!this.chart) return
      this.refreshTrendChart()
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
        legend: { data: ['振动速度', '温度'], textStyle: { color: '#d9e2e8' } },
        grid: { left: 52, right: 52, top: 42, bottom: 36 },
        xAxis: { type: 'category', boundaryGap: false, data: xData, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: '#d9e2e8' } },
        yAxis: [
          { type: 'value', name: 'mm/s', axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.08)' } }, axisLabel: { color: '#d9e2e8' } },
          { type: 'value', name: '℃', axisLine: { lineStyle: { color: 'rgba(255,255,255,0.18)' } }, splitLine: { show: false }, axisLabel: { color: '#d9e2e8' } }
        ],
        series: [
          {
            name: '振动速度',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: vib,
            lineStyle: { width: 2, color: '#00FFFF' },
            itemStyle: { color: '#00FFFF' },
            areaStyle: { color: 'rgba(0,255,255,0.14)' },
            markLine: {
              symbol: 'none',
              label: { color: '#fff' },
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
            lineStyle: { width: 2, color: '#7dd3fc' },
            itemStyle: { color: '#7dd3fc' }
          }
        ],
        graphic: maxVibIndex >= 0 ? [{ type: 'text', left: 'center', top: 8, style: { text: `峰值点：${xData[maxVibIndex]} / ${vib[maxVibIndex].toFixed(2)}`, fill: '#eaffff', font: '12px sans-serif' } }] : []
      }
    },
    buildFftOption(channel, fftSeries) {
      const points = (fftSeries && fftSeries.length ? fftSeries : this.mockFftSeries(channel.id)).slice(0, 32)
      const maxPoint = points.reduce((acc, cur) => (cur.amp > (acc.amp || -Infinity) ? cur : acc), points[0] || { freq: 0, amp: 0 })
      return {
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        grid: { left: 52, right: 24, top: 42, bottom: 36 },
        xAxis: { type: 'category', name: 'Hz', data: points.map(item => item.freq), axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: '#d9e2e8' } },
        yAxis: { type: 'value', name: '幅值', axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.08)' } }, axisLabel: { color: '#d9e2e8' } },
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
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
    },
    disposeChart() {
      if (this.chart) {
        this.chart.dispose()
        this.chart = null
      }
    },
    handleResize() {
      if (this.chart) this.chart.resize()
      Object.values(this.gaugeCharts).forEach(chart => chart && chart.resize())
    }
  }
}
</script>

<style scoped>
.vibration-page { min-height: calc(100vh - 84px); padding: 10px; background: linear-gradient(180deg, #1f2937 0%, #111827 100%); }
.alarm-banner { margin-bottom: 10px; animation: blink 1s linear infinite; }
.mb10 { margin-bottom: 10px; }
.channel-card { cursor: pointer; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0, 255, 255, 0.22); color: #eef2f7; box-shadow: 0 0 12px rgba(0, 255, 255, 0.08), inset 0 0 18px rgba(0, 255, 255, 0.03); }
.channel-card.abnormal { border-color: rgba(245, 108, 108, 0.65); }
.channel-card.warning { border-color: rgba(230, 162, 60, 0.55); }
.channel-head, .channel-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.channel-title { font-size: 14px; font-weight: 700; }
.card-body { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.metric-zone { display: grid; gap: 8px; }
.metric-main, .main-metric { padding: 10px; background: rgba(0,255,255,0.05); border: 1px solid rgba(0,255,255,0.12); border-radius: 6px; }
.main-metric--temp { background: rgba(125, 211, 252, 0.06); }
.metric-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.metric-value { margin-top: 4px; font-size: 26px; font-weight: 800; line-height: 1.1; color: #f2ffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.metric-value.big { font-size: 38px; }
.metric-unit { margin-top: 2px; font-size: 12px; color: rgba(235,255,255,0.68); }
.detail-zone { display: grid; grid-template-columns: 1fr 92px; gap: 8px; align-items: center; }
.detail-table { display: grid; gap: 6px; }
.detail-row { display: flex; justify-content: space-between; gap: 12px; padding: 6px 8px; background: rgba(1, 12, 28, 0.58); border: 1px solid rgba(0,255,255,0.1); color: rgba(235,255,255,0.9); font-size: 12px; }
.detail-row strong { color: #eaffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.health-gauge { width: 100%; height: 92px; }
.channel-footer { margin-top: 12px; font-size: 12px; color: #aeb7c2; }
.foot-time { color: #e5edf5; }
.trend-panel, .alarm-panel { margin-top: 12px; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); border-radius: 8px; padding: 12px; color: #eef2f7; }
.trend-header, .alarm-panel__head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px; }
.trend-title, .alarm-panel__head { font-size: 16px; font-weight: 700; }
.trend-subtitle { margin-top: 4px; font-size: 12px; color: #aeb7c2; }
.drawer-shell { height: 100vh; display: flex; flex-direction: column; background: linear-gradient(180deg, #111827 0%, #1f2937 100%); color: #eef2f7; }
.drawer-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 20px; border-bottom: 1px solid rgba(0,255,255,0.10); }
.drawer-title { font-size: 20px; font-weight: 800; }
.drawer-subtitle { margin-top: 4px; font-size: 12px; color: #aeb7c2; }
.drawer-actions { display: flex; align-items: center; gap: 10px; }
.drawer-content { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 12px 20px 20px; min-height: 0; }
.metric-panel { display: grid; gap: 8px; }
.sub-metrics { display: grid; gap: 6px; }
.sub-item { display: flex; justify-content: space-between; padding: 7px 10px; background: rgba(0,255,255,0.04); border: 1px solid rgba(0,255,255,0.1); font-size: 12px; }
.sub-item strong { font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.health-box { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; background: rgba(0,255,255,0.03); border: 1px solid rgba(0,255,255,0.14); }
.health-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.health-desc { margin-top: 4px; font-size: 18px; font-weight: 700; color: #eaffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.drawer-chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.drawer-chart-tip { font-size: 12px; color: #aeb7c2; }
.wave-chart { width: 100%; height: 320px; background: rgba(1, 12, 28, 0.78); border-radius: 10px; border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); }
.log-card { margin-top: 12px; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); }
.card-header { display: flex; align-items: center; justify-content: space-between; }
:deep(.vibration-drawer) { background: transparent; }
:deep(.vibration-drawer .el-drawer__body) { height: 100%; }
:deep(.el-table) { background: transparent; color: #eaf0f6; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(0,255,255,0.08); }
:deep(.el-radio-button__inner) { background: #2b3340; color: #eaf0f6; border-color: rgba(255,255,255,0.12); }
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) { background: #409eff; border-color: #409eff; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.65; } }
@media (max-width: 1200px) { .card-body { grid-template-columns: 1fr; } .detail-zone { grid-template-columns: 1fr; } .trend-header, .drawer-topbar { flex-direction: column; align-items: flex-start; } .wave-chart { height: 260px; } }
</style>