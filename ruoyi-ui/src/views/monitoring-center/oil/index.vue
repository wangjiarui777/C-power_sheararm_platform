<template>
  <div class="monitoring-page oil-monitoring-page">
    <context-bar
      eyebrow="油液状态监测"
      title="在线油液监测"
      :device="activeDevice"
      :latest-sample-time="realtime.sampleTime"
      :delay-seconds="delaySeconds"
      :connection-state="connectionState"
      @refresh="refresh"
    />

    <section class="oil-toolbar oil-surface">
      <div class="device-picker">
        <label for="oil-device-select">监测设备</label>
        <el-select
          id="oil-device-select"
          v-model="deviceCode"
          filterable
          :disabled="!apiEnabled || loadingDevices"
          placeholder="请选择油液监测设备"
          popper-class="dark-select-dropdown"
          @change="selectDevice"
        >
          <el-option
            v-for="device in devices"
            :key="device.deviceCode"
            :label="device.deviceName || device.deviceCode"
            :value="device.deviceCode"
          >
            <span>{{ device.deviceName || device.deviceCode }}</span>
            <small class="device-option-code">{{ device.deviceCode }}</small>
          </el-option>
        </el-select>
      </div>
      <div class="toolbar-context">
        <span>{{ activeDevice ? activeDevice.oilType || '未设置油品类型' : '等待设备接入' }}</span>
        <el-tag size="mini" :type="serviceTagType">{{ serviceText }}</el-tag>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="oil-tabs" @tab-click="onTabClick">
      <el-tab-pane label="实时监测" name="realtime">
        <el-alert
          v-if="!apiEnabled"
          class="service-alert"
          title="油液数据服务尚未接入"
          description="页面已按正式接口结构预留。启用接口后，这里将自动加载设备快照与实时增量数据。"
          type="info"
          :closable="false"
          show-icon
        />
        <div v-else-if="apiState === 'error'" class="service-error-row">
          <el-alert
            class="service-alert"
            title="油液数据服务暂不可用"
            description="未能取得设备或实时数据，请检查服务状态后重试。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-button size="mini" type="warning" plain @click="refresh">重新加载</el-button>
        </div>

        <section class="oil-sample-rail" :class="`rail-${overallStatus.toLowerCase()}`">
          <div class="sample-identity">
            <span class="oil-drop" aria-hidden="true"><i></i></span>
            <div>
              <span>当前油样状态</span>
              <strong>{{ overallStatusText }}</strong>
            </div>
          </div>
          <div v-for="item in railItems" :key="item.code" class="rail-reading">
            <span>{{ item.label }}</span>
            <strong>{{ displayValue(item.value, item.precision) }}<small v-if="item.unit">{{ item.unit }}</small></strong>
          </div>
          <div class="rail-reading sample-time">
            <span>样本时间</span>
            <strong>{{ formatTime(realtime.sampleTime) }}</strong>
          </div>
        </section>

        <div class="realtime-grid" v-loading="loadingRealtime">
          <oil-metric-card
            title="污染度"
            eyebrow="洁净度等级"
            :items="cleanlinessItems"
            :status="groupStatus(cleanlinessItems)"
          />
          <oil-metric-card
            title="粘度与油温"
            eyebrow="理化特性"
            :items="viscosityItems"
            :status="groupStatus(viscosityItems)"
          />
          <oil-metric-card
            title="微水"
            eyebrow="水分活性"
            tone="water"
            :items="microWaterItems"
            :status="groupStatus(microWaterItems)"
          />
          <oil-metric-card
            title="含水率"
            eyebrow="水分含量"
            tone="water"
            :items="moistureItems"
            :status="groupStatus(moistureItems)"
          />
        </div>

        <div class="particle-layout">
          <particle-distribution title="铁磁颗粒" :rows="ferrousRows" />
          <particle-distribution title="非铁磁颗粒" :rows="nonFerrousRows" />
          <oil-metric-card
            class="device-state-card"
            title="设备状态"
            eyebrow="采集与过滤"
            tone="state"
            :items="deviceStateItems"
            :status="realtime.deviceState.status || realtime.status"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="历史趋势" name="history">
        <section class="history-filter oil-surface">
          <div class="filter-field device-filter">
            <label>监测设备</label>
            <el-select
              v-model="deviceCode"
              filterable
              :disabled="!apiEnabled"
              placeholder="请选择设备"
              popper-class="dark-select-dropdown"
              @change="selectDevice"
            >
              <el-option
                v-for="device in devices"
                :key="device.deviceCode"
                :label="device.deviceName || device.deviceCode"
                :value="device.deviceCode"
              />
            </el-select>
          </div>
          <div class="filter-field">
            <label>时间范围</label>
            <el-radio-group v-model="rangePreset" size="small">
              <el-radio-button label="24h">24小时</el-radio-button>
              <el-radio-button label="3d">3天</el-radio-button>
              <el-radio-button label="7d">7天</el-radio-button>
              <el-radio-button label="30d">30天</el-radio-button>
              <el-radio-button label="custom">自定义</el-radio-button>
            </el-radio-group>
          </div>
          <div v-if="rangePreset === 'custom'" class="filter-field custom-range">
            <label>自定义时间</label>
            <el-date-picker
              v-model="customRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              popper-class="dark-date-picker"
            />
          </div>
          <div class="filter-field metric-filter">
            <label>监测指标</label>
            <el-select v-model="historyMetric" popper-class="dark-select-dropdown">
              <el-option
                v-for="metric in historyMetrics"
                :key="metric.code"
                :label="metric.label"
                :value="metric.code"
              />
            </el-select>
          </div>
          <el-button
            type="primary"
            icon="el-icon-search"
            :loading="loadingTrend"
            :disabled="!apiEnabled || !deviceCode"
            @click="queryTrend"
          >
            查询趋势
          </el-button>
        </section>

        <el-alert
          v-if="!apiEnabled"
          class="service-alert"
          title="历史接口尚未启用"
          description="选择器与趋势图已就绪，启用油液数据服务后可按设备、时间和指标查询。"
          type="info"
          :closable="false"
          show-icon
        />

        <oil-trend-chart
          :rows="trend.rows"
          :metric-label="activeHistoryMetric.label"
          :unit="trend.unit || activeHistoryMetric.unit"
          :thresholds="trend.thresholds"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import ContextBar from '@/components/IndustrialMonitoring/ContextBar'
