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
      <section class="panel chart-panel">
        <div class="panel-head">
          <div>
            <strong>{{ point.pointName || '未选择测点' }}</strong>
            <span>{{ data.message || '等待分析数据' }}</span>
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
          <span>质量 {{ snapshot.quality || 'OFFLINE' }} · 采样 {{ formatTime(snapshot.sampleTime) }}</span>
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
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { mapState } from 'vuex'
import { getVibrationAnalysis } from '@/api/monitoring'
import ContextBar from '@/components/IndustrialMonitoring/ContextBar'
import TimeRangeSelect from '@/components/IndustrialMonitoring/TimeRangeSelect'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'VibrationIndex',
  components: { ContextBar, TimeRangeSelect },
  data() {
    return {
      data: {},
      chartMode: 'trend',
      chart: null,
      selectedDevice: '',
      selectedPointId: null
    }
  },
  computed: {
    ...mapState('monitoring', ['assets', 'workbench', 'connectionState']),
    devices() { return this.assets.reduce((all, org) => all.concat(org.children || []), []) },
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
        { key: 'peak', label: 'Peak', value: this.features.peak, unit: '—' },
        { key: 'peakToPeak', label: 'Peak-Peak', value: this.features.peakToPeak, unit: '—' },
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
    await this.loadAnalysis()
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
      this.syncRoute()
      this.loadAnalysis()
    },
    changePoint(value) {
      this.$store.dispatch('monitoring/setContext', { pointId: value })
      this.syncRoute()
      this.loadAnalysis()
    },
    syncRoute() {
      this.$router.replace({ query: { deviceCode: this.selectedDevice, pointId: this.selectedPointId, range: this.range } })
    },
    async loadAnalysis() {
      if (!this.selectedPointId) return
      const response = await getVibrationAnalysis(this.selectedPointId, { ...this.rangeParams(), maxPoints: 1200 })
      this.data = response.data || {}
      if (this.chartMode !== 'trend' && this.chartEmpty) this.chartMode = 'trend'
      this.renderChart()
    },
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
      getVibrationAnalysis(this.selectedPointId, { from, to, maxPoints: 1200 }).then(response => {
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
.analysis-layout{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:12px}.chart-panel{position:relative;min-width:0}.side-column{display:grid;align-content:start;gap:12px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.panel-head strong,.panel-head span{display:block}.panel-head span{margin-top:3px;color:var(--color-muted);font-size:12px}
.quality-banner{display:flex;justify-content:space-between;margin-bottom:8px;padding:8px 10px;border-left:3px solid var(--color-muted);background:var(--color-surface-soft);color:var(--color-muted);font-size:12px}.quality-banner.good{border-color:var(--color-success)}.quality-banner.stale{border-color:var(--color-warning)}.quality-banner.bad,.quality-banner.offline{border-color:var(--color-danger)}.quality-banner strong{color:var(--color-text)}
.analysis-chart{height:570px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface-soft)}.chart-empty{position:absolute;inset:54% 0 auto;color:var(--color-muted);text-align:center;pointer-events:none}
.feature-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.feature-grid>div{padding:10px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface-soft)}.feature-grid span,.feature-grid small{display:block;color:var(--color-muted);font-size:11px}.feature-grid strong{display:block;margin:6px 0 2px;font-family:var(--font-data);font-size:22px}
.rule-card h3{margin:0 0 10px;font-size:15px}.rule-values{display:flex;gap:8px}.rule-values span{flex:1;padding:8px;border:1px solid var(--color-border);border-radius:var(--radius-sm);color:var(--color-muted)}.rule-values b{display:block;margin-top:4px;color:var(--color-text);font-family:var(--font-data)}.rule-card p{margin:10px 0 0;color:var(--color-muted);font-size:12px;line-height:1.6}
.alarm-list{display:grid;gap:7px}.alarm-list button{display:grid;grid-template-columns:8px minmax(0,1fr);gap:9px;padding:9px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface-soft);color:var(--color-text);text-align:left;cursor:pointer}.alarm-list i{width:7px;height:7px;margin-top:4px;border-radius:50%;background:var(--color-warning)}.alarm-list i.alarm{background:var(--color-danger)}.alarm-list strong,.alarm-list small{display:block}.alarm-list small{margin-top:3px;color:var(--color-muted)}.empty-card{padding:16px;color:var(--color-muted);text-align:center}
::v-deep .el-input__inner{border-color:var(--color-border);background:var(--color-surface-soft);color:var(--color-text)}
@media(max-width:1100px){.analysis-layout{grid-template-columns:1fr}.side-column{grid-template-columns:repeat(3,minmax(0,1fr))}.analysis-chart{height:440px}}
@media(max-width:800px){.toolbar,.panel-head{align-items:flex-start;flex-direction:column}.selectors,.side-column{grid-template-columns:1fr;flex-direction:column}.selectors .el-select:first-child,.selectors .el-select:last-child{width:100%}}
</style>
