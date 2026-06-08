<template>
  <div class="app-container monitoring-center-page">
    <section class="page-shell">
      <header class="page-toolbar">
        <div>
          <div class="page-title">实时监测中心</div>
          <div class="page-subtitle">8 通道实时追踪 · 推理服务数据流 · 异常快速定位</div>
        </div>
        <div class="toolbar-actions">
          <el-tag size="mini" type="success">推理服务 5001</el-tag>
          <el-button size="mini" icon="el-icon-refresh" @click="fetchLatestInference">刷新</el-button>
          <el-button size="mini" type="primary" icon="el-icon-data-analysis" @click="$router.push('/monitoring-center/vibration')">历史分析</el-button>
        </div>
      </header>

      <section class="workflow-strip">
        <div class="workflow-step active"><span>1</span><strong>选择通道</strong></div>
        <div class="workflow-step active"><span>2</span><strong>实时监测</strong></div>
        <div class="workflow-step"><span>3</span><strong>异常复核</strong></div>
      </section>

      <main class="monitor-layout">
        <section class="zone control-zone">
          <div class="zone-header">
            <span>通道选择</span>
            <el-tag size="mini">8 通道</el-tag>
          </div>

          <div class="channel-grid">
            <button
              v-for="item in channelList"
              :key="item.channelId"
              class="channel-tile"
              :class="[statusClass(item.status), { active: activeChannelId === item.channelId }]"
              @click="activeChannelId = item.channelId"
              @dblclick="openChannelDetail(item.channelId)"
            >
              <span class="tile-title">{{ item.name }}</span>
              <el-tag size="mini" :type="statusTagType(item.status)">{{ statusText(item.status) }}</el-tag>
              <strong>{{ formatNumber(item.value) }}</strong>
              <small>{{ item.unit }}</small>
            </button>
          </div>
        </section>

        <section class="zone visualization-zone">
          <div class="zone-header">
            <span>实时趋势 · 通道{{ activeChannelId }}</span>
            <div class="zone-actions">
              <el-tag size="mini" :type="statusTagType(activeChannel.status)">{{ statusText(activeChannel.status) }}</el-tag>
              <el-button size="mini" type="text" @click="openChannelDetail(activeChannelId)">详情</el-button>
            </div>
          </div>

          <div class="kpi-row">
            <div class="kpi-item">
              <span>有效值</span>
              <strong>{{ formatNumber(activeChannel.metrics.rms) }}</strong>
              <small>mm/s</small>
            </div>
            <div class="kpi-item">
              <span>峰值</span>
              <strong>{{ formatNumber(activeChannel.metrics.peak) }}</strong>
              <small>峰值</small>
            </div>
            <div class="kpi-item">
              <span>位移</span>
              <strong>{{ formatNumber(activeChannel.metrics.displacement) }}</strong>
              <small>mm</small>
            </div>
            <div class="kpi-item">
              <span>温度</span>
              <strong>{{ formatNumber(activeChannel.metrics.temp) }}</strong>
              <small>℃</small>
            </div>
          </div>

          <div ref="overviewChartRef" class="primary-chart"></div>
        </section>

        <aside class="zone log-zone">
          <div class="zone-header">
            <span>运行日志</span>
            <el-button size="mini" type="text" @click="goHistory">更多</el-button>
          </div>

          <el-table :data="activeLogs" height="100%" size="mini" stripe>
            <el-table-column prop="time" label="时间" min-width="130" />
            <el-table-column prop="value" label="有效值" width="80" />
            <el-table-column prop="alarmLevel" label="级别" width="74">
              <template slot-scope="scope">
                <el-tag :type="alarmTagType(scope.row.alarmLevel)" size="mini">{{ scope.row.alarmLevel }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </aside>
      </main>

      <el-drawer
        :visible.sync="detailVisible"
        direction="btt"
        size="100%"
        custom-class="monitoring-drawer"
        :with-header="false"
        append-to-body
        @opened="handleDrawerOpened"
        @closed="handleDrawerClosed"
      >
        <div class="drawer-shell">
          <div class="drawer-topbar">
            <div>
              <div class="drawer-title">通道{{ activeChannelId }} 通道详情</div>
              <div class="drawer-subtitle">实时曲线 · 关键指标 · 最近 20 条日志</div>
            </div>
            <div class="drawer-actions">
              <el-tag :type="statusTagType(activeChannel.status)" size="mini">{{ statusText(activeChannel.status) }}</el-tag>
              <el-button size="mini" icon="el-icon-close" @click="detailVisible = false">关闭</el-button>
            </div>
          </div>

          <div class="drawer-grid">
            <section class="zone metric-zone">
              <div class="main-metric">
                <div class="metric-label">振动速度有效值 RMS</div>
                <div class="metric-value big">{{ formatNumber(activeChannel.metrics.rms) }}</div>
                <div class="metric-unit">mm/s</div>
              </div>
              <div class="sub-metrics">
                <div class="sub-item"><span>峰值</span><strong>{{ formatNumber(activeChannel.metrics.peak) }}</strong></div>
                <div class="sub-item"><span>位移</span><strong>{{ formatNumber(activeChannel.metrics.displacement) }}</strong></div>
                <div class="sub-item"><span>实时温度</span><strong>{{ formatNumber(activeChannel.metrics.temp) }}</strong></div>
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
              <div class="zone-header"><span>实时曲线</span></div>
              <div ref="waveChartRef" class="primary-chart"></div>
            </section>

            <section class="zone drawer-log-zone">
              <div class="zone-header">
                <span>最近 20 条历史记录</span>
                <el-button size="mini" type="primary" plain @click="goHistory">查看更多</el-button>
              </div>
              <el-table :data="activeLogs" height="100%" size="mini" stripe>
                <el-table-column prop="time" label="采集时间" min-width="150" />
                <el-table-column prop="value" label="测量值" width="90" />
                <el-table-column prop="alarmLevel" label="级别" width="80">
                  <template slot-scope="scope">
                    <el-tag :type="alarmTagType(scope.row.alarmLevel)" size="mini">{{ scope.row.alarmLevel }}</el-tag>
                  </template>
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
import inferenceWebSocket from '@/utils/inference-websocket'

export default {
  name: 'MonitoringCenter',
  data() {
    return {
      activeChannelId: 1,
      detailVisible: false,
      unsubscribeWs: null,
      inferencePollTimer: null,
      overviewChart: null,
      waveChart: null,
      channelDataMap: {
        1: this.createChannel('通道1', 'mm/s'),
        2: this.createChannel('通道2', 'mm/s'),
        3: this.createChannel('通道3', 'mm/s'),
        4: this.createChannel('通道4', 'mm/s'),
        5: this.createChannel('通道5', 'mm/s'),
        6: this.createChannel('通道6', 'mm/s'),
        7: this.createChannel('通道7', 'mm/s'),
        8: this.createChannel('通道8', 'mm/s')
      }
    }
  },
  computed: {
    channelList() {
      return Object.keys(this.channelDataMap).map(id => {
        const item = this.channelDataMap[id]
        return {
          channelId: Number(id),
          name: item.name,
          unit: item.unit,
          status: item.status,
          value: item.value,
          latest: item.latest
        }
      })
    },
    activeChannel() {
      return this.channelDataMap[this.activeChannelId] || this.createChannel(`CH${this.activeChannelId}`, 'mm/s')
    },
    activeLogs() {
      return (this.activeChannel.logs || []).slice(0, 20)
    }
  },
  watch: {
    activeChannelId() {
      this.$nextTick(() => {
        this.renderWaveChart()
      })
    },
    detailVisible(val) {
      if (val) {
        this.$nextTick(() => this.renderWaveChart())
      }
    }
  },
  mounted() {
    this.connectWebSocket()
    this.$nextTick(() => {
      this.renderWaveChart()
      window.addEventListener('resize', this.handleResize)
    })
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize)
    if (this.unsubscribeWs) {
      this.unsubscribeWs()
      this.unsubscribeWs = null
    }
    this.stopInferencePolling()
    inferenceWebSocket.close()
    if (this.overviewChart) {
      this.overviewChart.dispose()
      this.overviewChart = null
    }
    if (this.waveChart) {
      this.waveChart.dispose()
      this.waveChart = null
    }
  },
  methods: {
    createChannel(name, unit) {
      return {
        name,
        unit,
        status: 'success',
        value: null,
        health: 100,
        latest: { rms: null, temperatureValue: null },
        metrics: { rms: null, peak: null, displacement: null, temp: null },
        wave: [],
        logs: []
      }
    },
    openChannelDetail(channelId) {
      this.activeChannelId = channelId
      this.detailVisible = true
    },
    connectWebSocket() {
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
        this.applyRealtimeData(payload)
      })
      inferenceWebSocket.connect()
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
        .then(payload => this.applyRealtimeData(payload))
        .catch(() => {})
    },
    applyRealtimeData(payload) {
      payload = this.normalizeInferencePayload(payload)
      if (!payload) return

      var channelId = Number(payload.channelId || payload.channel || payload.channelNo || 1)
      if (!this.channelDataMap[channelId]) {
        this.$set(this.channelDataMap, channelId, this.createChannel(`通道${channelId}`, 'mm/s'))
      }

      var channel = this.channelDataMap[channelId]
      var rms = this.toNumber(payload.rms, channel.metrics.rms)
      var peak = this.toNumber(payload.peak, channel.metrics.peak)
      var displacement = this.toNumber(payload.displacement || payload.peakToPeak || payload.pp, channel.metrics.displacement)
      var temp = this.toNumber(payload.temperatureValue || payload.temp, channel.metrics.temp)
      var vibrationValue = this.toNumber(payload.vibrationValue, rms)
      var health = payload.health == null ? this.calcHealth(rms, temp) : this.toNumber(payload.health, this.calcHealth(rms, temp))
      var status = payload.alarm ? 'danger' : payload.warning ? 'warning' : health >= 80 ? 'success' : health >= 60 ? 'warning' : 'danger'
      var time = payload.sampleTime ? this.formatLogTime(payload.sampleTime) : this.formatTime(new Date())

      channel.value = vibrationValue
      channel.status = status
      channel.health = health
      channel.latest = {
        deviceCode: payload.deviceCode || channel.latest.deviceCode,
        channelId: channelId,
        sampleTime: time,
        vibrationValue: vibrationValue,
        temperatureValue: temp,
        rms: rms,
        peak: peak,
        alarm: !!payload.alarm,
        alarmMessage: payload.alarmMessage || ''
      }
      channel.metrics.rms = rms
      channel.metrics.peak = peak
      channel.metrics.displacement = displacement
      channel.metrics.temp = temp

      if (Array.isArray(payload.wave) && payload.wave.length) {
        channel.wave = payload.wave.slice(-200)
      } else {
        channel.wave.push({ time: time, value: rms == null ? 0 : rms })
        if (channel.wave.length > 200) {
          channel.wave.splice(0, channel.wave.length - 200)
        }
      }

      channel.logs.unshift({
        time: time,
        value: this.formatNumber(rms),
        alarmLevel: status === 'danger' ? '高' : status === 'warning' ? '中' : '低',
        statusDesc: payload.alarm ? '告警触发' : payload.warning ? '预警' : '正常运行'
      })
      if (channel.logs.length > 20) channel.logs.length = 20

      if (channelId === this.activeChannelId) {
        this.$nextTick(() => {
          this.renderWaveChart()
        })
      }
    },
    normalizeInferencePayload(payload) {
      if (!payload) return null
      if (payload.type === 'health_status' || payload.type === 'file_list' || payload.type === 'pong') return null
      var data = payload.data || payload
      if (payload.type === 'auto_analysis' && payload.success === false) return null
      if (!data || (!data.rms && !data.latestRms && !data.waveform && !data.time_data)) return null

      var channelId = Number(data.channelId || data.channel || data.channelNo || 1)
      var timeAxis = Array.isArray(data.time_axis) ? data.time_axis : []
      var waveform = Array.isArray(data.waveform) ? data.waveform : (Array.isArray(data.time_data) ? data.time_data : [])
      var wave = waveform.map((value, index) => ({
        time: timeAxis[index] == null ? String(index) : String(timeAxis[index]),
        value: this.toNumber(value, 0)
      }))
      var health = data.healthIndex == null ? data.health : data.healthIndex
      var risk = data.riskLevel || data.alarmLevel

      return {
        channelId: channelId || 1,
        rms: data.rms == null ? data.latestRms : data.rms,
        peak: data.peak == null ? data.latestPeak : data.peak,
        displacement: data.displacement || data.peakToPeak || data.pp,
        temperatureValue: data.temperatureValue || data.temp,
        vibrationValue: data.vibrationValue || data.latestRms || data.rms,
        health: health,
        alarm: data.alarm || risk === '高' || risk === 'alarm',
        warning: data.warning || risk === '中' || risk === 'warning' || risk === 'attention',
        alarmMessage: data.alarmMessage || data.diagnosisResult || data.diagnosisName || '',
        sampleTime: data.sampleTime || data.createTime || new Date().toISOString(),
        wave: wave
      }
    },
    renderWaveChart() {
      this.renderChartByRef('overviewChartRef', 'overviewChart')
      this.renderChartByRef('waveChartRef', 'waveChart')
    },
    renderChartByRef(refName, chartName) {
      var el = this.$refs[refName]
      if (Array.isArray(el)) el = el[0]
      if (!el) return
      if (!this[chartName]) {
        this[chartName] = echarts.init(el)
      }
      var series = this.activeChannel.wave || []
      this[chartName].setOption({
        backgroundColor: 'transparent',
        animation: true,
        animationDuration: 300,
        tooltip: { trigger: 'axis' },
        grid: { left: 38, right: 18, top: 18, bottom: 28 },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: series.map(item => item.time),
          axisLine: { lineStyle: { color: '#cbd5e1' } },
          axisLabel: { color: '#64748b' }
        },
        yAxis: {
          type: 'value',
          scale: true,
          splitLine: { show: false },
          axisLine: { lineStyle: { color: '#cbd5e1' } },
          axisLabel: { color: '#64748b' }
        },
        series: [{
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: series.map(item => item.value),
          lineStyle: { width: 2, color: '#2563eb' },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(37,99,235,0.20)' },
                { offset: 1, color: 'rgba(37,99,235,0)' }
              ]
            }
          }
        }]
      }, true)
    },
    calcHealth(rms, temp) {
      var rmsNum = Number(rms)
      var tempNum = Number(temp)
      var rmsScore = Number.isNaN(rmsNum) ? 0.5 : Math.max(0, Math.min(1, 1 - rmsNum / 8))
      var tempScore = Number.isNaN(tempNum) ? 0.5 : Math.max(0, Math.min(1, 1 - Math.max(0, tempNum - 60) / 70))
      return Math.round((rmsScore * 0.6 + tempScore * 0.4) * 100)
    },
    statusClass(status) {
      return status === 'danger' ? 'danger' : status === 'warning' ? 'warning' : 'success'
    },
    statusTagType(status) {
      return status === 'danger' ? 'danger' : status === 'warning' ? 'warning' : 'success'
    },
    statusText(status) {
      return status === 'danger' ? '告警' : status === 'warning' ? '预警' : '正常'
    },
    healthColor(health) {
      if (health >= 85) return '#67c23a'
      if (health >= 60) return '#e6a23c'
      return '#f56c6c'
    },
    alarmTagType(level) {
      if (level === '高') return 'danger'
      if (level === '中') return 'warning'
      return 'success'
    },
    goHistory() {
      this.$message.info('这里可跳转至历史报表页面')
    },
    formatNumber(value) {
      if (value === null || value === undefined || value === '') return '--'
      var num = Number(value)
      return Number.isNaN(num) ? value : num.toFixed(2)
    },
    toNumber(value, fallback) {
      var num = Number(value)
      if (Number.isNaN(num)) return fallback == null ? null : fallback
      return num
    },
    formatTime(date) {
      var d = new Date(date)
      var pad = function(n) { return n < 10 ? '0' + n : '' + n }
      return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
    },
    formatLogTime(value) {
      if (!value) return this.formatTime(new Date())
      return String(value)
    },
    handleDrawerOpened() {
      this.$nextTick(() => this.renderWaveChart())
    },
    handleDrawerClosed() {
      if (this.waveChart) {
        this.waveChart.dispose()
        this.waveChart = null
      }
    },
    handleResize() {
      if (this.overviewChart) {
        this.overviewChart.resize()
      }
      if (this.waveChart) {
        this.waveChart.resize()
      }
    }
  }
}
</script>

