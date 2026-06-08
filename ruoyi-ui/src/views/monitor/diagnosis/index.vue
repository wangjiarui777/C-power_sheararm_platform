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

    <!-- ===== 主体区域：左图表(35%) + 右诊断面板(65%) ===== -->
    <div class="main-area">
      <!-- 左栏：时域图 + 频域图（上下各占50%） -->
      <div class="charts-column">
        <!-- 时域波形图卡片 -->
        <el-card shadow="hover" class="panel-card chart-card">
          <div slot="header" class="card-header">
            <span class="card-title">时域图</span>
            <span class="card-unit">位移 / mm</span>
          </div>
          <!-- ECharts 挂载容器 -->
          <div ref="timeChartRef" class="chart-box"></div>
          <div v-if="!hasTimeData" class="empty-overlay">暂无数据</div>
        </el-card>
        <!-- 频域频谱图卡片 -->
        <el-card shadow="hover" class="panel-card chart-card">
          <div slot="header" class="card-header">
            <span class="card-title">频域图</span>
            <span class="card-unit">归一化幅值</span>
          </div>
          <div ref="freqChartRef" class="chart-box"></div>
          <div v-if="!hasFreqData" class="empty-overlay">暂无数据</div>
        </el-card>
      </div>

      <!-- 右栏：诊断信息面板（垂直排列，可滚动） -->
      <div class="info-column">
        <!-- 诊断结果卡片（视觉上最突出，左侧有彩色装饰条） -->
        <el-card shadow="hover" class="panel-card result-card">
          <div class="result-hero">
            <!-- 环形置信度仪表盘（SVG 手绘，避免引入额外图表库） -->
            <div class="conf-ring">
              <svg viewBox="0 0 100 100">
                <!-- 底色轨道 -->
                <circle class="ring-track" cx="50" cy="50" r="42" />
                <!-- 填充弧线：dasharray 动态控制进度的 SVG 环形图 -->
                <circle class="ring-bar" cx="50" cy="50" r="42"
                  :stroke-dasharray="confidenceDashArray"
                  :class="confidenceRingClass" />
              </svg>
              <div class="ring-inner">
                <span class="ring-num">{{ confidenceText }}</span>
              </div>
            </div>
            <!-- 诊断标签 + 风险/告警标签 -->
            <div class="hero-info">
              <div class="hero-label" :class="resultToneClass">{{ diagnosisName || '--' }}</div>
              <div class="hero-tags">
                <span class="hero-tag" :class="'tag-' + riskBadgeClass">风险: {{ riskLevel || '--' }}</span>
                <span class="hero-tag tag-alarm">告警: {{ alarmLevelText }}</span>
              </div>
            </div>
          </div>
          <!-- 健康指数进度条 -->
          <div class="health-section">
            <div class="health-header">
              <span>健康指数</span>
              <strong>{{ healthIndex > 0 ? healthIndex : '--' }}</strong>
            </div>
            <div class="health-bar-wrap">
              <!-- 进度条颜色：>=80 绿色，>=60 黄色，<60 红色 -->
              <div class="health-bar" :style="{ width: healthBarPercent + '%' }" :class="healthBarClass"></div>
            </div>
          </div>
        </el-card>

        <!-- 关键指标卡片（2列网格） -->
        <el-card shadow="hover" class="panel-card metrics-card">
          <div slot="header" class="card-header">
            <span class="card-title">关键指标</span>
          </div>
          <div class="metrics-grid">
            <div class="metric-cell">
              <span class="metric-name">RMS</span>
              <span class="metric-val">{{ displayMetric(latestRms, 4) }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-name">Peak</span>
              <span class="metric-val">{{ displayMetric(latestPeak, 4) }}</span>
            </div>
            <!-- Unknown 比例：>0.5 红色，>0.3 黄色，否则默认色 -->
            <div class="metric-cell">
              <span class="metric-name">Unknown 比例</span>
              <span class="metric-val" :class="unknownRatio > 0.5 ? 'tone-failed' : unknownRatio > 0.3 ? 'tone-running' : ''">{{ displayMetric(unknownRatio, 4) }}</span>
            </div>
            <!-- 片段一致性：>0.8 绿色，>0.5 黄色 -->
            <div class="metric-cell">
              <span class="metric-name">片段一致性</span>
              <span class="metric-val" :class="segmentConsistency > 0.8 ? 'tone-done' : segmentConsistency > 0.5 ? 'tone-running' : ''">{{ displayMetric(segmentConsistency, 4) }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-name">Mean Mahalanobis</span>
              <span class="metric-val">{{ displayMetric(meanMahalanobis, 2) }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-name">Average Entropy</span>
              <span class="metric-val">{{ displayMetric(meanEntropy, 4) }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-name">闭集预测</span>
              <span class="metric-val">{{ closedPredictionText }}</span>
            </div>
            <div class="metric-cell">
              <span class="metric-name">采样率</span>
              <span class="metric-val">{{ sampleRate > 0 ? sampleRate + ' Hz' : '--' }}</span>
            </div>
          </div>
        </el-card>

        <!-- 决策原因卡片：显示模型做出当前诊断的判断依据 -->
        <el-card v-if="decisionReason" shadow="hover" class="panel-card reason-card">
          <div slot="header" class="card-header">
            <span class="card-title">决策原因</span>
          </div>
          <div class="reason-text">{{ decisionReason }}</div>
        </el-card>

        <!-- 证据链卡片：列出所有诊断证据项（类型图标 + 标题 + 描述） -->
        <el-card v-if="evidence.length" shadow="hover" class="panel-card evidence-card">
          <div slot="header" class="card-header">
            <span class="card-title">证据链</span>
            <span class="card-badge">{{ evidence.length }}</span>
          </div>
          <!-- 可滚动区域，容纳多条证据 -->
          <div class="evidence-scroll">
            <div v-for="(item, i) in evidence" :key="i" class="evidence-row">
              <!-- 证据类型圆点：success 绿 / warning 黄 / info 蓝 / danger 红 -->
              <span class="ev-dot" :class="'dot-' + (item.type || 'info')"></span>
              <span class="ev-title">{{ item.title }}</span>
              <span class="ev-desc">{{ item.desc }}</span>
            </div>
          </div>
        </el-card>

        <!-- 各类别概率卡片：水平条形图展示 Top-N 故障类别的预测概率 -->
        <el-card v-if="topProbabilities.length" shadow="hover" class="panel-card prob-card">
          <div slot="header" class="card-header">
            <span class="card-title">各类别概率</span>
          </div>
          <div class="prob-list">
            <div v-for="(item, i) in topProbabilities" :key="i" class="prob-row">
              <span class="prob-class">{{ item.class }}</span>
              <!-- 概率进度条 -->
              <div class="prob-track">
                <div class="prob-fill" :class="getProbBarClass(item.probability)" :style="{ width: Math.max(item.probability, 1) + '%' }"></div>
              </div>
              <span class="prob-pct">{{ item.probability.toFixed(1) }}%</span>
            </div>
          </div>
        </el-card>
      </div>
    </div>

    <!-- ===== 底部：历史诊断记录表格 ===== -->
    <el-card v-if="historyList.length" shadow="hover" class="panel-card history-card">
      <div slot="header" class="card-header">
        <span class="card-title">历史记录</span>
        <span class="card-badge">{{ historyList.length }}</span>
      </div>
      <!-- 紧凑表格：时间、结果、置信度、健康指数、风险等级 -->
      <el-table :data="historyList" size="mini" class="compact-table" max-height="160">
        <el-table-column label="时间" width="160">
          <template slot-scope="scope">{{ parseTime(scope.row.sampleTime) }}</template>
        </el-table-column>
        <el-table-column prop="diagnosisResult" label="结果" min-width="120" />
        <el-table-column prop="confidence" label="置信度" width="80" />
        <el-table-column prop="healthIndex" label="健康指数" width="90" />
        <el-table-column prop="riskLevel" label="风险" width="70">
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
          <el-button type="primary" :loading="uploading" @click="uploadByPath(localFilePath)">提交 infer</el-button>
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
    /** 告警等级文本（优先 alarmLevel，回退 riskLevel） */
    alarmLevelText() {
      return this.alarmLevel || this.riskLevel || '--'
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
      const c = 2 * Math.PI * 42
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
          name: this.timeAxis.length ? '时间/s' : '采样点',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10, interval: 'auto', showMaxLabel: true, showMinLabel: true }
        },
        yAxis: {
          type: 'value',
          name: '位移/mm',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10 },
          splitLine: { lineStyle: { color: '#e5eaf1' } }
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
          name: '频率/Hz',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10, interval: 'auto', showMaxLabel: true, showMinLabel: true }
        },
        yAxis: {
          type: 'value',
          name: '归一化幅值',
          nameTextStyle: { color: '#475569', fontSize: 10 },
          axisLabel: { color: '#64748b', fontSize: 10 },
          splitLine: { lineStyle: { color: '#e5eaf1' } }
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
      }, 100)
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
        this.diagnosisName = data.diagnosisResult || data.diagnosisName || data.label
      }
      if (data.diagnosisDetail || data.diagnosis_detail) {
        this.diagnosisDetail = data.diagnosisDetail || data.diagnosis_detail
      }
      if (data.decision_reason) this.decisionReason = data.decision_reason
      if (data.closedPrediction || data.closed_prediction) {
        this.closedPrediction = data.closedPrediction || data.closed_prediction
      }
      if (data.confidence != null) this.confidence = Math.max(0, Math.min(100, Number(data.confidence)))
      if (data.healthIndex != null) this.healthIndex = Number(data.healthIndex)
      if (data.riskLevel) this.riskLevel = data.riskLevel
      if (data.alarmLevel || data.alarm_level) this.alarmLevel = data.alarmLevel || data.alarm_level

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
          'RMS', 'Peak',
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
  height: calc(100vh - 84px);  /* 减去顶栏高度 */
  padding: 8px 12px;
  background: linear-gradient(180deg, #07131f 0%, #0a1c2d 100%);  /* 深蓝黑渐变背景 */
  overflow: hidden;
}

