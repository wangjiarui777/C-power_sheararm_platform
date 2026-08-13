<template>
  <div class="analysis-page">
    <context-bar
      eyebrow="热状态与耦合诊断"
      title="温度分析工作台"
      :device="workbench.device"
      :latest-sample-time="snapshot.sampleTime"
      :delay-seconds="delaySeconds"
      :connection-state="connectionState"
      @refresh="loadAll"
    />

    <section class="toolbar panel">
      <div class="selectors">
        <el-select v-model="selectedDevice" size="mini" filterable @change="changeDevice">
          <el-option v-for="device in devices" :key="device.deviceCode" :label="`${device.label} · ${device.deviceCode}`" :value="device.deviceCode" />
        </el-select>
        <el-select v-model="selectedPointIds" size="mini" multiple collapse-tags filterable @change="changePoints">
          <el-option v-for="point in workbench.points" :key="point.pointId" :label="point.pointName" :value="point.pointId" />
        </el-select>
      </div>
      <time-range-select v-model="range" @input="loadAll" />
      <div class="toolbar-actions">
        <el-button size="mini" @click="goVibration">振动分析</el-button>
        <el-button size="mini" type="primary" icon="el-icon-refresh" @click="loadAll">刷新</el-button>
      </div>
    </section>

    <section class="metric-strip">
      <div><span>当前温度</span><strong>{{ metric(snapshot.value) }}</strong><small>{{ snapshot.unit || '℃' }}</small></div>
      <div><span>温升速率</span><strong>{{ metric(statistics.latestRoc) }}</strong><small>℃/min</small></div>
      <div><span>历史极值</span><strong>{{ metric(statistics.maximum) }}</strong><small>℃</small></div>
      <div><span>同步振动</span><strong>{{ metric(statistics.latestVibration) }}</strong><small>mm/s</small></div>
      <div><span>数据质量</span><strong class="quality-value">{{ snapshot.quality || 'OFFLINE' }}</strong><small>{{ dataStatusText }}</small></div>
    </section>

    <main class="temperature-layout">
      <section class="panel chart-panel">
        <div class="panel-head">
          <div><strong>{{ chartMode === 'coupling' ? '温振耦合' : '多测点温度趋势' }}</strong><span>{{ point.pointName || '请选择测点' }}</span></div>
          <el-radio-group v-model="chartMode" size="mini" @change="renderChart">
            <el-radio-button label="trend">温度趋势</el-radio-button>
            <el-radio-button label="coupling">温振耦合</el-radio-button>
            <el-radio-button label="roc">变化率</el-radio-button>
          </el-radio-group>
        </div>
        <div ref="temperatureChart" class="temperature-chart"></div>
        <div v-if="!hasRows" class="chart-empty">该时间范围没有温度数据</div>
        <div class="legend-note">
          <span><i class="gap"></i>温振未在 30 秒内对齐时保留数据缺口，不进行补值</span>
          <span><i class="event"></i>图中标记维修、润滑、停机及告警处置事件</span>
        </div>
      </section>

      <aside class="side-column">
        <section class="panel rule-card">
          <div class="panel-head"><div><strong>温升规则</strong><span>{{ thresholds.ruleVersion || '--' }}</span></div></div>
          <h3>{{ thresholds.ruleName || '未配置规则' }}</h3>
          <div class="rule-grid">
            <span>高限<b>{{ metric(thresholds.high) }} ℃</b></span>
            <span>高高限<b>{{ metric(thresholds.highHigh) }} ℃</b></span>
            <span>增长周期<b>{{ thresholds.growthPeriod || '--' }}</b></span>
            <span>连续次数<b>{{ thresholds.consecutiveCount || 1 }}</b></span>
          </div>
          <p>{{ thresholds.actionAdvice }}</p>
        </section>

        <section class="panel">
          <div class="panel-head"><div><strong>设备事件</strong><span>用于解释趋势变化</span></div></div>
          <div class="event-list">
            <article v-for="event in events.slice(0, 8)" :key="event.id">
              <i></i>
              <span><strong>{{ eventType(event.eventType) }}</strong><small>{{ event.eventContent }}</small><em>{{ formatTime(event.eventTime) }}</em></span>
            </article>
            <div v-if="!events.length" class="empty-card">当前时间窗没有设备事件</div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><div><strong>温升告警</strong><span>条件与流程状态并列</span></div></div>
          <div class="alarm-list">
            <button v-for="alarm in alarms" :key="alarm.id" @click="openAlarm(alarm)">
              <span><strong>{{ alarm.diagnosisResult || '温度告警' }}</strong><small>{{ alarm.conditionStatus || 'ACTIVE' }} · {{ alarm.workflowStatus || 'NEW' }}</small></span>
              <i class="el-icon-arrow-right"></i>
            </button>
            <div v-if="!alarms.length" class="empty-card">当前测点无温升告警</div>
          </div>
        </section>
      </aside>
    </main>
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { mapState } from 'vuex'
import { getTemperatureAnalysis } from '@/api/monitoring'
import ContextBar from '@/components/IndustrialMonitoring/ContextBar'
import TimeRangeSelect from '@/components/IndustrialMonitoring/TimeRangeSelect'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'Temperature',
  components: { ContextBar, TimeRangeSelect },
  data() {
    return {
      selectedDevice: '',
      selectedPointIds: [],
      analyses: {},
      chartMode: 'trend',
      chart: null
    }
  },
  computed: {
    ...mapState('monitoring', ['assets', 'workbench', 'connectionState']),
    devices() { return this.assets.reduce((all, org) => all.concat(org.children || []), []) },
    activePointId() { return this.selectedPointIds[0] },
    data() { return this.analyses[this.activePointId] || {} },
    point() { return this.data.point || {} },
    snapshot() { return this.data.snapshot || {} },
    statistics() { return this.data.statistics || {} },
    thresholds() { return this.data.thresholds || {} },
    events() { return this.data.events || [] },
    alarms() { return this.data.alarms || [] },
    hasRows() { return Object.values(this.analyses).some(item => item.trend && item.trend.length) },
    delaySeconds() {
      if (!this.snapshot.sampleTime) return null
      return Math.max(0, Math.floor((Date.now() - new Date(this.snapshot.sampleTime).getTime()) / 1000))
    },
    dataStatusText() { return this.data.dataStatus === 'available' ? '真实采样' : '暂无采样' },
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
    const routePoint = Number(this.$route.query.pointId)
    this.selectedPointIds = [routePoint || this.$store.state.monitoring.pointId || (this.workbench.points[0] && this.workbench.points[0].pointId)].filter(Boolean)
    if (this.$route.query.range) this.$store.dispatch('monitoring/setContext', { range: this.$route.query.range })
    this.$store.dispatch('monitoring/connect')
    await this.loadAll()
  },
  mounted() {
    window.addEventListener('resize', this.resize)
    window.addEventListener('appearance-mode-change', this.renderChart)
    this.$nextTick(() => {
      this.chart = echarts.init(this.$refs.temperatureChart)
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
      this.selectedPointIds = this.workbench.points[0] ? [this.workbench.points[0].pointId] : []
      this.syncRoute()
      this.loadAll()
    },
    changePoints(value) {
      if (value.length > 4) this.selectedPointIds = value.slice(0, 4)
      this.$store.dispatch('monitoring/setContext', { pointId: this.activePointId })
      this.syncRoute()
      this.loadAll()
    },
    syncRoute() {
      this.$router.replace({ query: { deviceCode: this.selectedDevice, pointId: this.activePointId, range: this.range } })
    },
    async loadAll() {
      const entries = await Promise.all(this.selectedPointIds.map(async pointId => {
        const response = await getTemperatureAnalysis(pointId, { ...this.rangeParams(), maxPoints: 1200 })
        return [pointId, response.data || {}]
      }))
      this.analyses = Object.fromEntries(entries)
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
        const primaryRows = this.data.trend || []
        const times = primaryRows.map(row => this.axisTime(row.time))
        const series = []
        if (this.chartMode === 'trend') {
          Object.values(this.analyses).forEach((analysis, index) => {
            const rows = analysis.trend || []
            series.push({
              name: analysis.point ? analysis.point.pointName : `测点${index + 1}`,
              type: 'line',
              showSymbol: false,
              connectNulls: false,
              data: rows.map(row => row.temperature),
              lineStyle: { width: index === 0 ? 2.5 : 1.5 },
              markLine: index === 0 ? this.thresholdLines() : undefined,
              markPoint: index === 0 ? this.eventMarks(analysis.events || []) : undefined
            })
          })
        } else if (this.chartMode === 'coupling') {
          series.push(
            { name: '温度', type: 'line', showSymbol: false, connectNulls: false, data: primaryRows.map(row => row.temperature), lineStyle: { color: industrialChartTheme.temperature, width: 2.3 }, markLine: this.thresholdLines() },
            { name: '同步振动', type: 'line', yAxisIndex: 1, showSymbol: false, connectNulls: false, data: primaryRows.map(row => row.couplingQuality === 'ALIGNED' ? row.vibration : null), lineStyle: { color: industrialChartTheme.vibration, width: 2.1 } }
          )
        } else {
          series.push(
            { name: 'MA', type: 'line', showSymbol: false, connectNulls: false, data: primaryRows.map(row => row.ma), lineStyle: { color: industrialChartTheme.temperature, width: 2.1 } },
            { name: 'ROC', type: 'bar', yAxisIndex: 1, data: primaryRows.map(row => row.roc), itemStyle: { color: industrialChartTheme.warning }, barMaxWidth: 8 }
          )
        }
        this.chart.setOption({
          animation: false,
          color: [industrialChartTheme.temperature, industrialChartTheme.vibration, industrialChartTheme.event, industrialChartTheme.warning],
          tooltip: { trigger: 'axis', backgroundColor: industrialChartTheme.tooltipBg, borderColor: industrialChartTheme.tooltipBorder, textStyle: { color: industrialChartTheme.text } },
          legend: { top: 5, right: 10, textStyle: { color: industrialChartTheme.muted } },
          grid: { left: 58, right: 58, top: 48, bottom: 40 },
          xAxis: { type: 'category', boundaryGap: false, data: times, axisLabel: { color: industrialChartTheme.axis }, axisLine: { lineStyle: { color: industrialChartTheme.border } } },
          yAxis: [
            { type: 'value', name: '℃', scale: true, axisLabel: { color: industrialChartTheme.axis }, splitLine: { lineStyle: { color: industrialChartTheme.grid } } },
            { type: 'value', name: this.chartMode === 'roc' ? '℃/min' : 'mm/s', scale: true, axisLabel: { color: industrialChartTheme.axis }, splitLine: { show: false } }
          ],
          series
        }, true)
      })
    },
    thresholdLines() {
      const data = []
      if (this.thresholds.high !== null && this.thresholds.high !== undefined) data.push({ yAxis: this.thresholds.high, name: '高限', lineStyle: { color: industrialChartTheme.warning } })
      if (this.thresholds.highHigh !== null && this.thresholds.highHigh !== undefined) data.push({ yAxis: this.thresholds.highHigh, name: '高高限', lineStyle: { color: industrialChartTheme.danger } })
      return { symbol: 'none', label: { color: industrialChartTheme.muted }, data }
    },
    eventMarks(events) {
      const data = events.slice(0, 12).map(event => ({
        name: this.eventType(event.eventType),
        coord: [this.axisTime(event.eventTime), 'max'],
        value: this.eventType(event.eventType),
        itemStyle: { color: industrialChartTheme.event }
      }))
      return { symbol: 'pin', symbolSize: 34, label: { show: false }, data }
    },
    goVibration() {
      this.$router.push({ path: '/monitoring-center/vibration', query: { deviceCode: this.selectedDevice, pointId: this.activePointId, range: this.range } })
    },
    openAlarm(alarm) { this.$router.push({ path: '/phm/alarms', query: { alarmId: alarm.id } }) },
    eventType(value) { return { repair: '维修', maintenance: '保养', alarm_handle: '告警处置', diagnosis: '诊断', access: '投运', other: '事件' }[value] || value },
    metric(value) { return value === null || value === undefined ? '--' : Number(value).toFixed(2) },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--' },
    axisTime(value) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '' },
    resize() { if (this.chart) this.chart.resize() }
  }
}
</script>

