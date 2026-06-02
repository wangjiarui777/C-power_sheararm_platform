<template>
  <div class="app-container monitoring-center-page">
    <el-row :gutter="10">
      <el-col v-for="item in channelList" :key="item.channelId" :span="6" class="mb10">
        <el-card
          shadow="hover"
          class="channel-card"
          :class="[statusClass(item.status), { active: activeChannelId === item.channelId }]"
          @click.native="openChannelDetail(item.channelId)"
        >
          <div class="channel-head">
            <div class="channel-title">{{ item.name }}</div>
            <el-tag size="mini" :type="statusTagType(item.status)">{{ statusText(item.status) }}</el-tag>
          </div>
          <div class="metric-row">
            <div class="metric-block">
              <div class="metric-label">监测值</div>
              <div class="metric-value">{{ formatNumber(item.value) }}</div>
              <div class="metric-unit">{{ item.unit }}</div>
            </div>
            <div class="metric-block">
              <div class="metric-label">健康度</div>
              <div class="metric-value">{{ item.latest && item.latest.rms != null ? formatNumber(item.latest.rms) : '--' }}</div>
              <div class="metric-unit">RMS</div>
            </div>
          </div>
          <div class="channel-footer">
            <span class="foot-text">最新状态</span>
            <span class="foot-time">{{ item.status === 'danger' ? '异常' : item.status === 'warning' ? '预警' : '正常' }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

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
            <div class="drawer-title">选中通道详情 - CH{{ activeChannelId }}</div>
            <div class="drawer-subtitle">WebSocket 实时监测 · 深灰工业风 · 最近 20 条历史记录</div>
          </div>
          <div class="drawer-actions">
            <el-tag :type="statusTagType(activeChannel.status)" size="mini">{{ statusText(activeChannel.status) }}</el-tag>
            <el-button size="mini" icon="el-icon-close" @click="detailVisible = false">关闭</el-button>
          </div>
        </div>

        <div class="drawer-content">
          <el-row :gutter="12" class="detail-row">
            <el-col :xs="24" :md="8">
              <div class="metric-panel">
                <div class="main-metric">
                  <div class="metric-label">振动速度有效值 RMS</div>
                  <div class="metric-value big">{{ formatNumber(activeChannel.metrics.rms) }}</div>
                  <div class="metric-unit">mm/s</div>
                </div>

                <div class="sub-metrics">
                  <div class="sub-item">
                    <span>峰值</span>
                    <strong>{{ formatNumber(activeChannel.metrics.peak) }}</strong>
                  </div>
                  <div class="sub-item">
                    <span>位移</span>
                    <strong>{{ formatNumber(activeChannel.metrics.displacement) }}</strong>
                  </div>
                  <div class="sub-item">
                    <span>实时温度</span>
                    <strong>{{ formatNumber(activeChannel.metrics.temp) }}</strong>
                  </div>
                </div>

                <div class="health-box">
                  <div>
                    <div class="health-label">健康度</div>
                    <div class="health-desc">{{ activeChannel.health }}%</div>
                  </div>
                  <el-progress
                    type="circle"
                    :percentage="activeChannel.health"
                    :width="92"
                    :color="healthColor(activeChannel.health)"
                  />
                </div>
              </div>
            </el-col>

            <el-col :xs="24" :md="16">
              <div ref="waveChartRef" class="wave-chart"></div>
            </el-col>
          </el-row>

          <el-card class="log-card" shadow="never">
            <div slot="header" class="card-header">
              <span>最近 20 条历史记录</span>
              <el-button size="mini" type="primary" plain @click="goHistory">查看更多</el-button>
            </div>

            <el-table :data="activeLogs" height="320" stripe>
              <el-table-column prop="time" label="采集时间" min-width="180" />
              <el-table-column prop="value" label="测量值" min-width="120" />
              <el-table-column prop="alarmLevel" label="告警级别" min-width="100">
                <template slot-scope="scope">
                  <el-tag :type="alarmTagType(scope.row.alarmLevel)" size="mini">{{ scope.row.alarmLevel }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="statusDesc" label="状态描述" min-width="160" />
            </el-table>
          </el-card>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import sensorWebSocket from '@/utils/sensor-websocket'

export default {
  name: 'MonitoringCenter',
  data() {
    return {
      activeChannelId: 1,
      detailVisible: false,
      unsubscribeWs: null,
      waveChart: null,
      channelDataMap: {
        1: this.createChannel('CH1', 'mm/s'),
        2: this.createChannel('CH2', 'mm/s'),
        3: this.createChannel('CH3', 'mm/s'),
        4: this.createChannel('CH4', 'mm/s'),
        5: this.createChannel('CH5', 'mm/s'),
        6: this.createChannel('CH6', 'mm/s'),
        7: this.createChannel('CH7', 'mm/s'),
        8: this.createChannel('CH8', 'mm/s')
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
    sensorWebSocket.close()
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
      this.unsubscribeWs = sensorWebSocket.subscribe((event, payload) => {
        if (event === 'open') return
        if (event === 'error') {
          this.$modal.msgWarning('WebSocket 连接异常，请检查服务端是否启动')
          return
        }
        if (event !== 'message' || !payload) return
        this.applyRealtimeData(payload)
      })
      sensorWebSocket.connect('/ws/sensor')
    },
    applyRealtimeData(payload) {
      var channelId = Number(payload.channelId || payload.channel || payload.channelNo || 1)
      if (!this.channelDataMap[channelId]) {
        this.$set(this.channelDataMap, channelId, this.createChannel(`CH${channelId}`, 'mm/s'))
      }

      var channel = this.channelDataMap[channelId]
      var rms = this.toNumber(payload.rms, channel.metrics.rms)
      var peak = this.toNumber(payload.peak, channel.metrics.peak)
      var displacement = this.toNumber(payload.displacement || payload.peakToPeak || payload.pp, channel.metrics.displacement)
      var temp = this.toNumber(payload.temperatureValue || payload.temp, channel.metrics.temp)
      var vibrationValue = this.toNumber(payload.vibrationValue, rms)
      var health = this.calcHealth(rms, temp)
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

      channel.wave.push({ time: time, value: rms == null ? 0 : rms })
      if (channel.wave.length > 200) {
        channel.wave.splice(0, channel.wave.length - 200)
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
    renderWaveChart() {
      var el = this.$refs.waveChartRef
      if (!el) return
      if (!this.waveChart) {
        this.waveChart = echarts.init(el)
      }
      var series = this.activeChannel.wave || []
      this.waveChart.setOption({
        backgroundColor: 'transparent',
        animation: true,
        animationDuration: 300,
        tooltip: { trigger: 'axis' },
        grid: { left: 38, right: 18, top: 18, bottom: 28 },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: series.map(item => item.time),
          axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } },
          axisLabel: { color: 'rgba(235,255,255,0.75)' }
        },
        yAxis: {
          type: 'value',
          scale: true,
          splitLine: { show: false },
          axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } },
          axisLabel: { color: 'rgba(235,255,255,0.75)' }
        },
        series: [{
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: series.map(item => item.value),
          lineStyle: { width: 2, color: '#00FFFF' },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(0,255,255,0.38)' },
                { offset: 1, color: 'rgba(0,255,255,0)' }
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
  color: #eef2f7;
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
.metric-value { margin-top: 4px; font-size: 26px; font-weight: 800; line-height: 1.1; color: #f2ffff; font-family: 'Courier New', monospace; }
.metric-value.big { font-size: 38px; }
.metric-unit { margin-top: 2px; font-size: 12px; color: rgba(235,255,255,0.68); }
.channel-footer { margin-top: 12px; font-size: 12px; color: #aeb7c2; }
.foot-time { color: #e5edf5; }
.drawer-shell { height: 100vh; display: flex; flex-direction: column; background: linear-gradient(180deg, #111827 0%, #1f2937 100%); color: #eef2f7; }
.drawer-topbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 16px 20px; border-bottom: 1px solid rgba(0,255,255,0.10); }
.drawer-title { font-size: 20px; font-weight: 800; }
.drawer-subtitle { margin-top: 4px; font-size: 12px; color: #aeb7c2; }
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
.health-desc { margin-top: 4px; font-size: 18px; font-weight: 700; color: #eaffff; font-family: 'Courier New', monospace; }
.wave-chart { width: 100%; height: 320px; background: rgba(1, 12, 28, 0.78); border-radius: 10px; border: 1px solid rgba(0,255,255,0.12); box-shadow: 0 0 12px rgba(0,255,255,0.08), inset 0 0 18px rgba(0,255,255,0.03); }
.log-card { margin-top: 12px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
:deep(.monitoring-drawer) { background: transparent; }
:deep(.monitoring-drawer .el-drawer__body) { height: 100%; }
:deep(.el-table) { background: transparent; color: #eaf0f6; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(0,255,255,0.08); }
:deep(.el-radio-button__inner) { background: #2b3340; color: #eaf0f6; border-color: rgba(255,255,255,0.12); }
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) { background: #409eff; border-color: #409eff; }
@media (max-width: 1200px) {
  .metric-row { grid-template-columns: 1fr; }
  .drawer-topbar { flex-direction: column; align-items: flex-start; }
  .wave-chart { height: 260px; }
}
</style>
