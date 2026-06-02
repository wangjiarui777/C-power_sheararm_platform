<template>
  <div class="app-container bearing-diagnosis-page">
    <div class="page-shell">
      <aside class="side-panel">
        <div class="brand-block">
          <div class="brand-mark">BD</div>
          <div>
            <div class="brand-name">轴承诊断分析</div>
            <div class="brand-desc">Bearing Diagnosis Dashboard</div>
          </div>
        </div>

        <el-card class="module-card side-card" shadow="never">
          <div slot="header" class="card-title-row">
            <span>设备列表</span>
            <el-tag size="mini" type="success">实时</el-tag>
          </div>
          <el-input v-model="deviceKeyword" size="small" clearable prefix-icon="el-icon-search" placeholder="搜索设备编号" class="device-search" />
          <div class="device-list">
            <div
              v-for="item in filteredDeviceList"
              :key="item.deviceCode"
              class="device-item"
              :class="{ active: item.deviceCode === selectedDeviceCode }"
              @click="selectDevice(item)"
            >
              <div class="device-main">
                <div class="device-code">{{ item.deviceCode }}</div>
                <div class="device-meta">{{ item.deviceName || '运行监测节点' }}</div>
              </div>
              <el-tag size="mini" :type="item.status === 'danger' ? 'danger' : item.status === 'warning' ? 'warning' : 'success'">
                {{ item.statusText }}
              </el-tag>
            </div>
            <div v-if="!filteredDeviceList.length" class="empty-tip">暂无设备</div>
          </div>
        </el-card>

        <el-card class="module-card side-card" shadow="never">
          <div slot="header" class="card-title-row">
            <span>诊断概要</span>
            <el-tag size="mini" type="warning">{{ confidence }}%</el-tag>
          </div>
          <div class="summary-grid">
            <div class="summary-item">
              <span>健康度</span>
              <strong>{{ healthScore }}</strong>
            </div>
            <div class="summary-item">
              <span>风险等级</span>
              <strong>{{ riskLevel }}</strong>
            </div>
            <div class="summary-item">
              <span>故障类型</span>
              <strong>{{ diagnosisName }}</strong>
            </div>
          </div>
        </el-card>
      </aside>

      <main class="main-panel">
        <el-row :gutter="16" class="top-row">
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card shadow="hover" class="status-card">
              <div class="status-label">设备运行状态</div>
              <div class="status-value" :class="statusClass">{{ deviceStatusText }}</div>
              <div class="status-meta">{{ statusSubtitle }}</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card shadow="hover" class="status-card">
              <div class="status-label">当前 RMS</div>
              <div class="status-value">{{ formatNumber(latestRms, 3) }}</div>
              <div class="status-meta">平滑缓冲输出</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card shadow="hover" class="status-card">
              <div class="status-label">故障置信度</div>
              <div class="confidence-wrap">
                <el-progress :percentage="confidence" :stroke-width="14" :status="confidenceStatus" />
              </div>
              <div class="status-meta">{{ confidenceLabel }}</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card shadow="hover" class="status-card">
              <div class="status-label">诊断结论</div>
              <div class="status-value" :class="resultStateClass">{{ diagnosisName }}</div>
              <div class="status-meta">
                <el-tag size="mini" :type="resultStateTagType">{{ resultStateText }}</el-tag>
                <span class="result-state-desc">{{ diagnosisDetail }}</span>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <el-row :gutter="16" class="chart-row">
          <el-col :xs="24" :xl="12">
            <el-card shadow="hover" class="panel-card dark-panel">
              <div slot="header" class="card-title-row">
                <span>时域响应</span>
                <el-tag size="mini" type="success">60Hz</el-tag>
              </div>
              <div ref="timeChartRef" class="chart-box" />
              <div class="mini-metrics">
                <div class="mini-metric"><span>RMS</span><strong>{{ formatNumber(latestRms, 3) }}</strong></div>
                <div class="mini-metric"><span>阈值</span><strong>{{ formatNumber(vibrationThreshold, 3) }}</strong></div>
                <div class="mini-metric"><span>峰值</span><strong>{{ formatNumber(latestPeak, 3) }}</strong></div>
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :xl="12">
            <el-card shadow="hover" class="panel-card dark-panel">
              <div slot="header" class="card-title-row">
                <span>频域分析</span>
                <el-tag size="mini" type="danger">FFT</el-tag>
              </div>
              <div ref="fftChartRef" class="chart-box" />
              <div class="mini-metrics">
                <div class="mini-metric"><span>主频</span><strong>{{ formatNumber(primaryFrequency, 3) }}</strong></div>
                <div class="mini-metric"><span>特征频率</span><strong>{{ formatNumber(characteristicFrequency, 3) }}</strong></div>
                <div class="mini-metric"><span>频带能量</span><strong>{{ formatNumber(bandEnergy, 3) }}</strong></div>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <el-row :gutter="16" class="chart-row bottom-row">
          <el-col :xs="24" :lg="10">
            <el-card shadow="hover" class="panel-card dark-panel">
              <div slot="header" class="card-title-row">
                <span>诊断证据</span>
                <el-tag size="mini" type="warning">{{ diagnosisEvidence.length }} 条</el-tag>
              </div>
              <div class="evidence-list">
                <div v-for="(item, index) in diagnosisEvidence" :key="index" class="evidence-item">
                  <div class="evidence-index">{{ index + 1 }}</div>
                  <div class="evidence-body">
                    <div class="evidence-title">{{ item.title }}</div>
                    <div class="evidence-desc">{{ item.desc }}</div>
                  </div>
                  <el-tag size="mini" :type="item.type">{{ item.level }}</el-tag>
                </div>
              </div>
            </el-card>
          </el-col>
          <el-col :xs="24" :lg="14">
            <el-card shadow="hover" class="panel-card dark-panel">
              <div slot="header" class="card-title-row">
                <span>健康趋势</span>
                <el-tag size="mini" type="success">近 7 天</el-tag>
              </div>
              <div ref="healthTrendRef" class="trend-box" />
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="hover" class="panel-card dark-panel history-card">
          <div slot="header" class="card-title-row">
            <span>分析记录</span>
            <el-link type="primary" :underline="false" class="more-link" @click="goToMoreHistory">查看更多</el-link>
          </div>
          <el-table :data="historyTable" size="small" v-loading="loading" height="240" class="history-table">
            <el-table-column label="分析时间" prop="sampleTime" width="180">
              <template slot-scope="scope">{{ parseTime(scope.row.sampleTime) }}</template>
            </el-table-column>
            <el-table-column label="模型版本" prop="modelVersion" width="120" />
            <el-table-column label="诊断结果" prop="diagnosisResult" min-width="160" />
            <el-table-column label="置信度" prop="confidence" width="120" />
            <el-table-column label="健康指数" prop="healthIndex" width="120" />
            <el-table-column label="风险等级" prop="riskLevel" width="120">
              <template slot-scope="scope">
                <el-tag size="mini" :type="riskTagType(scope.row.riskLevel)">{{ scope.row.riskLevel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作人员" prop="operator" width="120" />
            <el-table-column label="状态" prop="status" width="120">
              <template slot-scope="scope">
                <el-tag size="mini" :type="historyStatusTagType(scope.row)">{{ scope.row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </main>
    </div>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import sensorWebSocket from '@/utils/sensor-websocket'
import {
  listBearingDiagnosisHistory,
  getBearingDiagnosisFftData,
  getLatestBearingDiagnosis,
  listBearingDevices,
  getBearingDiagnosisTrend,
  analyzeReceiverFile
} from '@/api/system/bearingDiagnosis'

export default {
  name: 'BearingDiagnosisAnalysis',
  data() {
    return {
      timeChart: null,
      fftChart: null,
      healthTrendChart: null,
      wsUnsubscribe: null,
      refreshTimer: null,
      animationFrameId: null,
      bufferQueue: [],
      seriesData: [],
      fftSeries: [],
      fftXAxis: [],
      healthTrendXAxis: [],
      healthTrendYAxis: [],
      loading: false,
      total: 0,
      queryParams: { pageNum: 1, pageSize: 10 },
      latestRms: 0,
      latestPeak: 0,
      confidence: 0,
      diagnosisName: '暂无诊断结果',
      diagnosisDetail: '等待最新分析结果返回。',
      deviceCode: 'BEARING-001',
      batchId: null,
      lastFrameTime: 0,
      deviceKeyword: '',
      selectedDeviceCode: 'BEARING-001',
      devicePoints: [],
      diagnosisEvidence: [],
      resultState: 'idle',
      historyTable: []
    }
  },
  computed: {
    filteredDeviceList() {
      const keyword = (this.deviceKeyword || '').trim().toLowerCase()
      if (!keyword) return this.devicePoints
      return this.devicePoints.filter(item => String(item.deviceCode || '').toLowerCase().includes(keyword) || String(item.deviceName || '').toLowerCase().includes(keyword))
    },
    deviceStatusText() {
      if (this.resultState === 'running') return '分析中'
      if (this.resultState === 'failed') return '推理失败'
      return this.latestRms > 7 ? '预警' : '运行正常'
    },
    statusClass() {
      return this.latestRms > 7 ? 'danger' : 'success'
    },
    statusSubtitle() {
      return this.batchId ? `批次 ${this.batchId}` : `最近 ${this.seriesData.length} 个点`
    },
    confidenceLabel() {
      return `${this.formatNumber(this.confidence, 0)}% 诊断置信度`
    },
    confidenceStatus() {
      if (this.resultState === 'running') return 'warning'
      if (this.resultState === 'failed') return 'exception'
      if (this.confidence >= 80) return 'success'
      if (this.confidence >= 60) return 'warning'
      return 'exception'
    },
    resultStateText() {
      if (this.resultState === 'running') return '分析中'
      if (this.resultState === 'failed') return '推理失败'
      if (this.resultState === 'done') return '已完成'
      return '待接收'
    },
    resultStateTagType() {
      if (this.resultState === 'running') return 'warning'
      if (this.resultState === 'failed') return 'danger'
      if (this.resultState === 'done') return 'success'
      return 'info'
    },
    resultStateClass() {
      if (this.resultState === 'running') return 'state-running'
      if (this.resultState === 'failed') return 'state-failed'
      if (this.resultState === 'done') return 'state-done'
      return 'state-idle'
    },
    healthScore() {
      return Math.max(0, 100 - Math.round(this.latestRms * 5))
    },
    riskLevel() {
      return this.latestRms > 7 ? '高' : this.latestRms > 4 ? '中' : '低'
    },
    vibrationThreshold() {
      return 7
    },
    primaryFrequency() {
      return this.fftXAxis.length ? this.fftXAxis[0] : 0
    },
    characteristicFrequency() {
      return this.fftXAxis.length > 1 ? this.fftXAxis[1] : 0
    },
    bandEnergy() {
      return this.fftSeries.reduce((sum, item) => sum + Number(item || 0), 0)
    }
  },
  mounted() {
    this.$nextTick(() => {
      this.initCharts()
      this.refreshPageData()
      this.connectWebSocket()
      this.animationFrameId = requestAnimationFrame(this.tick)
      window.addEventListener('resize', this.handleResize)
      this.refreshTimer = setInterval(() => this.fetchLatestDiagnosis(), 5000)
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize)
    if (this.refreshTimer) clearInterval(this.refreshTimer)
    if (this.wsUnsubscribe) {
      this.wsUnsubscribe()
      this.wsUnsubscribe = null
    }
    sensorWebSocket.close()
    if (this.animationFrameId) cancelAnimationFrame(this.animationFrameId)
    if (this.timeChart) this.timeChart.dispose()
    if (this.fftChart) this.fftChart.dispose()
    if (this.healthTrendChart) this.healthTrendChart.dispose()
  },
  methods: {
    selectDevice(item) {
      this.selectedDeviceCode = item.deviceCode
      this.deviceCode = item.deviceCode
      this.refreshPageData()
    },
    goToMoreHistory() {
      this.$message && this.$message.info('查看更多历史诊断记录')
    },
    formatNumber(value, digits) {
      if (value === null || value === undefined || value === '') return '--'
      const num = Number(value)
      if (Number.isNaN(num)) return String(value)
      return num.toFixed(digits == null ? 2 : digits)
    },
    normalizePoints(payload) {
      if (payload == null) return []
      if (Array.isArray(payload)) return payload.flatMap(item => this.normalizePoints(item))
      if (typeof payload === 'number') return [payload]
      if (typeof payload === 'string') {
        const parsed = Number(payload)
        return Number.isFinite(parsed) ? [parsed] : []
      }
      if (typeof payload === 'object') {
        if (Array.isArray(payload.values)) return payload.values.flatMap(item => this.normalizePoints(item))
        if (Array.isArray(payload.data)) return payload.data.flatMap(item => this.normalizePoints(item))
        if (Array.isArray(payload.rms)) return payload.rms.flatMap(item => this.normalizePoints(item))
        if (typeof payload.value === 'number') return [payload.value]
        if (typeof payload.rms === 'number') return [payload.rms]
      }
      return []
    },
    tick(now) {
      if (!this.lastFrameTime) this.lastFrameTime = now
      if (now - this.lastFrameTime >= 16.6667 && this.bufferQueue.length > 0) {
        const point = this.bufferQueue.shift()
        if (Number.isFinite(Number(point))) {
          this.seriesData.push(Number(point))
          if (this.seriesData.length > 120) this.seriesData.splice(0, this.seriesData.length - 120)
          this.latestRms = Number(point)
          this.latestPeak = Math.max(this.latestPeak, Number(point))
          this.renderTimeChart()
        }
        this.lastFrameTime = now
      }
      this.animationFrameId = requestAnimationFrame(this.tick)
    },
    buildTimeOption() {
      return {
        animation: false,
        grid: { left: 42, right: 20, top: 30, bottom: 36 },
        xAxis: { type: 'category', boundaryGap: false, data: this.seriesData.map((_, index) => index + 1), axisLabel: { color: '#666' } },
        yAxis: { type: 'value', min: 0, max: 10, splitNumber: 5, axisLabel: { color: '#666' }, splitLine: { lineStyle: { color: '#e8e8e8' } } },
        series: [{ name: 'RMS', type: 'line', smooth: true, showSymbol: false, data: this.seriesData, lineStyle: { width: 2, color: '#409EFF' }, areaStyle: { opacity: 0.08 } }]
      }
    },
    buildFftOption() {
      return {
        animation: false,
        grid: { left: 42, right: 20, top: 30, bottom: 36 },
        xAxis: { type: 'category', data: this.fftXAxis, axisLabel: { color: '#666' } },
        yAxis: { type: 'value', axisLabel: { color: '#666' }, splitLine: { lineStyle: { color: '#e8e8e8' } } },
        series: [{ name: 'FFT', type: 'bar', data: this.fftSeries, itemStyle: { color: '#67C23A' } }]
      }
    },
    buildHealthTrendOption() {
      return {
        animation: false,
        grid: { left: 42, right: 20, top: 30, bottom: 36 },
        xAxis: { type: 'category', data: this.healthTrendXAxis, axisLabel: { color: '#666' } },
        yAxis: { type: 'value', min: 0, max: 100, axisLabel: { color: '#666' }, splitLine: { lineStyle: { color: '#e8e8e8' } } },
        series: [{ name: '健康指数', type: 'line', smooth: true, showSymbol: false, data: this.healthTrendYAxis, lineStyle: { width: 2, color: '#8e71ff' }, areaStyle: { opacity: 0.1 } }]
      }
    },
    initCharts() {
      if (this.$refs.timeChartRef && !this.timeChart) this.timeChart = echarts.init(this.$refs.timeChartRef)
      if (this.$refs.fftChartRef && !this.fftChart) this.fftChart = echarts.init(this.$refs.fftChartRef)
      if (this.$refs.healthTrendRef && !this.healthTrendChart) this.healthTrendChart = echarts.init(this.$refs.healthTrendRef)
      this.renderCharts()
    },
    renderTimeChart() {
      if (this.timeChart) this.timeChart.setOption(this.buildTimeOption(), true)
    },
    renderCharts() {
      this.renderTimeChart()
      if (this.fftChart) this.fftChart.setOption(this.buildFftOption(), true)
      if (this.healthTrendChart) this.healthTrendChart.setOption(this.buildHealthTrendOption(), true)
    },
    async refreshPageData() {
      await Promise.all([this.fetchDeviceList(), this.fetchLatestDiagnosis(), this.fetchHistory(), this.fetchTrend(), this.fetchTengineData()])
    },
    async triggerAnalysisFromReceiver(payload) {
      try {
        await analyzeReceiverFile(payload)
        await Promise.all([this.fetchLatestDiagnosis(), this.fetchHistory(), this.fetchTrend()])
      } catch (error) {
        console.error('触发后端自动推理失败', error)
      }
    },
    async fetchDeviceList() {
      try {
        const res = await listBearingDevices({})
        const rows = (res && (res.rows || (res.data && res.data.rows) || res.data)) || []
        if (rows.length) {
          this.devicePoints = rows
          if (!this.selectedDeviceCode && rows[0]) this.selectedDeviceCode = rows[0].deviceCode
        }
      } catch (error) {
        console.error('获取设备列表失败', error)
      }
    },
    connectWebSocket() {
      this.wsUnsubscribe = sensorWebSocket.subscribe((event, payload) => {
        if (event !== 'message') return
        this.handleWebSocketMessage(payload)
      })
      sensorWebSocket.connect('/ws/sensor')
    },
    handleWebSocketMessage(payload) {
      if (!payload) return
      if (payload.deviceCode && payload.deviceCode !== this.selectedDeviceCode) return

      const state = this.resolveResultState(payload)
      if (state) this.resultState = state

      if (typeof payload.rms === 'number') {
        this.latestRms = payload.rms
        this.seriesData.push(payload.rms)
        if (this.seriesData.length > 120) this.seriesData.splice(0, this.seriesData.length - 120)
        this.renderTimeChart()
      }
      if (typeof payload.peak === 'number') {
        this.latestPeak = payload.peak
      }
      if (payload.message || payload.alarmMessage || payload.diagnosisDetail) {
        this.diagnosisDetail = payload.message || payload.alarmMessage || payload.diagnosisDetail
      }
      if (payload.resultState) {
        this.resultState = payload.resultState
      }
      if (payload.alarm != null) {
        this.diagnosisName = payload.alarm ? '异常预警' : '运行正常'
      }
      this.batchId = payload.batchId || this.batchId
      if (payload.diagnosisName || payload.diagnosisResult) {
        this.diagnosisName = payload.diagnosisName || payload.diagnosisResult
      }
      if (payload.evidence && Array.isArray(payload.evidence)) {
        this.diagnosisEvidence = payload.evidence
      }
      if (state === 'running') {
        this.fetchHistory()
        return
      }
      if (state === 'done' || state === 'failed') {
        this.fetchLatestDiagnosis()
        this.fetchHistory()
      }
    },
    resolveResultState(payload) {
      const status = String(payload.status || payload.resultState || payload.phase || '').toLowerCase()
      if (!status) {
        if (payload.alarm == null && payload.confidence == null && payload.diagnosisResult == null) return ''
        if (payload.resultState) return String(payload.resultState)
        if (payload.alarm === false && (payload.diagnosisResult || '').includes('接收')) return 'running'
        if (payload.alarm === false && payload.confidence === 0 && payload.diagnosisResult === '推理失败') return 'failed'
        if (payload.alarm === true || (payload.diagnosisResult && payload.diagnosisResult !== '在线诊断任务已接收')) return 'done'
        return 'running'
      }
      if (status.includes('分析中') || status.includes('running') || status.includes('pending')) return 'running'
      if (status.includes('失败') || status.includes('error') || status.includes('failed')) return 'failed'
      if (status.includes('完成') || status.includes('done') || status.includes('success')) return 'done'
      return status
    },
    async fetchLatestDiagnosis() {
      try {
        const res = await getLatestBearingDiagnosis({ deviceCode: this.selectedDeviceCode || this.deviceCode })
        const data = (res && (res.data || res)) || {}
        this.applyLatestDiagnosis(data)
        this.resultState = this.resolveResultState(data) || this.resultState
      } catch (error) {
        console.error('获取最新诊断失败', error)
      }
    },
    applyLatestDiagnosis(data) {
      this.confidence = Number(data.confidence != null ? data.confidence : this.confidence)
      this.diagnosisName = data.diagnosisResult || data.diagnosisName || data.diagnosis || this.diagnosisName
      this.diagnosisDetail = data.diagnosisDetail || data.reason || data.detail || this.diagnosisDetail
      this.latestRms = Number(data.latestRms != null ? data.latestRms : this.latestRms)
      this.latestPeak = Number(data.latestPeak != null ? data.latestPeak : this.latestPeak)
      this.batchId = data.batchId || this.batchId
      this.resultState = this.resolveResultState(data) || this.resultState
      if (Array.isArray(data.evidence) && data.evidence.length) {
        this.diagnosisEvidence = data.evidence
      }
      if (data.waveform && Array.isArray(data.waveform)) {
        this.seriesData = data.waveform.slice(-120)
        this.renderTimeChart()
      }
      if (data.frequencyAxis && Array.isArray(data.frequencyAxis)) {
        this.fftXAxis = data.frequencyAxis
      }
      if (data.spectrum && Array.isArray(data.spectrum)) {
        this.fftSeries = data.spectrum
      }
      this.renderCharts()
    },
    async fetchTengineData() {
      try {
        const res = await getBearingDiagnosisFftData({ deviceCode: this.selectedDeviceCode || this.deviceCode, channelId: 1, timeLimit: 120, fftLimit: 64 })
        const data = (res && (res.data || res)) || {}
        if (Array.isArray(data.waveform) && data.waveform.length) {
          this.seriesData = data.waveform.slice(-120)
          this.latestRms = this.seriesData[this.seriesData.length - 1]
          this.latestPeak = Math.max.apply(null, this.seriesData)
        }
        this.fftXAxis = data.frequencyAxis || data.freqs || data.x || data.labels || []
        this.fftSeries = data.spectrum || data.magnitudes || data.values || data.y || []
        this.renderCharts()
      } catch (error) {
        console.error('获取 TDengine 数据失败', error)
      }
    },
    async fetchHistory() {
      this.loading = true
      try {
        const res = await listBearingDiagnosisHistory({ ...this.queryParams, deviceCode: this.selectedDeviceCode || this.deviceCode })
        const rows = (res && (res.rows || (res.data && res.data.rows) || res.data)) || []
        this.historyTable = rows
        this.total = (res && (res.total || (res.data && res.data.total))) || this.historyTable.length
        if (!this.healthTrendYAxis.length) {
          this.healthTrendXAxis = ['D-6', 'D-5', 'D-4', 'D-3', 'D-2', 'D-1', 'Today']
          this.healthTrendYAxis = [72, 74, 71, 68, 65, 62, this.healthScore]
          this.renderCharts()
        }
      } catch (error) {
        console.error('获取历史记录失败', error)
      } finally {
        this.loading = false
      }
    },
    getHistoryRowState(row) {
      const status = String(row && row.status ? row.status : '')
      if (status === '完成') return 'done'
      if (status === '分析中') return 'running'
      if (status === '失败' || status === '异常') return 'failed'
      return ''
    },
    async fetchTrend() {
      try {
        const res = await getBearingDiagnosisTrend({ deviceCode: this.selectedDeviceCode || this.deviceCode })
        const data = (res && (res.data || res)) || {}
        if (Array.isArray(data.xAxis) && data.xAxis.length) this.healthTrendXAxis = data.xAxis
        if (Array.isArray(data.values) && data.values.length) this.healthTrendYAxis = data.values
        this.renderCharts()
      } catch (error) {
        console.error('获取趋势数据失败', error)
      }
    },
    handleResize() {
      if (this.timeChart) this.timeChart.resize()
      if (this.fftChart) this.fftChart.resize()
      if (this.healthTrendChart) this.healthTrendChart.resize()
    },
    riskTagType(level) {
      return level === '高' ? 'danger' : level === '中' ? 'warning' : 'success'
    },
    historyStatusTagType(row) {
      const state = this.getHistoryRowState(row)
      if (state === 'running') return 'warning'
      if (state === 'failed') return 'danger'
      if (state === 'done') return 'success'
      return 'info'
    }
  }
}
</script>

<style scoped>
.bearing-diagnosis-page { padding: 12px; background: #071624; }
.page-shell { display: grid; grid-template-columns: 300px 1fr; gap: 16px; min-height: calc(100vh - 86px); }
.side-panel { display: flex; flex-direction: column; gap: 16px; }
.brand-block { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-radius: 14px; background: linear-gradient(180deg, #07111d 0%, #0b2234 100%); border: 1px solid rgba(82,171,255,0.16); }
.brand-mark { width: 46px; height: 46px; border-radius: 12px; background: linear-gradient(135deg, #57d1ff, #3b82f6); color: #06111f; display: flex; align-items: center; justify-content: center; font-weight: 800; }
.brand-name { color: #f3fbff; font-size: 16px; font-weight: 700; }
.brand-desc { color: #7ea8c8; font-size: 12px; margin-top: 2px; }
.module-card { border-radius: 14px; background: linear-gradient(180deg, #07131f 0%, #0b2234 100%); border: 1px solid rgba(82,171,255,0.16); }
.side-card { flex: 1; }
.card-title-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #f4fbff; font-weight: 700; }
.device-search { margin-bottom: 10px; }
.device-list { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow: auto; }
.device-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px; border-radius: 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(87,209,255,0.08); cursor: pointer; }
.device-item.active, .device-item:hover { background: rgba(53,143,255,0.16); border-color: rgba(87,209,255,0.38); }
.device-main { min-width: 0; }
.device-code { color: #f3fbff; font-size: 13px; font-weight: 700; }
.device-meta { color: #86afcb; font-size: 12px; margin-top: 2px; }
.empty-tip { color: #7ea8c8; padding: 14px 0; text-align: center; }
.summary-grid { display: grid; grid-template-columns: 1fr; gap: 10px; }
.summary-item { padding: 12px; border-radius: 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(87,209,255,0.08); }
.summary-item span { display: block; font-size: 12px; color: #7ea8c8; }
.summary-item strong { display: block; margin-top: 6px; color: #f3fbff; font-size: 18px; }
.main-panel { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.top-row, .chart-row { margin: 0; }
.status-card { min-height: 136px; border-radius: 14px; background: linear-gradient(180deg, #07131f 0%, #0b2234 100%); border: 1px solid rgba(82,171,255,0.16); color: #d8e7f3; }
.status-label { color: #8fb5cf; font-size: 13px; margin-bottom: 8px; }
.status-value { font-size: 28px; font-weight: 700; line-height: 1.2; color: #f3fbff; }
.status-value.success { color: #67c23a; }
.status-value.danger { color: #f56c6c; }
.status-meta { margin-top: 10px; color: #86afcb; font-size: 13px; min-height: 18px; }
.result-state-desc { margin-left: 8px; }
.confidence-wrap { margin-top: 8px; }
.panel-card { border-radius: 14px; }
.status-value.state-running { color: #e6a23c; }
.status-value.state-failed { color: #f56c6c; }
.status-value.state-done { color: #67c23a; }
.status-value.state-idle { color: #f3fbff; }
.dark-panel { background: linear-gradient(180deg, #07131f 0%, #0b2234 100%); border: 1px solid rgba(82,171,255,0.16); }
.chart-box { width: 100%; height: 300px; }
.trend-box { width: 100%; height: 300px; }
.mini-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 12px; }
.mini-metric { padding: 10px 12px; background: rgba(255,255,255,0.03); border: 1px solid rgba(87,209,255,0.08); border-radius: 10px; }
.mini-metric span { display: block; font-size: 12px; color: #86afcb; }
.mini-metric strong { display: block; margin-top: 6px; color: #f3fbff; font-size: 16px; }
.evidence-list { display: flex; flex-direction: column; gap: 10px; }
.evidence-item { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; border-radius: 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(87,209,255,0.08); }
.evidence-index { width: 28px; height: 28px; border-radius: 8px; display: flex; align-items: center; justify-content: center; background: rgba(87,209,255,0.12); color: #57d1ff; font-weight: 800; flex-shrink: 0; }
.evidence-body { flex: 1; min-width: 0; }
.evidence-title { color: #f4fbff; font-weight: 700; }
.evidence-desc { margin-top: 4px; color: #86afcb; font-size: 12px; line-height: 1.6; }
.history-card { margin-bottom: 0; }
:deep(.el-card__header) { background: transparent; border-bottom-color: rgba(255,255,255,0.08); }
:deep(.el-table) { background: transparent; color: #d8e7f3; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(87,209,255,0.08); }
@media (max-width: 1400px) { .page-shell { grid-template-columns: 1fr; } }
</style>