<style scoped>
.analysis-page{min-height:calc(100vh - 84px);padding:var(--space-page);background:transparent;color:var(--color-text);font-family:var(--font-ui)}
.panel{padding:14px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:var(--space-section) 0}.selectors,.toolbar-actions{display:flex;gap:8px}.selectors .el-select:first-child{width:240px}.selectors .el-select:last-child{width:260px}
.metric-strip{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px;margin-bottom:12px}.metric-strip>div{padding:11px 13px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}.metric-strip span,.metric-strip small{display:block;color:var(--color-muted);font-size:11px}.metric-strip strong{display:block;margin:5px 0 2px;font-family:var(--font-data);font-size:24px}.quality-value{font-size:18px!important}
.temperature-layout{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:12px}.chart-panel{position:relative;min-width:0}.side-column{display:grid;align-content:start;gap:12px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.panel-head strong,.panel-head span{display:block}.panel-head span{margin-top:3px;color:var(--color-muted);font-size:12px}
.temperature-chart{height:560px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface-soft)}.chart-empty{position:absolute;inset:53% 0 auto;color:var(--color-muted);text-align:center;pointer-events:none}.legend-note{display:flex;justify-content:space-between;gap:12px;padding-top:9px;color:var(--color-muted);font-size:11px}.legend-note i{display:inline-block;width:8px;height:8px;margin-right:5px;border-radius:50%;background:var(--color-muted)}.legend-note .event{background:var(--color-violet)}
.rule-card h3{margin:0 0 10px;font-size:15px}.rule-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.rule-grid span{padding:8px;border:1px solid var(--color-border);border-radius:var(--radius-sm);color:var(--color-muted);font-size:11px}.rule-grid b{display:block;margin-top:4px;color:var(--color-text);font-family:var(--font-data)}.rule-card p{margin:10px 0 0;color:var(--color-muted);font-size:12px;line-height:1.6}
.event-list{display:grid;gap:8px}.event-list article{display:grid;grid-template-columns:8px minmax(0,1fr);gap:9px}.event-list i{width:7px;height:7px;margin-top:5px;border-radius:50%;background:var(--color-violet)}.event-list strong,.event-list small,.event-list em{display:block}.event-list small{margin-top:2px;color:var(--color-text);font-size:11px;line-height:1.4}.event-list em{margin-top:2px;color:var(--color-muted);font-size:10px;font-style:normal}
.alarm-list{display:grid;gap:7px}.alarm-list button{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:9px;border:1px solid var(--color-border);border-left:3px solid var(--color-warning);border-radius:var(--radius-md);background:var(--color-surface-soft);color:var(--color-text);text-align:left;cursor:pointer}.alarm-list strong,.alarm-list small{display:block}.alarm-list small{margin-top:3px;color:var(--color-muted)}.empty-card{padding:16px;color:var(--color-muted);text-align:center}
::v-deep .el-input__inner{border-color:var(--color-border);background:var(--color-surface-soft);color:var(--color-text)}
@media(max-width:1100px){.temperature-layout{grid-template-columns:1fr}.side-column{grid-template-columns:repeat(3,minmax(0,1fr))}.metric-strip{grid-template-columns:repeat(3,1fr)}.temperature-chart{height:440px}}
@media(max-width:800px){.toolbar,.panel-head{align-items:flex-start;flex-direction:column}.selectors,.side-column{grid-template-columns:1fr;flex-direction:column}.selectors .el-select:first-child,.selectors .el-select:last-child{width:100%}.metric-strip{grid-template-columns:1fr}.legend-note{flex-direction:column}}
</style>