import sensorWebSocket from '@/utils/sensor-websocket'
import {
  getOilMonitoringDevices,
  getOilMonitoringRealtime,
  getOilMonitoringTrend
} from '@/api/oilMonitoring'
import OilMetricCard from './components/OilMetricCard'
import ParticleDistribution from './components/ParticleDistribution'
import OilTrendChart from './components/OilTrendChart'
import { oilStatusText } from '@/utils/industrialLabels'

const METRICS = [
  { code: 'cleanlinessNas', label: '污染度 NAS', unit: '级', precision: 0, group: 'cleanliness' },
  { code: 'viscosity40', label: '40℃粘度', unit: 'cSt', precision: 2, group: 'viscosity' },
  { code: 'oilTemperature', label: '油液温度', unit: '℃', precision: 1, group: 'viscosity' },
  { code: 'density', label: '密度', unit: 'kg/m³', precision: 1, group: 'viscosity' },
  { code: 'dielectricConstant', label: '介电常数', unit: '', precision: 2, group: 'viscosity' },
  { code: 'waterTemperature', label: '水相温度', unit: '℃', precision: 1, group: 'microWater' },
  { code: 'waterSaturation', label: '水分饱和度', unit: '%', precision: 1, group: 'microWater' },
  { code: 'microWater', label: '微水值', unit: 'ppm', precision: 1, group: 'microWater' },
  { code: 'moistureContent', label: '含水率', unit: '%', precision: 2, group: 'moisture' },
  { code: 'moistureTemperature', label: '测量温度', unit: '℃', precision: 1, group: 'moisture' }
]