/* =====================================================================
   顶栏 - 状态指示条
   ===================================================================== */
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 6px 16px;
  margin-bottom: 8px;
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
  font-size: 12px;
  font-weight: 700;
  padding: 2px 10px;
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
   主体区域 - flex 横向布局
   ===================================================================== */
.main-area {
  flex: 1;
  min-height: 0;  /* 允许 flex 子元素收缩，配合 overflow 实现滚动 */
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
}

/* =====================================================================
   图表栏（左侧 35%）
   ===================================================================== */
.charts-column {
  flex: 3.5;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}
.chart-card {
  flex: 1;
  min-height: 0;
}

/* =====================================================================
   信息栏（右侧 65%）
   ===================================================================== */
.info-column {
  flex: 6.5;
  min-width: 300px;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;     /* 内容过多时纵向滚动 */
  overflow-x: hidden;
  min-height: 0;
}
.info-column > .panel-card {
  flex-shrink: 0;  /* 卡片不收缩，保持完整内容可见 */
}

/* =====================================================================
   通用面板卡片 - 统一的深色半透明风格
   ===================================================================== */
.panel-card {
  border-radius: 10px;
  border: 1px solid rgba(87, 209, 255, 0.12);
  background: rgba(7, 19, 31, 0.92);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-card ::v-deep .el-card__header {
  padding: 6px 12px;
  border-bottom-color: rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}
.panel-card ::v-deep .el-card__body {
  padding: 8px 12px;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card-title {
  color: #f4fbff;
  font-weight: 700;
  font-size: 13px;
}
.card-unit {
  color: #6b8599;
  font-size: 11px;
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
  padding: 4px 8px;
}
.chart-box {
  flex: 1;
  min-height: 120px;
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
  gap: 18px;
  padding: 20px 22px 18px 22px;
  overflow: visible;
}

/* ---- 英雄区域：环形置信度 + 诊断标签 ---- */
.result-hero {
  display: flex;
  align-items: center;
  gap: 22px;
}

/* ---- SVG 环形置信度仪表盘 ---- */
.conf-ring {
  width: 100px;
  height: 100px;
  flex-shrink: 0;
  position: relative;
  filter: drop-shadow(0 0 8px rgba(87, 209, 255, 0.25));  /* 发光效果 */
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
  stroke-width: 8;
}
/* 填充弧线：颜色由 confidenceRingClass 计算属性决定 */
.ring-bar {
  fill: none;
  stroke-width: 8;
  stroke-linecap: round;
  transition: stroke-dasharray 0.5s ease, stroke 0.4s ease;
  filter: drop-shadow(0 0 4px currentColor);
}
/* 置信度 >= 80% 绿色，>= 50% 黄色，< 50% 红色 */
.ring-bar.fill-high { stroke: #67c23a; }
.ring-bar.fill-mid  { stroke: #e6a23c; }
.ring-bar.fill-low  { stroke: #f56c6c; }
/* 环内文字：居中显示置信度百分比 */
.ring-inner {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ring-num {
  color: #f4fbff;
  font-size: 22px;
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
  padding: 6px 12px;
}
.metrics-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;  /* 两列等宽 */
  gap: 2px;
}
.metric-cell {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 5px 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px solid rgba(87, 209, 255, 0.06);
}
.metric-name {
  color: #6b8599;
  font-size: 11px;
}
.metric-val {
  color: #d9e8f3;
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;  /* 等宽数字，避免数值变化时布局抖动 */
}

/* =====================================================================
   决策原因卡片
   ===================================================================== */
.reason-card ::v-deep .el-card__body {
  padding: 8px 12px;
}
.reason-text {
  color: #b0c8da;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}

/* =====================================================================
   证据链卡片 - 可滚动列表
   ===================================================================== */
.evidence-card {
  max-height: 220px;
}
.evidence-card ::v-deep .el-card__body {
  padding: 4px 12px;
  display: flex;
  flex-direction: column;
}
.evidence-scroll {
  flex: 1;
  overflow-y: auto;  /* 证据条数过多时可滚动 */
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.evidence-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.02);
  font-size: 12px;
}
/* 证据类型圆点指示器 */
.ev-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ev-dot.dot-success { background: #67c23a; }
.ev-dot.dot-warning { background: #e6a23c; }
.ev-dot.dot-info    { background: #57d1ff; }
.ev-dot.dot-danger  { background: #f56c6c; }
.ev-title {
  color: #c0d4e3;
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
}
.ev-desc {
  color: #7a94a8;
  margin-left: auto;  /* 推至右侧 */
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* =====================================================================
   各类别概率卡片
   ===================================================================== */
.prob-card ::v-deep .el-card__body {
  padding: 6px 12px;
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
}
/* 类别名称 - 固定宽度，溢出省略 */
.prob-class {
  color: #c0d4e3;
  font-size: 11px;
  width: 80px;
  flex-shrink: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 概率条轨道 */
.prob-track {
  flex: 1;
  height: 14px;
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
  max-height: 200px;
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

/* 浅色工业生产主题覆盖 */
.diagnosis-page {
  background: linear-gradient(180deg, #fbfcfe 0%, #f3f6f8 100%);
  color: #1f2937;
}
.top-bar,
.panel-card {
  background: #ffffff;
  border-color: #d7dee8;
  box-shadow: 0 8px 18px rgba(31, 41, 55, 0.08);
}
.top-eyebrow,
.card-badge,
.upload-dropzone .el-upload__text em {
  color: #2563eb;
}
.top-divider {
  color: #cbd5e1;
}
.top-time,
.card-unit,
.metric-name,
.ev-desc,
.download-dialog-body ::v-deep .el-alert__title,
.upload-dialog-body ::v-deep .el-alert__title,
.upload-dropzone .el-upload__tip {
  color: #64748b;
}
.top-file,
.card-title,
.metric-val,
.ring-num,
.hero-label,
.health-header strong,
.prob-pct,
.compact-table ::v-deep .el-table,
.compact-table ::v-deep .el-table th,
.compact-table ::v-deep .el-table tr,
.compact-table ::v-deep .el-table td {
  color: #1f2937;
}
.panel-card ::v-deep .el-card__header,
.health-section {
  border-bottom-color: #e5eaf1;
  border-top-color: #e5eaf1;
}
.card-badge,
.hero-tag.tag-alarm {
  background: #eef6ff;
}
.empty-overlay,
.health-header span,
.prob-class {
  color: #64748b;
}
.result-card {
  background: linear-gradient(160deg, #ffffff 0%, #f4f8fc 100%) !important;
  border-color: #b8c3d4 !important;
  box-shadow: 0 10px 24px rgba(31, 41, 55, 0.10) !important;
}
.conf-ring {
  filter: none;
}
.ring-track {
  stroke: #e5eaf1;
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
  background: #e5eaf1;
}
.metric-cell,
.evidence-row {
  background: #f8fafc;
  border-color: #e5eaf1;
}
.reason-text,
.ev-title {
  color: #344054;
}
.compact-table ::v-deep .el-table th {
  color: #475569;
  background: #eef3f8 !important;
}
.compact-table ::v-deep .el-table td {
  background: #ffffff !important;
}
.compact-table ::v-deep .el-table::before {
  background-color: #d7dee8;
}
.upload-dialog-body ::v-deep .el-alert--info,
.download-dialog-body ::v-deep .el-alert--info {
  background-color: #eef6ff;
  border-color: #bfd7f2;
}
.upload-dialog-body ::v-deep .el-alert__icon,
.download-dialog-body ::v-deep .el-alert__icon,
.upload-dropzone .el-icon-upload {
  color: #2563eb;
}
.upload-dropzone {
  background: #f8fafc;
  border-color: #cbd5e1;
}
.upload-dropzone:hover {
  background: #eef6ff;
  border-color: #2563eb;
}
.upload-dropzone .el-upload__text {
  color: #344054;
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

/* 浅色工业生产主题：诊断页弹窗与浮层 */
.dark-dialog {
  background: #ffffff;
  border-color: #d7dee8;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.16);
}
.dark-dialog .el-dialog__header,
.dark-dialog .el-dialog__footer {
  border-color: #e5eaf1;
}
.dark-dialog .el-dialog__title,
.dark-dialog .el-dialog__body,
.dark-dialog .el-date-editor .el-range-input {
  color: #1f2937;
}
.dark-dialog .el-dialog__headerbtn .el-dialog__close,
.dark-dialog .el-range-separator,
.dark-dialog .el-date-editor .el-range__icon,
.dark-dialog .el-date-editor .el-range__close-icon,
.dark-dialog .el-input__prefix,
.dark-dialog .el-input__suffix {
  color: #64748b;
}
.dark-dialog .el-dialog__headerbtn .el-dialog__close:hover {
  color: #2563eb;
}
.dark-dialog .el-input__inner {
  background: #ffffff;
  border-color: #cbd5e1;
  color: #1f2937;
}
.dark-dialog .el-input__inner::placeholder {
  color: #94a3b8;
}
.dark-dialog .el-input__inner:focus,
.dark-dialog .el-input__inner:hover {
  border-color: #2563eb;
}
.dark-date-picker,
.dark-date-picker .el-picker-panel__body-wrapper,
.dark-date-picker .el-picker-panel__content,
.dark-date-picker .el-picker-panel__footer,
.dark-date-picker .el-time-panel,
.dark-select-dropdown.el-select-dropdown {
  background: #ffffff;
  color: #1f2937;
  border-color: #d7dee8;
  box-shadow: 0 8px 20px rgba(31, 41, 55, 0.12);
}
.dark-date-picker .el-date-range-picker__header,
.dark-date-picker .el-date-range-picker__header button,
.dark-date-picker .el-time-spinner__item,
.dark-select-dropdown .el-select-dropdown__item {
  color: #344054;
}
.dark-date-picker .el-date-table th,
.dark-date-picker .el-date-table td.next-month,
.dark-date-picker .el-date-table td.prev-month,
.dark-date-picker .el-date-table td.disabled div,
.dark-select-dropdown .el-select-dropdown__empty {
  color: #94a3b8;
}
.dark-date-picker .el-date-table th,
.dark-date-picker .el-picker-panel__footer,
.dark-date-picker .el-time-panel {
  border-color: #e5eaf1;
}
.dark-date-picker .el-date-table td {
  color: #1f2937;
}
.dark-date-picker .el-date-table td.available:hover,
.dark-date-picker .el-date-table td.today span,
.dark-date-picker .el-date-range-picker__header button:hover,
.dark-select-dropdown .el-select-dropdown__item.selected {
  color: #2563eb;
}
.dark-date-picker .el-date-table td.current:not(.disabled) span,
.dark-date-picker .el-date-table td.start-date span,
.dark-date-picker .el-date-table td.end-date span {
  background: #2563eb;
  color: #ffffff;
}
.dark-date-picker .el-date-table td.in-range div,
.dark-select-dropdown .el-select-dropdown__item.hover,
.dark-select-dropdown .el-select-dropdown__item:hover {
  background: #eef6ff;
}
.dark-date-picker .el-picker-panel__footer .el-button--default {
  color: #344054;
  background: #ffffff;
  border-color: #cbd5e1;
}
.dark-date-picker .el-picker-panel__footer .el-button--default:hover {
  color: #2563eb;
  border-color: #2563eb;
}
</style>
