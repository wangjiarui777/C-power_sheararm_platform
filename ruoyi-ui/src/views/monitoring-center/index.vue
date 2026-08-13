<template>
  <div class="monitoring-page">
    <context-bar
      title="实时监测工作台"
      :device="workbench.device"
      :latest-sample-time="summary.latestSampleTime"
      :delay-seconds="summary.dataDelaySeconds"
      :connection-state="connectionState"
      @refresh="refresh"
    />

    <section class="kpi-strip">
      <div><span>在线设备</span><strong>{{ summary.onlineDevices || 0 }}</strong><small>当前筛选范围</small></div>
      <div><span>异常设备</span><strong class="alarm">{{ summary.abnormalDevices || 0 }}</strong><small>需优先复核</small></div>
      <div><span>未确认告警</span><strong class="warning">{{ summary.unacknowledgedAlarms || 0 }}</strong><small>NEW 状态</small></div>
      <div><span>数据延迟</span><strong>{{ delayText }}</strong><small>最后采样至今</small></div>
    </section>

    <main class="workbench-layout">
      <asset-navigator
        :assets="assets"
        :active-device-code="deviceCode"
        @select-device="selectDevice"
      />

      <section class="center-column">
        <section class="panel point-panel">
          <div class="panel-head">
            <div><strong>设备测点</strong><span>状态与数据质量分开呈现</span></div>
            <div class="actions">
              <el-button size="mini" type="primary" plain :disabled="!activePoint" @click="openAnalysis('vibration')">振动分析</el-button>
              <el-button size="mini" type="warning" plain :disabled="!activePoint" @click="openAnalysis('temperature')">温度分析</el-button>
            </div>
          </div>
          <div class="point-grid">
            <point-card
              v-for="point in workbench.points"
              :key="point.pointId"
              :point="point"
              :active="Number(point.pointId) === Number(pointId)"
              @select="selectPoint"
            />
            <div v-if="!workbench.points.length" class="empty-state">
              <strong>当前设备尚未配置测点</strong>
              <span>请先在 PHM 配置管理中建立设备与测点关系。</span>
            </div>
          </div>
        </section>

        <status-rail :events="workbench.stateRail || []" @select="openAlarmFromRail" />

        <section class="panel trend-panel">
          <div class="panel-head">
            <div><strong>最近趋势</strong><span>{{ activePoint ? activePoint.pointName : '请选择测点' }}</span></div>
            <time-range-select v-model="range" @input="loadTrend" />
          </div>
          <div ref="trendChart" class="trend-chart"></div>
          <div v-if="!trendRows.length" class="chart-empty">该时间范围没有有效采样数据</div>
        </section>
      </section>

      <aside class="panel alarm-panel">
        <div class="panel-head">
          <div><strong>待处置告警</strong><span>确认并不解除告警条件</span></div>
          <el-tag type="danger" size="mini">{{ workbench.alarms.length }}</el-tag>
        </div>
        <div class="alarm-list">
          <article v-for="alarm in workbench.alarms" :key="alarm.id" class="alarm-item" :class="alarmClass(alarm)">
            <div class="alarm-title">
              <span>{{ alarm.pointName || alarm.deviceName }}</span>
              <el-tag :type="alarm.alarmLevel >= 3 ? 'danger' : 'warning'" size="mini">{{ alarm.alarmLevel }}级</el-tag>
            </div>
            <strong>{{ alarm.diagnosisResult || '监测指标触发告警规则' }}</strong>
            <small>{{ alarm.alarmNo }} · {{ formatTime(alarm.alarmTime) }}</small>
            <div class="alarm-meta">
              <span>{{ workflowText(alarm.workflowStatus) }}</span>
              <span>{{ conditionText(alarm.conditionStatus) }}</span>
            </div>
            <div class="alarm-actions">
              <el-button v-if="!alarm.workflowStatus || alarm.workflowStatus === 'NEW'" size="mini" type="primary" @click="acknowledge(alarm)">确认</el-button>
              <el-button size="mini" @click="openAssign(alarm)">指派</el-button>
              <el-button size="mini" type="text" @click="openAlarm(alarm)">详情</el-button>
            </div>
          </article>
          <div v-if="!workbench.alarms.length" class="empty-state compact">
            <strong>没有待处置告警</strong><span>设备处于正常或已恢复状态。</span>
          </div>
        </div>
      </aside>
    </main>

    <el-dialog title="指派告警" :visible.sync="assignVisible" width="420px">
      <el-form label-width="80px">
        <el-form-item label="处理人"><el-input v-model="assignForm.assignee" placeholder="输入账号或姓名" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="assignForm.remark" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!assignForm.assignee" @click="submitAssign">确认指派</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { mapState } from 'vuex'