const FERROUS_BINS = [
  { code: 'lt50', label: '< 50 μm' },
  { code: '50_100', label: '50–100 μm' },
  { code: '100_200', label: '100–200 μm' },
  { code: '200_400', label: '200–400 μm' },
  { code: '400_800', label: '400–800 μm' },
  { code: 'gt800', label: '> 800 μm' }
]

const NON_FERROUS_BINS = [
  { code: '150_200', label: '150–200 μm' },
  { code: '200_400', label: '200–400 μm' },
  { code: '400_800', label: '400–800 μm' },
  { code: '800_1600', label: '800–1600 μm' },
  { code: 'gt1600', label: '> 1600 μm' }
]

function emptyRealtime() {
  return {
    device: null,
    sampleTime: null,
    quality: 'OFFLINE',
    status: 'UNKNOWN',
    metrics: {},
    ferrousParticles: [],
    nonFerrousParticles: [],
    deviceState: {}
  }
}

export default {
  name: 'OilMonitoring',
  components: {
    ContextBar,
    OilMetricCard,
    ParticleDistribution,
    OilTrendChart
  },
  data() {
    return {
      apiEnabled: String(process.env.VUE_APP_OIL_MONITORING_API_ENABLED).toLowerCase() === 'true',
      activeTab: 'realtime',
      apiState: 'idle',
      connectionState: 'offline',
      devices: [],
      deviceCode: '',
      realtime: emptyRealtime(),
      loadingDevices: false,
      loadingRealtime: false,
      loadingTrend: false,
      rangePreset: '24h',
      customRange: [],
      historyMetric: 'viscosity40',
      trend: { rows: [], thresholds: {}, unit: '' },
      unsubscribe: null,
      subscribedDeviceCode: ''
    }
  },
  computed: {
    activeDevice() {
      return this.realtime.device ||
        this.devices.find(item => item.deviceCode === this.deviceCode) ||
        null
    },
    delaySeconds() {
      if (!this.realtime.sampleTime) return null
      const value = new Date(this.realtime.sampleTime).getTime()
      return Number.isNaN(value) ? null : Math.max(0, Math.floor((Date.now() - value) / 1000))
    },
    serviceText() {
      if (!this.apiEnabled) return '接口未启用'
      if (this.apiState === 'error') return '服务异常'
      if (this.connectionState === 'online') return '实时连接'
      return '等待连接'
    },
    serviceTagType() {
      if (!this.apiEnabled) return 'info'
      return this.apiState === 'error' ? 'warning' : this.connectionState === 'online' ? 'success' : 'info'
    },
    normalizedMetrics() {
      return this.normalizeMetrics(this.realtime.metrics)
    },
    cleanlinessItems() {
      return this.metricItems('cleanliness')
    },
    viscosityItems() {
      return this.metricItems('viscosity')
    },
    microWaterItems() {
      return this.metricItems('microWater')
    },
    moistureItems() {
      return this.metricItems('moisture')
    },
    deviceStateItems() {
      const state = this.realtime.deviceState || {}
      return [
        { code: 'pressure', label: '压力值', value: state.pressure, unit: 'MPa', precision: 2 },
        { code: 'flow', label: '流量', value: state.flow, unit: 'ml/min', precision: 2 },
        { code: 'filterStatus', label: '过滤器', value: state.filterStatus || null, unit: '' },
        { code: 'communication', label: '通讯状态', value: state.communication || null, unit: '' }
      ]
    },
    ferrousRows() {
      return this.particleRows(this.realtime.ferrousParticles, FERROUS_BINS)
    },
    nonFerrousRows() {
      return this.particleRows(this.realtime.nonFerrousParticles, NON_FERROUS_BINS)
    },
    overallStatus() {
      const explicit = String(this.realtime.status || '').toUpperCase()
      if (explicit && explicit !== 'UNKNOWN') return explicit
      const statuses = Object.values(this.normalizedMetrics)
        .map(item => String(item.status || '').toUpperCase())
      if (statuses.includes('ALARM') || statuses.includes('BAD')) return 'ALARM'
      if (statuses.includes('WARNING')) return 'WARNING'
      if (statuses.includes('NORMAL') || statuses.includes('GOOD')) return 'NORMAL'
      return this.apiEnabled ? 'OFFLINE' : 'UNKNOWN'
    },
    overallStatusText() {
      return {
        NORMAL: '油样正常',
        GOOD: '油样正常',
        WARNING: '建议复核',
        ALARM: '指标报警',
        BAD: '质量异常',
        OFFLINE: '设备离线',
        UNKNOWN: '等待接入'
      }[this.overallStatus] || this.oilStatusText(this.overallStatus)
    },
    railItems() {
      return [
        this.metricDefinition('cleanlinessNas'),
        this.metricDefinition('viscosity40'),
        this.metricDefinition('microWater')
      ]
    },
    historyMetrics() {
      return METRICS
    },
    activeHistoryMetric() {
      return METRICS.find(item => item.code === this.historyMetric) || METRICS[0]
    }
  },
  async created() {
    this.deviceCode = this.$route.query.deviceCode || ''
    if (!this.apiEnabled) return
    await this.loadDevices()
    if (this.deviceCode) {
      await this.loadRealtime()
      this.connectRealtime()
    }
  },
  beforeDestroy() {
    this.disconnectRealtime()
  },
  methods: {
    oilStatusText(value) { return oilStatusText(value) },
    async loadDevices() {
      this.loadingDevices = true
      try {
        const response = await getOilMonitoringDevices()
        this.devices = (response.data || []).map(item => ({ ...item }))
        if (!this.devices.some(item => item.deviceCode === this.deviceCode)) {
          this.deviceCode = this.devices.length ? this.devices[0].deviceCode : ''
        }
        this.syncDeviceQuery()
        this.apiState = 'ready'
      } catch (error) {
        this.devices = []
        this.apiState = 'error'
      } finally {
        this.loadingDevices = false
      }
    },
    async loadRealtime() {
      if (!this.apiEnabled || !this.deviceCode) {
        this.realtime = emptyRealtime()
        return
      }
      this.loadingRealtime = true
      try {
        const response = await getOilMonitoringRealtime(this.deviceCode)
        this.realtime = this.normalizeRealtime(response.data || {})
        this.apiState = 'ready'
      } catch (error) {
        this.realtime = emptyRealtime()
        this.apiState = 'error'
      } finally {
        this.loadingRealtime = false
      }
    },
    async refresh() {
      if (!this.apiEnabled) {
        this.$message.info('油液数据服务尚未接入')
        return
      }
      await this.loadDevices()
      await this.loadRealtime()
      if (this.deviceCode) this.connectRealtime()
      if (this.activeTab === 'history') await this.queryTrend()
    },
    async selectDevice(code) {
      const previous = this.subscribedDeviceCode
      this.deviceCode = code || ''
      this.syncDeviceQuery()
      this.trend = { rows: [], thresholds: {}, unit: '' }
      await this.loadRealtime()
      if (previous && previous !== this.deviceCode) {
        sensorWebSocket.send({ type: 'unsubscribe', channel: `device:${previous}` })
      }
      if (this.deviceCode && this.connectionState === 'online') {
        sensorWebSocket.send({ type: 'subscribe', channel: `device:${this.deviceCode}` })
        this.subscribedDeviceCode = this.deviceCode
      }
      if (this.activeTab === 'history') await this.queryTrend()
    },
    syncDeviceQuery() {
      const query = { ...this.$route.query }
      if (this.deviceCode) query.deviceCode = this.deviceCode
      else delete query.deviceCode
      this.$router.replace({ query })
    },
    connectRealtime() {
      if (!this.apiEnabled || this.unsubscribe) return
      this.unsubscribe = sensorWebSocket.subscribe((event, payload) => {
        if (event === 'open') {
          this.connectionState = 'online'
          sensorWebSocket.send({ type: 'subscribe', channel: 'monitoring' })
          if (this.deviceCode) {
            sensorWebSocket.send({ type: 'subscribe', channel: `device:${this.deviceCode}` })
            this.subscribedDeviceCode = this.deviceCode
          }
        } else if (event === 'message') {
          this.applyRealtimeEvent(payload)
        } else if (event === 'close' || event === 'error') {
          this.connectionState = 'offline'
        }
      })
      sensorWebSocket.connect('/ws/monitoring').catch(() => {
        this.connectionState = 'offline'
      })
    },
    disconnectRealtime() {
      if (this.subscribedDeviceCode) {
        sensorWebSocket.send({ type: 'unsubscribe', channel: `device:${this.subscribedDeviceCode}` })
      }
      if (this.unsubscribe) this.unsubscribe()
      this.unsubscribe = null
      this.subscribedDeviceCode = ''
    },
    applyRealtimeEvent(payload) {
      if (!payload || payload.event !== 'oil.metric.changed') return
      let eventData = payload
      if (payload.message && typeof payload.message === 'string') {
        try {
          eventData = { ...payload, ...JSON.parse(payload.message) }
        } catch (error) {
          eventData = payload
        }
      }
      if (eventData.deviceCode !== this.deviceCode) return
      const incomingMetrics = this.normalizeMetrics(eventData.metrics)
      this.realtime = this.normalizeRealtime({
        ...this.realtime,
        ...eventData,
        metrics: { ...this.normalizedMetrics, ...incomingMetrics },
        deviceState: { ...this.realtime.deviceState, ...(eventData.deviceState || {}) }
      })
      this.apiState = 'ready'
    },
    normalizeRealtime(payload) {
      return {
        ...emptyRealtime(),
        ...payload,
        device: payload.device || this.devices.find(item => item.deviceCode === this.deviceCode) || null,
        metrics: this.normalizeMetrics(payload.metrics),
        ferrousParticles: payload.ferrousParticles || [],
        nonFerrousParticles: payload.nonFerrousParticles || [],
        deviceState: payload.deviceState || {}
      }
    },
    normalizeMetrics(metrics) {
      if (Array.isArray(metrics)) {
        return metrics.reduce((result, item) => {
          if (item && item.code) result[item.code] = item
          return result
        }, {})
      }
      if (!metrics || typeof metrics !== 'object') return {}
      return Object.keys(metrics).reduce((result, code) => {
        const item = metrics[code]
        result[code] = item && typeof item === 'object' && !Array.isArray(item)
          ? { code, ...item }
          : { code, value: item }
        return result
      }, {})
    },
    metricDefinition(code) {
      const definition = METRICS.find(item => item.code === code)
      const metric = this.normalizedMetrics[code] || {}
      return { ...definition, ...metric, value: metric.value === undefined ? null : metric.value }
    },
    metricItems(group) {
      return METRICS.filter(item => item.group === group).map(item => this.metricDefinition(item.code))
    },
    groupStatus(items) {
      const statuses = items.map(item => String(item.status || '').toUpperCase())
      if (statuses.includes('ALARM') || statuses.includes('BAD')) return 'ALARM'
      if (statuses.includes('WARNING')) return 'WARNING'
      if (statuses.includes('NORMAL') || statuses.includes('GOOD')) return 'NORMAL'
      return 'UNKNOWN'
    },
    particleRows(source, definitions) {
      if (Array.isArray(source)) {
        const map = source.reduce((result, item) => {
          if (item && item.code) result[item.code] = item.count
          return result
        }, {})
        return definitions.map(item => ({ ...item, count: map[item.code] === undefined ? null : map[item.code] }))
      }
      const values = source && typeof source === 'object' ? source : {}
      return definitions.map(item => ({ ...item, count: values[item.code] === undefined ? null : values[item.code] }))
    },
    onTabClick() {
      if (this.activeTab === 'history' && this.apiEnabled && this.deviceCode && !this.trend.rows.length) {
        this.queryTrend()
      }
    },
    async queryTrend() {
      if (!this.apiEnabled || !this.deviceCode) {
        this.trend = { rows: [], thresholds: {}, unit: '' }
        return
      }
      const range = this.resolveRange()
      if (!range) return
      this.loadingTrend = true
      try {
        const response = await getOilMonitoringTrend(this.deviceCode, {
          metricCode: this.historyMetric,
          from: range.from,
          to: range.to,
          maxPoints: 1200
        })
        const data = response.data || {}
        this.trend = {
          rows: Array.isArray(data.rows) ? data.rows : [],
          thresholds: data.thresholds || {},
          unit: data.unit || this.activeHistoryMetric.unit
        }
        this.apiState = 'ready'
      } catch (error) {
        this.trend = { rows: [], thresholds: {}, unit: this.activeHistoryMetric.unit }
        this.apiState = 'error'
      } finally {
        this.loadingTrend = false
      }
    },
    resolveRange() {
      if (this.rangePreset === 'custom') {
        if (!Array.isArray(this.customRange) || this.customRange.length !== 2) {
          this.$message.warning('请选择完整的开始和结束时间')
          return null
        }
        const start = new Date(this.customRange[0])
        const end = new Date(this.customRange[1])
        if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || start >= end) {
          this.$message.warning('结束时间必须晚于开始时间')
          return null
        }
        return { from: start.toISOString(), to: end.toISOString() }
      }
      const durations = {
        '24h': 24 * 60 * 60 * 1000,
        '3d': 3 * 24 * 60 * 60 * 1000,
        '7d': 7 * 24 * 60 * 60 * 1000,
        '30d': 30 * 24 * 60 * 60 * 1000
      }
      const end = new Date()
      const start = new Date(end.getTime() - durations[this.rangePreset])
      return { from: start.toISOString(), to: end.toISOString() }
    },
    displayValue(value, precision) {
      if (value === null || value === undefined || value === '') return '--'
      const number = Number(value)
      if (Number.isNaN(number)) return String(value)
      return number.toFixed(precision === undefined ? 2 : precision)
    },
    formatTime(value) {
      if (!value) return '--'
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return String(value)
      return date.toLocaleString('zh-CN', { hour12: false })
    }
  }
}
</script>

