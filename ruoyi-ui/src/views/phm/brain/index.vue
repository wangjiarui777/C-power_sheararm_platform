<template>
  <div class="app-container brain-page" v-loading="loading">
    <section class="brain-hero">
      <div>
        <el-button type="text" icon="el-icon-arrow-left" @click="$router.push('/phm/cluster')">返回设备集群</el-button>
        <h2>{{ device.deviceName || '机器大脑' }}</h2>
        <p>{{ device.deviceCode }} · {{ device.deviceType }} · {{ device.orgName }}</p>
      </div>
      <div class="brain-device-context">
        <span>当前设备</span>
        <el-select
          v-model="selectedDeviceId"
          filterable
          placeholder="请选择设备"
          size="small"
          :disabled="!devices.length"
          @change="changeDevice"
        >
          <el-option
            v-for="item in devices"
            :key="item.id"
            :label="`${item.deviceName} · ${item.deviceCode}`"
            :value="String(item.id)"
          />
        </el-select>
      </div>
      <div class="brain-status">
        <el-tag :type="statusTag(device.status)">{{ statusText(device.status) }}</el-tag>
        <strong>{{ withUnit(device.healthIndex, '%') }}</strong>
        <span>健康指数</span>
      </div>
    </section>

    <section class="brain-grid">
      <aside class="brain-panel nameplate">
        <h3>电子铭牌</h3>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="型号">{{ device.modelName || '--' }}</el-descriptions-item>
          <el-descriptions-item label="制造商">{{ device.manufacturer || '--' }}</el-descriptions-item>
          <el-descriptions-item label="安装位置">{{ device.location || '--' }}</el-descriptions-item>
          <el-descriptions-item label="累计运行">{{ withUnit(device.runHours, ' h') }}</el-descriptions-item>
          <el-descriptions-item label="当前故障">{{ display(device.faultType) }}</el-descriptions-item>
        </el-descriptions>
        <h3>工况参数</h3>
        <pre>{{ prettyJson(device.processJson) }}</pre>
      </aside>

      <main class="brain-panel">
        <div class="panel-head">
          <h3>测点特征值</h3>
          <div class="head-actions">
            <el-button size="mini" type="primary" plain @click="openDiagnosis">诊断分析</el-button>
            <el-button size="mini" plain @click="openVibrationAnalysis">波形/频谱</el-button>
          </div>
        </div>
        <div class="morphology-box">
          <img v-if="device.morphologyUrl" :src="fileHref(device.morphologyUrl)" alt="设备形貌图">
          <div v-else class="morphology-empty">暂无形貌图，请在配置管理中上传</div>
          <button
            v-for="item in points"
            :key="`point-${item.point.id}`"
            class="point-marker"
            :style="pointMarkerStyle(item.point)"
            @click="selectPoint(item)"
          >
            {{ item.point.channelId || '?' }}
          </button>
          <article
            v-for="item in points"
            :key="`card-${item.point.id}`"
            class="morph-card"
            :style="pointCardStyle(item.point)"
            @click="selectPoint(item)"
          >
            <strong>{{ item.point.pointName }}</strong>
            <span>振动 {{ display(item.latestVibration) }}</span>
            <span>温度 {{ display(item.latestTemperature) }}</span>
          </article>
        </div>
        <div class="point-grid">
          <article v-for="item in points" :key="item.point.id" class="point-card" @click="selectPoint(item)">
            <div class="point-title">{{ item.point.pointName }}</div>
            <div class="point-sub">通道 {{ item.point.channelId || '--' }} · {{ item.point.signalType }}</div>
            <div class="point-values">
              <span>振动 <b>{{ display(item.latestVibration) }}</b></span>
              <span>温度 <b>{{ display(item.latestTemperature) }}</b></span>
            </div>
          </article>
        </div>
        <div class="trend-box">
          <div class="panel-head">
            <h3>{{ activePointName }} 趋势</h3>
            <div class="head-actions">
              <el-tag size="mini">最近 100 条</el-tag>
              <el-button size="mini" type="text" @click="exportTrendCsv">导出CSV</el-button>
              <el-button size="mini" type="text" @click="saveTrendImage">保存图片</el-button>
            </div>
          </div>
          <div ref="trendChart" class="trend-chart"></div>
        </div>
      </main>

      <aside class="brain-panel">
        <div class="diagnosis-card">
          <span>最新诊断</span>
          <strong>{{ latestDiagnosis.diagnosisResult || device.faultType || '暂无异常诊断' }}</strong>
          <p>{{ latestDiagnosis.diagnosisDetail || latestDiagnosis.decisionReason || '可进入诊断分析查看波形、频谱和模型证据。' }}</p>
          <div class="diagnosis-actions">
            <el-button size="mini" type="primary" plain @click="openDiagnosis">查看模型证据</el-button>
            <el-button size="mini" plain @click="$router.push('/phm/events?deviceId=' + device.id)">设备大事记</el-button>
          </div>
        </div>

        <div class="panel-head">
          <h3>告警摘要</h3>
          <el-button type="text" @click="$router.push('/phm/alarms')">全部</el-button>
        </div>
        <el-table :data="alarms" height="220" size="mini">
          <el-table-column prop="alarmLevel" label="等级" width="64" />
          <el-table-column prop="pointName" label="测点" />
          <el-table-column prop="status" label="状态" width="88" />
        </el-table>

        <div class="panel-head event-head">
          <h3>设备大事记</h3>
          <el-button type="text" @click="eventVisible = true">新增</el-button>
        </div>
        <el-timeline class="event-list">
          <el-timeline-item v-for="event in events" :key="event.id" :timestamp="parseTime(event.eventTime)" placement="top">
            <strong>{{ eventTypeText(event.eventType) }}</strong>
            <p>{{ event.eventContent }}</p>
          </el-timeline-item>
        </el-timeline>
      </aside>
    </section>

    <el-dialog title="新增大事记" :visible.sync="eventVisible" width="520px">
      <el-form :model="eventForm" label-width="90px">
        <el-form-item label="事件时间">
          <el-date-picker v-model="eventForm.eventTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-select v-model="eventForm.eventType">
            <el-option label="维修" value="repair" />
            <el-option label="保养" value="maintenance" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="事件记录">
          <el-input v-model="eventForm.eventContent" type="textarea" :rows="4" maxlength="300" show-word-limit />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="eventVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEvent">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { getDeviceBrain, listPhmDevices, saveDeviceEvent } from '@/api/phm'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'PhmBrain',
  data() {
    return {
      loading: false,
      chart: null,
      devices: [],
      selectedDeviceId: '',
      device: {},
      points: [],
      alarms: [],
      events: [],
      latestDiagnosis: {},
      activePoint: null,
      eventVisible: false,
      eventForm: {
        eventType: 'maintenance',
        eventTime: '',
        eventContent: ''
      }
    }
  },
  computed: {
    activePointName() {
      return this.activePoint && this.activePoint.point ? this.activePoint.point.pointName : '测点'
    }
  },
  mounted() {
    window.addEventListener('appearance-mode-change', this.renderChart)
    this.initializeBrain()
  },
  beforeDestroy() {
    window.removeEventListener('appearance-mode-change', this.renderChart)
    if (this.chart) this.chart.dispose()
  },
  watch: {
    '$route.fullPath'() {
      this.initializeBrain()
    }
  },
  methods: {
    display(value) {
      return value === null || value === undefined || value === '' ? '--' : value
    },
    withUnit(value, unit) {
      return value === null || value === undefined || value === '' ? '--' : `${value}${unit}`
    },
    async initializeBrain() {
      this.loading = true
      try {
        const deviceRes = await listPhmDevices()
        this.devices = deviceRes.data || []
        if (!this.devices.length) {
          this.selectedDeviceId = ''
          this.resetBrainData()
          return
        }
        const requestedId = this.$route.params.deviceId || this.$route.query.deviceId
        const requested = requestedId == null ? '' : String(requestedId)
        const matched = this.devices.find(item => String(item.id) === requested)
        this.selectedDeviceId = matched ? String(matched.id) : String(this.devices[0].id)
        await this.loadBrain()
      } catch (error) {
        this.$message.error(`机器大脑加载失败：${error.message || error}`)
        this.resetBrainData()
      } finally {
        this.loading = false
      }
    },
    async loadBrain() {
      if (!this.selectedDeviceId) {
        this.resetBrainData()
        return
      }
      const res = await getDeviceBrain(this.selectedDeviceId)
        const data = res.data || {}
        this.device = data.device || {}
        this.points = data.points || []
        this.alarms = data.alarms || []
        this.events = data.events || []
        this.latestDiagnosis = data.latestDiagnosis || {}
        this.activePoint = this.points[0] || null
        this.$nextTick(this.renderChart)
    },
    changeDevice(deviceId) {
      const id = String(deviceId)
      const target = this.$route.params.deviceId
        ? `/phm/brain/${id}`
        : { path: '/phm/brain', query: { deviceId: id } }
      this.$router.push(target)
    },
    resetBrainData() {
      this.device = {}
      this.points = []
      this.alarms = []
      this.events = []
      this.latestDiagnosis = {}
      this.activePoint = null
      if (this.chart) {
        this.chart.clear()
      }
    },
    selectPoint(item) {
      this.activePoint = item
      this.renderChart()
    },
    pointMarkerStyle(point) {
      return {
        left: `${point.pointX == null ? 50 : point.pointX}%`,
        top: `${point.pointY == null ? 50 : point.pointY}%`
      }
    },
    pointCardStyle(point) {
      return {
        left: `${point.cardX == null ? 8 : point.cardX}%`,
        top: `${point.cardY == null ? 8 : point.cardY}%`
      }
    },
    renderChart() {
      if (!this.$refs.trendChart) return
      if (!this.chart) this.chart = echarts.init(this.$refs.trendChart)
      const trend = (this.activePoint && this.activePoint.trend) || []
      this.chart.setOption({
        animation: false,
        color: [industrialChartTheme.vibration],
        tooltip: { trigger: 'axis', backgroundColor: industrialChartTheme.tooltipBg, borderColor: industrialChartTheme.tooltipBorder, textStyle: { color: industrialChartTheme.text } },
        grid: { left: 42, right: 18, top: 28, bottom: 32 },
        xAxis: {
          type: 'category',
          data: trend.map(item => this.parseTime(item.sampleTime, '{h}:{i}:{s}')),
          axisLabel: { color: industrialChartTheme.axis },
          axisLine: { lineStyle: { color: industrialChartTheme.border } }
        },
        yAxis: {
          type: 'value',
          axisLabel: { color: industrialChartTheme.axis },
          splitLine: { lineStyle: { color: industrialChartTheme.grid } }
        },
        series: [{
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: trend.map(item => item.value),
          lineStyle: { color: industrialChartTheme.vibration, width: 2.4 },
          areaStyle: { color: 'rgba(56,189,248,.14)' }
        }]
      })
    },
    openDiagnosis() {
      this.$router.push({
        path: '/analysis-toolkit/bearing-diagnosis',
        query: {
          deviceCode: this.device.deviceCode,
          pointId: this.activePoint && this.activePoint.point ? this.activePoint.point.id : undefined,
          channelId: this.activePoint && this.activePoint.point ? this.activePoint.point.channelId : undefined,
          modelType: this.latestDiagnosis.modelType || this.$route.query.modelType || undefined,
          modelVersion: this.latestDiagnosis.modelVersion || this.$route.query.modelVersion || undefined
        }
      })
    },
    openVibrationAnalysis() {
      this.$router.push({
        path: '/monitoring-center/vibration',
        query: {
          deviceCode: this.device.deviceCode,
          channelId: this.activePoint && this.activePoint.point ? this.activePoint.point.channelId : undefined
        }
      })
    },
    exportTrendCsv() {
      const point = this.activePoint && this.activePoint.point
      const trend = (this.activePoint && this.activePoint.trend) || []
      if (!trend.length) {
        this.$message.warning('当前测点暂无趋势数据')
        return
      }
      const rows = [['sampleTime', 'featureCode', 'value', 'deviceCode', 'pointName']]
      trend.forEach(item => {
        rows.push([
          this.parseTime(item.sampleTime),
          item.featureCode || 'vibration',
          item.value,
          this.device.deviceCode || '',
          point ? point.pointName : ''
        ])
      })
      const csv = rows.map(row => row.map(value => `"${String(value == null ? '' : value).replace(/"/g, '""')}"`).join(',')).join('\n')
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${this.device.deviceCode || 'device'}-${point ? point.pointName : 'point'}-trend.csv`
      link.click()
      window.URL.revokeObjectURL(url)
    },
    saveTrendImage() {
      if (!this.chart) {
        this.$message.warning('趋势图尚未渲染')
        return
      }
      const point = this.activePoint && this.activePoint.point
      const url = this.chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#09111d' })
      const link = document.createElement('a')
      link.href = url
      link.download = `${this.device.deviceCode || 'device'}-${point ? point.pointName : 'point'}-trend.png`
      link.click()
    },
    async saveEvent() {
      await saveDeviceEvent(Object.assign({}, this.eventForm, {
        deviceId: this.device.id,
        deviceCode: this.device.deviceCode
      }))
      this.$message.success('大事记已保存')
      this.eventVisible = false
      this.loadBrain()
    },
    prettyJson(text) {
      if (!text) return '--'
      try {
        return JSON.stringify(JSON.parse(text), null, 2)
      } catch (e) {
        return text
      }
    },
    statusText(status) {
      const map = { normal: '正常', stopped: '停机', level1: '1级告警', level2: '2级告警', level3: '3级告警', level4: '4级告警', level5: '5级告警' }
      return map[status] || status || '--'
    },
    statusTag(status) {
      if (status === 'normal') return 'success'
      if (status === 'stopped') return 'info'
      if (status === 'level1' || status === 'level2') return 'warning'
      return 'danger'
    },
    eventTypeText(type) {
      return { access: '设备接入', repair: '维修', maintenance: '保养', diagnosis: '智能诊断', alarm_handle: '告警处置', other: '其他' }[type] || type
    },
    fileHref(url) {
      if (!url) return ''
      if (/^(https?:)?\/\//.test(url)) return url
      const base = process.env.VUE_APP_BASE_API || ''
      return url.indexOf('/') === 0 ? base + url : url
    }
  }
}
</script>

<style scoped>
.brain-page { min-height: calc(100vh - 84px); }
.brain-hero { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.brain-hero h2 { margin: 6px 0; color: var(--ops-heading); }
.brain-hero p { margin: 0; color: var(--ops-muted); }
.brain-device-context { display: flex; align-items: center; gap: 10px; margin-left: auto; color: var(--ops-muted); }
.brain-device-context .el-select { width: 260px; }
.brain-status { min-width: 180px; padding: 16px 18px; text-align: right; border-radius: 16px; }
.brain-status strong { display: block; margin-top: 8px; font-size: 30px; color: var(--ops-heading); }
.brain-status span { color: var(--ops-muted); font-size: 12px; }
.brain-grid { display: grid; grid-template-columns: 280px minmax(0, 1fr) 360px; gap: 14px; }
.brain-panel { padding: 16px; min-height: 200px; }
.brain-panel h3 { margin: 0 0 12px; color: var(--ops-heading); }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.diagnosis-card { margin-bottom: 14px; padding: 16px; border-radius: 16px; background: linear-gradient(180deg, rgba(17, 47, 70, 0.92), rgba(10, 26, 42, 0.96)); border: 1px solid rgba(56, 189, 248, 0.22); }
.diagnosis-card span { color: var(--ops-accent); font-size: 12px; }
.diagnosis-card strong { display: block; margin-top: 6px; color: var(--ops-heading); }
.diagnosis-card p { margin: 6px 0 0; color: var(--ops-text); line-height: 1.6; }
.diagnosis-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.nameplate pre { margin: 0; padding: 12px; background: rgba(8, 17, 30, 0.68); border-radius: 10px; color: var(--ops-text); white-space: pre-wrap; border: 1px solid rgba(120, 153, 186, 0.16); }
.morphology-box { position: relative; min-height: 260px; margin-bottom: 14px; overflow: hidden; border-radius: 16px; background: rgba(8, 17, 30, 0.62); }
.morphology-box img { display: block; width: 100%; height: 300px; object-fit: contain; background: rgba(9, 17, 29, 0.65); }
.morphology-empty { display: flex; align-items: center; justify-content: center; height: 260px; color: var(--ops-muted); }
.point-marker { position: absolute; width: 28px; height: 28px; transform: translate(-50%, -50%); border-radius: 50%; background: var(--ops-accent); color: #08111d; cursor: pointer; font-weight: 700; }
.morph-card { position: absolute; min-width: 128px; transform: translate(-10%, -10%); border-radius: 12px; padding: 10px; cursor: pointer; }
.morph-card strong { display: block; color: var(--ops-heading); font-size: 12px; }
.morph-card span { display: block; margin-top: 3px; color: var(--ops-text); font-size: 12px; }
.point-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 10px; margin-bottom: 14px; }
.point-card { border-radius: 12px; padding: 14px; cursor: pointer; }
.point-title { font-weight: 700; color: var(--ops-heading); }
.point-sub { margin-top: 4px; color: var(--ops-muted); font-size: 12px; }
.point-values { display: flex; justify-content: space-between; margin-top: 10px; color: var(--ops-text); }
.trend-chart { height: 320px; }
.event-head { margin-top: 18px; }
.event-list { max-height: 260px; overflow: auto; padding-right: 8px; }
.event-list p { margin: 4px 0 0; color: var(--ops-muted); }
@media (max-width: 1200px) {
  .brain-grid { grid-template-columns: 1fr; }
  .brain-hero { align-items: stretch; flex-direction: column; }
  .brain-device-context { margin-left: 0; }
}
</style>
