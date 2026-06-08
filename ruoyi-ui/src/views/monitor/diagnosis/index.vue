<template>
  <div class="app-container diagnosis-page">
    <!-- ===== 顶栏：状态指示 + 文件信息 + 操作按钮 ===== -->
    <div class="top-bar">
      <div class="top-left">
        <span class="top-eyebrow">振动诊断</span>
        <span class="top-divider">|</span>
        <!-- 诊断状态标签：待机/分析中/推理失败/已完成，颜色随状态变化 -->
        <span class="top-status" :class="statusClass">{{ resultStateText || '待机' }}</span>
        <span class="top-time">{{ lastUpdateText }}</span>
      </div>
      <div class="top-right">
        <div class="model-picker">
          <span class="model-picker-label">诊断模型</span>
          <el-select
            v-model="selectedModelType"
            size="mini"
            class="model-select"
            popper-class="dark-select-dropdown"
            :disabled="polling || uploading"
            @change="handleModelTypeChange"
          >
            <el-option label="齿轮诊断模型" value="gear" />
            <el-option label="轴承诊断模型" value="bearing" />
          </el-select>
        </div>
        <!-- 当前选中的文件名（溢出省略） -->
        <span class="top-file" :title="selectedFileLabel">{{ selectedFileLabel }}</span>
        <el-button size="mini" type="success" plain @click="handleRefresh">刷新</el-button>
        <el-button size="mini" type="primary" plain @click="uploadDialogVisible = true">上传</el-button>
        <el-button size="mini" type="warning" plain icon="el-icon-download" @click="downloadDialogVisible = true">历史下载</el-button>
      </div>
    </div>

    <!-- ===== 三栏主体：左图表 | 中诊断核心 | 右辅助信息 ===== -->
    <div class="main-area">
      <!-- 左栏：时域图 + 频域图 -->
      <div class="left-column">
        <el-card shadow="hover" class="panel-card chart-card">
          <div slot="header" class="card-header">
            <span class="card-title">时域波形</span>
            <span class="card-unit">位移 / mm</span>
          </div>
          <div ref="timeChartRef" class="chart-box"></div>
          <div v-if="!hasTimeData" class="empty-overlay">暂无数据</div>
        </el-card>
        <el-card shadow="hover" class="panel-card chart-card">
          <div slot="header" class="card-header">
            <span class="card-title">频域频谱</span>
            <span class="card-unit">归一化幅值</span>
          </div>
          <div ref="freqChartRef" class="chart-box"></div>
          <div v-if="!hasFreqData" class="empty-overlay">暂无数据</div>
        </el-card>
      </div>

      <!-- 中栏：诊断核心信息 -->
      <div class="center-column">
        <!-- 健康指数 + 诊断结果 同一行 -->
        <div class="center-hero">
          <!-- 左：健康指数环形仪表 -->
          <div class="health-gauge">
            <div class="gauge-ring" :class="healthBarClass">
              <svg viewBox="0 0 100 100">
                <circle class="g-track" cx="50" cy="50" r="40" />
                <circle class="g-fill" cx="50" cy="50" r="40"
                  :stroke-dasharray="healthDashArray" :class="healthBarClass" />
              </svg>
              <div class="g-inner">
                <span class="g-num">{{ healthIndex > 0 ? healthIndex : '--' }}</span>
              </div>
            </div>
            <span class="gauge-label">健康指数</span>
          </div>

          <!-- 右：诊断结果 + 标签同行 + 元信息 -->
          <div class="hero-right">
            <div class="diag-row">
              <span class="diag-label" :class="resultToneClass">{{ diagnosisName || '--' }}</span>
              <span class="diag-tag" :class="'dt-' + riskBadgeClass">
                <span class="dt-dot"></span>风险 {{ riskLevel || '--' }}
              </span>
              <span class="diag-tag dt-alarm">
                <span class="dt-dot"></span>告警 {{ alarmLevelText }}
              </span>
              <span class="diag-tag dt-model">
                <span class="dt-dot"></span>{{ selectedModelLabel }}
              </span>
            </div>
            <div class="hero-meta">
              <span>模型版本 {{ modelVersion || '--' }}</span>
              <span>推理状态 {{ resultStateText || '待机' }}</span>
            </div>
            <div class="health-bar-wrap">
              <div class="health-bar" :style="{ width: healthBarPercent + '%' }" :class="healthBarClass"></div>
            </div>
          </div>
        </div>

        <!-- 置信度条带 -->
        <div class="confidence-strip">
          <div class="conf-label">
            <span>置信度</span>
            <strong>{{ confidenceText }}</strong>
          </div>
          <div class="conf-bar-wrap">
            <div class="conf-bar" :style="{ width: Math.max(Number(confidence) || 0, 1) + '%' }" :class="confidenceRingClass"></div>
          </div>
        </div>

        <!-- 关键指标网格 -->
        <div class="center-metrics">
          <div class="cm-cell">
            <span class="cm-label">有效值 (RMS)</span>
            <span class="cm-val">{{ displayMetric(latestRms, 4) }}</span>
            <span class="cm-unit">mm/s</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">峰值 (Peak)</span>
            <span class="cm-val">{{ displayMetric(latestPeak, 4) }}</span>
            <span class="cm-unit">mm/s</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">未知类别占比</span>
            <span class="cm-val" :class="unknownRatio > 0.5 ? 'val-danger' : unknownRatio > 0.3 ? 'val-warn' : ''">{{ displayMetric(unknownRatio, 4) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">片段一致性</span>
            <span class="cm-val" :class="segmentConsistency > 0.8 ? 'val-ok' : segmentConsistency > 0.5 ? 'val-warn' : ''">{{ displayMetric(segmentConsistency, 4) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">平均马氏距离</span>
            <span class="cm-val">{{ displayMetric(meanMahalanobis, 2) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">平均熵值</span>
            <span class="cm-val">{{ displayMetric(meanEntropy, 4) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">闭集预测</span>
            <span class="cm-val cm-val-sm">{{ closedPredictionText }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">采样率</span>
            <span class="cm-val cm-val-sm">{{ sampleRate > 0 ? sampleRate + ' Hz' : '--' }}</span>
          </div>
        </div>

        <!-- 健康趋势图 -->
        <div class="health-trend-card">
          <div class="trend-header">
            <span class="trend-title">健康趋势</span>
            <span class="trend-sub">近 7 天日均健康指数</span>
          </div>
          <div ref="healthTrendRef" class="trend-chart"></div>
        </div>
      </div>

      <!-- 右栏：辅助面板 -->
      <div class="right-column">
        <el-card v-if="topProbabilities.length" shadow="hover" class="panel-card prob-card">
          <div slot="header" class="card-header">
            <span class="card-title">各类别概率</span>
          </div>
          <div class="prob-list">
            <div v-for="(item, i) in topProbabilities" :key="i" class="prob-row" v-show="i < 6">
              <span class="prob-class">{{ item.class }}</span>
              <div class="prob-track">
                <div class="prob-fill" :class="getProbBarClass(item.probability)" :style="{ width: Math.max(item.probability, 1) + '%' }"></div>
              </div>
              <span class="prob-pct">{{ item.probability.toFixed(1) }}%</span>
            </div>
          </div>
        </el-card>

        <el-card v-if="evidence.length" shadow="hover" class="panel-card evidence-card">
          <div slot="header" class="card-header">
            <span class="card-title">证据链</span>
            <span class="card-badge">{{ evidence.length }}</span>
          </div>
          <div class="evidence-scroll">
            <div v-for="(item, i) in evidence" :key="i" class="evidence-row" v-show="i < 5">
              <span class="ev-dot" :class="'dot-' + (item.type || 'info')"></span>
              <div class="ev-body">
                <span class="ev-title">{{ item.title }}</span>
                <span class="ev-desc">{{ item.desc }}</span>
              </div>
            </div>
          </div>
        </el-card>

        <el-card v-if="decisionReason" shadow="hover" class="panel-card reason-card">
          <div slot="header" class="card-header">
            <span class="card-title">决策原因</span>
          </div>
          <div class="reason-text">{{ decisionReason }}</div>
        </el-card>

        <div v-if="!decisionReason && !evidence.length && !topProbabilities.length" class="empty-info-placeholder">
          <i class="el-icon-upload2"></i>
          <span>上传数据文件开始分析</span>
        </div>
      </div>
    </div>

    <!-- ===== 底部：历史记录（紧凑） ===== -->
    <el-card v-if="historyList.length" shadow="hover" class="panel-card history-card">
      <div slot="header" class="card-header">
        <span class="card-title">历史记录</span>
        <span class="card-badge">{{ historyList.length }}</span>
      </div>
      <el-table :data="historyList.slice(0, 3)" size="mini" class="compact-table">
        <el-table-column label="时间" width="160">
          <template slot-scope="scope">{{ parseTime(scope.row.sampleTime) }}</template>
        </el-table-column>
        <el-table-column prop="diagnosisResult" label="诊断结果" min-width="140" />
        <el-table-column prop="confidence" label="置信度" width="70" />
        <el-table-column prop="healthIndex" label="健康" width="60" />
        <el-table-column prop="riskLevel" label="风险" width="60">
          <template slot-scope="scope">
            <el-tag size="mini" :type="riskTagType(scope.row.riskLevel)">{{ scope.row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ===== 历史数据下载弹窗 ===== -->
    <el-dialog title="历史数据下载" :visible.sync="downloadDialogVisible" width="480px" append-to-body custom-class="dark-dialog" @closed="downloadDateRange = []">
      <div class="download-dialog-body">
        <el-alert title="选择时间范围，下载该时段内的诊断记录为 CSV 文件" type="info" show-icon :closable="false" class="download-tip" />
        <el-date-picker
          v-model="downloadDateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="yyyy-MM-dd HH:mm:ss"
          :default-time="['00:00:00', '23:59:59']"
          popper-class="dark-date-picker"
          style="width: 100%; margin-top: 12px"
        />
        <div class="download-device-row">
          <el-input v-model="downloadDeviceCode" placeholder="设备编码（可选，留空查询全部）" clearable />
        </div>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="downloadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="downloading" :disabled="!downloadDateRange || downloadDateRange.length !== 2" @click="handleDownloadHistory">下载 CSV</el-button>
      </span>
    </el-dialog>

    <!-- ===== 上传弹窗 ===== -->
    <el-dialog title="上传推理文件" :visible.sync="uploadDialogVisible" width="480px" append-to-body custom-class="dark-dialog">
      <div class="upload-dialog-body">
        <el-alert title="支持 .mat 和 .npy 文件" type="info" show-icon :closable="false" class="upload-tip" />
        <!-- 文件选择区域：native input 绕过 el-upload 兼容问题 -->
        <div class="upload-dropzone" @click="$refs.nativeFileInput.click()" @dragover.prevent @drop.prevent="handleFileDrop">
          <input
            ref="nativeFileInput"
            type="file"
            accept=".mat,.npy"
            style="display:none"
            @change="handleNativeFileChange"
          />
          <i class="el-icon-upload" />
          <div class="el-upload__text">将文件拖到这里，或<em>点击选择文件</em></div>
          <div class="el-upload__tip">只接受 .mat / .npy 文件</div>
        </div>
        <!-- 本地路径输入行：手动输入文件路径后点击提交 -->
        <div class="path-upload-row">
          <el-input v-model="localFilePath" placeholder="或直接输入本地文件路径" clearable />
          <el-button type="primary" :loading="uploading" @click="uploadByPath(localFilePath)">提交分析</el-button>
        </div>
        <!-- 后端文件下拉选择：列出 DATA_DIR 中的 .mat/.npy 文件 -->
        <div class="mat-file-row">
          <el-select v-model="selectedMatFile" filterable clearable placeholder="自动选择后端最新文件" popper-class="dark-select-dropdown" style="width: 100%" @change="handleSelectedMatFileChange">
            <el-option v-for="item in matFileList" :key="item.source_name || item.name" :label="item.label || item.name" :value="item.source_name || item.name" />
          </el-select>
        </div>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="uploadDialogVisible = false">取消</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { getInferenceHealth, inferWithFilePath, listMatFiles, uploadDiagnosisToInferenceService, analyzeLatestFile, fetchHistory } from '@/api/system/bearingDiagnosis'
import inferenceWebSocket from '@/utils/inference-websocket'
import { translateDiagnosisLabel, translateAlarmLevel, translateRiskLevel, translateAll } from '@/utils/diagnosis-translations'

export default {
  name: 'InferenceResultPage',

  data() {
    return {
      // ---- ECharts 实例与定时器 ----
      timeChart: null,      // 时域图 ECharts 实例
      freqChart: null,      // 频域图 ECharts 实例
      resizeTimer: null,    // 窗口 resize 防抖定时器
      unsubscribeInferenceWs: null, // WebSocket 取消订阅函数
      polling: false,       // 是否正在请求中（防止同时间内多次请求）
      userManualMode: false, // 用户是否手动选择了文件（手动选择后停止自动刷新）
      chartDirty: false,    // 图表数据是否已更新待重绘（避免不必要的 render）

      // ---- 后端服务状态 ----
      serviceHealthy: false,  // 推理服务是否可达
      healthApiLabel: '',     // 健康状态文字标签

      // ---- 文件选择 ----
      matFileList: [],        // 后端 DATA_DIR 中的文件列表
      selectedMatFile: '',    // 当前选中的文件名
      selectedModelType: 'gear', // 当前选择的推理模型：gear / bearing
      filename: '',           // 当前分析文件名
      filePath: '',           // 文件完整路径
      deviceCode: '',         // 设备编码（从分析结果回传）

      // ---- 诊断核心结果 ----
      diagnosisName: '',      // 诊断结果名称（如 healthy, single_pitting 等）
      diagnosisDetail: '',    // 诊断详情文本
      closedPrediction: '',   // 闭集预测结果
      confidence: 0,          // 置信度（0-100）
      healthIndex: 0,         // 健康指数（0-100）
      healthTrendData: [],    // 健康趋势历史数据 [{time, value}]
      healthTrendChart: null, // 健康趋势 ECharts 实例
      riskLevel: '',          // 风险等级（低/中/高）
      alarmLevel: '',         // 告警等级（normal/attention/warning/alarm）

      // ---- 关键数值指标 ----
      latestRms: 0,           // 均方根值（Root Mean Square）
      latestPeak: 0,          // 峰值
      unknownRatio: 0,        // 未知类别片段占比
      segmentConsistency: 0,  // 各片段预测一致性
      meanMahalanobis: 0,     // 平均马氏距离（开集识别关键指标）
      meanEntropy: 0,         // 平均预测熵（不确定性指标）
      decisionReason: '',     // 模型决策原因文本
      sampleRate: 0,          // 信号采样率（Hz）
      dataPointCount: 0,      // 波形数据点数
      modelVersion: '',       // 模型版本标识

      // ---- 页面状态 ----
      resultState: 'idle',    // 当前状态：idle/running/done/failed
      lastUpdate: null,       // 最后一次更新时间

      // ---- 图表数据 ----
      timeAxis: [],           // 时域横轴（时间或采样点序号）
      timeData: [],           // 时域纵轴（振动幅值）
      freqAxis: [],           // 频域横轴（频率 Hz）
      freqData: [],           // 频域纵轴（归一化幅值）

      // ---- 证据链与概率 ----
      evidence: [],           // 诊断证据列表
      topProbabilities: [],   // 各类别预测概率（降序排列）

      // ---- 历史记录 ----
      historyList: [],        // 本地历史诊断记录（最新 10 条）

      // ---- 上传相关 ----
      uploadDialogVisible: false, // 上传弹窗可见性
      uploading: false,           // 是否正在上传/分析中
      localFilePath: '',          // 手动输入的文件路径
      lastAnalyzeResultText: '',  // 最近一次分析结果的文字摘要

      // ---- 历史下载 ----
      downloadDialogVisible: false,  // 下载弹窗可见性
      downloadDateRange: [],         // 日期范围 [startTime, endTime]
      downloadDeviceCode: '',        // 设备编码筛选
      downloading: false             // 是否正在下载中
    }
  },

  // =========================================================================
  // 计算属性
  // =========================================================================
  computed: {
    /** 格式化最后一次更新时间 */
    lastUpdateText() {
      return this.lastUpdate ? this.parseTime(this.lastUpdate) : ''
    },
    /** 当前选中文件的显示名称 */
    selectedFileLabel() {
      return this.selectedMatFile || this.filename || '--'
    },
    /** 置信度文本（百分比，取整） */
    confidenceText() {
      return `${Number.isFinite(Number(this.confidence)) ? Number(this.confidence).toFixed(0) : 0}%`
    },
    /** 将内部状态码映射为用户可读的中文状态文本 */
    resultStateText() {
      if (this.resultState === 'running') return '分析中'
      if (this.resultState === 'failed') return '推理失败'
      if (this.resultState === 'done') return '已完成'
      return ''
    },
    /** 根据状态和风险等级决定顶栏状态标签的 CSS 类 */
    statusClass() {
      if (this.resultState === 'running') return 'is-running'
      if (this.resultState === 'failed') return 'is-failed'
      if (this.riskLevel === '高' || this.alarmLevel === 'alarm') return 'is-alarm'
      if (this.riskLevel === '中' || this.alarmLevel === 'warning') return 'is-warning'
      if (this.resultState === 'done') return 'is-done'
      return 'is-idle'
    },
    /** 诊断标签的色调：失败红色 / 运行中黄色 / 完成绿色 */
    resultToneClass() {
      if (this.resultState === 'failed') return 'tone-failed'
      if (this.resultState === 'running') return 'tone-running'
      if (this.resultState === 'done') return 'tone-done'
      return ''
    },
    /** 时域数据是否存在 */
    hasTimeData() {
      return Array.isArray(this.timeData) && this.timeData.length > 0
    },
    /** 频域数据是否存在 */
    hasFreqData() {
      return Array.isArray(this.freqData) && this.freqData.length > 0
    },
    /** 闭集预测文本（无数据时显示 '--'） */
    closedPredictionText() {
      return this.closedPrediction || '--'
    },
    /** 告警等级文本（优先 alarmLevel，回退 riskLevel，已翻译为中文） */
    alarmLevelText() {
      const raw = this.alarmLevel || this.riskLevel
      return raw ? translateAll(raw) : '--'
    },
    /** 健康指数百分比（限制在 0-100 区间） */
    healthBarPercent() {
      return Math.max(0, Math.min(100, Number(this.healthIndex) || 0))
    },
    /** 健康条颜色：>=80 绿色，>=60 黄色，<60 红色 */
    healthBarClass() {
      const v = this.healthBarPercent
      if (v >= 80) return 'bar-high'
      if (v >= 60) return 'bar-mid'
      return 'bar-low'
    },
    /** 风险等级颜色映射 */
    riskColorClass() {
      if (this.riskLevel === '高') return 'tone-failed'
      if (this.riskLevel === '中') return 'tone-running'
      return 'tone-done'
    },
    /** 风险徽章样式：高=危险红，中=警告黄，低=成功绿 */
    riskBadgeClass() {
      if (this.riskLevel === '高') return 'danger'
      if (this.riskLevel === '中') return 'warning'
      return 'success'
    },
    /** 环形置信度的颜色类 */
    confidenceRingClass() {
      const v = Number(this.confidence) || 0
      if (v >= 80) return 'fill-high'
      if (v >= 50) return 'fill-mid'
      return 'fill-low'
    },
    /**
     * 计算 SVG 环形图的 stroke-dasharray
     * 圆周长 = 2π × 42 ≈ 263.89，用百分比换算已填充和空白的弧长
     */
    confidenceDashArray() {
      const pct = Math.max(0, Math.min(100, Number(this.confidence) || 0))
      const c = 2 * Math.PI * 40
      const filled = (pct / 100) * c
      return `${filled} ${c}`
    },
    healthDashArray() {
      const pct = Math.max(0, Math.min(100, Number(this.healthIndex) || 0))
      const c = 2 * Math.PI * 40
      const filled = (pct / 100) * c
      return `${filled} ${c}`
    }
  },

  // =========================================================================
  // 生命周期
  // =========================================================================
  mounted() {
    this.$nextTick(() => {
      // 使用 requestAnimationFrame 确保 DOM 完全渲染后再初始化图表
      requestAnimationFrame(() => {
        this.initCharts()
      })
      // 监听浏览器窗口大小变化，自动缩放图表
      window.addEventListener('resize', this.handleResize)
      // 首次加载：检查服务健康 + 获取文件列表 + 获取最新分析结果
      this.checkHealth()
      this.fetchMatFiles()
      this.fetchLatestAnalysis()
      // 连接推理服务 WebSocket，替代 HTTP 轮询
      this.connectInferenceWs()
    })
  },

  /** 组件销毁前清理：移除事件监听、释放 WebSocket 连接、释放 ECharts 实例 */
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize)
    this.closeInferenceWs()
    if (this.timeChart) this.timeChart.dispose()
    if (this.freqChart) this.freqChart.dispose()
    if (this.healthTrendChart) this.healthTrendChart.dispose()
    if (this.resizeTimer) clearTimeout(this.resizeTimer)
  },

  // =========================================================================
  // 方法
  // =========================================================================
  methods: {
    /**
     * 安全地格式化数值指标显示
     * 处理 null / undefined / NaN / Infinity 等边界情况
     *
     * @param {*} value - 原始值
     * @param {number} digits - 保留小数位数
     * @returns {string} 格式化后的字符串，无效值返回 '--'
     */
    displayMetric(value, digits) {
      if (value == null || value === '') return '--'
      const num = Number(value)
      if (!Number.isFinite(num)) return '--'
      return num.toFixed(digits)
    },

    /**
     * 概率条颜色分类
     * >=80% 绿色（高置信），>=50% 黄色（中等），<50% 蓝色（低置信）
     */
    getProbBarClass(pct) {
      if (pct >= 80) return 'fill-high'
      if (pct >= 50) return 'fill-mid'
      return 'fill-low'
    },

    // -----------------------------------------------------------------------
    // 图表相关方法
    // -----------------------------------------------------------------------

    /**
     * 频谱数据降采样
     * 当频谱点数超过 maxPoints 时，等间隔抽取，避免前端渲染卡顿
     *
     * @param {Array} axis - 频率轴数组
     * @param {Array} data - 幅值数组
     * @param {number} maxPoints - 最大保留点数，默认 500
     * @returns {{ axis: Array, data: Array }} 降采样后的数据
     */
    downsampleSpectrum(axis, data, maxPoints = 500) {
      if (!Array.isArray(axis) || !Array.isArray(data)) return { axis: axis || [], data: data || [] }
      if (axis.length <= maxPoints) return { axis, data }
      const step = Math.max(1, Math.floor(axis.length / maxPoints))
      const newAxis = []
      const newData = []
      for (let i = 0; i < axis.length && newAxis.length < maxPoints; i += step) {
        newAxis.push(axis[i])
        newData.push(data[i] || 0)
      }
      return { axis: newAxis, data: newData }
    },

    /**
     * 一维数据降采样
     * 等间隔抽取，使数据点数不超过 maxPoints
     */
    downsample1D(data, maxPoints = 800) {
      if (!Array.isArray(data) || data.length <= maxPoints) return data
      const step = Math.max(1, Math.floor(data.length / maxPoints))
      const result = []
      for (let i = 0; i < data.length && result.length < maxPoints; i += step) {
        result.push(data[i] || 0)
      }
      return result
    },

    /**
     * 构建时域波形图 ECharts 配置
     * X 轴优先使用真实时间轴，否则使用采样点序号
     */
    buildTimeOption() {
      const xData = this.timeAxis.length === this.timeData.length
        ? this.timeAxis.map(v => Number(v).toFixed(3))
        : this.timeData.map((_, i) => i + 1)
      return {
        animation: false,  // 关闭动画，提升高频更新时的性能
        grid: { left: 48, right: 16, top: 12, bottom: 28 },
        tooltip: { trigger: 'axis', confine: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: xData,
          name: this.timeAxis.length ? '时间（秒）' : '采样点',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10, interval: 'auto', showMaxLabel: true, showMinLabel: true }
        },
        yAxis: {
          type: 'value',
          name: '位移（毫米）',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10 },
          splitLine: { lineStyle: { color: '#333333' } }
        },
        series: [{
          type: 'line',
          smooth: false,       // 不开启平滑曲线（保留原始信号细节）
          showSymbol: false,   // 不显示数据点标记
          data: this.timeData,
          lineStyle: { width: 1.2, color: '#2563eb' },
          areaStyle: { opacity: 0.10, color: '#2563eb' }  // 半透明填充区域
        }]
      }
    },

    /**
     * 构建频域频谱图 ECharts 配置
     * 使用柱状图展示归一化 FFT 幅值
     */
    buildFreqOption() {
      return {
        animation: false,
        grid: { left: 48, right: 16, top: 12, bottom: 28 },
        tooltip: { trigger: 'axis', confine: true },
        xAxis: {
          type: 'category',
          data: this.freqAxis,
          name: '频率（赫兹）',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10, interval: 'auto', showMaxLabel: true, showMinLabel: true }
        },
        yAxis: {
          type: 'value',
          name: '归一化幅值',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10 },
          splitLine: { lineStyle: { color: '#333333' } }
        },
        series: [{
          type: 'bar',
          data: this.freqData,
          itemStyle: { color: '#d97706' },
          barWidth: '99%'  // 柱状图几乎无间隙，模拟密度谱效果
        }]
      }
    },

    /** 初始化 ECharts 实例，挂载到 ref 对应的 DOM 元素 */
    initCharts() {
      if (this.$refs.timeChartRef && !this.timeChart) this.timeChart = echarts.init(this.$refs.timeChartRef)
      if (this.$refs.freqChartRef && !this.freqChart) this.freqChart = echarts.init(this.$refs.freqChartRef)
      this.chartDirty = true
      this.renderCharts()
    },

    /**
     * 渲染/更新图表
     * 仅在数据变更后（chartDirty=true）才执行，避免不必要的高频渲染
     */
    renderCharts() {
      if (!this.chartDirty) return
      this.chartDirty = false
      // 如果实例在渲染前被销毁（如组件切换），重新初始化
      if (!this.timeChart && this.$refs.timeChartRef) {
        this.timeChart = echarts.init(this.$refs.timeChartRef)
      }
      if (!this.freqChart && this.$refs.freqChartRef) {
        this.freqChart = echarts.init(this.$refs.freqChartRef)
      }
      if (this.timeChart) {
        this.timeChart.setOption(this.buildTimeOption(), true)  // true = notMerge，完全替换配置
        this.timeChart.resize()
      }
      if (this.freqChart) {
        this.freqChart.setOption(this.buildFreqOption(), true)
        this.freqChart.resize()
      }
    },

    /**
     * 窗口 resize 防抖处理
     * 延迟 100ms 执行，避免拖拽窗口时频繁重绘
     */
    handleResize() {
      if (this.resizeTimer) clearTimeout(this.resizeTimer)
      this.resizeTimer = setTimeout(() => {
        if (this.timeChart) this.timeChart.resize()
        if (this.freqChart) this.freqChart.resize()
        if (this.healthTrendChart) this.healthTrendChart.resize()
      }, 100)
    },

    /**
     * 渲染健康趋势 — 近 7 天每日平均健康指数
     */
    renderHealthTrend() {
      const el = this.$refs.healthTrendRef
      if (!el) return
      if (!this.healthTrendChart) {
        this.healthTrendChart = echarts.init(el)
      }
      // 按天分组计算平均值
      const dayMap = {}
      const now = new Date()
      for (let i = 6; i >= 0; i--) {
        const d = new Date(now)
        d.setDate(d.getDate() - i)
        const key = `${d.getMonth() + 1}/${d.getDate()}`
        dayMap[key] = { total: 0, count: 0 }
      }
      this.healthTrendData.forEach(item => {
        const d = new Date(item.time)
        const key = `${d.getMonth() + 1}/${d.getDate()}`
        if (dayMap[key]) {
          dayMap[key].total += Number(item.value)
          dayMap[key].count++
        }
      })
      const days = Object.keys(dayMap)
      const values = days.map(k => dayMap[k].count > 0
        ? Math.round(dayMap[k].total / dayMap[k].count)
        : null)

      this.healthTrendChart.setOption({
        backgroundColor: 'transparent',
        grid: { left: 36, right: 12, top: 8, bottom: 22 },
        xAxis: {
          type: 'category',
          data: days,
          axisLine: { lineStyle: { color: '#333' } },
          axisLabel: { color: '#94a3b8', fontSize: 10 },
          axisTick: { show: false }
        },
        yAxis: {
          type: 'value',
          min: 0,
          max: 100,
          interval: 20,
          splitLine: { lineStyle: { color: '#333' } },
          axisLabel: { color: '#94a3b8', fontSize: 10 }
        },
        series: [{
          type: 'line',
          data: values,
          smooth: true,
          connectNulls: true,
          showSymbol: true,
          symbol: 'circle',
          symbolSize: 6,
          lineStyle: { width: 2, color: '#4ade80' },
          itemStyle: { color: '#4ade80' },
          areaStyle: {
            color: {
              type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(74,222,128,0.2)' },
                { offset: 1, color: 'rgba(74,222,128,0)' }
              ]
            }
          }
        }]
      }, true)
    },

    // -----------------------------------------------------------------------
    // 数据处理方法
    // -----------------------------------------------------------------------

    /**
     * 统一化 API 响应结构
     * 后端返回格式可能是 { data: { data: {...} } } 或 { data: {...} }
     * 此函数统一提取内层真正有用的 data 对象
     *
     * @param {Object} response - API 原始响应
     * @returns {Object} 标准化的数据对象
     */
    normalizeAnalyzeResponse(response) {
      const root = (response && (response.data || response)) || {}
      return root.data || root
    },

    /**
     * 将诊断数据应用到组件状态
     * 核心方法：几乎所有 API 的回调最终都会调用此方法来更新界面
     *
     * 支持双字段名兼容：Python 后端使用 snake_case（如 diagnosis_result），
     * 但前端同时兼容 camelCase（如 diagnosisResult）
     *
     * @param {Object} data - 诊断结果数据对象
     */
    applyDiagnosis(data) {
      if (!data || !Object.keys(data).length) {
        this.clearDiagnosis()
        return
      }

      // ---- 诊断核心结果（兼容 Python snake_case 和 JS camelCase） ----
      if (data.diagnosisResult || data.diagnosisName || data.label) {
        const rawDiagnosis = data.diagnosisResult || data.diagnosisName || data.label
        this.diagnosisName = translateDiagnosisLabel(rawDiagnosis)
      }
      if (data.diagnosisDetail || data.diagnosis_detail) {
        this.diagnosisDetail = data.diagnosisDetail || data.diagnosis_detail
      }
      if (data.decision_reason) this.decisionReason = data.decision_reason
      if (data.closedPrediction || data.closed_prediction) {
        const rawPrediction = data.closedPrediction || data.closed_prediction
        this.closedPrediction = translateDiagnosisLabel(rawPrediction)
      }
      if (data.confidence != null) this.confidence = Math.max(0, Math.min(100, Number(data.confidence)))
      if (data.healthIndex != null) {
        this.healthIndex = Number(data.healthIndex)
        // 记录健康趋势（带完整时间戳）
        const now = new Date()
        this.healthTrendData.push({
          time: now.toISOString(),
          value: this.healthIndex
        })
        // 保留最近 30 天数据
        const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000
        this.healthTrendData = this.healthTrendData.filter(d => new Date(d.time).getTime() > cutoff)
        this.$nextTick(() => this.renderHealthTrend())
      }
      if (data.riskLevel) this.riskLevel = translateRiskLevel(data.riskLevel)
      if (data.alarmLevel || data.alarm_level) {
        const rawAlarm = data.alarmLevel || data.alarm_level
        this.alarmLevel = translateAlarmLevel(rawAlarm)
      }

      // ---- 数值指标（多字段名兼容） ----
      if (data.latestRms != null) this.latestRms = Number(data.latestRms)
      else if (data.rms != null) this.latestRms = Number(data.rms)
      if (data.latestPeak != null) this.latestPeak = Number(data.latestPeak)
      else if (data.peak != null) this.latestPeak = Number(data.peak)
      if (data.unknownRatio != null) this.unknownRatio = Number(data.unknownRatio)
      if (data.segmentConsistency != null) this.segmentConsistency = Number(data.segmentConsistency)
      if (data.meanMahalanobis != null) this.meanMahalanobis = Number(data.meanMahalanobis)
      if (data.meanEntropy != null) this.meanEntropy = Number(data.meanEntropy)
      if (data.sampleRate) this.sampleRate = Number(data.sampleRate)
      else if (data.sample_rate) this.sampleRate = Number(data.sample_rate)
      if (data.count != null) this.dataPointCount = Number(data.count)

      // ---- 元信息 ----
      if (data.filename) this.filename = data.filename
      if (data.filePath) this.filePath = data.filePath
      if (data.deviceCode) this.deviceCode = data.deviceCode
      if (data.modelVersion) this.modelVersion = data.modelVersion
      if (data.sourceName) this.filePath = data.filePath || data.sourceName

      // ---- 状态推导 ----
      if (data.status || data.resultState) {
        this.resultState = this.resolveState(data.status || data.resultState)
      } else if (data.diagnosisName || data.diagnosisResult || data.label) {
        this.resultState = 'done'  // 有诊断结果即视为完成
      }
      this.lastUpdate = data.sampleTime || data.updateTime || new Date()

      // ---- 时域波形数据（多字段名兼容 + 降采样） ----
      if (Array.isArray(data.waveform) && data.waveform.length) {
        if (Array.isArray(data.time_axis) && data.time_axis.length === data.waveform.length) {
          const ds = this.downsampleSpectrum(data.time_axis, data.waveform, 800)
          this.timeAxis = ds.axis
          this.timeData = ds.data
        } else {
          this.timeData = this.downsample1D(data.waveform, 800)
        }
        this.chartDirty = true
      } else if (Array.isArray(data.time_data) && data.time_data.length) {
        if (Array.isArray(data.time_axis) && data.time_axis.length === data.time_data.length) {
          const ds = this.downsampleSpectrum(data.time_axis, data.time_data, 800)
          this.timeAxis = ds.axis
          this.timeData = ds.data
        } else {
          this.timeData = this.downsample1D(data.time_data, 800)
        }
        this.chartDirty = true
      }
      // 如果只有时间轴数据（极端情况），单独保留
      if (Array.isArray(data.time_axis) && data.time_axis.length && !this.timeAxis.length) {
        this.timeAxis = data.time_axis
        this.chartDirty = true
      }

      // ---- 频域频谱数据（多字段名兼容 + 降采样） ----
      if (Array.isArray(data.frequencyAxis) && data.frequencyAxis.length) {
        const ds = this.downsampleSpectrum(data.frequencyAxis, data.spectrum || data.freq_data)
        this.freqAxis = ds.axis
        this.freqData = ds.data
        this.chartDirty = true
      } else if (Array.isArray(data.freq_axis) && data.freq_axis.length) {
        const ds = this.downsampleSpectrum(data.freq_axis, data.freq_data || data.spectrum)
        this.freqAxis = ds.axis
        this.freqData = ds.data
        this.chartDirty = true
      } else if (Array.isArray(data.spectrum) && data.spectrum.length) {
        this.freqData = data.spectrum
        this.chartDirty = true
      } else if (Array.isArray(data.freq_data) && data.freq_data.length) {
        this.freqData = data.freq_data
        this.chartDirty = true
      }

      // ---- 证据链与概率（分别更新，不会互相覆盖） ----
      if (Array.isArray(data.topProbabilities) && data.topProbabilities.length) {
        this.topProbabilities = data.topProbabilities.sort((a, b) => (b.probability || 0) - (a.probability || 0))
      }
      if (Array.isArray(data.evidence) && data.evidence.length) {
        this.evidence = data.evidence
      }

      // 触发图表重绘
      this.renderCharts()
    },

    /** 清空所有诊断数据，恢复初始状态 */
    clearDiagnosis() {
      this.resultState = 'idle'
      this.diagnosisName = ''
      this.diagnosisDetail = ''
      this.closedPrediction = ''
      this.confidence = 0
      this.healthIndex = 0
      this.riskLevel = ''
      this.alarmLevel = ''
      this.latestRms = 0
      this.latestPeak = 0
      this.unknownRatio = 0
      this.segmentConsistency = 0
      this.meanMahalanobis = 0
      this.meanEntropy = 0
      this.decisionReason = ''
      this.sampleRate = 0
      this.dataPointCount = 0
      this.filename = ''
      this.filePath = ''
      this.modelVersion = ''
      this.deviceCode = ''
      this.lastUpdate = null
      this.timeAxis = []
      this.timeData = []
      this.freqAxis = []
      this.freqData = []
      this.evidence = []
      this.topProbabilities = []
      this.historyList = []
      this.lastAnalyzeResultText = ''
      this.chartDirty = true
      this.renderCharts()
    },

    /**
     * 将后端返回的状态字符串解析为统一状态码
     * 后端可能返回 running/pending/failed/error/done/success/complete 等
     */
    resolveState(raw) {
      const value = String(raw || '').toLowerCase()
      if (!value) return 'idle'
      if (value.includes('running') || value.includes('pending')) return 'running'
      if (value.includes('failed') || value.includes('error')) return 'failed'
      if (value.includes('done') || value.includes('success') || value.includes('complete')) return 'done'
      return 'idle'
    },

    /**
     * 将一次诊断结果追加到本地历史记录
     * 保持在最新 10 条，新记录插入头部
     */
    appendLocalHistory(payload) {
      if (!payload || !Object.keys(payload).length) return
      const entry = {
        sampleTime: payload.sampleTime || payload.updateTime || new Date(),
        diagnosisResult: payload.diagnosisResult || payload.diagnosisName || payload.label || '',
        confidence: payload.confidence != null ? payload.confidence : '',
        healthIndex: payload.healthIndex != null ? payload.healthIndex : '',
        riskLevel: payload.riskLevel || ''
      }
      this.historyList = [entry, ...this.historyList].slice(0, 10)
    },

    /**
     * 风险等级 → Element UI Tag 类型映射
     * 高/alarm → danger（红色），中/warning → warning（黄色），其余 → success（绿色）
     */
    riskTagType(level) {
      if (level === '高' || level === 'alarm') return 'danger'
      if (level === '中' || level === 'warning') return 'warning'
      return 'success'
    },

    // -----------------------------------------------------------------------
    // API 调用方法
    // -----------------------------------------------------------------------

    /**
     * 手动刷新：重置为自动模式，读取后端目录中最新的文件进行分析
     * 点击刷新按钮后恢复读取后端目录最新文件的行为
     */
    async handleRefresh() {
      this.userManualMode = false
      this.selectedMatFile = ''
      this.localFilePath = ''
      this.polling = true
      try {
        await Promise.all([
          this.checkHealth(),
          this.fetchMatFiles(),
          this.fetchLatestAnalysis()
        ])
        this.$message.success('已刷新，正在读取目录最新文件')
      } catch (error) {
        // ignore
      } finally {
        this.polling = false
      }
    },

    // ---- WebSocket (primary data path, replaces HTTP polling) ----

    connectInferenceWs() {
      this.unsubscribeInferenceWs = inferenceWebSocket.subscribe((event, payload) => {
        if (event === 'open') {
          // Subscribe to health + file_list channels; auto_analysis is broadcast to all
          inferenceWebSocket.send({ type: 'subscribe', channel: 'health' })
          inferenceWebSocket.send({ type: 'subscribe', channel: 'mat_files' })
          return
        }
        if (event === 'error') {
          console.warn('Inference WebSocket 连接异常')
          return
        }
        if (event !== 'message' || !payload) return
        this.handleInferenceWsMessage(payload)
      })
      inferenceWebSocket.connect()
    },

    closeInferenceWs() {
      if (this.unsubscribeInferenceWs) {
        this.unsubscribeInferenceWs()
        this.unsubscribeInferenceWs = null
      }
      inferenceWebSocket.close()
    },

    handleInferenceWsMessage(msg) {
      if (msg.type === 'health_status') {
        this.serviceHealthy = msg.status === 'ok' && msg.model_loaded !== false
        this.healthApiLabel = this.serviceHealthy ? '正常' : '异常'
      } else if (msg.type === 'file_list' && Array.isArray(msg.data)) {
        this.matFileList = msg.data
        const latestMat = this.matFileList[0]
        const latestName = latestMat ? (latestMat.source_name || latestMat.name || '') : ''
        if (latestName && !this.selectedMatFile && !this.userManualMode) {
          this.selectedMatFile = latestName
        }
      } else if (msg.type === 'auto_analysis') {
        if (msg.success && msg.data) {
          const data = this.normalizeAnalyzeResponse({ data: msg.data })
          this.applyDiagnosis(data)
          this.lastAnalyzeResultText = data.diagnosisResult || data.label || ''
          this.appendLocalHistory(data)
          if (data.sourceName) this.selectedMatFile = data.sourceName
          else if (data.filename) this.selectedMatFile = data.filename
        }
      }
    },

    /**
     * 检查 Python 推理服务的健康状态
     * GET /health (HTTP fallback)
     */
    async checkHealth() {
      try {
        const res = await getInferenceHealth()
        const data = this.normalizeAnalyzeResponse(res)
        this.serviceHealthy = data.status === 'ok' || data.model_loaded !== false
        this.healthApiLabel = this.serviceHealthy ? '正常' : '异常'
      } catch (error) {
        this.serviceHealthy = false
        this.healthApiLabel = ''
        // 忽略 500 / 网络错误（避免控制台刷屏）
        if (error && error.response && error.response.status !== 500) {
          console.error('健康检查失败', error)
        }
      }
    },

    /**
     * 获取后端 DATA_DIR 中的 .mat / .npy 文件列表
     * GET /mat-files
     */
    async fetchMatFiles() {
      try {
        const res = await listMatFiles()
        const data = this.normalizeAnalyzeResponse(res)
        this.matFileList = Array.isArray(data) ? data : []
        // 自动选中最新文件（列表已按 mtime 降序排列）
        const latestMat = this.matFileList[0]
        const latestName = latestMat ? (latestMat.source_name || latestMat.name || '') : ''
        if (latestName && !this.selectedMatFile) {
          this.selectedMatFile = latestName
        }
      } catch (error) {
        if (error && error.response && error.response.status !== 500) {
          console.error('获取 mat 文件列表失败', error)
        }
      }
    },

    /**
     * 获取最新文件的诊断分析结果
     * GET /analyze（无参数时自动分析最新文件）
     */
    async fetchLatestAnalysis() {
      try {
        const res = await analyzeLatestFile(null, this.selectedModelType)
        const data = this.normalizeAnalyzeResponse(res)
        if (!data || !Object.keys(data).length) return
        this.applyDiagnosis(data)
        this.lastAnalyzeResultText = data.diagnosisResult || data.label || ''
        this.appendLocalHistory(data)
        if (data.sourceName) this.selectedMatFile = data.sourceName
        else if (data.filename) this.selectedMatFile = data.filename
      } catch (error) {
        if (error && error.response && error.response.status !== 500) {
          console.error('获取最新分析结果失败', error)
        }
      }
    },

    /**
     * 根据指定文件路径获取诊断结果
     * POST /infer
     *
     * @param {string} filePath - 文件路径或文件名
     */
    async fetchLatest(filePath) {
      const targetPath = String(filePath || this.selectedMatFile || '').trim()
      if (!targetPath) {
        this.clearDiagnosis()
        return
      }
      try {
        const res = await inferWithFilePath({
          filePath: targetPath,
          analysisMode: 'latest',
          filename: targetPath.split(/[\\/]/).pop(),  // 从路径中提取纯文件名
          deviceCode: this.deviceCode,
          modelType: this.selectedModelType
        })
        const data = this.normalizeAnalyzeResponse(res)
        if (!data || !Object.keys(data).length) {
          this.clearDiagnosis()
          return
        }
        this.applyDiagnosis(data)
        this.lastAnalyzeResultText = data.diagnosisResult || data.label || ''
        this.appendLocalHistory(data)
        if (data.sourceName) this.selectedMatFile = data.sourceName
        else if (data.filename) this.selectedMatFile = data.filename
      } catch (error) {
        this.clearDiagnosis()
        if (error && error.response && error.response.status !== 500) {
          console.error('获取最新推理结果失败', error)
        }
      }
    },

    /** 下拉选择文件变更时，触发分析 */
    handleSelectedMatFileChange(val) {
      const selected = String(val || '').trim()
      if (!selected) return
      this.userManualMode = true
      this.localFilePath = selected
      this.fetchLatest(selected)
    },

    handleModelTypeChange() {
      if (this.selectedMatFile || this.localFilePath) {
        this.fetchLatest(this.selectedMatFile || this.localFilePath)
      } else {
        this.fetchLatestAnalysis()
      }
    },

    // -----------------------------------------------------------------------
    // 文件上传方法
    // -----------------------------------------------------------------------

    /** 本地文件选择（native input change 事件，直接拿到 File 对象） */
    handleNativeFileChange(e) {
      const files = e.target && e.target.files
      if (files && files.length > 0) {
        this.uploadSelectedFile(files[0])
      }
      // 重置 input，允许重复选择同名文件
      if (this.$refs.nativeFileInput) {
        this.$refs.nativeFileInput.value = ''
      }
    },

    /** 拖拽文件处理 */
    handleFileDrop(e) {
      const files = e.dataTransfer && e.dataTransfer.files
      if (files && files.length > 0) {
        const file = files[0]
        const suffix = String(file.name || '').toLowerCase()
        if (!suffix.endsWith('.mat') && !suffix.endsWith('.npy')) {
          this.$message.error('仅支持 .mat 或 .npy 文件')
          return
        }
        this.uploadSelectedFile(file)
      }
    },

    /**
     * 上传文件到 Python 推理服务进行分析
     * POST /analyze/upload（multipart/form-data）
     */
    async uploadSelectedFile(file) {
      const suffix = String(file.name || '').toLowerCase()
      if (!suffix.endsWith('.mat') && !suffix.endsWith('.npy')) {
        this.$message.error('仅支持 .mat 或 .npy 文件')
        return
      }
      this.userManualMode = true
      this.uploading = true
      try {
        const formData = new FormData()
        formData.append('file', file)
        formData.append('model_type', this.selectedModelType)
        const response = await uploadDiagnosisToInferenceService(formData)
        const data = this.normalizeAnalyzeResponse(response)
        this.applyDiagnosis(data)
        this.selectedMatFile = file.name
        this.localFilePath = file.name
        this.$message.success('文件已提交分析')
      } catch (error) {
        this.clearDiagnosis()
        if (error && error.response && error.response.status !== 500) {
          console.error('文件上传失败', error)
        }
        this.$message.error('上传失败，请检查后端服务或文件格式')
      } finally {
        this.uploading = false
      }
    },

    /**
     * 通过输入的文件路径提交分析（不传文件，只传路径）
     * POST /infer
     */
    async uploadByPath(filePath) {
      const normalizedPath = String(filePath || '').trim()
      if (!normalizedPath) return
      this.userManualMode = true
      this.uploading = true
      try {
        const response = await inferWithFilePath({
          deviceCode: this.deviceCode,
          filePath: normalizedPath,
          analysisMode: 'upload',
          filename: normalizedPath.split(/[\\/]/).pop(),
          modelType: this.selectedModelType
        })
        const data = this.normalizeAnalyzeResponse(response)
        this.applyDiagnosis(data)
        this.selectedMatFile = normalizedPath.split(/[\\/]/).pop()
        this.localFilePath = normalizedPath
        this.$message.success('文件路径已提交分析')
      } catch (error) {
        this.clearDiagnosis()
        if (error && error.response && error.response.status !== 500) {
          console.error('路径分析失败', error)
        }
        this.$message.error('提交失败，请检查文件路径或后端服务')
      } finally {
        this.uploading = false
      }
    },

    // -----------------------------------------------------------------------
    // 历史数据下载方法
    // -----------------------------------------------------------------------

    /**
     * 下载指定时间范围内的历史诊断记录为 CSV 文件
     */
    async handleDownloadHistory() {
      if (!this.downloadDateRange || this.downloadDateRange.length !== 2) {
        this.$message.warning('请选择时间范围')
        return
      }
      const [startTime, endTime] = this.downloadDateRange
      this.downloading = true
      try {
        const params = { start_time: startTime, end_time: endTime }
        if (this.downloadDeviceCode.trim()) {
          params.device_code = this.downloadDeviceCode.trim()
        }
        const res = await fetchHistory(params)
        const records = res.data || []

        if (!records.length) {
          this.$message.warning('该时段内无诊断记录')
          this.downloading = false
          return
        }

        // 生成 CSV 内容（BOM + UTF-8，Excel 兼容）
        const headers = [
          'ID', '批次ID', '设备编码', '数据文件', '分析模式', '采样率(Hz)',
          '诊断结果', '闭集预测', '置信度(%)', '健康指数',
          '风险等级', '告警等级', '诊断详情', '决策原因',
          '未知比例', '片段一致性', '平均马氏距离', '平均熵值',
          '有效值(RMS)', '峰值(Peak)',
          '采样时间', '创建时间'
        ]
        const keys = [
          'id', 'batch_id', 'device_code', 'source_file', 'analysis_mode', 'sample_rate',
          'diagnosis_result', 'closed_prediction', 'confidence', 'health_index',
          'risk_level', 'alarm_level', 'diagnosis_detail', 'decision_reason',
          'unknown_ratio', 'segment_consistency', 'mean_mahalanobis', 'mean_entropy',
          'rms', 'peak',
          'sample_time', 'create_time'
        ]

        const escapeCsv = (v) => {
          if (v == null) return ''
          const s = String(v)
          if (s.includes(',') || s.includes('"') || s.includes('\n')) {
            return '"' + s.replace(/"/g, '""') + '"'
          }
          return s
        }

        const lines = []
        lines.push('﻿' + headers.join(','))
        for (const row of records) {
          lines.push(keys.map(k => escapeCsv(row[k])).join(','))
        }
        const csvContent = lines.join('\n')

        // 触发浏览器下载
        const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        const now = new Date()
        const ts = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}${String(now.getSeconds()).padStart(2, '0')}`
        link.download = `诊断历史_${ts}.csv`
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)

        this.$message.success(`已导出 ${records.length} 条记录`)
        this.downloadDialogVisible = false
      } catch (error) {
        console.error('下载历史数据失败', error)
        this.$message.error('下载失败，请检查后端服务')
      } finally {
        this.downloading = false
      }
    }
  }
}
</script>

<style scoped>
/* =====================================================================
   页面容器
   深色工业风背景，flex 纵向布局，高度自适应视口
   ===================================================================== */
.diagnosis-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 84px);
  padding: 8px 14px;
  background: linear-gradient(180deg, #07131f 0%, #0a1c2d 100%);
  overflow-y: auto;
  overflow-x: hidden;
}

/* =====================================================================
   顶栏 - 状态指示条
   ===================================================================== */
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 16px;
  margin-bottom: 10px;
  border-radius: 10px;
  border: 1px solid rgba(87, 209, 255, 0.12);  /* 半透明青色边框 */
  background: rgba(7, 19, 31, 0.92);
  flex-shrink: 0;
  flex-wrap: wrap;
}
.top-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.top-eyebrow {
  color: #8adfff;
  font-weight: 800;
  letter-spacing: 1px;
  text-transform: uppercase;
  font-size: 11px;
}
.top-divider {
  color: rgba(87, 209, 255, 0.2);
  font-size: 14px;
}
.top-status {
  font-size: 13px;
  font-weight: 700;
  padding: 3px 12px;
  border-radius: 999px;  /* 胶囊形状 */
}
/* 状态颜色 */
.top-status.is-running { background: rgba(230, 162, 60, 0.18); color: #e6a23c; }
.top-status.is-failed  { background: rgba(245, 108, 108, 0.18); color: #f56c6c; }
.top-status.is-done    { background: rgba(103, 194, 58, 0.18); color: #67c23a; }
.top-status.is-idle    { background: rgba(144, 147, 153, 0.18); color: #909399; }
.top-status.is-alarm   { background: rgba(245, 108, 108, 0.18); color: #f56c6c; }
.top-status.is-warning { background: rgba(230, 162, 60, 0.18); color: #e6a23c; }
.top-time {
  color: #7a94a8;
  font-size: 11px;
}
.top-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.model-picker {
  display: flex;
  align-items: center;
  gap: 6px;
}
.model-picker-label {
  color: #7a94a8;
  font-size: 12px;
  white-space: nowrap;
}
.model-select {
  width: 132px;
}
.top-file {
  color: #b0c8da;
  font-size: 12px;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* =====================================================================
   三栏主体：左图表 | 中诊断核心 | 右辅助信息
   ===================================================================== */
.main-area {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(250px, 1.6fr) minmax(360px, 2.4fr) minmax(250px, 1.6fr);
  gap: 8px;
  margin-bottom: 6px;
}

/* ---- 左栏：图表 ---- */
.left-column {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}
.left-column .chart-card {
  flex: 1;
  min-height: 0;
}

/* ---- 中栏：诊断核心 ---- */
.center-column {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 1px solid #444444;
  background: linear-gradient(160deg, rgba(255,87,34,0.04) 0%, rgba(7,19,31,0.9) 100%);
}
/* 健康指数 + 诊断结果 同一行 */
.center-hero {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 12px;
  border-radius: 6px;
  background: rgba(255,255,255,0.02);
  border: 1px solid #333333;
}
.hero-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.diag-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.diag-label {
  font-size: 32px;
  font-weight: 900;
  color: #fff;
  line-height: 1;
  white-space: nowrap;
}
.diag-label.tone-running { color: #fbbf24; }
.diag-label.tone-failed  { color: #f87171; }
.diag-label.tone-done    { color: #4ade80; }

.hero-right .health-bar-wrap {
  height: 8px;
  border-radius: 4px;
  background: rgba(255,255,255,0.08);
  overflow: hidden;
}
.hero-right .health-bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.5s;
}
.health-bar.bar-high { background: #4ade80; }
.health-bar.bar-mid  { background: #fbbf24; }
.health-bar.bar-low  { background: #f87171; }

.hero-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: #94a3b8;
}
.diag-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 14px;
  font-weight: 700;
  padding: 4px 12px;
  border-radius: 4px;
  white-space: nowrap;
}
.dt-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.diag-tag.dt-danger  { background: rgba(248,113,113,0.15); color: #f87171; }
.diag-tag.dt-danger .dt-dot  { background: #f87171; }
.diag-tag.dt-warning { background: rgba(251,191,36,0.12); color: #fbbf24; }
.diag-tag.dt-warning .dt-dot { background: #fbbf24; }
.diag-tag.dt-success { background: rgba(74,222,128,0.12); color: #4ade80; }
.diag-tag.dt-success .dt-dot { background: #4ade80; }
.diag-tag.dt-alarm   { background: rgba(248,113,113,0.10); color: #f87171; }
.diag-tag.dt-alarm .dt-dot   { background: #f87171; }
.diag-tag.dt-model   { background: rgba(96,165,250,0.10); color: #60a5fa; }
.diag-tag.dt-model .dt-dot   { background: #60a5fa; }

.health-gauge {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
}
/* SVG 健康指数环形仪表 — 突出显示 */
.gauge-ring {
  width: 100px;
  height: 100px;
  position: relative;
  flex-shrink: 0;
}
.gauge-ring svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.g-track {
  fill: none;
  stroke: rgba(255,255,255,0.08);
  stroke-width: 7;
}
.g-fill {
  fill: none;
  stroke-width: 7;
  stroke-linecap: round;
  transition: stroke-dasharray 0.5s ease;
}
.g-fill.bar-high { stroke: #4ade80; }
.g-fill.bar-mid  { stroke: #fbbf24; }
.g-fill.bar-low  { stroke: #f87171; }
.g-inner {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.g-num {
  font-size: 28px;
  font-weight: 900;
  color: #fff;
  line-height: 1;
}
.gauge-label {
  font-size: 13px;
  color: #94a3b8;
  font-weight: 600;
}

/* 置信度条带 — 简洁线形 */
.confidence-strip {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 14px;
  border-radius: 6px;
  background: rgba(255,255,255,0.02);
  border: 1px solid #333333;
}
.conf-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-shrink: 0;
}
.conf-label span {
  font-size: 14px;
  color: #94a3b8;
}
.conf-label strong {
  font-size: 26px;
  font-weight: 900;
  color: #fff;
}
.conf-bar-wrap {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: rgba(255,255,255,0.08);
  overflow: hidden;
}
.conf-bar {
  height: 100%;
  border-radius: 4px;
  transition: width 0.4s ease;
  min-width: 2px;
}
.conf-bar.fill-high { background: #4ade80; }
.conf-bar.fill-mid  { background: #fbbf24; }
.conf-bar.fill-low  { background: #f87171; }

/* 健康趋势卡片 */
.health-trend-card {
  border: 1px solid #333333;
  border-radius: 6px;
  background: rgba(255,255,255,0.01);
  overflow: hidden;
}
.trend-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  border-bottom: 1px solid #333333;
}
.trend-title {
  font-size: 13px;
  font-weight: 700;
  color: #e2e8f0;
}
.trend-sub {
  font-size: 11px;
  color: #94a3b8;
}
.trend-chart {
  width: 100%;
  height: 130px;
  min-height: 100px;
}

.center-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
}
.cm-cell {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 8px;
  border-radius: 4px;
  background: rgba(255,255,255,0.02);
  border: 1px solid rgba(255,255,255,0.05);
}
.cm-label {
  font-size: 12px;
  color: #94a3b8;
  flex-shrink: 0;
  margin-right: 6px;
}
.cm-val {
  font-size: 17px;
  font-weight: 700;
  color: #e2e8f0;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.cm-val-sm { font-size: 14px; }
.cm-unit {
  font-size: 11px;
  color: #94a3b8;
  margin-left: 4px;
}
.cm-val.val-danger { color: #f87171; }
.cm-val.val-warn   { color: #fbbf24; }
.cm-val.val-ok     { color: #4ade80; }

/* ---- 右栏：辅助面板 ---- */
.right-column {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 无数据占位 */
.empty-info-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 16px;
  color: #64748b;
  font-size: 12px;
  border: 1px dashed rgba(255,255,255,0.08);
  border-radius: 6px;
  background: rgba(255,255,255,0.01);
  flex: 1;
}
.empty-info-placeholder i {
  font-size: 20px;
  opacity: 0.5;
}

/* =====================================================================
   通用面板卡片 - 统一的深色半透明风格
   ===================================================================== */
.panel-card {
  border-radius: 8px;
  border: 1px solid #444444;
  background: rgba(7, 19, 31, 0.92);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: visible;
}
.panel-card ::v-deep .el-card__header {
  padding: 6px 12px;
  border-bottom: 1px solid #333333;
  flex-shrink: 0;
}
.panel-card ::v-deep .el-card__body {
  padding: 8px 12px;
  flex: 1;
  overflow: visible;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title {
  color: #f4fbff;
  font-weight: 700;
  font-size: 16px;
}
.card-unit {
  color: #94a3b8;
  font-size: 13px;
}
.card-badge {
  color: #8adfff;
  font-size: 11px;
  background: rgba(87, 209, 255, 0.12);
  padding: 1px 8px;
  border-radius: 999px;
}

/* =====================================================================
   图表卡片 - 内边距较小，最大化图表可视面积
   ===================================================================== */
.chart-card ::v-deep .el-card__body {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 6px 10px;
}
.chart-box {
  flex: 1;
  min-height: 140px;
  width: 100%;
}
.empty-overlay {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #8aa0b6;
  font-size: 14px;
  pointer-events: none;  /* 不阻挡 ECharts 的鼠标事件 */
  z-index: 1;
}

/* =====================================================================
   诊断结果卡片 - 视觉上最突出的卡片
   左侧有渐变色装饰条，边框和阴影比普通卡片更明显
   ===================================================================== */
.result-card {
  border: 1px solid rgba(87, 209, 255, 0.35) !important;
  box-shadow: 0 0 24px rgba(87, 209, 255, 0.10), 0 8px 24px rgba(0, 0, 0, 0.20) !important;
  background: linear-gradient(160deg, rgba(7, 19, 31, 0.95) 0%, rgba(10, 30, 50, 0.95) 100%) !important;
  position: relative;
}
/* 左侧彩色装饰条（伪元素实现） */
.result-card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; bottom: 0;
  width: 3px;
  border-radius: 10px 0 0 10px;
  background: linear-gradient(180deg, #57d1ff, #409eff, #67c23a);
  opacity: 0.8;
}
.result-card ::v-deep .el-card__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px 8px 12px;
  overflow: visible;
  flex: 1;
}

/* ---- 英雄区域：环形置信度 + 诊断标签 ---- */
.result-hero {
  display: flex;
  align-items: center;
  gap: 22px;
}

/* ---- SVG 环形置信度仪表盘 ---- */
.conf-ring {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  position: relative;
}
.conf-ring svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);  /* SVG 圆起点在 3 点钟方向，旋转后从 12 点方向开始 */
}
/* 底色轨道：半透明暗色 */
.ring-track {
  fill: none;
  stroke: rgba(255,255,255,0.06);
  stroke-width: 7;
}
.ring-bar {
  fill: none;
  stroke-width: 7;
  stroke-linecap: round;
  transition: stroke-dasharray 0.5s ease, stroke 0.4s ease;
}
/* 置信度 >= 80% 绿色，>= 50% 黄色，< 50% 红色 */
.ring-bar.fill-high { stroke: #4ade80; }
.ring-bar.fill-mid  { stroke: #fbbf24; }
.ring-bar.fill-low  { stroke: #f87171; }
/* 环内文字：居中显示置信度百分比 */
.ring-inner {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ring-num {
  color: #fff;
  font-size: 15px;
  font-weight: 800;
}

/* ---- 诊断信息（标签 + 风险/告警徽章） ---- */
.hero-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hero-label {
  font-size: 34px;
  font-weight: 800;
  color: #f4fbff;
  line-height: 1.15;
  word-break: break-word;
  text-shadow: 0 0 12px rgba(87, 209, 255, 0.15);
}
/* 不同状态下的标签颜色 */
.hero-label.tone-running { color: #ffd166; text-shadow: 0 0 16px rgba(255, 209, 102, 0.25); }
.hero-label.tone-failed  { color: #ff8a8a; text-shadow: 0 0 16px rgba(255, 138, 138, 0.25); }
.hero-label.tone-done    { color: #8ee28e; text-shadow: 0 0 16px rgba(142, 226, 142, 0.25); }
.hero-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.hero-tag {
  font-size: 13px;
  font-weight: 700;
  padding: 4px 12px;
  border-radius: 999px;
}
/* 风险标签：红色/黄色/绿色 */
.hero-tag.tag-danger  { background: rgba(245, 108, 108, 0.18); color: #f56c6c; }
.hero-tag.tag-warning { background: rgba(230, 162, 60, 0.18); color: #e6a23c; }
.hero-tag.tag-success { background: rgba(103, 194, 58, 0.18); color: #67c23a; }
.hero-tag.tag-alarm   { background: rgba(87, 209, 255, 0.12); color: #8adfff; }

/* ---- 健康指数进度条 ---- */
.health-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.health-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.health-header span {
  color: #7a94a8;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
}
.health-header strong {
  color: #f4fbff;
  font-size: 24px;
  font-weight: 800;
}
.health-bar-wrap {
  height: 14px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}
.health-bar {
  height: 100%;
  border-radius: 7px;
  transition: width 0.5s ease;
  box-shadow: 0 0 8px rgba(87, 209, 255, 0.20);
}
/* 健康条渐变色：>=80 蓝→绿，>=60 蓝→黄，<60 黄→红 */
.health-bar.bar-high { background: linear-gradient(90deg, #57d1ff, #67c23a); }
.health-bar.bar-mid  { background: linear-gradient(90deg, #57d1ff, #e6a23c); }
.health-bar.bar-low  { background: linear-gradient(90deg, #e6a23c, #f56c6c); }

/* =====================================================================
   关键指标卡片 - 2列网格布局
   ===================================================================== */
.metrics-card ::v-deep .el-card__body {
  padding: 4px 6px;
}
.metrics-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px;
}
.metric-cell {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 3px 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(87, 209, 255, 0.06);
}
.metric-name {
  color: #94a3b8;
  font-size: 10px;
  flex-shrink: 0;
  margin-right: 4px;
}
.metric-val {
  color: #e2e8f0;
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;  /* 等宽数字，避免数值变化时布局抖动 */
}

/* =====================================================================
   决策原因卡片
   ===================================================================== */
.reason-card ::v-deep .el-card__body {
  padding: 8px 14px;
}
.reason-text {
  color: #e2e8f0;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

/* =====================================================================
   证据链卡片 — 自动扩展显示全部
   ===================================================================== */
.evidence-card ::v-deep .el-card__body {
  padding: 6px 12px;
  display: flex;
  flex-direction: column;
}
.evidence-scroll {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.evidence-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 4px;
  border: 1px solid #333333;
  background: rgba(255, 255, 255, 0.02);
  font-size: 13px;
}
/* 证据类型圆点指示器 */
.ev-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ev-dot.dot-success { background: #4ade80; }
.ev-dot.dot-warning { background: #fbbf24; }
.ev-dot.dot-info    { background: #60a5fa; }
.ev-dot.dot-danger  { background: #f87171; }
.ev-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.ev-title {
  color: #e2e8f0;
  font-weight: 600;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ev-desc {
  color: #94a3b8;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* =====================================================================
   各类别概率卡片
   ===================================================================== */
.prob-card ::v-deep .el-card__body {
  padding: 8px 14px;
}
.prob-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.prob-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
}
/* 类别名称 - 固定宽度，溢出省略 */
.prob-class {
  color: #e2e8f0;
  font-size: 13px;
  width: 100px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 概率条轨道 */
.prob-track {
  flex: 1;
  height: 16px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}
/* 概率条填充 */
.prob-fill {
  height: 100%;
  border-radius: 7px;
  transition: width 0.4s ease;
  min-width: 2px;  /* 即使概率很低也可见 */
}
.prob-fill.fill-high { background: linear-gradient(90deg, #57d1ff, #67c23a); }
.prob-fill.fill-mid  { background: linear-gradient(90deg, #57d1ff, #e6a23c); }
.prob-fill.fill-low  { background: #57d1ff; }
/* 百分比数字 */
.prob-pct {
  color: #d9e8f3;
  font-size: 11px;
  font-weight: 600;
  width: 48px;
  text-align: right;
  flex-shrink: 0;
}

/* =====================================================================
   颜色辅助类 - 全局通用的状态色调
   ===================================================================== */
.tone-running { color: #ffd166 !important; }
.tone-failed  { color: #ff8a8a !important; }
.tone-done    { color: #8ee28e !important; }

/* =====================================================================
   历史记录表格
   ===================================================================== */
.history-card {
  flex-shrink: 0;
}
.history-card ::v-deep .el-card__body {
  padding: 0;
}
/* 紧凑表格：透明背景，融入深色主题 */
.compact-table ::v-deep .el-table,
.compact-table ::v-deep .el-table th,
.compact-table ::v-deep .el-table tr,
.compact-table ::v-deep .el-table td {
  background: transparent !important;
  color: #d9e8f3;
  font-size: 12px;
}
.compact-table ::v-deep .el-table th {
  padding: 6px 0;
  color: #6b8599;
  font-weight: 600;
}
.compact-table ::v-deep .el-table td {
  padding: 4px 0;
}
.compact-table ::v-deep .el-table::before {
  background-color: rgba(87, 209, 255, 0.08);
}

/* =====================================================================
   上传弹窗
   ===================================================================== */
.download-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.download-tip {
  flex-shrink: 0;
}
.download-device-row {
  margin-top: 4px;
}

.upload-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.upload-tip {
  flex-shrink: 0;
}
/* el-alert 深色主题覆盖 */
.upload-dialog-body ::v-deep .el-alert--info,
.download-dialog-body ::v-deep .el-alert--info {
  background-color: rgba(0, 255, 255, 0.06);
  border-color: rgba(0, 255, 255, 0.18);
}
.upload-dialog-body ::v-deep .el-alert__title,
.download-dialog-body ::v-deep .el-alert__title {
  color: #b0c8da;
}
.upload-dialog-body ::v-deep .el-alert__icon,
.download-dialog-body ::v-deep .el-alert__icon {
  color: #57d1ff;
}
.upload-dropzone {
  border: 2px dashed rgba(87, 209, 255, 0.25);
  border-radius: 8px;
  padding: 28px 16px;
  text-align: center;
  cursor: pointer;
  background: rgba(255, 255, 255, 0.02);
  transition: border-color 0.3s, background 0.3s;
}
.upload-dropzone:hover {
  border-color: rgba(87, 209, 255, 0.55);
  background: rgba(87, 209, 255, 0.04);
}
.upload-dropzone .el-icon-upload {
  font-size: 36px;
  color: #6b8599;
  margin-bottom: 6px;
}
.upload-dropzone .el-upload__text {
  color: #b0c8da;
  font-size: 13px;
}
.upload-dropzone .el-upload__text em {
  color: #57d1ff;
  font-style: normal;
}
.upload-dropzone .el-upload__tip {
  color: #6b8599;
  font-size: 11px;
  margin-top: 6px;
}
.path-upload-row {
  display: flex;
  gap: 8px;
}
.path-upload-row .el-input { flex: 1; }
.mat-file-row { margin-top: 4px; }

/* =====================================================================
   响应式布局
   ===================================================================== */
/* 中等屏幕（≤992px）：图表和信息栏上下排列 */
@media (max-width: 992px) {
  .main-area {
    flex-direction: column;
  }
  .charts-column {
    flex: none;
    height: 50%;
  }
  .info-column {
    flex: none;
    max-width: none;
    overflow-y: visible;
  }
  .top-bar { flex-direction: column; align-items: flex-start; }
}
/* 小屏幕（≤768px）：所有区域纵向堆叠，允许整页滚动 */
@media (max-width: 768px) {
  .diagnosis-page {
    height: auto;
    min-height: calc(100vh - 84px);
    overflow-y: auto;
  }
  .main-area { flex-direction: column; }
  .charts-column { height: 400px; flex: none; }
  .chart-box { height: 180px; flex: none; }
  .info-column { max-width: none; }
}

/* 深色主题统一覆盖 — 确保所有元素在暗色背景下可视 */
.diagnosis-page {
  background: #121212;
  color: #e2e8f0;
}
.top-bar,
.panel-card {
  background: #1a1a1a;
  border: 1px solid #444444;
  box-shadow: 0 8px 18px rgba(0,0,0,0.3);
}
.top-eyebrow,
.card-badge,
.upload-dropzone .el-upload__text em {
  color: #ffb300;
}
.top-divider {
  color: #555555;
}
.top-time,
.card-unit,
.empty-overlay,
.prob-class {
  color: #94a3b8;
}
.top-file,
.card-title,
.ring-num,
.prob-pct {
  color: #ffffff;
}
.metric-name,
.ev-desc,
.download-dialog-body ::v-deep .el-alert__title,
.upload-dialog-body ::v-deep .el-alert__title,
.upload-dropzone .el-upload__tip {
  color: #94a3b8;
}
.metric-val,
.hero-label,
.health-header strong {
  color: #ffffff;
}
.health-header span {
  color: #94a3b8;
}
.panel-card ::v-deep .el-card__header,
.health-section {
  border-bottom-color: #333333;
  border-top-color: #333333;
}
.card-badge,
.hero-tag.tag-alarm {
  background: #262626;
  color: #ffffff;
}
.result-card {
  background: #1a1a1a !important;
  border-color: #333333 !important;
  box-shadow: 0 10px 24px rgba(0,0,0,0.4) !important;
}
.conf-ring {
  filter: none;
}
.ring-track {
  stroke: #333333;
}
.hero-label {
  text-shadow: none;
}
.hero-label.tone-running,
.hero-label.tone-failed,
.hero-label.tone-done {
  text-shadow: none;
}
.health-bar-wrap,
.prob-track {
  background: #2a2a2a;
}
.metric-cell,
.evidence-row {
  background: #1e1e1e !important;
  border-color: #333333 !important;
}
.reason-text,
.ev-title {
  color: #e2e8f0;
}
.compact-table ::v-deep .el-table {
  background: #1a1a1a !important;
}
.compact-table ::v-deep .el-table th {
  color: #94a3b8;
  background: #171717 !important;
}
.compact-table ::v-deep .el-table tr,
.compact-table ::v-deep .el-table td {
  color: #e2e8f0;
  background: #1a1a1a !important;
}
.compact-table ::v-deep .el-table::before {
  background-color: #333333;
}
.upload-dialog-body ::v-deep .el-alert--info,
.download-dialog-body ::v-deep .el-alert--info {
  background-color: #1a2a3a;
  border-color: #2a3a4a;
}
.upload-dialog-body ::v-deep .el-alert__icon,
.download-dialog-body ::v-deep .el-alert__icon,
.upload-dropzone .el-icon-upload {
  color: #60a5fa;
}
.upload-dropzone {
  background: #1a1a1a;
  border-color: #444444;
}
.upload-dropzone:hover {
  background: #1f1f1f;
  border-color: #ffb300;
}
.upload-dropzone .el-upload__text {
  color: #e2e8f0;
}
</style>

<style>
/* =====================================================================
   深色主题：el-dialog 弹窗（append-to-body 后脱离组件作用域，需全局样式）
   ===================================================================== */
.dark-dialog {
  background: linear-gradient(180deg, #0b1a2e 0%, #0f2237 100%);
  border: 1px solid rgba(87, 209, 255, 0.2);
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5), 0 0 20px rgba(87, 209, 255, 0.08);
}
.dark-dialog .el-dialog__header {
  padding: 14px 20px 10px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.dark-dialog .el-dialog__title {
  color: #e0edf6;
  font-weight: 700;
  font-size: 15px;
}
.dark-dialog .el-dialog__headerbtn {
  top: 14px;
  right: 16px;
}
.dark-dialog .el-dialog__headerbtn .el-dialog__close {
  color: #6b8599;
  font-size: 18px;
}
.dark-dialog .el-dialog__headerbtn .el-dialog__close:hover {
  color: #57d1ff;
}
.dark-dialog .el-dialog__body {
  padding: 16px 20px;
  color: #b0c8da;
}
.dark-dialog .el-dialog__footer {
  padding: 10px 20px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
/* 弹窗内的输入框和日期选择器深色覆盖 */
.dark-dialog .el-input__inner {
  background: rgba(7, 19, 31, 0.7);
  border-color: rgba(87, 209, 255, 0.18);
  color: #d9e8f3;
}
.dark-dialog .el-input__inner::placeholder {
  color: #5a778b;
}
.dark-dialog .el-input__inner:focus,
.dark-dialog .el-input__inner:hover {
  border-color: rgba(87, 209, 255, 0.45);
}
.dark-dialog .el-range-separator {
  color: #6b8599;
}
.dark-dialog .el-date-editor .el-range-input {
  background: transparent;
  color: #d9e8f3;
}
.dark-dialog .el-date-editor .el-range__icon,
.dark-dialog .el-date-editor .el-range__close-icon {
  color: #6b8599;
}
.dark-dialog .el-input__prefix,
.dark-dialog .el-input__suffix {
  color: #6b8599;
}

/* 深色主题：日期选择器下拉面板（append-to-body 后脱离组件作用域，需全局样式） */
.dark-date-picker {
  background: #0b1a2e;
  border: 1px solid rgba(87, 209, 255, 0.2);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.5);
  color: #d9e8f3;
}
.dark-date-picker .el-picker-panel__body-wrapper {
  background: #0b1a2e;
}
.dark-date-picker .el-date-range-picker__header {
  color: #b0c8da;
  font-size: 12px;
}
.dark-date-picker .el-date-range-picker__header button {
  color: #b0c8da;
}
.dark-date-picker .el-date-range-picker__header button:hover {
  color: #57d1ff;
}
.dark-date-picker .el-picker-panel__content {
  background: #0b1a2e;
}
.dark-date-picker .el-date-table th {
  color: #6b8599;
  border-bottom-color: rgba(255, 255, 255, 0.06);
}
.dark-date-picker .el-date-table td {
  color: #d9e8f3;
  background: transparent;
}
.dark-date-picker .el-date-table td.available:hover {
  color: #57d1ff;
}
.dark-date-picker .el-date-table td.today span {
  color: #57d1ff;
  font-weight: 700;
}
.dark-date-picker .el-date-table td.current:not(.disabled) span {
  background: rgba(87, 209, 255, 0.25);
  color: #fff;
}
.dark-date-picker .el-date-table td.in-range div {
  background: rgba(87, 209, 255, 0.08);
}
.dark-date-picker .el-date-table td.start-date span,
.dark-date-picker .el-date-table td.end-date span {
  background: #409eff;
  color: #fff;
}
.dark-date-picker .el-date-table td.disabled div {
  background: transparent;
  color: #4a5e6e;
}
.dark-date-picker .el-date-table td.next-month,
.dark-date-picker .el-date-table td.prev-month {
  color: #4a5e6e;
}
.dark-date-picker .el-date-table td.next-month:hover,
.dark-date-picker .el-date-table td.prev-month:hover {
  color: #6b8599;
}
.dark-date-picker .el-picker-panel__footer {
  background: #0b1a2e;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.dark-date-picker .el-picker-panel__footer .el-button--default {
  color: #b0c8da;
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(87, 209, 255, 0.15);
}
.dark-date-picker .el-picker-panel__footer .el-button--default:hover {
  color: #57d1ff;
  border-color: rgba(87, 209, 255, 0.4);
}
.dark-date-picker .el-time-panel {
  background: #0b1a2e;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
}
.dark-date-picker .el-time-panel__content::after,
.dark-date-picker .el-time-panel__content::before {
  background: transparent;
}
.dark-date-picker .el-time-spinner__item {
  color: #6b8599;
}
.dark-date-picker .el-time-spinner__item.active:not(.disabled) {
  color: #57d1ff;
  font-weight: 700;
}

/* 深色主题：el-select 下拉面板（append-to-body 后脱离组件作用域，需全局样式） */
.dark-select-dropdown.el-select-dropdown {
  background: rgba(1, 12, 28, 0.95);
  border: 1px solid rgba(0, 255, 255, 0.35);
  box-shadow: 0 0 18px rgba(0, 0, 0, 0.4);
}
.dark-select-dropdown .el-select-dropdown__item {
  color: #d9e8f3;
}
.dark-select-dropdown .el-select-dropdown__item.hover,
.dark-select-dropdown .el-select-dropdown__item:hover {
  background: rgba(0, 255, 255, 0.1);
}
.dark-select-dropdown .el-select-dropdown__item.selected {
  color: #89d8ff;
  font-weight: 700;
}
.dark-select-dropdown .el-select-dropdown__empty {
  color: #6b8599;
}

/* 深色主题：诊断页弹窗与浮层（append-to-body 元素，需全局样式覆盖） */
</style>