import { getPointTrend } from '@/api/monitoring'
import { acknowledgeAlarm, assignAlarm } from '@/api/phm'
import ContextBar from '@/components/IndustrialMonitoring/ContextBar'
import AssetNavigator from '@/components/IndustrialMonitoring/AssetNavigator'
import PointCard from '@/components/IndustrialMonitoring/PointCard'
import StatusRail from '@/components/IndustrialMonitoring/StatusRail'
import TimeRangeSelect from '@/components/IndustrialMonitoring/TimeRangeSelect'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'MonitoringCenter',
  components: { ContextBar, AssetNavigator, PointCard, StatusRail, TimeRangeSelect },
  data() {
    return {
      trendChart: null,
      trendRows: [],
      assignVisible: false,
      currentAlarm: null,
      assignForm: { assignee: '', remark: '' }
    }
  },
  computed: {
    ...mapState('monitoring', ['assets', 'workbench', 'deviceCode', 'pointId', 'connectionState']),
    summary() { return this.workbench.summary || {} },
    activePoint() { return this.workbench.points.find(item => Number(item.pointId) === Number(this.pointId)) || this.workbench.points[0] },
    range: {
      get() { return this.$store.state.monitoring.range },
      set(value) { this.$store.dispatch('monitoring/setContext', { range: value }) }
    },
    delayText() {
      const value = this.summary.dataDelaySeconds
      if (value === null || value === undefined) return '--'
      return Number(value) < 60 ? `${value}s` : `${Math.floor(value / 60)}m`
    }
  },
  async created() {
    await this.$store.dispatch('monitoring/loadAssets')
    const routeDevice = this.$route.query.deviceCode
    if (routeDevice) this.$store.dispatch('monitoring/setContext', { deviceCode: routeDevice })
    await this.$store.dispatch('monitoring/loadWorkbench')
    this.$store.dispatch('monitoring/connect')
    this.loadTrend()
  },
  mounted() {
    window.addEventListener('resize', this.resize)
    window.addEventListener('appearance-mode-change', this.renderTrend)
    this.$nextTick(() => {
      if (this.$refs.trendChart) this.trendChart = echarts.init(this.$refs.trendChart)
      this.renderTrend()
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resize)
    window.removeEventListener('appearance-mode-change', this.renderTrend)
    if (this.trendChart) this.trendChart.dispose()
    this.$store.dispatch('monitoring/disconnect')
  },
  methods: {
    async refresh() {
      await Promise.all([
        this.$store.dispatch('monitoring/loadAssets'),
        this.$store.dispatch('monitoring/loadWorkbench')
      ])
      this.loadTrend()
    },
    async selectDevice(device) {
      this.$store.dispatch('monitoring/setContext', { deviceCode: device.deviceCode, pointId: null })
      await this.$store.dispatch('monitoring/loadWorkbench', { deviceCode: device.deviceCode })
      this.$router.replace({ query: { ...this.$route.query, deviceCode: device.deviceCode } })
      this.loadTrend()
    },
    selectPoint(point) {
      this.$store.dispatch('monitoring/setContext', { pointId: point.pointId })
      this.$router.replace({ query: { ...this.$route.query, pointId: point.pointId } })
      this.loadTrend()
    },
    openAnalysis(type) {
      if (!this.activePoint) return
      this.$router.push({
        path: `/monitoring-center/${type}`,
        query: { deviceCode: this.deviceCode, pointId: this.activePoint.pointId, range: this.range }
      })
    },
    async loadTrend() {
      if (!this.activePoint) {
        this.trendRows = []
        this.renderTrend()
        return
      }
      const response = await getPointTrend(this.activePoint.pointId, {
        ...this.rangeParams(),
        metrics: 'vibration,temperature',
        maxPoints: 600
      })
      this.trendRows = (response.data && response.data.rows) || []
      this.renderTrend()
    },
    rangeParams() {
      if (this.range === 'realtime') return {}
      const durations = { '15m': 15 * 60e3, '1h': 60 * 60e3, '8h': 8 * 60 * 60e3, '24h': 24 * 60 * 60e3 }
      const end = new Date()
      const start = new Date(end.getTime() - (durations[this.range] || durations['15m']))
      return { from: start.toISOString(), to: end.toISOString() }
    },
    renderTrend() {
      this.$nextTick(() => {
        if (!this.trendChart) return
        const rows = this.trendRows
        const thresholds = (this.activePoint && this.activePoint.thresholds) || {}
        this.trendChart.setOption({
          animation: false,
          backgroundColor: 'transparent',
          color: [industrialChartTheme.vibration, industrialChartTheme.temperature],
          tooltip: { trigger: 'axis', backgroundColor: industrialChartTheme.tooltipBg, borderColor: industrialChartTheme.tooltipBorder, textStyle: { color: industrialChartTheme.text } },
          legend: { top: 4, right: 12, textStyle: { color: industrialChartTheme.muted } },
          grid: { left: 55, right: 50, top: 42, bottom: 34 },
          xAxis: { type: 'category', boundaryGap: false, data: rows.map(row => this.formatAxis(row.time)), axisLabel: { color: industrialChartTheme.axis }, axisLine: { lineStyle: { color: industrialChartTheme.border } } },
          yAxis: [
            { type: 'value', name: 'mm/s', scale: true, axisLabel: { color: industrialChartTheme.axis }, splitLine: { lineStyle: { color: industrialChartTheme.grid } } },
            { type: 'value', name: '℃', scale: true, axisLabel: { color: industrialChartTheme.axis }, splitLine: { show: false } }
          ],
          series: [
            { name: '振动', type: 'line', showSymbol: false, connectNulls: false, data: rows.map(row => row.vibration), lineStyle: { color: industrialChartTheme.vibration, width: 2.4 }, markLine: this.markLine(thresholds) },
            { name: '温度', type: 'line', yAxisIndex: 1, showSymbol: false, connectNulls: false, data: rows.map(row => row.temperature), lineStyle: { color: industrialChartTheme.temperature, width: 2.1 } }
          ]
        }, true)
      })
    },
    markLine(thresholds) {
      const data = []
      if (thresholds.high !== null && thresholds.high !== undefined) data.push({ yAxis: thresholds.high, name: '高限', lineStyle: { color: industrialChartTheme.warning } })
      if (thresholds.highHigh !== null && thresholds.highHigh !== undefined) data.push({ yAxis: thresholds.highHigh, name: '高高限', lineStyle: { color: industrialChartTheme.danger } })
      return { symbol: 'none', label: { color: industrialChartTheme.muted }, data }
    },
    acknowledge(alarm) {
      acknowledgeAlarm(alarm.id, { remark: '监测工作台确认' }).then(() => {
        this.$message.success('告警已确认，条件状态保持不变')
        this.$store.dispatch('monitoring/loadWorkbench')
      }).catch(e => {
        this.$message.error(e.message || '确认失败')
      })
    },
    openAssign(alarm) {
      this.currentAlarm = alarm
      this.assignForm = { assignee: alarm.assignee || '', remark: '' }
      this.assignVisible = true
    },
    submitAssign() {
      assignAlarm(this.currentAlarm.id, this.assignForm).then(() => {
        this.$message.success('告警已指派')
        this.assignVisible = false
        this.$store.dispatch('monitoring/loadWorkbench')
      }).catch(e => {
        this.$message.error(e.message || '指派失败')
      })
    },
    openAlarm(alarm) { this.$router.push({ path: '/phm/alarms', query: { alarmId: alarm.id } }) },
    openAlarmFromRail(item) { if (item.alarmId) this.openAlarm({ id: item.alarmId }) },
    alarmClass(alarm) { return alarm.alarmLevel >= 3 ? 'alarm' : 'warning' },
    workflowText(value) { return { NEW: '待确认', ACKNOWLEDGED: '已确认', ASSIGNED: '已指派', CLOSED: '已关闭' }[value] || '待确认' },
    conditionText(value) { return value === 'RETURNED_TO_NORMAL' ? '条件已恢复' : '条件仍活动' },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--' },
    formatAxis(value) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '' },
    resize() { if (this.trendChart) this.trendChart.resize() }
  }
}
</script>

