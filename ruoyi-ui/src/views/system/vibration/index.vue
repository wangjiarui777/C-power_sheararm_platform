<template>
  <div class="analysis-page">
    <context-bar
      eyebrow="旋转设备诊断"
      title="振动分析工作台"
      :device="workbench.device"
      :latest-sample-time="snapshot.sampleTime"
      :delay-seconds="delaySeconds"
      :connection-state="connectionState"
      @refresh="loadAnalysis"
    />

    <section class="toolbar panel">
      <el-radio-group v-model="sourceMode" size="mini" @change="changeSourceMode">
        <el-radio-button label="LIVE">实时数据</el-radio-button>
        <el-radio-button label="FILE">文件数据</el-radio-button>
      </el-radio-group>
      <div class="selectors">
        <el-select v-model="selectedDevice" size="mini" filterable @change="changeDevice">
          <el-option v-for="device in devices" :key="device.deviceCode" :label="`${device.label} · ${device.deviceCode}`" :value="device.deviceCode" />
        </el-select>
        <el-select v-model="selectedPointId" size="mini" filterable @change="changePoint">
          <el-option v-for="point in workbench.points" :key="point.pointId" :label="point.pointName" :value="point.pointId" />
        </el-select>
      </div>
      <time-range-select v-model="range" @input="loadAnalysis" />
      <div class="toolbar-actions">
        <el-button size="mini" @click="goTemperature">温度分析</el-button>
        <el-button size="mini" type="primary" icon="el-icon-refresh" @click="loadAnalysis">刷新</el-button>
      </div>
    </section>

    <main class="analysis-layout">
      <aside class="source-column">
        <section class="panel source-panel">
          <div class="panel-head">
            <div><strong>数据源与文件台账</strong><span>接入 → 映射 → 校验 → 分析</span></div>
            <el-button type="text" icon="el-icon-refresh" :loading="fileLoading" @click="loadFiles">刷新</el-button>
          </div>
          <div class="source-identity" :class="sourceMode.toLowerCase()">
            <span class="source-dot" />
            <div><small>当前分析源</small><strong>{{ sourceMode === 'FILE' ? (activeFile.fileName || '未选择文件') : '实时采集数据' }}</strong></div>
          </div>
          <div v-if="sourceMode === 'FILE'" class="file-filter-stack">
            <el-select v-model="fileQuery.status" clearable size="mini" placeholder="全部状态" @change="searchFiles">
              <el-option v-for="item in fileStatuses" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-input v-model.trim="fileQuery.keyword" size="mini" clearable prefix-icon="el-icon-search" placeholder="文件 / 设备 / 测点" @keyup.enter.native="searchFiles" @clear="searchFiles" />
          </div>
          <div v-if="sourceMode === 'FILE'" class="source-pipeline">
            <button v-for="stage in fileStatuses" :key="stage.value" type="button" :class="[{ active: fileQuery.status === stage.value }, stage.tone]" @click="filterFilesByStatus(stage.value)">
              <i /><span>{{ stage.label }}</span><b>{{ fileStatusCount(stage.value) }}</b>
            </button>
          </div>
          <div v-if="sourceMode === 'FILE'" v-loading="fileLoading" class="file-list">
            <button v-for="row in fileRows" :key="row.id" type="button" class="file-row" :class="{ selected: activeFile.id === row.id }" @click="selectFile(row)">
              <span class="file-ext">{{ (row.fileExt || 'BIN').toUpperCase() }}</span>
              <span class="file-row-copy"><strong :title="row.fileName">{{ row.fileName }}</strong><small>{{ row.deviceCode || '待映射设备' }} · {{ row.pointCode || '待映射测点' }}</small><em :class="statusTone(row.status)"><i />{{ statusLabel(row.status) }}</em></span>
              <i class="el-icon-arrow-right" />
            </button>
            <div v-if="!fileRows.length && !fileLoading" class="source-empty">当前设备暂无接收文件</div>
          </div>
          <div v-if="sourceMode === 'FILE' && activeFile.id" class="file-actions">
            <el-button v-if="canAssociate(activeFile)" v-hasPermi="['sensor:ingest:associate']" size="mini" type="warning" plain @click="openAssociate(activeFile)">关联测点</el-button>
            <el-button v-if="activeFile.status === 'FAILED'" v-hasPermi="['sensor:ingest:retry']" size="mini" type="warning" plain @click="retryFile(activeFile)">重新接收</el-button>
            <el-button v-if="isFileReady(activeFile)" size="mini" type="primary" plain @click="loadFileAnalysis(activeFile)">载入分析</el-button>
            <el-button v-if="isFileReady(activeFile)" size="mini" type="text" @click="openDiagnosis(activeFile)">执行模型诊断</el-button>
          </div>
          <div v-if="sourceMode === 'FILE' && activeFile.id" class="file-meta">
            <span>接收时间 <b>{{ formatTime(activeFile.receivedTime || activeFile.createTime) }}</b></span>
            <span>通道 <b>CH{{ pad(activeFile.channelId) }}</b></span>
            <span v-if="activeFile.errorMessage" class="error-text">{{ activeFile.errorMessage }}</span>
          </div>
        </section>
      </aside>
      <section class="panel chart-panel">
        <div class="panel-head">
          <div>
            <strong>{{ point.pointName || '未选择测点' }}</strong>
            <span>{{ sourceMode === 'FILE' ? (data.attachment ? `文件源 · ${data.attachment.fileName}` : '请选择已接收文件') : (data.message || '等待分析数据') }}</span>
          </div>
          <el-radio-group v-model="chartMode" size="mini" @change="renderChart">
            <el-radio-button label="trend">历史趋势</el-radio-button>
            <el-radio-button label="waveform" :disabled="!hasWaveform">时域波形</el-radio-button>
            <el-radio-button label="spectrum" :disabled="!hasSpectrum">FFT</el-radio-button>
            <el-radio-button label="envelope" :disabled="!hasEnvelope">包络频谱</el-radio-button>
            <el-radio-button label="waterfall" :disabled="!hasWaterfall">瀑布图</el-radio-button>
          </el-radio-group>
        </div>
        <div class="quality-banner" :class="String(snapshot.quality || 'OFFLINE').toLowerCase()">
          <strong>{{ dataStatusText }}</strong>
          <span>质量 {{ qualityText(snapshot.quality) }} · 采样 {{ formatTime(snapshot.sampleTime) }}</span>
        </div>
        <div ref="analysisChart" class="analysis-chart"></div>
        <div v-if="chartEmpty" class="chart-empty">{{ emptyText }}</div>
      </section>

      <aside class="side-column">
        <section class="panel">
          <div class="panel-head"><div><strong>特征指标</strong><span>当前有效采样窗口</span></div></div>
          <div class="feature-grid">
            <div v-for="feature in featureCards" :key="feature.key">
              <span>{{ feature.label }}</span><strong>{{ metric(feature.value) }}</strong><small>{{ feature.unit }}</small>
            </div>
          </div>
        </section>

        <section class="panel rule-card">
          <div class="panel-head"><div><strong>生效规则</strong><span>{{ thresholds.ruleVersion || '--' }}</span></div></div>
          <h3>{{ thresholds.ruleName || '未配置规则' }}</h3>
          <div class="rule-values">
            <span>高限 <b>{{ metric(thresholds.high) }}</b></span>
            <span>高高限 <b>{{ metric(thresholds.highHigh) }}</b></span>
          </div>
          <p>{{ thresholds.actionAdvice }}</p>
        </section>

        <section class="panel">
          <div class="panel-head"><div><strong>关联告警</strong><span>点击定位证据窗口</span></div></div>
          <div class="alarm-list">
            <button v-for="alarm in alarms" :key="alarm.id" @click="focusAlarm(alarm)">
              <i :class="alarm.alarmLevel >= 3 ? 'alarm' : 'warning'"></i>
              <span><strong>{{ alarm.diagnosisResult || '振动告警' }}</strong><small>{{ formatTime(alarm.alarmTime) }}</small></span>
            </button>
            <div v-if="!alarms.length" class="empty-card">当前测点无关联告警</div>
          </div>
        </section>
      </aside>
    </main>

    <el-dialog title="关联接收文件" :visible.sync="associateVisible" width="620px" append-to-body custom-class="industrial-dialog">
      <div class="associate-file"><span class="file-ext">{{ (currentFile.fileExt || 'BIN').toUpperCase() }}</span><div><small>待关联文件</small><strong>{{ currentFile.fileName }}</strong></div></div>
      <el-alert title="选择目标设备、测点以及绑定的采集通道，服务端会再次校验权限与映射一致性。" type="info" :closable="false" show-icon />
      <el-form ref="associateForm" :model="associateForm" :rules="associateRules" label-width="92px" class="associate-form">
        <el-form-item label="目标设备" prop="deviceId"><el-select v-model="associateForm.deviceId" filterable @change="onAssociateDeviceChange"><el-option v-for="item in ingestDevices" :key="item.id" :label="`${item.deviceName} · ${item.deviceCode}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="目标测点" prop="pointId"><el-select v-model="associateForm.pointId" filterable @change="onAssociatePointChange"><el-option v-for="item in associatePoints" :key="item.id" :label="`${item.pointName} · ${item.pointCode}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="物理通道" prop="channelId"><el-select v-model="associateForm.channelId" filterable><el-option v-for="item in associateChannels" :key="item.id" :label="`CH${pad(item.channelNo)}${item.enabled ? '' : ' · 已停用'}`" :disabled="!item.enabled" :value="item.channelNo" /></el-select><span v-if="associateForm.pointId && !associateChannels.length" class="field-warning">该测点尚未绑定 MAT 通道。</span></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="associateVisible = false">取消</el-button><el-button type="primary" :loading="associateSubmitting" @click="submitAssociate">确认关联并校验</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { mapState } from 'vuex'
import { getVibrationAnalysis } from '@/api/monitoring'
import { listIngestFiles, associateIngestFile, retryIngestFile, getAcquisitionOptions, listAcquisitionChannels } from '@/api/sensor/access'
import { checkPermi } from '@/utils/permission'
import ContextBar from '@/components/IndustrialMonitoring/ContextBar'
import TimeRangeSelect from '@/components/IndustrialMonitoring/TimeRangeSelect'
import { industrialChartTheme } from '@/utils/industrialTheme'
import { qualityText } from '@/utils/industrialLabels'

export default {
  name: 'VibrationIndex',
  components: { ContextBar, TimeRangeSelect },
  data() {
    return {
      data: {},
      chartMode: 'trend',
      chart: null,
      selectedDevice: '',
      selectedPointId: null,
      sourceMode: 'LIVE',
      fileLoading: false,
      fileRows: [],
      fileTotal: 0,
      fileStatusCounts: {},
      ingestDevices: [],
      ingestPoints: [],
      ingestChannels: [],
      activeFile: {},
      fileQuery: { pageNum: 1, pageSize: 8, status: null, sourceType: null, keyword: '', deviceCode: '', pointId: null },
      associateVisible: false,
      associateSubmitting: false,
      currentFile: {},
      associateForm: { deviceId: null, pointId: null, channelId: null },
      associateRules: {
        deviceId: [{ required: true, message: '请选择目标设备', trigger: 'change' }],
        pointId: [{ required: true, message: '请选择目标测点', trigger: 'change' }],
        channelId: [{ required: true, message: '请选择采集通道', trigger: 'change' }]
      }
    }
  },
  computed: {
    ...mapState('monitoring', ['assets', 'workbench', 'connectionState']),
    devices() { return this.assets.reduce((all, org) => all.concat(org.children || []), []) },
    fileStatuses() {
      return [
        { value: 'RECEIVING', label: '接收中', tone: 'cyan' },
        { value: 'UNMAPPED', label: '待映射', tone: 'amber' },
        { value: 'VALIDATING', label: '校验中', tone: 'cyan' },
        { value: 'ACCEPTED', label: '已接收', tone: 'green' },
        { value: 'DUPLICATE', label: '重复文件', tone: 'green' },
        { value: 'REJECTED', label: '校验拒绝', tone: 'red' },
        { value: 'FAILED', label: '接收失败', tone: 'red' }
      ]
    },
    associatePoints() { return this.ingestPoints.filter(item => String(item.deviceId) === String(this.associateForm.deviceId)) },
    associateChannels() { return this.ingestChannels.filter(item => String(item.deviceId) === String(this.associateForm.deviceId) && String(item.pointId) === String(this.associateForm.pointId)) },
    point() { return this.data.point || {} },
    snapshot() { return this.data.snapshot || {} },
    features() { return this.data.features || {} },
    thresholds() { return this.data.thresholds || {} },
    alarms() { return this.data.alarms || [] },
    hasWaveform() { return Array.isArray(this.data.waveform) && this.data.waveform.length },
    hasSpectrum() { return Array.isArray(this.data.spectrum) && this.data.spectrum.length },
    hasEnvelope() { return Array.isArray(this.data.envelopeSpectrum) && this.data.envelopeSpectrum.length },
    hasWaterfall() { return Array.isArray(this.data.waterfall) && this.data.waterfall.length },
    chartEmpty() {
      if (this.chartMode === 'trend') return !(this.data.trend && this.data.trend.length)
      if (this.chartMode === 'waveform') return !this.hasWaveform
      if (this.chartMode === 'spectrum') return !this.hasSpectrum
      if (this.chartMode === 'envelope') return !this.hasEnvelope
      return !this.hasWaterfall
    },
    emptyText() {
      return {
        trend: '该时间范围没有振动趋势数据',
        waveform: '未采集原始时域波形',
        spectrum: '未采集 FFT 频谱',
        envelope: '未生成包络频谱',
        waterfall: '连续频谱数量不足，不能生成瀑布图'
      }[this.chartMode]
    },
    dataStatusText() {
      return { full: '原始信号与特征数据完整', featureOnly: '当前仅有特征趋势', empty: '当前无有效数据' }[this.data.dataStatus] || '等待数据'
    },
    featureCards() {
      return [
        { key: 'rms', label: 'RMS', value: this.features.rms, unit: 'mm/s' },
        { key: 'acceleration', label: '加速度', value: this.features.acceleration, unit: 'm/s²' },
        { key: 'peak', label: '峰值 Peak', value: this.features.peak, unit: '—' },
        { key: 'peakToPeak', label: '峰峰值 Peak-Peak', value: this.features.peakToPeak, unit: '—' },
        { key: 'kurtosis', label: '峭度', value: this.features.kurtosis, unit: '—' },
        { key: 'crestFactor', label: '波峰因数', value: this.features.crestFactor, unit: '—' },
        { key: 'mainFrequency', label: '主频', value: this.features.mainFrequency, unit: 'Hz' },
        { key: 'speedOrder', label: '转速倍频', value: this.features.speedOrder, unit: 'X' }
      ]
    },
    delaySeconds() {
      if (!this.snapshot.sampleTime) return null
      return Math.max(0, Math.floor((Date.now() - new Date(this.snapshot.sampleTime).getTime()) / 1000))
    },
    range: {
      get() { return this.$store.state.monitoring.range },
      set(value) { this.$store.dispatch('monitoring/setContext', { range: value }) }
    }
  },
  async created() {
    if (!this.assets.length) await this.$store.dispatch('monitoring/loadAssets')
    this.selectedDevice = this.$route.query.deviceCode || this.$store.state.monitoring.deviceCode
    if (this.selectedDevice) this.$store.dispatch('monitoring/setContext', { deviceCode: this.selectedDevice })
    await this.$store.dispatch('monitoring/loadWorkbench')
    this.selectedPointId = Number(this.$route.query.pointId) || this.$store.state.monitoring.pointId || (this.workbench.points[0] && this.workbench.points[0].pointId)
    if (this.$route.query.range) this.$store.dispatch('monitoring/setContext', { range: this.$route.query.range })
    this.$store.dispatch('monitoring/connect')
    this.sourceMode = String(this.$route.query.source || '').toUpperCase() === 'FILE' ? 'FILE' : 'LIVE'
    await this.loadIngestContext()
    await this.loadAnalysis()
    if (this.sourceMode === 'FILE') await this.restoreFileFromRoute()
  },
  mounted() {
    window.addEventListener('resize', this.resize)
    window.addEventListener('appearance-mode-change', this.renderChart)
    this.$nextTick(() => {
      this.chart = echarts.init(this.$refs.analysisChart)
      this.renderChart()
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resize)
    window.removeEventListener('appearance-mode-change', this.renderChart)
    if (this.chart) this.chart.dispose()
    this.$store.dispatch('monitoring/disconnect')
  },
  methods: {
    async changeDevice(value) {
      this.$store.dispatch('monitoring/setContext', { deviceCode: value, pointId: null })
      await this.$store.dispatch('monitoring/loadWorkbench', { deviceCode: value })
      this.selectedPointId = this.workbench.points[0] && this.workbench.points[0].pointId
      this.activeFile = {}
      this.fileQuery.deviceCode = value
      this.fileQuery.pointId = this.selectedPointId
      this.syncRoute()
      await this.loadFiles()
      this.loadAnalysis()
    },
    changePoint(value) {
      this.$store.dispatch('monitoring/setContext', { pointId: value })
      this.activeFile = {}
      this.fileQuery.pointId = value
      this.syncRoute()
      this.loadFiles()
      this.loadAnalysis()
    },
    changeSourceMode(value) {
      if (value === 'LIVE') {
        this.activeFile = {}
        this.chartMode = 'trend'
        this.syncRoute()
        this.loadAnalysis()
        return
      }
      this.fileQuery.deviceCode = this.selectedDevice
      this.fileQuery.pointId = this.selectedPointId
      this.syncRoute()
      this.loadFiles()
    },
    syncRoute() {
      const query = { deviceCode: this.selectedDevice, pointId: this.selectedPointId, range: this.range }
      if (this.sourceMode === 'FILE') {
        query.source = 'file'
        if (this.activeFile.attachmentId) query.attachmentId = this.activeFile.attachmentId
      }
      this.$router.replace({ query })
    },
    async loadAnalysis() {
      if (!this.selectedPointId) return
      const params = { ...this.rangeParams(), maxPoints: 1200 }
      if (this.sourceMode === 'FILE') {
        if (!this.activeFile.attachmentId) {
          this.data = {}
          this.renderChart()
          return
        }
        params.attachmentId = this.activeFile.attachmentId
      }
      const response = await getVibrationAnalysis(this.selectedPointId, params)
      this.data = response.data || {}
      if (this.sourceMode === 'FILE' && !(this.data.trend && this.data.trend.length) && this.hasWaveform) {
        this.chartMode = 'waveform'
      } else if (this.chartMode !== 'trend' && this.chartEmpty) {
        this.chartMode = 'trend'
      }
      this.renderChart()
    },
    async loadIngestContext() {
      if (!checkPermi(['sensor:ingest:list'])) return
      try {
        const options = await getAcquisitionOptions()
        const data = options.data || {}
        this.ingestDevices = data.devices || []
        this.ingestPoints = data.points || []
      } catch (error) {
        this.ingestDevices = []
        this.ingestPoints = []
      }
      if (checkPermi(['sensor:ingest:associate'])) {
        try {
          const response = await listAcquisitionChannels({ pageNum: 1, pageSize: 10000 })
          this.ingestChannels = response.rows || []
        } catch (error) { this.ingestChannels = [] }
      }
      this.fileQuery.deviceCode = this.selectedDevice
      this.fileQuery.pointId = this.selectedPointId
      await this.loadFiles(false)
    },
    async loadFiles(showLoading = true) {
      if (showLoading) this.fileLoading = true
      try {
        const response = await listIngestFiles(this.fileQuery)
        this.fileRows = response.rows || []
        this.fileTotal = response.total || 0
        this.fileStatusCounts = this.fileRows.reduce((all, row) => { all[row.status] = (all[row.status] || 0) + 1; return all }, {})
        if (this.activeFile.id) {
          const refreshed = this.fileRows.find(row => row.id === this.activeFile.id)
          if (refreshed) this.activeFile = refreshed
        }
      } finally { if (showLoading) this.fileLoading = false }
    },
    async restoreFileFromRoute() {
      const attachmentId = Number(this.$route.query.attachmentId)
      if (!attachmentId) return
      try {
        let row = this.fileRows.find(item => Number(item.attachmentId) === attachmentId)
        if (!row) {
          const response = await listIngestFiles({ attachmentId, pageNum: 1, pageSize: 1 })
          row = (response.rows || [])[0]
        }
        if (row) this.selectFile(row)
      } catch (error) {
        this.activeFile = {}
      }
    },
    searchFiles() { this.fileQuery.pageNum = 1; this.loadFiles() },
    filterFilesByStatus(status) { this.fileQuery.status = this.fileQuery.status === status ? null : status; this.searchFiles() },
    fileStatusCount(status) { return this.fileStatusCounts[status] || 0 },
    selectFile(row, load = true) {
      this.activeFile = row
      this.sourceMode = 'FILE'
      this.syncRoute()
      if (load && this.isFileReady(row)) this.loadFileAnalysis(row)
    },
    async loadFileAnalysis(row) {
      if (!this.isFileReady(row) || !row.attachmentId) return
      this.activeFile = row
      this.sourceMode = 'FILE'
      this.syncRoute()
      await this.loadAnalysis()
    },
    isFileReady(row) { return Boolean(row && row.attachmentId && ['ACCEPTED', 'DUPLICATE'].includes(row.status)) },
    canAssociate(row) { return row && ['UNMAPPED', 'REJECTED'].includes(row.status) },
    openAssociate(row) {
      this.currentFile = row
      this.associateForm = { deviceId: null, pointId: null, channelId: null }
      this.associateVisible = true
      this.$nextTick(() => this.$refs.associateForm && this.$refs.associateForm.clearValidate())
    },
    onAssociateDeviceChange() { this.associateForm.pointId = null; this.associateForm.channelId = null },
    onAssociatePointChange() { this.associateForm.channelId = null },
    submitAssociate() {
      this.$refs.associateForm.validate(async valid => {
        if (!valid || this.associateSubmitting) return
        this.associateSubmitting = true
        try {
          await associateIngestFile(this.currentFile.id, this.associateForm)
          this.$modal.msgSuccess('关联已提交，文件进入校验队列')
          this.associateVisible = false
          await this.loadFiles()
        } finally { this.associateSubmitting = false }
      })
    },
    async retryFile(row) {
      await this.$modal.confirm(`确认重新接收“${row.fileName}”？`)
      await retryIngestFile(row.id)
      this.$modal.msgSuccess('已重新进入接收队列')
      await this.loadFiles()
    },
    openDiagnosis(row) {
      this.$router.push({ path: '/analysis-toolkit/bearing-diagnosis', query: { attachmentId: row.attachmentId, deviceCode: row.deviceCode, pointId: row.pointId, view: 'detail' } })
    },
    qualityText(value) { return qualityText(value) },
    statusLabel(value) { const item = this.fileStatuses.find(status => status.value === value); return item ? item.label : value || '--' },
    statusTone(value) { return { ACCEPTED: 'green', DUPLICATE: 'green', FAILED: 'red', REJECTED: 'red', UNMAPPED: 'amber', RECEIVING: 'cyan', VALIDATING: 'cyan' }[value] || '' },
    pad(value) { return String(value == null ? '--' : value).padStart(2, '0') },
    rangeParams() {
      if (this.range === 'realtime') return {}
      const durations = { '15m': 15 * 60e3, '1h': 60 * 60e3, '8h': 8 * 60 * 60e3, '24h': 24 * 60 * 60e3 }
      const to = new Date()
      return { from: new Date(to.getTime() - (durations[this.range] || durations['15m'])).toISOString(), to: to.toISOString() }
    },
    renderChart() {
      this.$nextTick(() => {
        if (!this.chart) return
        this.chart.setOption(this.chartOption(), true)
      })
    },
    chartOption() {
      const base = {
        animation: false,
        backgroundColor: 'transparent',
        color: [industrialChartTheme.vibration, industrialChartTheme.temperature, industrialChartTheme.warning],
        tooltip: { trigger: 'axis', backgroundColor: industrialChartTheme.tooltipBg, borderColor: industrialChartTheme.tooltipBorder, textStyle: { color: industrialChartTheme.text } },
        grid: { left: 58, right: 28, top: 42, bottom: 38 },
        xAxis: { type: 'category', axisLabel: { color: industrialChartTheme.axis }, axisLine: { lineStyle: { color: industrialChartTheme.border } } },
        yAxis: { type: 'value', scale: true, axisLabel: { color: industrialChartTheme.axis }, splitLine: { lineStyle: { color: industrialChartTheme.grid } } },
        series: []
      }
      if (this.chartMode === 'trend') {
        const rows = this.data.trend || []
        base.xAxis.data = rows.map(row => this.axisTime(row.time))
        base.yAxis.name = 'mm/s'
        base.series = [{ type: 'line', name: 'RMS', showSymbol: false, connectNulls: false, data: rows.map(row => row.vibration), lineStyle: { color: industrialChartTheme.vibration, width: 2.4 }, markLine: this.thresholdLines() }]
      } else if (this.chartMode === 'waveform') {
        base.xAxis.data = (this.data.waveform || []).map((_, index) => index)
        base.xAxis.name = '采样点'
        base.series = [{ type: 'line', name: '波形', showSymbol: false, data: this.data.waveform || [], lineStyle: { color: industrialChartTheme.vibration, width: 1.2 } }]
      } else if (this.chartMode === 'spectrum') {
        base.xAxis.data = this.data.frequencyAxis || []
        base.xAxis.name = 'Hz'
        base.series = [{ type: 'bar', name: '幅值', data: this.data.spectrum || [], itemStyle: { color: industrialChartTheme.temperature }, barMaxWidth: 9 }]
      } else if (this.chartMode === 'envelope') {
        base.xAxis.data = (this.data.envelopeSpectrum || []).map((_, index) => index)
        base.series = [{ type: 'line', showSymbol: false, data: this.data.envelopeSpectrum || [], lineStyle: { color: industrialChartTheme.warning, width: 1.8 } }]
      }
      return base
    },
    thresholdLines() {
      const data = []
      if (this.thresholds.high !== null && this.thresholds.high !== undefined) data.push({ yAxis: this.thresholds.high, name: '高限', lineStyle: { color: industrialChartTheme.warning } })
      if (this.thresholds.highHigh !== null && this.thresholds.highHigh !== undefined) data.push({ yAxis: this.thresholds.highHigh, name: '高高限', lineStyle: { color: industrialChartTheme.danger } })
      return { symbol: 'none', label: { color: industrialChartTheme.muted }, data }
    },
    focusAlarm(alarm) {
      this.range = '1h'
      const time = new Date(alarm.alarmTime)
      const from = new Date(time.getTime() - 30 * 60e3).toISOString()
      const to = new Date(time.getTime() + 30 * 60e3).toISOString()
      const params = { from, to, maxPoints: 1200 }
      if (this.sourceMode === 'FILE' && this.activeFile.attachmentId) params.attachmentId = this.activeFile.attachmentId
      getVibrationAnalysis(this.selectedPointId, params).then(response => {
        this.data = response.data || {}
        this.chartMode = 'trend'
        this.renderChart()
      })
    },
    goTemperature() {
      this.$router.push({ path: '/monitoring-center/temperature', query: { deviceCode: this.selectedDevice, pointId: this.selectedPointId, range: this.range } })
    },
    metric(value) { return value === null || value === undefined ? '--' : Number(value).toFixed(2) },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--' },
    axisTime(value) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '' },
    resize() { if (this.chart) this.chart.resize() }
  }
}
</script>

<style scoped>
.analysis-page{min-height:calc(100vh - 84px);padding:var(--space-page);background:transparent;color:var(--color-text);font-family:var(--font-ui)}
.panel{padding:14px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:var(--space-section) 0}.selectors,.toolbar-actions{display:flex;gap:8px}.selectors .el-select:first-child{width:240px}.selectors .el-select:last-child{width:180px}
.analysis-layout{display:grid;grid-template-columns:292px minmax(0,1fr) 330px;gap:12px}.source-column,.chart-panel,.side-column{min-width:0}.source-column{display:flex;align-items:flex-start}.chart-panel{position:relative}.side-column{display:grid;align-content:start;gap:12px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.panel-head strong,.panel-head span{display:block}.panel-head span{margin-top:3px;color:var(--color-muted);font-size:12px}
.source-panel{width:100%;min-height:100%;padding:12px}.source-identity{display:flex;align-items:center;gap:9px;margin-bottom:12px;padding:10px;border:1px solid var(--color-border);background:var(--color-surface-soft)}.source-identity small,.source-identity strong{display:block}.source-identity small{color:var(--color-muted);font-size:10px}.source-identity strong{margin-top:3px;max-width:210px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.source-dot{width:8px;height:8px;border-radius:50%;background:var(--color-success);box-shadow:0 0 10px currentColor}.source-identity.file .source-dot{background:var(--color-warning)}.file-filter-stack{display:grid;gap:7px;margin-bottom:10px}.source-pipeline{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:5px;margin-bottom:10px}.source-pipeline button{display:flex;align-items:center;gap:5px;min-width:0;padding:6px 7px;border:1px solid var(--color-border);background:var(--color-surface-soft);color:var(--color-muted);font-size:10px;text-align:left;cursor:pointer}.source-pipeline button.active{border-color:currentColor;background:var(--color-surface)}.source-pipeline button i{width:6px;height:6px;flex:0 0 auto;border-radius:50%;background:currentColor}.source-pipeline button span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.source-pipeline button b{margin-left:auto;font-family:var(--font-data)}.source-pipeline .cyan{color:var(--color-info)}.source-pipeline .amber{color:var(--color-warning)}.source-pipeline .green{color:var(--color-success)}.source-pipeline .red{color:var(--color-danger)}.file-list{display:grid;gap:6px;min-height:180px}.file-row{display:grid;grid-template-columns:34px minmax(0,1fr) 12px;align-items:center;gap:7px;padding:7px;border:1px solid var(--color-border);background:var(--color-surface-soft);color:var(--color-text);text-align:left;cursor:pointer}.file-row:hover,.file-row.selected{border-color:var(--color-info);background:rgba(34,211,238,.06)}.file-row-copy{min-width:0}.file-row-copy strong,.file-row-copy small,.file-row-copy em{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-row-copy strong{font-size:11px}.file-row-copy small{margin-top:3px;color:var(--color-muted);font-size:10px}.file-row-copy em{margin-top:4px;font-size:10px;font-style:normal}.file-row-copy em i{display:inline-block;width:5px;height:5px;margin-right:4px;border-radius:50%;background:currentColor}.file-ext{display:grid;place-items:center;width:34px;height:30px;border:1px solid var(--color-border-strong,var(--color-border));color:var(--color-info);font:700 9px var(--font-data)}.file-row .green{color:var(--color-success)}.file-row .amber{color:var(--color-warning)}.file-row .cyan{color:var(--color-info)}.file-row .red,.error-text{color:var(--color-danger)}.source-empty{padding:30px 8px;color:var(--color-muted);font-size:12px;text-align:center}.file-actions{display:flex;flex-wrap:wrap;gap:5px;margin-top:10px}.file-meta{display:grid;gap:5px;margin-top:10px;padding-top:9px;border-top:1px solid var(--color-border);color:var(--color-muted);font-size:10px}.file-meta b{color:var(--color-text);font-family:var(--font-data);font-weight:500}.associate-file{display:flex;align-items:center;gap:10px;margin:-6px 0 16px;padding:12px;border:1px solid var(--color-border);background:var(--color-surface-soft)}.associate-file small,.associate-file strong{display:block}.associate-file small{color:var(--color-muted)}.associate-file strong{margin-top:4px}.associate-form{margin-top:20px}.associate-form .el-select{width:100%}.field-warning{display:block;color:var(--color-warning);font-size:12px;line-height:1.5}
.quality-banner{display:flex;justify-content:space-between;margin-bottom:8px;padding:8px 10px;border-left:3px solid var(--color-muted);background:var(--color-surface-soft);color:var(--color-muted);font-size:12px}.quality-banner.good{border-color:var(--color-success)}.quality-banner.stale{border-color:var(--color-warning)}.quality-banner.bad,.quality-banner.offline{border-color:var(--color-danger)}.quality-banner strong{color:var(--color-text)}
.analysis-chart{height:570px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface-soft)}.chart-empty{position:absolute;inset:54% 0 auto;color:var(--color-muted);text-align:center;pointer-events:none}
.feature-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.feature-grid>div{padding:10px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface-soft)}.feature-grid span,.feature-grid small{display:block;color:var(--color-muted);font-size:11px}.feature-grid strong{display:block;margin:6px 0 2px;font-family:var(--font-data);font-size:22px}
.rule-card h3{margin:0 0 10px;font-size:15px}.rule-values{display:flex;gap:8px}.rule-values span{flex:1;padding:8px;border:1px solid var(--color-border);border-radius:var(--radius-sm);color:var(--color-muted)}.rule-values b{display:block;margin-top:4px;color:var(--color-text);font-family:var(--font-data)}.rule-card p{margin:10px 0 0;color:var(--color-muted);font-size:12px;line-height:1.6}
.alarm-list{display:grid;gap:7px}.alarm-list button{display:grid;grid-template-columns:8px minmax(0,1fr);gap:9px;padding:9px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface-soft);color:var(--color-text);text-align:left;cursor:pointer}.alarm-list i{width:7px;height:7px;margin-top:4px;border-radius:50%;background:var(--color-warning)}.alarm-list i.alarm{background:var(--color-danger)}.alarm-list strong,.alarm-list small{display:block}.alarm-list small{margin-top:3px;color:var(--color-muted)}.empty-card{padding:16px;color:var(--color-muted);text-align:center}
::v-deep .el-input__inner{border-color:var(--color-border);background:var(--color-surface-soft);color:var(--color-text)}
@media(max-width:1320px){.analysis-layout{grid-template-columns:260px minmax(0,1fr) 300px}}
@media(max-width:1100px){.analysis-layout{grid-template-columns:1fr}.source-column{order:-1}.source-panel{min-height:auto}.file-list{grid-template-columns:repeat(2,minmax(0,1fr))}.side-column{grid-template-columns:repeat(3,minmax(0,1fr))}.analysis-chart{height:440px}}
@media(max-width:800px){.toolbar,.panel-head{align-items:flex-start;flex-direction:column}.selectors,.side-column{grid-template-columns:1fr;flex-direction:column}.selectors .el-select:first-child,.selectors .el-select:last-child{width:100%}}
@media(max-width:560px){.file-list{grid-template-columns:1fr}.source-pipeline{grid-template-columns:repeat(3,minmax(0,1fr))}.source-pipeline button{font-size:9px}.source-pipeline button b{display:none}}
@media(prefers-reduced-motion:reduce){.file-row{transition:none}}
</style>