<style scoped>
.monitoring-center-page {
  min-height: calc(100vh - 84px);
  padding: 10px;
  background: linear-gradient(180deg, #111827 0%, #1f2937 100%);
}
.mb10 { margin-bottom: 10px; }
.channel-card {
  cursor: pointer;
  background: rgba(1, 12, 28, 0.78);
  border: 1px solid rgba(0, 255, 255, 0.22);
  color: #ffffff;
  box-shadow: 0 0 12px rgba(0, 255, 255, 0.08), inset 0 0 18px rgba(0, 255, 255, 0.03);
}
.channel-card.active { border-color: #00ffff; box-shadow: 0 0 18px rgba(0,255,255,0.18), 0 0 0 1px rgba(0,255,255,0.15) inset; }
.channel-card.success { border-color: rgba(0, 255, 255, 0.22); }
.channel-card.warning { border-color: rgba(230, 162, 60, 0.55); }
.channel-card.danger { border-color: rgba(245, 108, 108, 0.65); }
.channel-head, .channel-footer { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.channel-title { font-size: 14px; font-weight: 700; }
.metric-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 12px; }
.metric-block { padding: 10px; background: rgba(0,255,255,0.05); border: 1px solid rgba(0,255,255,0.12); border-radius: 6px; }
.metric-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.metric-value { margin-top: 4px; font-size: 26px; font-weight: 800; line-height: 1.1; color: #ffffff; font-family: 'Courier New', monospace; }
.metric-value.big { font-size: 38px; }
.metric-unit { margin-top: 2px; font-size: 12px; color: rgba(235,255,255,0.68); }
.channel-footer { margin-top: 12px; font-size: 12px; color: #ffffff; }
.foot-time { color: #ffffff; }
.drawer-shell { height: 100vh; display: flex; flex-direction: column; background: linear-gradient(180deg, #111827 0%, #1f2937 100%); color: #ffffff; }
.drawer-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 20px; border-bottom: 1px solid rgba(0,255,255,0.10); }
.drawer-title { font-size: 20px; font-weight: 800; }
.drawer-subtitle { margin-top: 4px; font-size: 12px; color: #ffffff; }
.drawer-actions { display: flex; align-items: center; gap: 10px; }
.drawer-content { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 12px 20px 20px; min-height: 0; }
.detail-row { flex: 0 0 auto; }
.metric-panel { display: grid; gap: 8px; }
.main-metric, .log-card { background: rgba(1, 12, 28, 0.78); border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); }
.main-metric { padding: 10px; }
.sub-metrics { display: grid; gap: 6px; }
.sub-item { display: flex; justify-content: space-between; padding: 7px 10px; background: rgba(0,255,255,0.04); border: 1px solid rgba(0,255,255,0.1); font-size: 12px; }
.sub-item strong { font-family: 'Courier New', monospace; }
.health-box { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; background: rgba(0,255,255,0.03); border: 1px solid rgba(0,255,255,0.14); }
.health-label { font-size: 12px; color: rgba(0,255,255,0.72); }
.health-desc { margin-top: 4px; font-size: 18px; font-weight: 700; color: #ffffff; font-family: 'Courier New', monospace; }
.wave-chart { width: 100%; height: 320px; background: rgba(1, 12, 28, 0.78); border-radius: 10px; border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); }
.log-card { margin-top: 12px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
:deep(.monitoring-drawer) { background: transparent; }
:deep(.monitoring-drawer .el-drawer__body) { height: 100%; }
:deep(.el-table) { background: transparent; color: #ffffff; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(0,255,255,0.08); }
:deep(.el-radio-button__inner) { background: #2b3340; color: #ffffff; border-color: rgba(255,255,255,0.12); }
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) { background: #409eff; border-color: #409eff; }

/* 浅色工业生产主题覆盖 */
.monitoring-center-page {
  background: linear-gradient(180deg, #1a1a1a 0%, #121212 100%);
  color: #ffffff;
}
.channel-card,
.main-metric,
.log-card,
.wave-chart {
  background: #1a1a1a;
  border-color: #d7dee8;
  color: #ffffff;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.08);
}
.channel-card.active {
  border-color: #2563eb;
  box-shadow: 0 10px 22px rgba(37, 99, 235, 0.12);
}
.channel-card.success { border-color: #bfd7f2; }
.metric-block,
.sub-item,
.health-box {
  background: #171717;
  border-color: #b8b8b8;
}
.metric-label,
.health-label {
  color: #ffffff;
}
.metric-value,
.health-desc,
.foot-time,
.drawer-title,
.sub-item strong {
  color: #ffffff;
}
.metric-unit,
.channel-footer,
.drawer-subtitle {
  color: #ffffff;
}
.drawer-shell {
  background: linear-gradient(180deg, #1a1a1a 0%, #121212 100%);
  color: #ffffff;
}
.drawer-topbar {
  border-bottom-color: #d7dee8;
}
:deep(.monitoring-drawer) { background: #121212; }
:deep(.el-table) { background: #1a1a1a; color: #ffffff; }
:deep(.el-table th) { background: #171717 !important; color: #ffffff !important; }
:deep(.el-table tr),
:deep(.el-table td) { background: #1a1a1a !important; color: #ffffff !important; }
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
.workflow-strip,
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
.toolbar-actions,
.zone-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.workflow-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  padding: 10px;
}
.workflow-step {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #ffffff;
}
.workflow-step span {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #333333;
  color: #ffffff;
  font-size: 12px;
}
.workflow-step.active span {
  background: #2563eb;
  color: #ffffff;
}
.monitor-layout {
  display: grid;
  grid-template-columns: minmax(260px, 320px) minmax(0, 1fr) minmax(280px, 360px);
  gap: 12px;
  min-height: 620px;
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
.channel-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.channel-tile {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 8px;
  min-height: 96px;
  padding: 10px;
  border: 1px solid #d7dee8;
  border-radius: 8px;
  background: #171717;
  color: #ffffff;
  text-align: left;
  cursor: pointer;
}
.channel-tile.active {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}
.channel-tile.warning { border-color: rgba(217, 119, 6, 0.46); }
.channel-tile.danger { border-color: rgba(220, 38, 38, 0.46); }
.channel-tile strong {
  grid-column: 1 / -1;
  font-size: 24px;
  line-height: 1.1;
}
.channel-tile small {
  color: #ffffff;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
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
  font-size: 26px;
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
.log-zone,
.drawer-log-zone {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.drawer-grid {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr) 360px;
  gap: 12px;
  padding: 12px;
  min-height: 0;
  flex: 1;
}
.metric-zone {
  display: grid;
  align-content: start;
  gap: 10px;
}
@media (max-width: 1200px) {
  .page-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .monitor-layout,
  .drawer-grid {
    grid-template-columns: 1fr;
  }
  .workflow-strip,
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .channel-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .metric-row { grid-template-columns: 1fr; }
  .drawer-topbar { flex-direction: column; align-items: flex-start; }
  .wave-chart { height: 260px; }
  .primary-chart { height: 320px; }
}
@media (max-width: 768px) {
  .workflow-strip,
  .channel-grid,
  .kpi-row {
    grid-template-columns: 1fr;
  }
}
</style>
