<template>
  <div class="app-container vibration-analysis-page">
    <div class="hero-card">
      <div class="hero-left">
        <div class="hero-badge">Vibration Diagnosis</div>
        <h2>主扇风机振动智能分析</h2>
        <p>从 `get/got` 自动加载 `.mat` 文件，调用后端接口完成诊断与频谱分析。</p>
      </div>
      <div class="hero-right">
        <el-tag effect="dark" type="info">{{ matFiles.length }} 个文件可选</el-tag>
        <el-tag effect="dark" :type="statusTagType">{{ statusText }}</el-tag>
      </div>
    </div>

    <el-card shadow="never" class="control-card">
      <div slot="header" class="section-title">分析输入</div>
      <el-form :inline="true" :model="queryForm" size="small" class="toolbar-form">
        <el-form-item label="MAT文件">
          <el-select
            v-model="queryForm.selectedMat"
            filterable
            placeholder="请选择 get/got 下的 .mat 文件"
            style="width: 460px;"
            clearable
          >
            <el-option v-for="item in matFiles" :key="item.name" :label="item.label" :value="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="手动文件名">
          <el-input
            v-model="queryForm.fileName"
            placeholder="不带后缀的文件名"
            clearable
            style="width: 280px;"
            @input="queryForm.selectedMat = ''"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-folder-opened" @click="loadMatFiles">刷新文件列表</el-button>
          <el-button type="success" icon="el-icon-data-analysis" @click="handleAnalyzeFromSidecar" :loading="loadingAnalyze">开始分析</el-button>
          <el-button icon="el-icon-refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16" class="metrics-row">
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">诊断结果</div>
          <div class="metric-value" :class="statusClass">{{ diagnosis }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">模型置信度</div>
          <div class="metric-value">{{ confidenceText }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">RMS / Peak</div>
          <div class="metric-value">{{ metricsText }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">数据源</div>
          <div class="metric-value metric-source">{{ currentSourceName }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="charts-row">
      <el-col :span="12">
        <el-card shadow="hover" class="chart-card">
          <div slot="header" class="section-title">时域波形</div>
          <div ref="timeChart" class="chart-box"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover" class="chart-card">
          <div slot="header" class="section-title">频域频谱</div>
          <div ref="freqChart" class="chart-box"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" class="result-card">
      <div slot="header" class="section-title">分析详情</div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="诊断标签">{{ result.label || result.diagnosis || result.diagnosisResult || '-' }}</el-descriptions-item>
        <el-descriptions-item label="模型置信度">{{ confidenceText }}</el-descriptions-item>
        <el-descriptions-item label="RMS">{{ formatNumber(result.rms) }}</el-descriptions-item>
        <el-descriptions-item label="峰值">{{ formatNumber(result.peak) }}</el-descriptions-item>
        <el-descriptions-item label="谱重心频率">{{ formatNumber(result.centroidFrequency || result.centroid_frequency) }}</el-descriptions-item>
        <el-descriptions-item label="均方根频率">{{ formatNumber(result.rmsFrequency || result.rms_frequency) }}</el-descriptions-item>
        <el-descriptions-item label="时域点数">{{ waveformLength }}</el-descriptions-item>
        <el-descriptions-item label="频域点数">{{ spectrumLength }}</el-descriptions-item>
        <el-descriptions-item label="来源文件" :span="2">{{ currentSourceName }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { analyzeVibrationFromSidecar, fetchMatFiles } from '@/api/system/vibrationAnalysis'

export default {
  name: 'VibrationAnalysis',
  data() {
    return {
      loadingAnalyze: false,
      queryForm: {
        fileName: 'CH1_20260515_085738_sr7497_rpm3000_UN_7500',
        selectedMat: 'CH1_20260515_085738_sr7497_rpm3000_UN_7500'
      },
      matFiles: [],
      sampleRate: 10240,
      signal: [],
      diagnosis: '等待分析',
      statusClass: 'warning',
      confidence: 0,
      result: {},
      timeChart: null,
      freqChart: null
    }
  },
  computed: {
    statusText() {
      return this.diagnosis || '待分析'
    },
    statusTagType() {
      if (this.statusClass === 'normal') return 'success'
      if (this.statusClass === 'danger') return 'danger'
      return 'warning'
    },
    confidenceText() {
      const value = Number(this.confidence)
      if (Number.isNaN(value)) return '-'
      return `${value.toFixed(2)}%`
    },
    currentSourceName() {
      return this.getCurrentFileName() || '-'
    },
    metricsText() {
      const rms = this.formatNumber(this.result.rms)
      const peak = this.formatNumber(this.result.peak)
      return `${rms} / ${peak}`
    },
    waveformLength() {
      return Array.isArray(this.result.time_data || this.result.waveform) ? (this.result.time_data || this.result.waveform).length : this.signal.length
    },
    spectrumLength() {
      const data = this.result.freq_data || this.result.spectrum || []
      return Array.isArray(data) ? data.length : 0
    }
  },
  mounted() {
    this.generateDemoSignal()
    this.initCharts()
    this.loadMatFiles()
    this.renderTimeChart()
    this.renderFreqChart([], [])
    window.addEventListener('resize', this.resizeCharts)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeCharts)
    if (this.timeChart) this.timeChart.dispose()
    if (this.freqChart) this.freqChart.dispose()
  },
  methods: {
    formatNumber(value) {
      const num = Number(value)
      if (Number.isNaN(num)) return '-'
      return num.toFixed(4)
    },
    generateDemoSignal() {
      const data = []
      const sampleCount = 1024
      const sampleRate = Number(this.sampleRate) || 10240
      for (let i = 0; i < sampleCount; i++) {
        const t = i / sampleRate
        data.push(Math.sin(2 * Math.PI * 50 * t) + 0.25 * Math.sin(2 * Math.PI * 100 * t))
      }
      this.signal = data
    },
    getCurrentFileName() {
      return (this.queryForm.selectedMat || this.queryForm.fileName || '').trim()
    },
    refreshCurrentSelection() {
      const fileName = this.getCurrentFileName()
      if (fileName) {
        this.queryForm.fileName = fileName
      }
    },
    normalizeApiList(res) {
      const raw = res && res.data ? res.data : res
      if (Array.isArray(raw)) return raw
      if (raw && Array.isArray(raw.rows)) return raw.rows
      if (raw && Array.isArray(raw.data)) return raw.data
      return []
    },
    async loadMatFiles() {
      try {
        const res = await fetchMatFiles()
        const data = this.normalizeApiList(res)
        this.matFiles = data.map(item => ({
          name: item.name || item.filename || item.fileName,
          label: item.label || item.name || item.filename || item.fileName
        })).filter(item => item.name)
        if (!this.queryForm.selectedMat && this.matFiles.length > 0) {
          this.queryForm.selectedMat = this.matFiles[0].name
        }
        this.refreshCurrentSelection()
      } catch (e) {
        this.$modal.msgError((e && e.response && e.response.data && e.response.data.detail) || '获取 MAT 文件列表失败')
      }
    },
    initCharts() {
      this.timeChart = echarts.init(this.$refs.timeChart)
      this.freqChart = echarts.init(this.$refs.freqChart)
    },
    renderTimeChart() {
      const timeAxis = this.signal.map((_, i) => i / this.sampleRate)
      const option = {
        title: { text: '时域波形图' },
        tooltip: { trigger: 'axis' },
        grid: { left: 45, right: 20, top: 50, bottom: 35 },
        xAxis: { type: 'value', name: '时间(s)' },
        yAxis: { type: 'value', name: '幅值' },
        series: [{ type: 'line', smooth: true, showSymbol: false, data: timeAxis.map((t, i) => [t, this.signal[i]]) }]
      }
      if (this.timeChart) this.timeChart.setOption(option)
    },
    renderFreqChart(freqAxis, spectrum) {
      const option = {
        title: { text: '频域频谱图' },
        tooltip: { trigger: 'axis' },
        grid: { left: 45, right: 20, top: 50, bottom: 35 },
        xAxis: { type: 'value', name: 'Hz' },
        yAxis: { type: 'value', name: '幅值' },
        series: [{ type: 'line', smooth: true, showSymbol: false, data: freqAxis.map((f, i) => [f, spectrum[i] ?? 0]) }]
      }
      if (this.freqChart) this.freqChart.setOption(option)
    },
    normalizeAnalysisResult(res) {
      const raw = res && res.data !== undefined ? res.data : res
      const data = raw && raw.data && typeof raw.data === 'object' ? raw.data : raw
      return data || {}
    },
    async handleAnalyzeFromSidecar() {
      this.loadingAnalyze = true
      try {
        const fileName = this.getCurrentFileName()
        if (!fileName) {
          this.$modal.msgWarning('请先选择或输入 MAT 文件名')
          return
        }
        const res = await analyzeVibrationFromSidecar(fileName)
        const data = this.normalizeAnalysisResult(res)
        this.result = data
        this.diagnosis = data.label || data.diagnosis || data.diagnosisResult || '未知类别'
        this.confidence = data.confidence != null ? Number(data.confidence) : this.confidence
        this.statusClass = this.getStatusClass(this.diagnosis)
        this.signal = data.time_data || data.waveform || []
        this.sampleRate = Number(data.sampleRate || data.sample_rate || 10240)
        this.renderTimeChart()
        this.renderFreqChart(data.freq_axis || data.frequencyAxis || [], data.freq_data || data.spectrum || [])
        this.$modal.msgSuccess('分析完成')
      } catch (e) {
        this.$modal.msgError((e && e.response && e.response.data && e.response.data.detail) || '分析失败')
      } finally {
        this.loadingAnalyze = false
      }
    },
    async handleBatchAnalyze() {
      this.$modal.msgInfo('当前页面仅保留单文件分析入口')
    },
    handleReset() {
      this.queryForm = {
        fileName: 'CH1_20260515_085738_sr7497_rpm3000_UN_7500',
        selectedMat: 'CH1_20260515_085738_sr7497_rpm3000_UN_7500'
      }
      this.sampleRate = 10240
      this.generateDemoSignal()
      this.result = {}
      this.diagnosis = '等待分析'
      this.confidence = 0
      this.statusClass = 'warning'
      this.renderTimeChart()
      this.renderFreqChart([], [])
    },
    getStatusClass(text) {
      const value = String(text || '')
      if (value.includes('正常')) return 'normal'
      if (value.includes('预警') || value.includes('不良') || value.includes('异常') || value.includes('故障') || value.includes('失败')) return 'danger'
      return 'warning'
    },
    resizeCharts() {
      if (this.timeChart) this.timeChart.resize()
      if (this.freqChart) this.freqChart.resize()
    }
  }
}
</script>

<style scoped>
.vibration-analysis-page {
  padding: 12px;
  background: linear-gradient(180deg, #f5f7fb 0%, #ffffff 100%);
}
.hero-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 22px 24px;
  margin-bottom: 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, #1f2d3d 0%, #2f5bea 100%);
  color: #fff;
  box-shadow: 0 12px 30px rgba(47, 91, 234, 0.18);
}
.hero-left h2 {
  margin: 8px 0 6px;
  font-size: 24px;
}
.hero-left p {
  margin: 0;
  opacity: 0.88;
}
.hero-badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255,255,255,0.16);
  font-size: 12px;
  letter-spacing: 0.5px;
}
.hero-right {
  display: flex;
  gap: 10px;
  align-items: center;
}
.control-card,
.result-card,
.chart-card,
.metric-card {
  border-radius: 16px;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}
.section-title {
  font-weight: 600;
  color: #1f2937;
}
.metrics-row {
  margin: 16px 0;
}
.metric-card {
  min-height: 110px;
}
.metric-label {
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 10px;
}
.metric-value {
  font-size: 18px;
  font-weight: 700;
  color: #111827;
  word-break: break-all;
}
.metric-source {
  font-size: 14px;
  font-weight: 600;
}
.chart-box {
  width: 100%;
  height: 360px;
}
.toolbar-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
.normal { color: #67c23a; }
.warning { color: #e6a23c; }
.danger { color: #f56c6c; }
.mb16 { margin-bottom: 16px; }
</style>
