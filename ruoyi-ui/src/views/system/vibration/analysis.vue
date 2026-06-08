<template>
  <div class="app-container vibration-analysis-page">
    <div class="hero-card">
      <div class="hero-left">
        <div class="hero-badge">Vibration Diagnosis</div>
        <h2>采煤机摇臂振动智能分析</h2>
        <p>从 `get/got` 自动加载振动数据完成诊断与频谱分析。</p>
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
            popper-class="dark-sidecar-select"
          >
            <el-option v-for="item in matFiles" :key="item.name" :label="item.label" :value="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="诊断模型">
          <el-select v-model="queryForm.modelType" style="width: 180px;" popper-class="dark-sidecar-select">
            <el-option label="齿轮诊断模型" value="gear" />
            <el-option label="轴承诊断模型" value="bearing" />
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
          <el-upload
            :show-file-list="false"
            :before-upload="handleUploadAnalyze"
            action=""
            accept=".mat"
            style="display: inline-block; margin-left: 8px;"
          >
            <el-button icon="el-icon-upload2" :loading="loadingUpload">上传并分析</el-button>
          </el-upload>
          <el-button icon="el-icon-refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="10" class="metrics-row compact-grid">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card compact-card">
          <div class="metric-label">诊断结果</div>
          <div class="metric-value" :class="statusClass">{{ diagnosis }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card compact-card">
          <div class="metric-label">模型置信度</div>
          <div class="metric-value">{{ confidenceText }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card compact-card">
          <div class="metric-label">RMS / Peak</div>
          <div class="metric-value">{{ metricsText }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="metric-card compact-card">
          <div class="metric-label">数据源</div>
          <div class="metric-value metric-source">{{ currentSourceName }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="10" class="charts-row compact-grid">
      <el-col :xs="24" :md="12">
        <el-card shadow="hover" class="chart-card compact-chart-card">
          <div slot="header" class="section-title">时域波形</div>
          <div ref="timeChart" class="chart-box compact-chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card shadow="hover" class="chart-card compact-chart-card">
          <div slot="header" class="section-title">频域频谱</div>
          <div ref="freqChart" class="chart-box compact-chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" class="result-card compact-result-card">
      <div slot="header" class="section-title">分析详情</div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="诊断标签">{{ result.label || result.diagnosis || result.diagnosisResult || '-' }}</el-descriptions-item>
        <el-descriptions-item label="模型置信度">{{ confidenceText }}</el-descriptions-item>
        <el-descriptions-item label="RMS">{{ formatNumber(result.rms) }}</el-descriptions-item>
        <el-descriptions-item label="峰值">{{ formatNumber(result.peak) }}</el-descriptions-item>
        <el-descriptions-item label="闭集预测">{{ result.closedPrediction || result.closed_prediction || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Unknown 比例">{{ formatNumber(result.unknownRatio || result.unknown_ratio) }}</el-descriptions-item>
        <el-descriptions-item label="文件一致性">{{ formatNumber(result.segmentConsistency || result.segment_consistency) }}</el-descriptions-item>
        <el-descriptions-item label="Mean Mahalanobis">{{ formatNumber(result.meanMahalanobis || result.mean_mahalanobis) }}</el-descriptions-item>
        <el-descriptions-item label="告警等级">{{ result.alarmLevel || result.alarm_level || '-' }}</el-descriptions-item>
        <el-descriptions-item label="决策原因">{{ result.decision_reason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="时域点数">{{ waveformLength }}</el-descriptions-item>
        <el-descriptions-item label="频域点数">{{ spectrumLength }}</el-descriptions-item>
        <el-descriptions-item label="来源文件" :span="2">{{ currentSourceName }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="hover" class="debug-card">
      <div slot="header" class="debug-head">
        <span class="section-title">原始接口响应</span>
        <el-button size="mini" plain icon="el-icon-document-copy" @click="copyRawResponse">复制 JSON</el-button>
      </div>
      <div class="debug-meta">{{ rawMetaText }}</div>
      <pre class="debug-pre">{{ rawResponseText }}</pre>
    </el-card>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { analyzeUploadedMatFromSidecar, analyzeVibrationFromSidecar, fetchMatFiles } from '@/api/system/vibrationAnalysis'

export default {
  name: 'VibrationAnalysis',
  data() {
    return {
      loadingAnalyze: false,
      loadingUpload: false,
      queryForm: {
        fileName: '',
        selectedMat: '',
        modelType: 'gear'
      },
      matFiles: [],
      sampleRate: 10240,
      signal: [],
      diagnosis: '等待分析',
      statusClass: 'warning',
      confidence: 0,
      result: {},
      rawResponse: null,
      rawMeta: {
        url: '',
        method: '',
        status: '',
        elapsedMs: null
      },
      timeChart: null,
      freqChart: null,
      inferenceWs: null
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
      return this.result.sourceName || this.result.source_name || this.getCurrentFileName() || '-'
    },
    rawResponseText() {
      if (!this.rawResponse) return '-'
      try {
        return JSON.stringify(this.rawResponse, null, 2)
      } catch (error) {
        return String(this.rawResponse)
      }
    },
    rawMetaText() {
      const meta = this.rawMeta || {}
      return [
        `URL: ${meta.url || '-'}`,
        `Method: ${meta.method || '-'}`,
        `Status: ${meta.status || '-'}`,
        `Elapsed: ${meta.elapsedMs != null ? `${meta.elapsedMs} ms` : '-'}`
      ].join(' | ')
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
    this.connectInferenceWs()
    window.addEventListener('resize', this.resizeCharts)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeCharts)
    this.closeInferenceWs()
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
        if (!this.queryForm.selectedMat && !this.queryForm.fileName && this.matFiles.length > 0) {
          this.queryForm.selectedMat = this.matFiles[0].name
        }
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
        animation: false,
        grid: { left: 48, right: 16, top: 12, bottom: 28 },
        tooltip: { trigger: 'axis', confine: true },
        xAxis: { type: 'value', name: '时间(s)', nameTextStyle: { color: '#475569', fontSize: 10 }, axisLabel: { color: '#64748b', fontSize: 10 }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
        yAxis: { type: 'value', name: '幅值', nameTextStyle: { color: '#475569', fontSize: 10 }, axisLabel: { color: '#64748b', fontSize: 10 }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
        series: [{ type: 'line', smooth: false, showSymbol: false, data: timeAxis.map((t, i) => [t, this.signal[i]]), lineStyle: { width: 1.2, color: '#2563eb' }, areaStyle: { opacity: 0.10, color: '#2563eb' } }]
      }
      if (this.timeChart) this.timeChart.setOption(option)
    },
    renderFreqChart(freqAxis, spectrum) {
      const option = {
        animation: false,
        grid: { left: 48, right: 16, top: 12, bottom: 28 },
        tooltip: { trigger: 'axis', confine: true },
        xAxis: { type: 'value', name: 'Hz', nameTextStyle: { color: '#475569', fontSize: 10 }, axisLabel: { color: '#64748b', fontSize: 10 }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
        yAxis: { type: 'value', name: '幅值', nameTextStyle: { color: '#475569', fontSize: 10 }, axisLabel: { color: '#64748b', fontSize: 10 }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
        series: [{ type: 'line', smooth: false, showSymbol: false, data: freqAxis.map((f, i) => [f, spectrum[i] == null ? 0 : spectrum[i]]), lineStyle: { width: 1.2, color: '#d97706' }, areaStyle: { opacity: 0.08, color: '#d97706' } }]
      }
      if (this.freqChart) this.freqChart.setOption(option)
    },
    normalizeAnalysisResult(res) {
      const raw = res && res.data !== undefined ? res.data : res
      const data = raw && raw.data && typeof raw.data === 'object' ? raw.data : raw || {}
      return {
        ...data,
        label: data.label || data.diagnosis || data.diagnosisResult,
        diagnosis: data.diagnosis || data.label || data.diagnosisResult,
        confidence: data.confidence != null ? data.confidence : data.confidenceRate,
        time_data: data.time_data || data.waveform || data.signal || [],
        time_axis: data.time_axis || data.timeAxis || [],
        freq_data: data.freq_data || data.spectrum || [],
        freq_axis: data.freq_axis || data.frequencyAxis || data.frequency_axis || [],
        sampleRate: data.sampleRate || data.sample_rate || data.sample_rate_hz || 10240
      }
    },
    async handleAnalyzeFromSidecar() {
      this.loadingAnalyze = true
      try {
        const fileName = this.getCurrentFileName()
        if (!fileName) {
          this.$modal.msgWarning('请先选择或输入 MAT 文件名')
          return
        }
        const res = await analyzeVibrationFromSidecar(fileName, this.queryForm.modelType)
        const normalized = this.normalizeAnalysisResult(res)
        this.captureRawResponse('GET', '/analyze', res)
        this.applyAnalysisResult(normalized)
        this.$modal.msgSuccess('分析完成')
      } catch (e) {
        this.captureRawResponse('GET', '/analyze', e && e.response ? e.response : e)
        this.$modal.msgError((e && e.response && e.response.data && e.response.data.detail) || '分析失败')
      } finally {
        this.loadingAnalyze = false
      }
    },
    async handleUploadAnalyze(file) {
      this.loadingUpload = true
      try {
        const res = await analyzeUploadedMatFromSidecar(file, this.queryForm.modelType)
        const normalized = this.normalizeAnalysisResult(res)
        this.captureRawResponse('POST', '/analyze/upload', res)
        this.applyAnalysisResult(normalized)
        this.$modal.msgSuccess('上传分析完成')
      } catch (e) {
        this.captureRawResponse('POST', '/analyze/upload', e && e.response ? e.response : e)
        this.$modal.msgError((e && e.response && e.response.data && e.response.data.detail) || '上传分析失败')
      } finally {
        this.loadingUpload = false
      }
      return false
    },
    captureRawResponse(method, url, res) {
      this.rawMeta = {
        method,
        url,
        status: res && res.status != null ? res.status : res && res.statusCode != null ? res.statusCode : 'unknown',
        elapsedMs: res && res.elapsedMs != null ? res.elapsedMs : null
      }
      this.rawResponse = res && res.data !== undefined ? res.data : res
    },
    copyRawResponse() {
      const text = this.rawResponseText
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text)
          .then(() => this.$modal.msgSuccess('已复制原始 JSON'))
          .catch(() => this.$modal.msgError('复制失败'))
        return
      }
      this.$modal.msgWarning('当前浏览器不支持复制')
    },
    getInferenceWsUrl() {
      const base = process.env.VUE_APP_INFERENCE_SERVICE_URL || 'http://127.0.0.1:5001'
      return base.replace(/^http/, 'ws') + '/ws'
    },
    connectInferenceWs() {
      this.closeInferenceWs()
      try {
        const url = this.getInferenceWsUrl()
        this.inferenceWs = new WebSocket(url)
        this.inferenceWs.onopen = () => {}
        this.inferenceWs.onmessage = (evt) => {
          try {
            const msg = JSON.parse(evt.data)
            this.handleInferenceMessage(msg)
          } catch (e) { /* ignore parse errors */ }
        }
        this.inferenceWs.onerror = () => {}
        this.inferenceWs.onclose = () => {
          this.inferenceWs = null
          setTimeout(() => { this.connectInferenceWs() }, 5000)
        }
      } catch (e) { /* ws not available */ }
    },
    closeInferenceWs() {
      if (this.inferenceWs) {
        this.inferenceWs.onclose = null
        this.inferenceWs.close()
        this.inferenceWs = null
      }
    },
    handleInferenceMessage(msg) {
      if (!msg || msg.type !== 'auto_analysis' || !msg.success || !msg.data) return
      const data = this.normalizeAnalysisResult({ data: msg.data })
      this.applyAnalysisResult(data)
      this.captureRawResponse('WS', this.getInferenceWsUrl(), { success: true, data: msg.data })
    },
    applyAnalysisResult(data) {
      this.result = data
      this.diagnosis = data.label || data.diagnosis || data.diagnosisResult || '未知类别'
      this.confidence = data.confidence != null ? Number(data.confidence) : this.confidence
      this.statusClass = this.getStatusClass(this.diagnosis)
      this.signal = data.time_data || data.waveform || []
      this.sampleRate = Number(data.sampleRate || data.sample_rate || 10240)
      this.renderTimeChart()
      this.renderFreqChart(data.freq_axis || data.frequencyAxis || [], data.freq_data || data.spectrum || [])
    },
    async handleBatchAnalyze() {
      this.$modal.msgInfo('当前页面仅保留单文件分析入口')
    },
    handleReset() {
      this.queryForm = {
        fileName: '',
        selectedMat: '',
        modelType: 'gear'
      }
      this.sampleRate = 10240
      this.generateDemoSignal()
      this.result = {}
      this.rawResponse = null
      this.rawMeta = {
        url: '',
        method: '',
        status: '',
        elapsedMs: null
      }
      this.diagnosis = '等待分析'
      this.confidence = 0
      this.statusClass = 'warning'
      this.loadingUpload = false
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
/* =====================================================================
   页面容器 - 深色工业风背景
   ===================================================================== */
.vibration-analysis-page {
  padding: 8px 12px;
  height: calc(100vh - 84px);
  background: linear-gradient(180deg, #07131f 0%, #0a1c2d 100%);
  overflow-y: auto;
}

/* =====================================================================
   Hero 卡片 - 保持蓝色渐变但调整以融入深色主题
   ===================================================================== */
.hero-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  margin-bottom: 10px;
  border-radius: 10px;
  background: linear-gradient(135deg, #0d2137 0%, #1a3a5c 100%);
  border: 1px solid rgba(87, 209, 255, 0.15);
  color: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
}
.hero-left h2 {
  margin: 6px 0 4px;
  font-size: 20px;
  color: #f4fbff;
}
.hero-left p {
  margin: 0;
  opacity: 0.75;
  font-size: 12px;
  color: #b0c8da;
}
.hero-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(255,255,255,0.12);
  font-size: 11px;
  letter-spacing: 0.5px;
  color: #8adfff;
}
.hero-right {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* =====================================================================
   通用卡片 - 深色半透明风格
   ===================================================================== */
.control-card,
.result-card,
.debug-card,
.chart-card,
.metric-card {
  border-radius: 10px;
  border: 1px solid rgba(87, 209, 255, 0.12);
  background: rgba(7, 19, 31, 0.92);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
}
.control-card ::v-deep .el-card__header,
.result-card ::v-deep .el-card__header,
.debug-card ::v-deep .el-card__header,
.chart-card ::v-deep .el-card__header,
.metric-card ::v-deep .el-card__header {
  padding: 6px 12px;
  border-bottom-color: rgba(255, 255, 255, 0.06);
}
.control-card ::v-deep .el-card__body,
.result-card ::v-deep .el-card__body,
.debug-card ::v-deep .el-card__body,
.chart-card ::v-deep .el-card__body,
.metric-card ::v-deep .el-card__body {
  padding: 8px 12px;
}
.section-title {
  font-weight: 700;
  font-size: 13px;
  color: #f4fbff;
}

/* =====================================================================
   表单工具栏
   ===================================================================== */
.toolbar-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}
/* 表单标签深色 */
.toolbar-form ::v-deep .el-form-item__label {
  color: #b0c8da;
  font-weight: 600;
}
/* 输入框深色 */
.control-card ::v-deep .el-input__inner {
  background: rgba(7, 19, 31, 0.7);
  border-color: rgba(87, 209, 255, 0.18);
  color: #d9e8f3;
}
.control-card ::v-deep .el-input__inner::placeholder {
  color: #5a778b;
}
.control-card ::v-deep .el-input__inner:focus,
.control-card ::v-deep .el-input__inner:hover {
  border-color: rgba(87, 209, 255, 0.45);
}
.control-card ::v-deep .el-input__prefix,
.control-card ::v-deep .el-input__suffix {
  color: #6b8599;
}

/* =====================================================================
   指标卡片行
   ===================================================================== */
.metrics-row {
  margin: 10px 0 8px;
}
.compact-grid .el-col {
  margin-bottom: 10px;
}
.metric-card {
  min-height: 82px;
}
.compact-card {
  min-height: 82px;
  padding: 0;
}
.metric-card ::v-deep .el-card__body {
  padding: 10px 14px;
}
.metric-label {
  font-size: 11px;
  color: #6b8599;
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
}
.metric-value {
  font-size: 16px;
  font-weight: 700;
  color: #d9e8f3;
  word-break: break-all;
  line-height: 1.3;
}
.metric-source {
  font-size: 11px;
  font-weight: 600;
}

/* =====================================================================
   图表卡片
   ===================================================================== */
.compact-chart-card {
  min-height: 310px;
}
.compact-chart-card ::v-deep .el-card__body {
  padding: 4px 8px;
}
.compact-chart {
  width: 100%;
  height: 240px;
}
.chart-box {
  width: 100%;
  height: 360px;
}

/* =====================================================================
   分析详情卡片 - el-descriptions 深色覆盖
   ===================================================================== */
.compact-result-card {
  margin-top: 8px;
}
.result-card ::v-deep .el-descriptions {
  background: transparent;
}
.result-card ::v-deep .el-descriptions__header {
  margin-bottom: 8px;
}
.result-card ::v-deep .el-descriptions__title {
  color: #f4fbff;
  font-size: 13px;
  font-weight: 700;
}
.result-card ::v-deep .el-descriptions__body {
  background: transparent;
}
.result-card ::v-deep .el-descriptions__body .el-descriptions__table {
  background: transparent;
}
.result-card ::v-deep .el-descriptions__body .el-descriptions__table .el-descriptions-item__cell {
  background: rgba(255, 255, 255, 0.02);
  border-color: rgba(87, 209, 255, 0.08);
}
.result-card ::v-deep .el-descriptions-item__label {
  color: #6b8599;
  font-weight: 600;
}
.result-card ::v-deep .el-descriptions-item__content {
  color: #d9e8f3;
}

/* =====================================================================
   调试卡片 - 原始响应
   ===================================================================== */
.debug-card {
  margin-top: 8px;
}
.debug-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.debug-meta {
  padding: 0 12px 8px;
  font-size: 12px;
  color: #6b8599;
  word-break: break-all;
}
.debug-pre {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #0a1622;
  border: 1px solid rgba(87, 209, 255, 0.1);
  color: #b0d8c0;
  font-size: 12px;
  line-height: 1.6;
  overflow: auto;
  max-height: 320px;
  white-space: pre-wrap;
  word-break: break-all;
}

/* =====================================================================
   颜色辅助类
   ===================================================================== */
.normal { color: #67c23a; }
.warning { color: #e6a23c; }
.danger { color: #f56c6c; }
.mb16 { margin-bottom: 16px; }

/* 浅色工业生产主题覆盖 */
.vibration-analysis-page {
  background: linear-gradient(180deg, #fbfcfe 0%, #f3f6f8 100%);
  color: #1f2937;
}
.hero-card {
  background: linear-gradient(135deg, #ffffff 0%, #eef4fb 100%);
  border-color: #d7dee8;
  color: #1f2937;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.08);
}
.hero-left h2,
.section-title,
.metric-value,
.result-card ::v-deep .el-descriptions__title {
  color: #1f2937;
}
.hero-left p,
.metric-label,
.debug-meta,
.result-card ::v-deep .el-descriptions-item__label {
  color: #64748b;
}
.hero-badge {
  background: #e8f1fb;
  color: #2563eb;
}
.control-card,
.result-card,
.debug-card,
.chart-card,
.metric-card {
  background: #ffffff;
  border-color: #d7dee8;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.08);
}
.control-card ::v-deep .el-card__header,
.result-card ::v-deep .el-card__header,
.debug-card ::v-deep .el-card__header,
.chart-card ::v-deep .el-card__header,
.metric-card ::v-deep .el-card__header {
  border-bottom-color: #e5eaf1;
}
.toolbar-form ::v-deep .el-form-item__label {
  color: #475569;
}
.control-card ::v-deep .el-input__inner {
  background: #ffffff;
  border-color: #cbd5e1;
  color: #1f2937;
}
.control-card ::v-deep .el-input__inner::placeholder {
  color: #94a3b8;
}
.control-card ::v-deep .el-input__inner:focus,
.control-card ::v-deep .el-input__inner:hover {
  border-color: #2563eb;
}
.control-card ::v-deep .el-input__prefix,
.control-card ::v-deep .el-input__suffix {
  color: #64748b;
}
.result-card ::v-deep .el-descriptions__body .el-descriptions__table .el-descriptions-item__cell {
  background: #f8fafc;
  border-color: #e5eaf1;
}
.result-card ::v-deep .el-descriptions-item__content {
  color: #1f2937;
}
.debug-pre {
  background: #f8fafc;
  border-color: #d7dee8;
  color: #1f2937;
}
</style>

<style>
/* 深色主题：el-select 下拉面板（append-to-body 后脱离组件作用域，需全局样式） */
.dark-sidecar-select.el-select-dropdown {
  background: rgba(1, 12, 28, 0.96);
  border: 1px solid rgba(87, 209, 255, 0.3);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.45);
}
.dark-sidecar-select .el-select-dropdown__item {
  color: #d9e8f3;
}
.dark-sidecar-select .el-select-dropdown__item.hover,
.dark-sidecar-select .el-select-dropdown__item:hover {
  background: rgba(87, 209, 255, 0.1);
}
.dark-sidecar-select .el-select-dropdown__item.selected {
  color: #57d1ff;
  font-weight: 700;
}
.dark-sidecar-select .el-select-dropdown__empty {
  color: #6b8599;
}
.dark-sidecar-select .popper__arrow::after {
  border-bottom-color: rgba(1, 12, 28, 0.96);
}

/* 浅色工业生产主题：分析页下拉框 */
.dark-sidecar-select.el-select-dropdown {
  background: #ffffff;
  border-color: #d7dee8;
  box-shadow: 0 8px 20px rgba(31, 41, 55, 0.12);
}
.dark-sidecar-select .el-select-dropdown__item {
  color: #344054;
}
.dark-sidecar-select .el-select-dropdown__item.hover,
.dark-sidecar-select .el-select-dropdown__item:hover {
  background: #eef6ff;
}
.dark-sidecar-select .el-select-dropdown__item.selected {
  color: #2563eb;
}
.dark-sidecar-select .el-select-dropdown__empty {
  color: #94a3b8;
}
.dark-sidecar-select .popper__arrow::after {
  border-bottom-color: #ffffff;
}
</style>
