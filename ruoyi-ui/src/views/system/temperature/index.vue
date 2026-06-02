<template>
  <div class="app-container temperature-page">
    <el-alert
      v-if="superEgoAlert.visible"
      class="super-ego-alert"
      :type="superEgoAlert.type"
      show-icon
      :closable="true"
      :title="superEgoAlert.title"
      :description="superEgoAlert.description"
      @close="superEgoAlert.visible = false"
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
              <div class="metric-main metric-main--temp">
                <div class="metric-label">当前温度</div>
                <div class="metric-value">{{ formatNumber(channel.temperature) }}</div>
                <div class="metric-unit">℃</div>
              </div>
              <div class="metric-main">
                <div class="metric-label">MA / ROC</div>
                <div class="metric-inline">
                  <span>MA {{ formatNumber(channel.ma) }}</span>
                  <span>ROC {{ formatNumber(channel.roc) }}</span>
                </div>
              </div>
            </div>

            <div class="detail-zone">
              <div class="detail-table">
                <div class="detail-row"><span>历史 MA 最大值</span><strong>{{ formatNumber(channel.maMax) }}</strong></div>
                <div class="detail-row"><span>历史 ROC 最大值</span><strong>{{ formatNumber(channel.rocMax) }}</strong></div>
                <div class="detail-row"><span>升温异常</span><strong>{{ channel.abnormalRise ? '是' : '否' }}</strong></div>
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
          <div class="trend-title">温度趋势图 · {{ activeChannel.title }}</div>
          <div class="trend-subtitle">原始温度与 MA 平滑曲线、ROC 升温监测、阈值联动</div>
        </div>
        <el-radio-group v-model="chartMode" size="mini" @change="refreshCharts">
          <el-radio-button label="trend">温度趋势</el-radio-button>
          <el-radio-button label="coupling">温-振耦合</el-radio-button>
        </el-radio-group>
      </div>
      <div ref="trendChartRef" class="trend-chart"></div>
    </div>

    <div class="alarm-panel">
      <div class="alarm-panel__head">
        <span>近期温升 / 复合告警事件</span>
        <el-tag size="mini" type="danger">点击定位通道</el-tag>
      </div>
      <el-table :data="alarmEvents" height="220" size="mini" stripe @row-click="jumpToAlarmEvent">
        <el-table-column prop="timeText" label="时间" min-width="180" />
        <el-table-column prop="channelTitle" label="通道" width="120" />
        <el-table-column prop="levelText" label="级别" width="100">
          <template slot-scope="scope">
            <el-tag :type="alarmTagType(scope.row.level)" size="mini">{{ scope.row.levelText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="告警描述" min-width="260" />
      </el-table>
    </div>

    <el-drawer
      :visible.sync="detailVisible"
      direction="btt"
      size="100%"
      custom-class="temperature-drawer"
      :with-header="false"
      append-to-body
      @opened="handleDrawerOpened"
      @closed="handleDrawerClosed"
    >
      <div class="drawer-shell">
        <div class="drawer-topbar">
          <div>
            <div class="drawer-title">{{ activeChannel.title }} 详细信息</div>
            <div class="drawer-subtitle">MA 窗口 {{ maWindow }} · ROC 异常升温判定 · 最近 100 条历史记录</div>
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
                <div class="main-metric main-metric--temp">
                  <div class="metric-label">当前温度</div>
                  <div class="metric-value big">{{ formatNumber(activeChannel.temperature) }}</div>
                  <div class="metric-unit">℃</div>
                </div>
                <div class="main-metric">
                  <div class="metric-label">滑动平均 MA</div>
                  <div class="metric-value big">{{ formatNumber(activeChannel.ma) }}</div>
                  <div class="metric-unit">℃</div>
                </div>
                <div class="main-metric">
                  <div class="metric-label">温度变化率 ROC</div>
                  <div class="metric-value big">{{ formatNumber(activeChannel.roc) }}</div>
                  <div class="metric-unit">℃/min</div>
                </div>
                <div class="sub-metrics compact-table">
                  <div class="sub-item"><span>历史 MA 最大值</span><strong>{{ formatNumber(activeChannel.maMax) }}</strong></div>
                  <div class="sub-item"><span>历史 ROC 最大值</span><strong>{{ formatNumber(activeChannel.rocMax) }}</strong></div>
                  <div class="sub-item"><span>升温异常</span><strong>{{ activeChannel.abnormalRise ? '是' : '否' }}</strong></div>
                  <div class="sub-item"><span>耦合判断</span><strong>{{ activeChannel.couplingDecision }}</strong></div>
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
                  <el-radio-button label="trend">温度趋势</el-radio-button>
                  <el-radio-button label="coupling">温-振耦合</el-radio-button>
                </el-radio-group>
                <div class="drawer-chart-tip">原始温度、MA 曲线、ROC 判定与振动联动</div>
              </div>
              <div ref="detailChartRef" class="chart-box"></div>
            </el-col>
          </el-row>

          <el-card class="log-card" shadow="never">
            <div slot="header" class="card-header">
              <span>MA / ROC 历史极值与关键记录</span>
              <el-button size="mini" type="primary" plain @click="loadHistory(activeChannel.id)">刷新</el-button>
            </div>
            <el-table :data="historyRows" height="300" stripe size="mini" border>
              <el-table-column type="index" label="序号" width="60" />
              <el-table-column prop="sampleTimeText" label="采集精确时间" min-width="180" />
              <el-table-column prop="temperatureValue" label="温度值" width="110" align="center">
                <template slot-scope="scope">{{ formatNumber(scope.row.temperatureValue) }}</template>
              </el-table-column>
              <el-table-column prop="maValue" label="MA" width="110" align="center">
                <template slot-scope="scope">{{ formatNumber(scope.row.maValue) }}</template>
              </el-table-column>
              <el-table-column prop="rocValue" label="ROC" width="110" align="center">
                <template slot-scope="scope">{{ formatNumber(scope.row.rocValue) }}</template>
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
import { listTemperature } from '@/api/system/temperature'

export default {
  name: 'Temperature',
  data() {
    return {
      ws: null,
      trendChart: null,
      gaugeCharts: {},
      detailVisible: false,
      historyLoading: false,
      historyRows: [],
      chartMode: 'trend',
      detailChartMode: 'trend',
      activeChannelId: 1,
      maWindow: 5,
      superEgoAlert: { visible: false, type: 'warning', title: '', description: '' },
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
    this.disposeCharts()
  },
  methods: {
    buildChannel(id) {
      const titles = ['驱动端轴承温度', '驱动端电机温度', '非驱动端轴承温度', '非驱动端电机温度', '减速机输入端温度', '减速机输出端温度', '油箱温度', '环境温度']
      return {
        id,
        title: `通道${id}`,
        desc: titles[id - 1],
        temperature: 0,
        ma: 0,
        roc: 0,
        peakTemp: 0,
        maMax: 0,
        rocMax: 0,
        vibration: 0,
        health: 100,
        status: 'normal',
        statusLabel: '正常',
        sampleTimeText: '--',
        abnormalRise: false,
        couplingDecision: '正常',
        history: [],
        vibrationHistory: [],
        alarmHistory: []
      }
    },
    initChannels() {
      this.channels.forEach(channel => {
        const history = this.mockTempSeries(60, channel.id)
        const vibrationHistory = this.mockVibrationSeries(60, channel.id)
        const enriched = this.enrichSeries(history, vibrationHistory)
        this.$set(this.liveData, channel.id, enriched)
        this.syncChannelState(channel.id, enriched)
      })
    },
    mockTempSeries(count, seed) {
      const now = Date.now()
      return Array.from({ length: count }, (_, i) => ({
        collectionTime: now - (count - i) * 1000,
        temperatureValue: Number((35 + Math.sin((i + seed) / 6) * 2.5 + seed * 0.45).toFixed(2))
      }))
    },
    mockVibrationSeries(count, seed) {
      const now = Date.now()
      return Array.from({ length: count }, (_, i) => ({
        collectionTime: now - (count - i) * 1000,
        vibrationValue: Number((1.3 + Math.cos((i + seed) / 4) * 0.4 + seed * 0.08).toFixed(2))
      }))
    },
    enrichSeries(tempSeries, vibrationSeries) {
      const rows = []
      for (let i = 0; i < tempSeries.length; i++) {
        const item = tempSeries[i]
        const window = tempSeries.slice(Math.max(0, i - this.maWindow + 1), i + 1).map(x => Number(this.pickNumber(x, ['temperatureValue', 'temperature', 'temp', 'value'])))
        const ma = this.calcMA(window)
        const prev = i > 0 ? Number(this.pickNumber(tempSeries[i - 1], ['temperatureValue', 'temperature', 'temp', 'value'])) : Number(this.pickNumber(item, ['temperatureValue', 'temperature', 'temp', 'value']))
        const current = Number(this.pickNumber(item, ['temperatureValue', 'temperature', 'temp', 'value']))
        const roc = this.calcROC(current, prev, 1)
        rows.push({
          collectionTime: item.collectionTime || item.sampleTime || item.time,
          temperatureValue: current,
          maValue: ma,
          rocValue: roc,
          vibrationValue: vibrationSeries[i] ? Number(this.pickNumber(vibrationSeries[i], ['vibrationValue', 'vibration', 'rms', 'speed', 'value'])) : 0
        })
      }
      return {
        history: rows.slice(-this.maxPoints),
        vibrationHistory: vibrationSeries.slice(-this.maxPoints)
      }
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

      const temperature = this.toNumber(this.pickField(msg, ['temperatureValue', 'temperature', 'temp']), channel.temperature)
      const vibration = this.toNumber(this.pickField(msg, ['vibrationValue', 'vibration', 'rms', 'speed']), channel.vibration)
      const sampleTime = this.pickField(msg, ['collectionTime', 'sampleTime', 'collectTime', 'createTime']) || new Date().toISOString()
      const maValue = this.toNumber(this.pickField(msg, ['maValue', 'ma']), temperature)
      const rocValue = this.toNumber(this.pickField(msg, ['rocValue', 'roc']), 0)
      const history = this.liveData[channelId].history.slice()
      const vibrationHistory = this.liveData[channelId].vibrationHistory.slice()
      const now = new Date(sampleTime).getTime()
      history.push({ collectionTime: now, temperatureValue: temperature, maValue, rocValue })
      vibrationHistory.push({ collectionTime: now, vibrationValue: vibration })

      const normalized = this.enrichSeries(history, vibrationHistory)
      this.$set(this.liveData, channelId, normalized)
      this.syncChannelState(channelId, normalized)

      if (msg.alarm || channel.abnormalRise) {
        const title = channel.abnormalRise ? '潜在故障演进' : '温度异常告警'
        const description = channel.abnormalRise
          ? `${channel.title} 出现持续升温且振动同步上升，建议重点关注机械磨损或润滑不良`
          : `${channel.title} 温度已超过阈值，请及时排查`
        this.superEgoAlert = { visible: true, type: 'error', title, description }
        this.alarmEvents.unshift({
          time: sampleTime,
          timeText: this.formatDateTime(sampleTime),
          channelId,
          channelTitle: channel.title,
          level: channel.abnormalRise ? '高' : '中',
          levelText: channel.abnormalRise ? '高优先级' : '预警',
          message: channel.abnormalRise ? 'ROC 持续为正且振动同步上升' : '温度触发阈值告警'
        })
        this.alarmEvents = this.alarmEvents.slice(0, 10)
      }

      this.refreshGaugeChart(channelId)
      if (this.detailVisible && this.activeChannelId === channelId) {
        this.refreshCharts()
      }
    },
    syncChannelState(channelId, normalized) {
      const channel = this.channels[channelId - 1]
      if (!channel) return
      const latest = normalized.history[normalized.history.length - 1]
      const maValues = normalized.history.map(item => item.maValue)
      const rocValues = normalized.history.map(item => item.rocValue)
      const latestTemp = latest ? latest.temperatureValue : 0
      const latestMa = latest ? latest.maValue : 0
      const latestRoc = latest ? latest.rocValue : 0
      const vibration = normalized.vibrationHistory[normalized.vibrationHistory.length - 1]
        ? this.pickNumber(normalized.vibrationHistory[normalized.vibrationHistory.length - 1], ['vibrationValue', 'vibration', 'rms', 'speed', 'value'])
        : 0
      const health = this.calcHealth(latestTemp, latestRoc, vibration)
      const abnormalRise = this.checkAbnormalRise(normalized.history, normalized.vibrationHistory)
      const status = abnormalRise ? 'abnormal' : health >= 80 ? 'normal' : health >= 60 ? 'warning' : 'abnormal'

      channel.temperature = latestTemp
      channel.ma = latestMa
      channel.roc = latestRoc
      channel.vibration = vibration
      channel.maMax = maValues.length ? Math.max(...maValues) : 0
      channel.rocMax = rocValues.length ? Math.max(...rocValues) : 0
      channel.health = health
      channel.status = status
      channel.statusLabel = status === 'abnormal' ? '异常' : status === 'warning' ? '预警' : '正常'
      channel.sampleTimeText = latest ? this.formatDateTime(latest.time) : '--'
      channel.abnormalRise = abnormalRise
      channel.couplingDecision = abnormalRise ? '潜在故障演进' : vibration > 1.8 && latestRoc > 0 ? '待观察' : '正常'
      channel.history = normalized.history
      channel.vibrationHistory = normalized.vibrationHistory
      channel.alarmHistory = channel.alarmHistory || []
    },
    pickField(obj, keys) {
      if (!obj) return null
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i]
        if (obj[key] !== undefined && obj[key] !== null && obj[key] !== '') return obj[key]
      }
      return null
    },
    pickNumber(obj, keys) {
      const value = this.pickField(obj, keys)
      const num = Number(value)
      return Number.isNaN(num) ? 0 : num
    },
    calcMA(values) {
      if (!values || !values.length) return 0
      const sum = values.reduce((total, item) => total + Number(item || 0), 0)
      return Number((sum / values.length).toFixed(2))
    },
    calcROC(now, prev, deltaT) {
      if (!deltaT) return 0
      return Number(((Number(now) - Number(prev)) / deltaT).toFixed(2))
    },
    checkAbnormalRise(history, vibrationHistory) {
      const len = history.length
      if (len < 3) return false
      const last3 = history.slice(-3)
      const rocPositive = last3.every(item => Number(item.roc) > 0)
      const vibrationPositive = vibrationHistory.slice(-3).every((item, index, arr) => index === 0 || Number(item.value) >= Number(arr[index - 1].value))
      return rocPositive && vibrationPositive
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
    openDetail(channel) {
      this.activeChannelId = channel.id
      this.detailVisible = true
      this.loadHistory(channel.id)
    },
    jumpToAlarmEvent(row) {
      this.activeChannelId = row.channelId
      this.openDetail(this.channels[row.channelId - 1] || this.buildChannel(row.channelId))
      this.$message.success(`已定位到 ${row.channelTitle}`)
    },
    handleDrawerOpened() {
      this.initCharts()
      this.refreshCharts()
    },
    handleDrawerClosed() {
      this.disposeCharts()
    },
    loadHistory(channelId) {
      this.historyLoading = true
      listTemperature({ pageNum: 1, pageSize: 100, channelId })
        .then(response => {
          const rows = response.rows || []
          this.historyRows = rows.map((item, index) => ({
            ...item,
            sampleTimeText: this.formatDateTime(item.collectionTime || item.sampleTime || item.collectTime || item.createTime),
            temperatureValue: this.pickField(item, ['temperatureValue', 'temperature', 'temp']) ?? (35 + Math.random() * 6).toFixed(2),
            maValue: this.pickField(item, ['maValue', 'ma']) ?? (35 + Math.random() * 5).toFixed(2),
            rocValue: this.pickField(item, ['rocValue', 'roc']) ?? (Math.random() * 2).toFixed(2),
            motorSpeed: this.pickField(item, ['motorSpeed', 'speed']) ?? 1480 + index
          }))
        })
        .finally(() => { this.historyLoading = false })
    },
    initCharts() {
      this.$nextTick(() => {
        if (!this.trendChart && this.$refs.trendChartRef) {
          this.trendChart = echarts.init(this.$refs.trendChartRef)
        }
        this.channels.forEach(channel => {
          if (!this.gaugeCharts[channel.id] && this.$refs[`gauge-${channel.id}`]) {
            this.gaugeCharts[channel.id] = echarts.init(this.$refs[`gauge-${channel.id}`])
          }
        })
      })
    },
    refreshGaugeCharts() {
      this.channels.forEach(channel => {
        const ref = this.$refs[`gauge-${channel.id}`]
        const dom = Array.isArray(ref) ? ref[0] : ref
        if (!dom) return
        if (!this.gaugeCharts[channel.id]) {
          this.gaugeCharts[channel.id] = echarts.init(dom)
        }
        this.gaugeCharts[channel.id].setOption(this.buildGaugeOption(channel.health), true)
      })
    },
    refreshGaugeChart(channelId) {
      const chart = this.gaugeCharts[channelId]
      const channel = this.channels[channelId - 1]
      if (chart && channel) chart.setOption(this.buildGaugeOption(channel.health), true)
    },
    refreshCharts() {
      this.$nextTick(() => {
        this.refreshGaugeCharts()
        this.refreshTrendChart()
      })
    },
    refreshTrendChart() {
      if (!this.trendChart) return
      const channel = this.channels[this.activeChannelId - 1]
      const live = this.liveData[this.activeChannelId] || { history: [], vibrationHistory: [] }
      if (this.chartMode === 'coupling' || this.detailChartMode === 'coupling') {
        this.trendChart.setOption(this.buildCouplingOption(channel, live.history, live.vibrationHistory), true)
      } else {
        this.trendChart.setOption(this.buildTrendOption(channel, live.history), true)
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
    buildTrendOption(channel, history) {
      const xData = history.map(item => this.formatChartTime(item.collectionTime))
      const raw = history.map(item => Number(item.temperatureValue || 0))
      const ma = history.map(item => Number(item.maValue || 0))
      const maxIndex = raw.length ? raw.indexOf(Math.max(...raw)) : -1
      return {
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        legend: { data: ['原始温度', 'MA 平滑'], textStyle: { color: '#d9e2e8' } },
        grid: { left: 52, right: 24, top: 42, bottom: 36 },
        xAxis: { type: 'category', boundaryGap: false, data: xData, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: '#d9e2e8' } },
        yAxis: { type: 'value', name: '℃', axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.08)' } }, axisLabel: { color: '#d9e2e8' } },
        series: [
          {
            name: '原始温度',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: raw,
            lineStyle: { color: 'rgba(125,211,252,0.65)', width: 1.5 }
          },
          {
            name: 'MA 平滑',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: ma,
            lineStyle: { color: '#00FFFF', width: 2.5 },
            markLine: {
              symbol: 'none',
              data: [{ yAxis: 65, name: '预警线' }, { yAxis: 75, name: '报警线' }],
              lineStyle: { color: '#f5c542', type: 'dashed' },
              label: { color: '#fff' }
            }
          }
        ],
        graphic: maxIndex >= 0 ? [{ type: 'text', left: 'center', top: 8, style: { text: `峰值温度：${xData[maxIndex]} / ${raw[maxIndex].toFixed(2)}℃`, fill: '#eaffff', font: '12px sans-serif' } }] : []
      }
    },
    buildCouplingOption(channel, history, vibrationHistory) {
      const xData = history.map(item => this.formatChartTime(item.collectionTime))
      const temp = history.map(item => Number(item.temperatureValue || 0))
      const vib = vibrationHistory.map(item => Number(this.pickField(item, ['vibrationValue', 'vibration', 'rms', 'speed', 'value']) || 0))
      const couplingLabel = this.activeChannel.couplingDecision === '潜在故障演进' ? '潜在故障演进' : this.activeChannel.couplingDecision
      return {
        backgroundColor: 'transparent',
        tooltip: { trigger: 'axis' },
        legend: { data: ['温度', '振动速度'], textStyle: { color: '#d9e2e8' } },
        grid: { left: 52, right: 52, top: 42, bottom: 36 },
        xAxis: { type: 'category', boundaryGap: false, data: xData, axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, axisLabel: { color: '#d9e2e8' } },
        yAxis: [
          { type: 'value', name: '℃', axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } }, splitLine: { lineStyle: { color: 'rgba(255,255,255,0.08)' } }, axisLabel: { color: '#d9e2e8' } },
          { type: 'value', name: 'mm/s', axisLine: { lineStyle: { color: 'rgba(255,255,255,0.18)' } }, splitLine: { show: false }, axisLabel: { color: '#d9e2e8' } }
        ],
        series: [
          { name: '温度', type: 'line', smooth: true, showSymbol: false, data: temp, lineStyle: { color: '#7dd3fc', width: 2 }, yAxisIndex: 0 },
          { name: '振动速度', type: 'line', smooth: true, showSymbol: false, data: vib, lineStyle: { color: '#f59e0b', width: 2 }, yAxisIndex: 1 }
        ],
        graphic: [{ type: 'text', left: 'center', top: 8, style: { text: couplingLabel, fill: '#eaffff', font: '12px sans-serif' } }]
      }
    },

    handleResize() {
      if (this.trendChart) this.trendChart.resize()
      Object.values(this.gaugeCharts).forEach(chart => chart && chart.resize())
    },
    closeWebSocket() {
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
    },
    disposeCharts() {
      if (this.trendChart) {
        this.trendChart.dispose()
        this.trendChart = null
      }
      Object.values(this.gaugeCharts).forEach(chart => chart && chart.dispose())
      this.gaugeCharts = {}
    }
  }
}
</script>

<style scoped>
.temperature-page { min-height: calc(100vh - 84px); padding: 10px; background: linear-gradient(180deg, #1f2937 0%, #111827 100%); }
.super-ego-alert, .alarm-panel, .trend-panel { margin-bottom: 12px; }
.super-ego-alert { animation: blink 1s linear infinite; }
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
.metric-inline { margin-top: 4px; display: flex; justify-content: space-between; gap: 10px; color: #eaffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.detail-zone { display: grid; grid-template-columns: 1fr 92px; gap: 8px; align-items: center; }
.detail-table { display: grid; gap: 6px; }
.detail-row, .sub-item { display: flex; justify-content: space-between; gap: 12px; padding: 6px 8px; background: rgba(1, 12, 28, 0.58); border: 1px solid rgba(0,255,255,0.1); color: rgba(235,255,255,0.9); font-size: 12px; }
.detail-row strong, .sub-item strong { color: #eaffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.health-gauge { width: 100%; height: 92px; }
.channel-footer { margin-top: 12px; font-size: 12px; color: #aeb7c2; }
.foot-time { color: #e5edf5; }
.trend-panel, .alarm-panel { background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); border-radius: 8px; padding: 12px; color: #eef2f7; }
.alarm-panel { margin-top: 12px; }
.trend-header, .alarm-panel__head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 10px; }
.trend-title { font-size: 16px; font-weight: 700; }
.trend-subtitle { margin-top: 4px; font-size: 12px; color: #aeb7c2; }
.trend-chart { width: 100%; height: 320px; }
.drawer-shell { height: 100vh; display: flex; flex-direction: column; background: linear-gradient(180deg, #111827 0%, #1f2937 100%); color: #eef2f7; }
.drawer-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 20px; border-bottom: 1px solid rgba(0,255,255,0.10); }
.drawer-title { font-size: 20px; font-weight: 800; }
.drawer-subtitle { margin-top: 4px; font-size: 12px; color: #aeb7c2; }
.drawer-actions { display: flex; align-items: center; gap: 10px; }
.drawer-content { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 12px 20px 20px; min-height: 0; }
.metric-panel { display: grid; gap: 8px; }
.sub-metrics { display: grid; gap: 6px; }
.compact-table { margin-top: 2px; }
.health-box { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; background: rgba(0,255,255,0.03); border: 1px solid rgba(0,255,255,0.14); }
.health-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.health-desc { margin-top: 4px; font-size: 18px; font-weight: 700; color: #eaffff; font-family: 'Digital-7', 'Segoe UI', 'Arial', sans-serif; }
.drawer-chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.drawer-chart-tip { font-size: 12px; color: #aeb7c2; }
.chart-box { width: 100%; height: 320px; background: rgba(1, 12, 28, 0.78); border-radius: 10px; border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); }
.log-card { margin-top: 12px; background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); }
.card-header { display: flex; align-items: center; justify-content: space-between; }
:deep(.temperature-drawer) { background: transparent; }
:deep(.temperature-drawer .el-drawer__body) { height: 100%; }
:deep(.el-table) { background: transparent; color: #eaf0f6; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(0,255,255,0.08); }
:deep(.el-radio-button__inner) { background: #2b3340; color: #eaf0f6; border-color: rgba(255,255,255,0.12); }
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) { background: #409eff; border-color: #409eff; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.7; } }
@media (max-width: 1200px) {
  .card-body { grid-template-columns: 1fr; }
  .detail-zone { grid-template-columns: 1fr; }
  .trend-header, .drawer-topbar { flex-direction: column; align-items: flex-start; }
  .trend-chart, .chart-box { height: 260px; }
}
</style>