<style scoped>
.oil-monitoring-page {
  --oil-amber: #d6a14a;
  --oil-amber-soft: rgba(214, 161, 74, .1);
  min-height: calc(100vh - 84px);
  padding: var(--space-page);
  color: var(--color-text);
  font-family: var(--font-ui);
}
.oil-surface {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: none;
}
.oil-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  margin: var(--space-section) 0 4px;
  padding: 12px 16px;
}
.device-picker {
  display: grid;
  grid-template-columns: auto minmax(240px, 360px);
  align-items: center;
  gap: 12px;
}
.device-picker label,
.filter-field label {
  color: var(--color-muted);
  font-size: 12px;
}
.device-picker .el-select { width: 100%; }
.toolbar-context {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--color-muted);
  font-size: 12px;
}
.oil-tabs { margin-top: 4px; }
.service-alert { margin: 10px 0 14px; }
.service-error-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0 14px;
}
.service-error-row .service-alert {
  flex: 1;
  margin: 0;
}
.oil-sample-rail {
  display: grid;
  grid-template-columns: minmax(210px, 1.25fr) repeat(3, minmax(130px, .75fr)) minmax(190px, 1fr);
  align-items: stretch;
  margin: var(--space-section) 0;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--oil-amber);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}
.rail-normal { border-left-color: #10b981; }
.rail-warning { border-left-color: #f59e0b; }
.rail-alarm,
.rail-bad { border-left-color: #ef4444; }
.rail-offline,
.rail-unknown { border-left-color: #64748b; }
.sample-identity,
.rail-reading {
  min-width: 0;
  padding: 15px 16px;
  border-right: 1px solid var(--color-border);
}
.sample-identity {
  display: flex;
  align-items: center;
  gap: 14px;
}
.oil-drop {
  position: relative;
  width: 34px;
  height: 34px;
  flex: none;
  transform: rotate(45deg);
  border: 1px solid rgba(214, 161, 74, .58);
  border-radius: 50% 50% 50% 10%;
  background: var(--oil-amber-soft);
}
.oil-drop i {
  position: absolute;
  right: 7px;
  bottom: 7px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--oil-amber);
}
.sample-identity span:not(.oil-drop),
.rail-reading > span {
  display: block;
  overflow: hidden;
  color: var(--color-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sample-identity strong {
  display: block;
  margin-top: 4px;
  color: var(--color-heading);
  font-size: 17px;
}
.rail-reading strong {
  display: block;
  overflow: hidden;
  margin-top: 7px;
  color: var(--color-text);
  font-family: var(--font-data);
  font-size: 19px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rail-reading strong small {
  margin-left: 5px;
  color: var(--color-muted);
  font-size: 10px;
  font-weight: 400;
}
.sample-time { border-right: 0; }
.sample-time strong { font-size: 13px; }
.realtime-grid {
  display: grid;
  grid-template-columns: .8fr 1.35fr 1.05fr .8fr;
  gap: 12px;
}
.particle-layout {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(260px, 1fr) minmax(300px, .9fr);
  gap: 12px;
  margin-top: 12px;
}
.device-state-card { align-self: stretch; }
.history-filter {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  margin: 10px 0 14px;
  padding: 14px 16px;
  flex-wrap: wrap;
}
.filter-field {
  display: grid;
  gap: 7px;
}
.device-filter { width: 240px; }
.metric-filter { width: 190px; }
.custom-range { min-width: 380px; }
.history-filter > .el-button { margin-bottom: 0; }
.device-option-code {
  float: right;
  margin-left: 16px;
  color: var(--color-muted);
}
@media (max-width: 1440px) {
  .oil-sample-rail { grid-template-columns: minmax(190px, 1.2fr) repeat(3, minmax(110px, .7fr)) minmax(155px, .9fr); }
  .realtime-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .particle-layout { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .device-state-card { grid-column: 1 / -1; }
}
@media (max-width: 900px) {
  .oil-toolbar,
  .toolbar-context { align-items: flex-start; }
  .oil-toolbar { flex-direction: column; }
  .device-picker { width: 100%; grid-template-columns: 1fr; }
  .oil-sample-rail { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .sample-identity { grid-column: 1 / -1; }
  .sample-identity,
  .rail-reading { border-bottom: 1px solid var(--color-border); }
  .sample-time { border-right: 1px solid var(--color-border); }
  .realtime-grid,
  .particle-layout { grid-template-columns: 1fr; }
  .device-state-card { grid-column: auto; }
  .history-filter { align-items: stretch; flex-direction: column; }
  .device-filter,
  .metric-filter,
  .custom-range { width: 100%; min-width: 0; }
  .custom-range .el-date-editor { width: 100%; }
}
</style>

<style>
html body #app .oil-monitoring-page .context-bar {
  border-color: var(--color-border) !important;
  background: var(--color-surface) !important;
  color: var(--color-text) !important;
}
html body #app .oil-monitoring-page .context-title h1,
html body #app .oil-monitoring-page .context-meta strong {
  color: var(--color-heading) !important;
}
html body #app .oil-monitoring-page .context-title p,
html body #app .oil-monitoring-page .context-meta span,
html body #app .oil-monitoring-page .eyebrow {
  color: var(--color-muted) !important;
}
html body #app .oil-monitoring-page .el-tabs__content {
  overflow: visible;
}
html body #app .oil-monitoring-page .el-alert {
  border: 1px solid var(--color-border) !important;
  background: var(--color-surface) !important;
}
html body #app .oil-monitoring-page .el-alert__title {
  color: var(--color-text) !important;
}
html body #app .oil-monitoring-page .el-alert__description {
  color: var(--color-muted) !important;
}
</style>