<style scoped>
.monitoring-page{min-height:calc(100vh - 84px);padding:var(--space-page);background:transparent;color:var(--color-text);font-family:var(--font-ui)}
.kpi-strip{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:var(--space-section) 0}.kpi-strip>div{padding:13px 15px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}.kpi-strip span,.kpi-strip small{display:block;color:var(--color-muted);font-size:12px}.kpi-strip strong{display:block;margin:5px 0 3px;font-family:var(--font-data);font-size:26px}.kpi-strip .alarm{color:var(--color-danger)}.kpi-strip .warning{color:var(--color-warning)}
.workbench-layout{display:grid;grid-template-columns:290px minmax(0,1fr) 330px;gap:12px;min-height:680px}.center-column{display:grid;align-content:start;gap:12px;min-width:0}
.panel{min-width:0;padding:14px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.panel-head strong,.panel-head span{display:block}.panel-head span{margin-top:3px;color:var(--color-muted);font-size:12px}.actions{display:flex;gap:7px}
.point-grid{display:grid;grid-template-columns:repeat(3,minmax(190px,1fr));gap:12px}.trend-panel{position:relative}.trend-chart{height:300px}.chart-empty{position:absolute;inset:90px 0 auto;color:var(--color-muted);text-align:center;pointer-events:none}
.alarm-panel{min-height:0}.alarm-list{display:grid;gap:9px;max-height:calc(100vh - 235px);overflow:auto}.alarm-item{display:grid;gap:8px;padding:11px;border:1px solid var(--color-border);border-left:3px solid var(--color-warning);border-radius:var(--radius-md);background:var(--color-surface-soft)}.alarm-item.alarm{border-left-color:var(--color-danger)}.alarm-title,.alarm-meta,.alarm-actions{display:flex;align-items:center;justify-content:space-between;gap:8px}.alarm-item small,.alarm-meta{color:var(--color-muted);font-size:11px}.alarm-actions{justify-content:flex-start}
.empty-state{display:grid;gap:5px;grid-column:1/-1;padding:30px;border:1px dashed var(--color-border-strong);border-radius:var(--radius-lg);color:var(--color-muted);text-align:center}.empty-state strong{color:var(--color-text)}.empty-state.compact{padding:20px}
@media(max-width:1280px){.workbench-layout{grid-template-columns:250px minmax(0,1fr)}.alarm-panel{grid-column:1/-1}.alarm-list{grid-template-columns:repeat(2,minmax(0,1fr));max-height:none}.point-grid{grid-template-columns:repeat(2,minmax(180px,1fr))}}
@media(max-width:850px){.kpi-strip,.workbench-layout,.point-grid,.alarm-list{grid-template-columns:1fr}.panel-head{align-items:flex-start;flex-direction:column}}
</style>
