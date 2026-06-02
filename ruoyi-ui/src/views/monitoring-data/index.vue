<template>
  <div class="app-container diagnosis-dashboard-page">
    <div class="dashboard-shell">
      <aside class="dashboard-sidebar">
        <div class="sidebar-brand">
          <div class="brand-icon">Ψ</div>
          <div>
            <div class="brand-title">智能诊断平台</div>
            <div class="brand-subtitle">工业设备状态监测</div>
          </div>
        </div>

        <div class="sidebar-card">
          <div class="sidebar-card-title">设备列表</div>
          <el-input
            v-model="deviceKeyword"
            size="small"
            placeholder="搜索设备名称/编号"
            prefix-icon="el-icon-search"
            clearable
            class="sidebar-search"
          />
          <div class="device-tree">
            <div
              v-for="item in filteredDevicePoints"
              :key="item.deviceCode"
              class="device-item"
              :class="{ active: item.deviceCode === selectedDeviceCode }"
              @click="handleDeviceClick(item)"
            >
              <div class="device-item-main">
                <i class="el-icon-cpu device-icon"></i>
                <div class="device-texts">
                  <div class="device-name">{{ item.deviceCode }}</div>
                  <div class="device-desc">{{ item.deviceName || '运行监测节点' }}</div>
                </div>
              </div>
              <el-tag size="mini" :type="deviceStatusType(item)">{{ deviceStatusText(item) }}</el-tag>
            </div>
            <div v-if="!filteredDevicePoints.length" class="empty-tip">暂无设备数据</div>
          </div>
        </div>

        <div class="sidebar-footer">
          <el-button type="primary" size="small" icon="el-icon-plus" class="full-width-btn">添加设备</el-button>
        </div>
      </aside>

      <main class="dashboard-main">
        <header class="dashboard-topbar">
          <div>
            <div class="page-title">诊断分析</div>
            <div class="page-subtitle">实时振动与温度监测 · 多模块联动视图</div>
          </div>
          <div class="topbar-actions">
            <el-tag size="mini" type="success">在线</el-tag>
            <span class="topbar-time">{{ overview.updateTime || '--' }}</span>
            <el-button size="mini" icon="el-icon-download" plain>导出报告</el-button>
          </div>
        </header>

        <el-row :gutter="16" class="metrics-row">
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card class="metric-card" shadow="hover">
              <div class="metric-head">
                <span class="metric-icon pulse">⟡</span>
                <span class="metric-title">健康指数</span>
              </div>
              <div class="metric-value metric-score">{{ healthIndex }}</div>
              <div class="metric-sub">/100</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card class="metric-card" shadow="hover">
              <div class="metric-head">
                <span class="metric-icon danger">⚠</span>
                <span class="metric-title">综合风险</span>
              </div>
              <div class="metric-value metric-risk" :class="riskClass">{{ riskText }}</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card class="metric-card" shadow="hover">
              <div class="metric-head">
                <span class="metric-icon info">⚙</span>
                <span class="metric-title">故障类型</span>
              </div>
              <div class="metric-value metric-fault">{{ faultTypeText }}</div>
            </el-card>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-card class="metric-card" shadow="hover">
              <div class="metric-head">
                <span class="metric-icon shield">⛨</span>
                <span class="metric-title">置信度</span>
              </div>
              <div class="metric-value metric-confidence">{{ confidenceDisplay }}%</div>
            </el-card>
          </el-col>
        </el-row>

        <div class="chart-grid">
          <el-card shadow="hover" class="panel-card dark-panel chart-card chart-main-left">
            <div slot="header" class="panel-header">
              <span>时域特征分析</span>
              <div class="panel-actions">
                <el-tag size="mini" type="success">实时</el-tag>
              </div>
            </div>
            <single-trend-chart
              :chart-data="vibrationChartData"
              series-name="振动"
              y-axis-name="振动"
              :loading="loading"
            />
            <div class="mini-stats">
              <div class="mini-stat">
                <span>RMS</span>
                <strong>{{ formatValue(overview.latestVibration) }}</strong>
              </div>
              <div class="mini-stat">
                <span>阈值</span>
                <strong>{{ formatValue(overview.vibrationThreshold) }}</strong>
              </div>
              <div class="mini-stat">
                <span>峰值</span>
                <strong>{{ formatValue(overview.latestPeak || overview.latestVibration) }}</strong>
              </div>
            </div>
          </el-card>

          <el-card shadow="hover" class="panel-card dark-panel chart-card chart-main-right">
            <div slot="header" class="panel-header">
              <span>频域诊断分析</span>
              <div class="panel-actions">
                <el-tag size="mini" type="danger">FFT</el-tag>
              </div>
            </div>
            <single-trend-chart
              :chart-data="temperatureChartData"
              series-name="频谱"
              y-axis-name="幅值"
              :loading="loading"
            />
            <div class="mini-stats">
              <div class="mini-stat">
                <span>主频</span>
                <strong>{{ formatValue(overview.primaryFrequency) }}</strong>
              </div>
              <div class="mini-stat">
                <span>特征频率</span>
                <strong>{{ formatValue(overview.characteristicFrequency) }}</strong>
              </div>
              <div class="mini-stat">
                <span>频带能量</span>
                <strong>{{ formatValue(overview.bandEnergy) }}</strong>
              </div>
            </div>
          </el-card>

          <el-card shadow="hover" class="panel-card dark-panel chart-card chart-side-panel">
            <div slot="header" class="panel-header">
              <span>模型判别结果</span>
            </div>
            <div class="model-result-list">
              <div v-for="item in modelResults" :key="item.name" class="model-result-item">
                <div class="model-result-meta">
                  <span>{{ item.name }}</span>
                  <span>{{ item.percent }}%</span>
                </div>
                <el-progress :percentage="item.percent" :stroke-width="10" :color="item.color" :show-text="false" />
              </div>
            </div>
          </el-card>
        </div>

        <div class="lower-grid">
          <el-card shadow="hover" class="panel-card dark-panel evidence-card">
            <div slot="header" class="panel-header">
              <span>诊断证据</span>
              <div class="panel-actions">
                <el-tag size="mini" type="warning">{{ diagnosisEvidence.length }} 条</el-tag>
              </div>
            </div>
            <div class="evidence-list">
              <div v-for="(item, index) in diagnosisEvidence" :key="index" class="evidence-item">
                <div class="evidence-index">{{ index + 1 }}</div>
                <div class="evidence-content">
                  <div class="evidence-title">{{ item.title }}</div>
                  <div class="evidence-desc">{{ item.desc }}</div>
                </div>
                <el-tag size="mini" :type="item.type">{{ item.level }}</el-tag>
              </div>
            </div>
          </el-card>

          <el-card shadow="hover" class="panel-card dark-panel trend-card">
            <div slot="header" class="panel-header">
              <span>健康趋势</span>
              <div class="panel-actions">
                <el-tag size="mini" type="success">近 7 天</el-tag>
              </div>
            </div>
            <single-trend-chart
              :chart-data="healthTrendChartData"
              series-name="健康指数"
              y-axis-name="健康指数"
              :loading="loading"
            />
          </el-card>
        </div>

        <el-card shadow="hover" class="panel-card dark-panel history-card">
          <div slot="header" class="panel-header">
            <span>分析记录</span>
            <el-button size="mini" type="text">更多记录</el-button>
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
                <el-tag size="mini" :type="scope.row.status === '完成' ? 'success' : 'warning'">{{ scope.row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </main>
    </div>
  </div>
</template>

<script>
import { getMonitoringOverview } from '@/api/system/monitoring'
import SingleTrendChart from './components/SingleTrendChart'

export default {
  name: 'MonitoringData',
  components: { SingleTrendChart },
  data() {
    return {
      loading: false,
      timer: null,
      overview: {},
      deviceKeyword: '',
      vibrationChartData: { xData: [], yData: [] },
      temperatureChartData: { xData: [], yData: [] },
      healthTrendChartData: { xData: [], yData: [] },
      vibrationTable: [],
      temperatureTable: [],
      selectedDeviceCode: '',
      alertVisible: false,
      alertMessage: '',
      alertDetail: '',
      modelResults: [
        { name: '轴承外圈故障', percent: 92, color: '#ff4d4f' },
        { name: '不平衡', percent: 36, color: '#409eff' },
        { name: '不对中', percent: 24, color: '#409eff' },
        { name: '正常', percent: 4, color: '#67c23a' }
      ],
      diagnosisEvidence: [],
      historyTable: []
    }
  },
  computed: {
    filteredDevicePoints() {
      const list = this.overview.devicePoints || []
      const keyword = (this.deviceKeyword || '').trim().toLowerCase()
      if (!keyword) return list
      return list.filter(item => {
        return String(item.deviceCode || '').toLowerCase().includes(keyword) || String(item.deviceName || '').toLowerCase().includes(keyword)
      })
    },
    healthIndex() {
      const v = Number(this.overview.latestVibration)
      const vt = Number(this.overview.vibrationThreshold)
      const t = Number(this.overview.latestTemperature)
      const tt = Number(this.overview.temperatureThreshold)
      const vScore = Number.isNaN(v) || Number.isNaN(vt) ? 50 : Math.max(0, Math.min(100, 100 - (v / Math.max(vt, 1)) * 50))
      const tScore = Number.isNaN(t) || Number.isNaN(tt) ? 50 : Math.max(0, Math.min(100, 100 - (t / Math.max(tt, 1)) * 50))
      return Math.round(vScore * 0.6 + tScore * 0.4)
    },
    riskText() {
      if (this.healthIndex >= 80) return '低'
      if (this.healthIndex >= 60) return '中'
      return '高'
    },
    riskClass() {
      return this.healthIndex >= 80 ? 'risk-low' : this.healthIndex >= 60 ? 'risk-mid' : 'risk-high'
    },
    faultTypeText() {
      return this.overview.faultType || '轴承外圈故障'
    },
    confidenceDisplay() {
      const value = Number(this.overview.confidence)
      return Number.isNaN(value) ? 92 : value
    }
  },
  created() {
    this.loadOverview()
    this.timer = setInterval(() => {
      this.loadOverview()
    }, 5000)
  },
  beforeDestroy() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  },
  methods: {
    loadOverview() {
      this.loading = true
      getMonitoringOverview().then(response => {
        this.overview = response.data || {}
        const vibrationTrend = this.overview.vibrationTrend || { xData: [], yData: [] }
        const temperatureTrend = this.overview.temperatureTrend || { xData: [], yData: [] }
        const healthTrend = this.overview.healthTrend || { xData: [], yData: [] }
        this.vibrationChartData = vibrationTrend
        this.temperatureChartData = temperatureTrend
        this.healthTrendChartData = healthTrend
        const points = this.overview.devicePoints || []
        this.vibrationTable = (vibrationTrend.xData || []).map((item, index) => ({
          deviceCode: this.selectedDeviceCode || (points[index] ? points[index].deviceCode : 'N/A'),
          vibrationValue: vibrationTrend.yData[index],
          sampleTime: item
        }))
        this.temperatureTable = (temperatureTrend.xData || []).map((item, index) => ({
          deviceCode: this.selectedDeviceCode || (points[index] ? points[index].deviceCode : 'N/A'),
          temperatureValue: temperatureTrend.yData[index],
          collectionTime: item
        }))
        this.diagnosisEvidence = this.overview.diagnosisEvidence || [
          { title: '1X/2X 倍频异常', desc: '存在明显倍频成分，提示转子不平衡或轴系偏心。', type: 'danger', level: '高' },
          { title: '频域升高', desc: '中高频能量增强，可能与轴承外圈接触损伤相关。', type: 'warning', level: '中' },
          { title: '温升趋势', desc: '监测周期内温度稳步抬升，需关注润滑状态。', type: 'warning', level: '中' }
        ]
        this.historyTable = this.overview.analysisRecords || [
          { sampleTime: '2025-05-20 10:24:36', modelVersion: 'V3.2.1', diagnosisResult: '轴承外圈故障', confidence: 92, healthIndex: 58, riskLevel: '高', operator: '运维工程师', status: '完成' },
          { sampleTime: '2025-05-20 09:24:12', modelVersion: 'V3.2.1', diagnosisResult: '轴承外圈故障', confidence: 90, healthIndex: 61, riskLevel: '高', operator: '运维工程师', status: '完成' },
          { sampleTime: '2025-05-20 08:24:05', modelVersion: 'V3.2.0', diagnosisResult: '不平衡', confidence: 45, healthIndex: 65, riskLevel: '中', operator: '运维工程师', status: '完成' }
        ]
        this.checkAlerts()
      }).finally(() => {
        this.loading = false
      })
    },
    handleDeviceClick(row) {
      this.selectedDeviceCode = row.deviceCode
      this.applyDeviceFilter(row.deviceCode)
    },
    applyDeviceFilter(deviceCode) {
      const device = (this.overview.devicePoints || []).find(item => item.deviceCode === deviceCode)
      const vibrationTrend = this.overview.vibrationTrend || { xData: [], yData: [] }
      const temperatureTrend = this.overview.temperatureTrend || { xData: [], yData: [] }
      this.vibrationChartData = vibrationTrend
      this.temperatureChartData = temperatureTrend
      this.vibrationTable = device ? [{
        deviceCode: device.deviceCode,
        vibrationValue: device.vibrationValue,
        sampleTime: this.overview.updateTime
      }] : []
      this.temperatureTable = device ? [{
        deviceCode: device.deviceCode,
        temperatureValue: device.temperatureValue,
        collectionTime: this.overview.updateTime
      }] : []
    },
    openThresholdSetting(type) {
      const value = type === 'vibration' ? this.overview.vibrationThreshold : this.overview.temperatureThreshold
      this.$prompt('请输入新的阈值', '阈值设置', {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputValue: value,
        inputPattern: /^\d+(\.\d+)?$/,
        inputErrorMessage: '请输入有效数字'
      }).then(({ value }) => {
        if (type === 'vibration') this.overview.vibrationThreshold = Number(value)
        else this.overview.temperatureThreshold = Number(value)
        this.$modal.msgSuccess('阈值已更新，若需持久化请同步后端配置。')
      }).catch(() => {})
    },
    checkAlerts() {
      const v = Number(this.overview.latestVibration)
      const vt = Number(this.overview.vibrationThreshold)
      const t = Number(this.overview.latestTemperature)
      const tt = Number(this.overview.temperatureThreshold)
      const alerts = []
      if (!Number.isNaN(v) && !Number.isNaN(vt) && v >= vt) alerts.push(`振动告警：${v.toFixed(2)} >= ${vt.toFixed(2)}`)
      if (!Number.isNaN(t) && !Number.isNaN(tt) && t >= tt) alerts.push(`温度告警：${t.toFixed(2)} >= ${tt.toFixed(2)}`)
      if (alerts.length) {
        this.alertMessage = '阈值超限'
        this.alertDetail = alerts.join(' | ')
        this.alertVisible = true
      }
    },
    formatValue(value) {
      if (value === null || value === undefined || value === '') return '--'
      const num = Number(value)
      return Number.isNaN(num) ? value : num.toFixed(2)
    },
    statusClass(value, threshold) {
      if (value === null || value === undefined || threshold === null || threshold === undefined) return 'status-normal'
      return Number(value) >= Number(threshold) ? 'status-alert' : 'status-normal'
    },
    deviceStatusType(item) {
      const value = Number(item.vibrationValue)
      const threshold = Number(this.overview.vibrationThreshold)
      if (Number.isNaN(value) || Number.isNaN(threshold)) return 'info'
      if (value >= threshold) return 'danger'
      if (value >= threshold * 0.8) return 'warning'
      return 'success'
    },
    deviceStatusText(item) {
      const type = this.deviceStatusType(item)
      return type === 'danger' ? '异常' : type === 'warning' ? '预警' : '正常'
    },
    riskTagType(level) {
      return level === '高' ? 'danger' : level === '中' ? 'warning' : 'success'
    }
  }
}
</script>

<style scoped>
.monitoring-page { padding: 0; }
.diagnosis-dashboard-page { min-height: calc(100vh - 84px); background: #071623; color: #d8e7f3; }
.dashboard-shell {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  min-height: calc(100vh - 84px);
  padding: 16px;
  box-sizing: border-box;
}
.dashboard-sidebar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: linear-gradient(180deg, #07111d 0%, #0b2234 100%);
  border: 1px solid rgba(82, 171, 255, 0.16);
  border-radius: 14px;
  padding: 14px;
  box-shadow: inset 0 0 20px rgba(0, 255, 255, 0.04), 0 8px 24px rgba(0, 0, 0, 0.22);
}
.sidebar-brand { display: flex; align-items: center; gap: 12px; padding: 6px 4px 14px; border-bottom: 1px solid rgba(255,255,255,0.08); }
.brand-icon { width: 44px; height: 44px; border-radius: 12px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #57d1ff, #3b82f6); color: #06111f; font-size: 22px; font-weight: 800; box-shadow: 0 0 18px rgba(87, 209, 255, 0.35); }
.brand-title { font-size: 18px; font-weight: 700; color: #eef8ff; }
.brand-subtitle { font-size: 12px; color: #7ea8c8; margin-top: 2px; }
.sidebar-card { flex: 1; display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.sidebar-card-title { font-size: 14px; font-weight: 700; color: #cfe7fb; }
.sidebar-search :deep(.el-input__inner) { background: rgba(255,255,255,0.04); border-color: rgba(87, 209, 255, 0.12); color: #eef8ff; }
.device-tree { flex: 1; overflow: auto; display: flex; flex-direction: column; gap: 8px; padding-right: 2px; }
.device-item { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px; border-radius: 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(87, 209, 255, 0.08); cursor: pointer; transition: all .2s ease; }
.device-item:hover, .device-item.active { background: rgba(53, 143, 255, 0.16); border-color: rgba(87, 209, 255, 0.38); transform: translateY(-1px); }
.device-item-main { display: flex; align-items: center; gap: 10px; min-width: 0; }
.device-icon { color: #63d6ff; font-size: 16px; }
.device-texts { min-width: 0; }
.device-name { font-size: 13px; font-weight: 700; color: #f2fbff; }
.device-desc { font-size: 12px; color: #8fb5cf; margin-top: 2px; }
.empty-tip { color: #7ea8c8; text-align: center; padding: 18px 0; font-size: 12px; }
.sidebar-footer { padding-top: 10px; border-top: 1px solid rgba(255,255,255,0.08); }
.full-width-btn { width: 100%; }
.dashboard-main { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.dashboard-topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px; background: linear-gradient(180deg, rgba(7,17,29,0.96), rgba(8,29,46,0.94)); border: 1px solid rgba(82, 171, 255, 0.16); border-radius: 14px; box-shadow: inset 0 0 20px rgba(0, 255, 255, 0.04); }
.page-title { font-size: 22px; font-weight: 800; color: #f3fbff; }
.page-subtitle { margin-top: 4px; font-size: 12px; color: #7ea8c8; }
.topbar-actions { display: flex; align-items: center; gap: 10px; color: #b9d2e7; }
.topbar-time { font-size: 12px; color: #86afcb; }
.metrics-row, .chart-row { margin: 0; }
.metric-card, .panel-card { background: linear-gradient(180deg, #07131f 0%, #0b2234 100%); border: 1px solid rgba(82, 171, 255, 0.16); color: #d8e7f3; border-radius: 14px; }
.metric-card { min-height: 120px; }
.metric-head { display: flex; align-items: center; gap: 8px; color: #b8d3e7; font-size: 13px; }
.metric-icon { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 50%; font-size: 14px; }
.metric-icon.pulse { background: rgba(87, 209, 255, 0.12); color: #57d1ff; }
.metric-icon.danger { background: rgba(255, 91, 91, 0.12); color: #ff6b6b; }
.metric-icon.info { background: rgba(56, 130, 246, 0.12); color: #63a4ff; }
.metric-icon.shield { background: rgba(102, 255, 204, 0.12); color: #66ffcc; }
.metric-value { margin-top: 14px; line-height: 1; font-weight: 800; color: #f3fbff; }
.metric-score { font-size: 34px; }
.metric-risk { font-size: 22px; }
.metric-fault { font-size: 20px; color: #ffb36b; }
.metric-confidence { font-size: 22px; color: #66ffcc; }
.metric-sub { margin-top: 4px; color: #7ea8c8; }
.risk-low { color: #67c23a; }
.risk-mid { color: #e6a23c; }
.risk-high { color: #f56c6c; }
.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1.25fr) 300px;
  gap: 16px;
  align-items: start;
}
.chart-card { min-height: 430px; }
.chart-main-left, .chart-main-right { min-width: 0; }
.chart-side-panel { width: 300px; }
.dark-panel { box-shadow: 0 8px 24px rgba(0, 0, 0, 0.24); }
.panel-header { display: flex; justify-content: space-between; align-items: center; color: #f0f8ff; font-weight: 700; }
.panel-actions { display: flex; align-items: center; gap: 8px; }
.mini-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 12px; }
.mini-stat { background: rgba(255,255,255,0.03); border: 1px solid rgba(87, 209, 255, 0.08); border-radius: 10px; padding: 10px 12px; }
.mini-stat span { display: block; font-size: 12px; color: #7ea8c8; }
.mini-stat strong { display: block; margin-top: 6px; font-size: 18px; color: #f3fbff; }
.model-result-list { display: flex; flex-direction: column; gap: 14px; }
.model-result-item { background: rgba(255,255,255,0.03); border: 1px solid rgba(87, 209, 255, 0.08); border-radius: 10px; padding: 12px; }
.model-result-meta { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; font-size: 12px; color: #d8e7f3; }
.lower-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1.15fr);
  gap: 16px;
}
.evidence-card, .trend-card { min-height: 300px; }
.evidence-list { display: flex; flex-direction: column; gap: 10px; }
.evidence-item { display: flex; align-items: flex-start; gap: 10px; background: rgba(255,255,255,0.03); border: 1px solid rgba(87, 209, 255, 0.08); border-radius: 10px; padding: 10px 12px; }
.evidence-index { width: 28px; height: 28px; border-radius: 8px; display: flex; align-items: center; justify-content: center; background: rgba(87,209,255,0.12); color: #57d1ff; font-weight: 800; flex-shrink: 0; }
.evidence-content { flex: 1; min-width: 0; }
.evidence-title { font-weight: 700; color: #f4fbff; }
.evidence-desc { margin-top: 4px; font-size: 12px; color: #86afcb; line-height: 1.6; }
.history-card { margin-bottom: 0; }
:deep(.el-card__header) { background: transparent; border-bottom-color: rgba(255,255,255,0.08); }
:deep(.el-table) { background: transparent; color: #d8e7f3; }
:deep(.el-table th), :deep(.el-table tr), :deep(.el-table td) { background: transparent !important; }
:deep(.el-table::before) { background-color: rgba(87, 209, 255, 0.08); }
:deep(.history-table .el-table__body-wrapper) { background: rgba(255,255,255,0.02); }
@media (max-width: 1600px) {
  .chart-grid { grid-template-columns: 1fr 1fr; }
  .chart-side-panel { width: auto; }
}
@media (max-width: 1400px) {
  .dashboard-shell { grid-template-columns: 1fr; }
  .dashboard-sidebar { order: 2; }
  .dashboard-main { order: 1; }
}
@media (max-width: 1200px) {
  .mini-stats { grid-template-columns: 1fr; }
  .dashboard-topbar { flex-direction: column; align-items: flex-start; }
  .chart-grid,
  .lower-grid { grid-template-columns: 1fr; }
}
</style>
