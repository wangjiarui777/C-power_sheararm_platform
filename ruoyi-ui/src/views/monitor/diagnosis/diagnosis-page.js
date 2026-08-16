import echarts from '@/utils/echarts'
import { getDiagnosisOptions, getInferenceHealth, inferWithAttachment, listMatFiles, uploadDiagnosisToInferenceService, fetchHistory, getServiceURL, createDiagnosisBatch, getDiagnosisBatch, retryDiagnosisBatch } from '@/api/system/bearingDiagnosis'
import { translateDiagnosisLabel, translateAlarmLevel, translateRiskLevel, translateAll } from '@/utils/diagnosis-translations'
import MeasurementPointOverview from './MeasurementPointOverview.vue'

export default {
  name: 'InferenceResultPage',
  components: {
    MeasurementPointOverview
  },

  data() {
    return {
      // ---- ECharts 实例与定时器 ----
      timeChart: null,      // 时域图 ECharts 实例
      freqChart: null,      // 频域图 ECharts 实例
      resizeTimer: null,    // 窗口 resize 防抖定时器
      polling: false,       // 是否正在请求中（防止同时间内多次请求）
      userManualMode: false, // 用户是否手动选择了文件（手动选择后停止自动刷新）
      chartDirty: false,    // 图表数据是否已更新待重绘（避免不必要的 render）

      // ---- 后端服务状态 ----
      serviceHealthy: false,  // 推理服务是否可达
      healthApiLabel: '',     // 健康状态文字标签

      // ---- 文件选择 ----
      matFileList: [],        // 已登记且当前用户可访问的后端附件
      selectedMatFile: '',    // 当前选中的附件 ID
      selectedDeviceCode: '',
      selectedPointIds: [],
      activePointId: '',
      selectedModelType: '',
      selectedModelVersion: '',
      multiPointEnabled: false,
      maxBatchPoints: 8,
      deviceOptions: [],
      pointOptions: [],
      modelTypeOptions: [],
      modelVersionOptions: [],
      optionsLoading: false,
      contextNotice: '',
      contextError: false,
      noAttachment: false,
      contextDebounceTimer: null,
      requestSequence: 0,
      contextInitialized: false,
      filename: '',           // 当前分析文件名
      filePath: '',           // 文件完整路径
      deviceCode: '',         // 设备编码（从分析结果回传）
      phmContext: {
        deviceCode: '',
        channelId: '',
        pointId: ''
      },

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
      uploadSourceTab: 'local',   // local / server
      pendingUploadFile: null,    // 等待用户确认上传的浏览器 File
      pendingUploadFiles: {},     // pointId -> File
      pointFileMappings: {},      // pointId -> attachment/file mapping
      activeMappingPointId: '',
      serverFileLoading: false,   // 服务器附件列表刷新状态
      lastAnalyzeResultText: '',  // 最近一次分析结果的文字摘要

      // ---- 多测点批次 ----
      diagnosisBatchId: '',
      diagnosisBatchStatus: '',
      diagnosisBatchItems: [],
      batchPollingTimer: null,

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
    detailMode() {
      const query = (this.$route && this.$route.query) || {}
      return query.view === 'detail' || Boolean(query.pointId || query.pointIds)
    },
    /** 格式化最后一次更新时间 */
    lastUpdateText() {
      return this.lastUpdate ? this.parseTime(this.lastUpdate) : ''
    },
    /** 当前选中文件的显示名称 */
    selectedFileLabel() {
      const mapping = this.pointFileMappings[String(this.activePointId)] || {}
      if (mapping.attachmentName) return mapping.attachmentName
      const selected = this.matFileList.find(item => String(item.id) === String(this.selectedMatFile))
      return (selected && (selected.label || selected.name)) || this.filename || '--'
    },
    selectedModelLabel() {
      const option = this.modelTypeOptions.find(item => item.value === this.selectedModelType)
      return option ? option.label : '--'
    },
    availableModelVersions() {
      return this.modelVersionOptions.filter(item => item.modelType === this.selectedModelType)
    },
    selectedVersionOption() {
      return this.availableModelVersions.find(item => item.semanticVersion === this.selectedModelVersion) || null
    },
    retiredVersionSelected() {
      return this.selectedVersionOption && this.selectedVersionOption.status === 'RETIRED'
    },
    selectedPointOption() {
      const target = this.activePointId || this.selectedPointIds[0]
      return this.pointOptions.find(item => String(item.id) === String(target)) || null
    },
    selectedPointOptions() {
      const ids = new Set(this.selectedPointIds.map(String))
      return this.pointOptions.filter(item => ids.has(String(item.id)))
    },
    availablePointOptions() {
      return this.pointOptions.filter(item => !this.selectedDeviceCode || item.deviceCode === this.selectedDeviceCode)
    },
    activeMappingPointOption() {
      return this.pointOptions.find(item => String(item.id) === String(this.activeMappingPointId)) || null
    },
    selectedPointLabel() {
      if (this.selectedPointOptions.length > 1) return `${this.selectedPointOptions.length} 个测点`
      return this.selectedPointOption ? this.pointOptionLabel(this.selectedPointOption) : '--'
    },
    contextComplete() {
      return Boolean(this.selectedDeviceCode && this.selectedPointIds.length && this.selectedModelType &&
        this.selectedModelVersion && this.selectedPointOptions.length === this.selectedPointIds.length && this.selectedVersionOption &&
        this.selectedVersionOption.available)
    },
    mappingRows() {
      return this.selectedPointOptions.map(point => {
        const mapping = this.pointFileMappings[String(point.id)] || {}
        const batchItem = this.diagnosisBatchItems.find(item => String(item.pointId) === String(point.id)) || {}
        return Object.assign({}, point, mapping, { batchItem })
      })
    },
    fileMappingComplete() {
      return this.mappingRows.length > 0 && this.mappingRows.every(row => row.attachmentId || row.localFile)
    },
    batchHasFailures() {
      return this.diagnosisBatchItems.some(item => item.status === 'FAILED' || item.status === 'INVALID')
    },
    batchProgressText() {
      if (!this.diagnosisBatchId) return ''
      const complete = this.diagnosisBatchItems.filter(item => ['SUCCEEDED', 'FAILED', 'INVALID'].includes(item.status)).length
      return `${complete}/${this.diagnosisBatchItems.length} 测点完成`
    },
    /** 根据当前模型类型返回对应的后端服务 URL */
    currentServiceBaseURL() {
      return getServiceURL(this.selectedModelType)
    },
    /** 置信度文本（百分比，取整） */
    confidenceText() {
      if (this.confidence == null || this.confidence === '') return '--'
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
      if (this.healthIndex == null || this.healthIndex === '') return 'bar-empty'
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
      if (!this.riskLevel) return 'empty'
      if (this.riskLevel === '高') return 'danger'
      if (this.riskLevel === '中') return 'warning'
      return 'success'
    },
    /** 环形置信度的颜色类 */
    confidenceRingClass() {
      if (this.confidence == null || this.confidence === '') return 'fill-empty'
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
    this.$nextTick(async () => {
      requestAnimationFrame(() => {
        this.initCharts()
      })
      window.addEventListener('resize', this.handleResize)
      // 浏览器只访问 Java 平台，不再直连内部 Python 推理服务。
      await this.loadDiagnosisOptions()
    })
  },

  watch: {
    detailMode(isDetail) {
      if (isDetail) {
        this.restoreDiagnosisContext()
        this.$nextTick(() => {
          requestAnimationFrame(() => this.initCharts())
        })
        return
      }
      this.disposeDetailCharts()
    }
  },

  /** 组件销毁前清理：移除事件监听、释放 WebSocket 连接、释放 ECharts 实例 */
  beforeDestroy() {
    window.removeEventListener('resize', this.handleResize)
    if (this.timeChart) this.timeChart.dispose()
    if (this.freqChart) this.freqChart.dispose()
    if (this.healthTrendChart) this.healthTrendChart.dispose()
    if (this.resizeTimer) clearTimeout(this.resizeTimer)
    if (this.contextDebounceTimer) clearTimeout(this.contextDebounceTimer)
    if (this.batchPollingTimer) clearTimeout(this.batchPollingTimer)
    this.requestSequence += 1
  },

  // =========================================================================
  // 方法
  // =========================================================================
  methods: {
    backToOverview() {
      this.$router.push({ path: this.$route.path })
    },
    disposeDetailCharts() {
      if (this.timeChart) this.timeChart.dispose()
      if (this.freqChart) this.freqChart.dispose()
      if (this.healthTrendChart) this.healthTrendChart.dispose()
      this.timeChart = null
      this.freqChart = null
      this.healthTrendChart = null
    },
    formatFileSize(bytes) {
      const value = Number(bytes)
      if (!Number.isFinite(value) || value <= 0) return '0 B'
      if (value < 1024) return `${value} B`
      if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
      return `${(value / 1024 / 1024).toFixed(1)} MB`
    },

    async loadDiagnosisOptions() {
      this.optionsLoading = true
      this.contextError = false
      this.contextNotice = '正在读取可用设备、测点和模型版本…'
      try {
        const response = await getDiagnosisOptions()
        const options = response.data || response || {}
        this.deviceOptions = Array.isArray(options.devices) ? options.devices : []
        this.pointOptions = Array.isArray(options.points) ? options.points : []
        this.modelTypeOptions = Array.isArray(options.modelTypes) ? options.modelTypes : []
        this.modelVersionOptions = Array.isArray(options.modelVersions) ? options.modelVersions : []
        this.multiPointEnabled = options.multiPointEnabled === true
        this.maxBatchPoints = Math.max(1, Number(options.maxBatchPoints) || 8)
        this.restoreDiagnosisContext()
      } catch (error) {
        this.contextError = true
        this.contextNotice = '诊断选项加载失败，请确认账号具有诊断查看权限。'
        console.error('诊断选项加载失败', error)
      } finally {
        this.optionsLoading = false
      }
    },
    restoreDiagnosisContext() {
      const query = (this.$route && this.$route.query) || {}
      let saved = {}
      try {
        saved = JSON.parse(window.localStorage.getItem('sensor.diagnosis.context.v2') || 'null') ||
          JSON.parse(window.localStorage.getItem('sensor.diagnosis.context.v1') || '{}')
      } catch (error) {
        saved = {}
      }

      const routeDevice = String(query.deviceCode || '').trim()
      const routePoints = String(query.pointIds || query.pointId || '').split(',').map(item => item.trim()).filter(Boolean)
      const routePoint = routePoints[0] || ''
      const routeModelType = String(query.modelType || '').trim()
      const routeModelVersion = String(query.modelVersion || '').trim()
      let deviceCode = routeDevice || String(saved.deviceCode || '').trim()
      let pointIds = routePoints.length ? routePoints
        : (Array.isArray(saved.pointIds) ? saved.pointIds.map(String) : [String(saved.pointId || '').trim()].filter(Boolean))
      let pointId = pointIds[0] || ''
      let modelType = routeModelType || String(saved.modelType || '').trim()
      let modelVersion = routeModelVersion || String(saved.modelVersion || '').trim()

      if (!this.deviceOptions.some(item => item.deviceCode === deviceCode)) deviceCode = ''
      const point = this.pointOptions.find(item => String(item.id) === pointId)
      if (!point || (routeDevice && !routePoint && point.deviceCode !== routeDevice)) {
        pointId = ''
      } else if (point) {
        // 测点是设备归属和通道的可信来源。
        deviceCode = point.deviceCode
      }
      pointIds = pointIds.filter(id => this.pointOptions.some(item => String(item.id) === id && (!deviceCode || item.deviceCode === deviceCode)))
      if (!this.multiPointEnabled) pointIds = pointIds.slice(0, 1)
      pointIds = pointIds.slice(0, this.maxBatchPoints)

      if (!this.modelTypeOptions.some(item => item.value === modelType)) modelType = ''
      const version = this.modelVersionOptions.find(item => item.modelType === modelType && item.semanticVersion === modelVersion)
      if (!version || !version.available) modelVersion = ''

      this.selectedDeviceCode = deviceCode
      this.selectedPointIds = pointIds
      this.activePointId = pointIds[0] || ''
      this.selectedModelType = modelType
      this.selectedModelVersion = modelVersion
      this.contextInitialized = true
      this.updateTrustedPointContext()
      this.persistDiagnosisContext()
      if (this.contextComplete) {
        this.contextNotice = '上下文已就绪，请为每个测点配置数据文件后开始诊断。'
      } else {
        this.contextNotice = '请选择设备、振动测点、模型类型和可执行版本。'
      }
    },
    pointOptionLabel(item) {
      const point = item.pointName || item.pointCode || `测点 ${item.id}`
      const device = item.deviceName || item.deviceCode || '未知设备'
      const channel = item.channelId == null ? '通道未配置' : `通道 ${item.channelId}`
      return `${point} / ${device} / ${channel}`
    },
    versionOptionLabel(item) {
      return `${item.semanticVersion} · ${item.status}${item.available ? '' : ' · 不可用'}`
    },
    updateTrustedPointContext() {
      const point = this.selectedPointOption
      this.phmContext.deviceCode = this.selectedDeviceCode
      this.phmContext.pointId = point ? String(point.id) : ''
      this.phmContext.channelId = point && point.channelId != null ? point.channelId : ''
    },
    persistDiagnosisContext() {
      if (!this.contextInitialized) return
      const context = {
        deviceCode: this.selectedDeviceCode,
        pointIds: this.selectedPointIds,
        modelType: this.selectedModelType,
        modelVersion: this.selectedModelVersion
      }
      try {
        if (this.contextComplete) {
          window.localStorage.setItem('sensor.diagnosis.context.v2', JSON.stringify(context))
        }
      } catch (error) {
        // 隐私模式或存储配额不足不影响页面使用。
      }
      if (this.detailMode && this.$route && this.$router) {
        const query = Object.assign({}, this.$route.query)
        ;['deviceCode', 'pointId', 'pointIds', 'channelId', 'modelType', 'modelVersion'].forEach(key => delete query[key])
        Object.keys(context).forEach(key => {
          if (Array.isArray(context[key]) && context[key].length) query[key] = context[key].join(',')
          else if (context[key] !== '' && context[key] != null) query[key] = String(context[key])
        })
        const same = JSON.stringify(query) === JSON.stringify(this.$route.query || {})
        if (!same) this.$router.replace({ path: this.$route.path, query }).catch(() => {})
      }
    },
    handleDeviceChange(deviceCode) {
      this.selectedPointIds = this.selectedPointIds.filter(id => this.pointOptions.some(point => String(point.id) === String(id) && point.deviceCode === deviceCode))
      this.activePointId = this.selectedPointIds[0] || ''
      this.handleContextChange()
    },
    handlePointChange(pointIds) {
      let selected = Array.isArray(pointIds) ? pointIds.map(String) : []
      if (!this.multiPointEnabled) selected = selected.slice(-1)
      if (selected.length > this.maxBatchPoints) {
        selected = selected.slice(0, this.maxBatchPoints)
        this.$message.warning(`单批最多选择 ${this.maxBatchPoints} 个测点`)
      }
      const point = this.pointOptions.find(item => String(item.id) === String(selected[0]))
      if (point) this.selectedDeviceCode = point.deviceCode
      this.selectedPointIds = selected.filter(id => this.pointOptions.some(item => String(item.id) === id && item.deviceCode === this.selectedDeviceCode))
      this.activePointId = this.selectedPointIds[0] || ''
      this.handleContextChange()
    },
    handleModelTypeChange() {
      const version = this.modelVersionOptions.find(item => item.modelType === this.selectedModelType && item.semanticVersion === this.selectedModelVersion)
      if (!version) this.selectedModelVersion = ''
      this.handleContextChange()
    },
    handleVersionChange() {
      this.handleContextChange()
    },
    handleContextChange() {
      this.requestSequence += 1
      if (this.contextDebounceTimer) clearTimeout(this.contextDebounceTimer)
      this.selectedMatFile = ''
      this.matFileList = []
      this.pointFileMappings = {}
      this.pendingUploadFiles = {}
      this.diagnosisBatchId = ''
      this.diagnosisBatchStatus = ''
      this.diagnosisBatchItems = []
      if (this.batchPollingTimer) clearTimeout(this.batchPollingTimer)
      this.noAttachment = false
      this.contextError = false
      this.clearDiagnosis()
      this.updateTrustedPointContext()
      this.persistDiagnosisContext()
      if (this.contextComplete) {
        this.contextNotice = '上下文已更新，请配置每个测点的数据文件。'
      } else {
        this.polling = false
        this.contextNotice = '请选择设备、振动测点、模型类型和可执行版本。'
      }
    },
    scheduleContextAnalysis() {
      if (this.contextDebounceTimer) clearTimeout(this.contextDebounceTimer)
      this.contextDebounceTimer = setTimeout(() => {
        this.contextDebounceTimer = null
        this.runContextAnalysis()
      }, 300)
    },
    phmRequestPayload(pointId) {
      const payload = {}
      const deviceCode = this.selectedDeviceCode || this.phmContext.deviceCode
      if (deviceCode) payload.deviceCode = deviceCode
      const point = this.pointOptions.find(item => String(item.id) === String(pointId || this.activePointId || this.selectedPointIds[0]))
      if (point && point.channelId != null) payload.channelId = point.channelId
      if (point) payload.pointId = String(point.id)
      return payload
    },

    /** 从对象中按顺序取第一个非空字段 */
    pickFirst(source, keys) {
      if (!source || !Array.isArray(keys)) return undefined
      for (const key of keys) {
        const value = source[key]
        if (value !== undefined && value !== null && value !== '') return value
      }
      return undefined
    },

    /** 将后端数值字段转为安全数字；缺失或非法时返回 null */
    toFiniteNumber(value) {
      if (value === undefined || value === null || value === '') return null
      const num = Number(value)
      return Number.isFinite(num) ? num : null
    },

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

    /** 安全格式化概率文本，避免缺 probability 字段时报错 */
    formatProbability(value) {
      const num = this.toFiniteNumber(value)
      return num == null ? '--' : `${num.toFixed(1)}%`
    },

    /**
     * 概率条颜色分类
     * >=80% 绿色（高置信），>=50% 黄色（中等），<50% 蓝色（低置信）
     */
    getProbBarClass(pct) {
      const value = this.toFiniteNumber(pct) || 0
      if (value >= 80) return 'fill-high'
      if (value >= 50) return 'fill-mid'
      return 'fill-low'
    },

    /** 标准化后端概率字段，支持数组、对象以及不完整数组项 */
    normalizeProbabilities(data) {
      const raw = this.pickFirst(data, ['topProbabilities', 'top_probabilities', 'probabilities', 'classProbabilities', 'class_probabilities'])
      let list = []
      if (Array.isArray(raw)) {
        list = raw
      } else if (raw && typeof raw === 'object') {
        list = Object.keys(raw).map(key => ({ class: key, probability: raw[key] }))
      }
      return list
        .map((item, index) => {
          if (item && typeof item === 'object') {
            return {
              class: item.class || item.label || item.name || item.category || `类别 ${index + 1}`,
              probability: this.toFiniteNumber(item.probability != null ? item.probability : item.value)
            }
          }
          return {
            class: `类别 ${index + 1}`,
            probability: this.toFiniteNumber(item)
          }
        })
        .map(item => ({
          class: item.class,
          probability: item.probability == null ? null : Math.max(0, Math.min(100, item.probability))
        }))
        .sort((a, b) => (b.probability || 0) - (a.probability || 0))
    },

    /** 标准化证据链，缺少 title/desc 时显示明确空字段 */
    normalizeEvidence(data) {
      const raw = this.pickFirst(data, ['evidence', 'diagnosisEvidence', 'diagnosis_evidence', 'decisionEvidence', 'decision_evidence'])
      if (!Array.isArray(raw)) return []
      return raw.map((item, index) => {
        if (item && typeof item === 'object') {
          return {
            type: item.type || item.level || 'info',
            title: item.title || item.name || `证据 ${index + 1}`,
            desc: item.desc || item.description || item.message || '描述字段为空'
          }
        }
        return {
          type: 'info',
          title: `证据 ${index + 1}`,
          desc: String(item || '描述字段为空')
        }
      })
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
      const rawDiagnosis = this.pickFirst(data, ['diagnosisResult', 'diagnosisName', 'diagnosis_result', 'label'])
      const rawDetail = this.pickFirst(data, ['diagnosisDetail', 'diagnosis_detail'])
      const rawDecisionReason = this.pickFirst(data, ['decisionReason', 'decision_reason'])
      const rawPrediction = this.pickFirst(data, ['closedPrediction', 'closed_prediction'])
      const confidenceValue = this.toFiniteNumber(this.pickFirst(data, ['confidence']))
      const healthValue = this.toFiniteNumber(this.pickFirst(data, ['healthIndex', 'health_index']))

      this.diagnosisName = rawDiagnosis ? translateDiagnosisLabel(rawDiagnosis) : ''
      this.diagnosisDetail = rawDetail || ''
      this.decisionReason = rawDecisionReason || ''
      this.closedPrediction = rawPrediction ? translateDiagnosisLabel(rawPrediction) : ''
      this.confidence = confidenceValue == null ? null : Math.max(0, Math.min(100, confidenceValue))
      if (healthValue != null) {
        this.healthIndex = healthValue
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
      } else {
        this.healthIndex = null
      }

      const rawRisk = this.pickFirst(data, ['riskLevel', 'risk_level'])
      const rawAlarm = this.pickFirst(data, ['alarmLevel', 'alarm_level'])
      this.riskLevel = rawRisk ? translateRiskLevel(rawRisk) : ''
      this.alarmLevel = rawAlarm ? translateAlarmLevel(rawAlarm) : ''

      // ---- 数值指标（多字段名兼容） ----
      this.latestRms = this.toFiniteNumber(this.pickFirst(data, ['latestRms', 'latest_rms', 'rms']))
      this.latestPeak = this.toFiniteNumber(this.pickFirst(data, ['latestPeak', 'latest_peak', 'peak']))
      this.unknownRatio = this.toFiniteNumber(this.pickFirst(data, ['unknownRatio', 'unknown_ratio']))
      this.segmentConsistency = this.toFiniteNumber(this.pickFirst(data, ['segmentConsistency', 'segment_consistency']))
      this.meanMahalanobis = this.toFiniteNumber(this.pickFirst(data, ['meanMahalanobis', 'mean_mahalanobis']))
      this.meanEntropy = this.toFiniteNumber(this.pickFirst(data, ['meanEntropy', 'mean_entropy']))
      this.sampleRate = this.toFiniteNumber(this.pickFirst(data, ['sampleRate', 'sample_rate']))
      this.dataPointCount = this.toFiniteNumber(this.pickFirst(data, ['count', 'dataPointCount', 'data_point_count']))

      // ---- 元信息 ----
      this.filename = this.pickFirst(data, ['filename', 'fileName', 'file_name']) || ''
      this.filePath = this.pickFirst(data, ['filePath', 'file_path', 'sourceName', 'source_name']) || ''
      this.deviceCode = this.pickFirst(data, ['deviceCode', 'device_code']) || ''
      this.modelVersion = this.pickFirst(data, ['modelVersion', 'model_version']) || ''
      // 从后端响应中读取实际使用的模型类型，确保 UI 与后端一致
      const responseModelType = this.pickFirst(data, ['modelType', 'model_type'])
      if (responseModelType && ['gear', 'bearing'].includes(responseModelType)) {
        this.selectedModelType = responseModelType
      }

      // ---- 状态推导 ----
      if (data.status || data.resultState) {
        this.resultState = this.resolveState(data.status || data.resultState)
      } else if (rawDiagnosis) {
        this.resultState = 'done'  // 有诊断结果即视为完成
      } else {
        this.resultState = 'done'  // 有返回但缺诊断字段，也展示为已完成并显示空字段
      }
      this.lastUpdate = data.sampleTime || data.updateTime || new Date()

      // ---- 时域波形数据（多字段名兼容 + 降采样） ----
      this.timeAxis = []
      this.timeData = []
      this.freqAxis = []
      this.freqData = []
      this.chartDirty = true
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
      this.topProbabilities = this.normalizeProbabilities(data)
      this.evidence = this.normalizeEvidence(data)

      // 触发图表重绘
      this.renderCharts()
    },

    /** 清空所有诊断数据，恢复初始状态 */
    clearDiagnosis() {
      this.resultState = 'idle'
      this.diagnosisName = ''
      this.diagnosisDetail = ''
      this.closedPrediction = ''
      this.confidence = null
      this.healthIndex = null
      this.riskLevel = ''
      this.alarmLevel = ''
      this.latestRms = null
      this.latestPeak = null
      this.unknownRatio = null
      this.segmentConsistency = null
      this.meanMahalanobis = null
      this.meanEntropy = null
      this.decisionReason = ''
      this.sampleRate = null
      this.dataPointCount = null
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
      if (!level) return 'empty'
      if (level === '高' || level === 'alarm') return 'danger'
      if (level === '中' || level === 'warning') return 'warning'
      return 'success'
    },

    // -----------------------------------------------------------------------
    // API 调用方法
    // -----------------------------------------------------------------------

    async handleRefresh() {
      if (!this.contextComplete) return
      if (this.diagnosisBatchId) await this.pollDiagnosisBatch(true)
      else await this.refreshServerFiles()
    },

    /**
     * 检查 Python 推理服务的健康状态
     * GET /health (HTTP fallback)
     */
    async checkHealth() {
      try {
        const res = await getInferenceHealth(this.currentServiceBaseURL)
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

    /** 获取当前设备已安全登记的诊断输入附件。 */
    async fetchMatFiles(pointId = this.activeMappingPointId || this.activePointId || this.selectedPointIds[0]) {
      if (!this.contextComplete) return []
      try {
        const res = await listMatFiles(this.currentServiceBaseURL, this.selectedDeviceCode, pointId)
        const data = this.normalizeAnalyzeResponse(res)
        this.matFileList = Array.isArray(data) ? data : []
        const mapping = this.pointFileMappings[String(pointId)] || {}
        this.selectedMatFile = mapping.attachmentId || ''
        return this.matFileList
      } catch (error) {
        if (error && error.response && error.response.status !== 500) {
          console.error('获取 mat 文件列表失败', error)
        }
        throw error
      }
    },

    async handleUploadDialogOpen() {
      this.pendingUploadFile = null
      if (!this.contextComplete) return
      this.selectedPointOptions.forEach(point => {
        const key = String(point.id)
        if (!this.pointFileMappings[key]) this.$set(this.pointFileMappings, key, {})
      })
      this.activeMappingPointId = this.activeMappingPointId || this.selectedPointIds[0]
      this.serverFileLoading = true
      try {
        await this.fetchMatFiles(this.activeMappingPointId)
      } catch (error) {
        this.$message.error('服务器文件列表刷新失败')
      } finally {
        this.serverFileLoading = false
      }
    },

    handleUploadDialogClosed() {
      this.pendingUploadFile = null
      if (this.$refs.nativeFileInput) this.$refs.nativeFileInput.value = ''
    },

    async refreshServerFiles() {
      this.serverFileLoading = true
      try {
        await this.fetchMatFiles(this.activeMappingPointId || this.selectedPointIds[0])
      } catch (error) {
        this.$message.error('服务器文件列表刷新失败')
      } finally {
        this.serverFileLoading = false
      }
    },

    async selectMappingPoint(pointId) {
      this.activeMappingPointId = String(pointId)
      this.pendingUploadFile = this.pendingUploadFiles[String(pointId)] || null
      await this.refreshServerFiles()
    },

    async runContextAnalysis() {
      if (!this.contextComplete) return
      const sequence = ++this.requestSequence
      this.polling = true
      this.contextError = false
      this.noAttachment = false
      this.contextNotice = '正在定位最新诊断文件…'
      this.clearDiagnosis()
      this.resultState = 'running'
      try {
        await this.checkHealth()
        const files = await this.fetchMatFiles()
        if (sequence !== this.requestSequence) return
        if (!files.length) {
          this.clearDiagnosis()
          this.noAttachment = true
          this.contextNotice = '当前设备暂无诊断文件。'
          return
        }
        const attachmentId = this.selectedMatFile || files[0].id
        this.selectedMatFile = attachmentId
        this.contextNotice = '正在执行指定模型版本的诊断…'
        const res = await inferWithAttachment({
          ...this.phmRequestPayload(),
          attachmentId,
          analysisMode: 'latest',
          modelVersion: this.selectedModelVersion
        }, this.currentServiceBaseURL)
        if (sequence !== this.requestSequence) return
        const data = this.normalizeAnalyzeResponse(res)
        if (!data || !Object.keys(data).length) throw new Error('分析完成但未返回有效结果')
        if ((data.modelType && data.modelType !== this.selectedModelType) ||
          (data.modelVersion && data.modelVersion !== this.selectedModelVersion)) {
          throw new Error('推理服务实际执行的模型与所选上下文不一致')
        }
        this.applyDiagnosis(data)
        this.appendLocalHistory(data)
        this.contextNotice = ''
      } catch (error) {
        if (sequence !== this.requestSequence) return
        const backendMessage = error && error.response && error.response.data && error.response.data.msg
        this.clearDiagnosis()
        this.resultState = 'failed'
        this.contextError = true
        this.contextNotice = backendMessage || error.message || '诊断失败，请检查推理服务和模型制品。'
        console.error('诊断上下文分析失败', error)
      } finally {
        if (sequence === this.requestSequence) this.polling = false
      }
    },

    async fetchLatestAnalysis() {
      return this.runContextAnalysis()
    },

    /** 根据可信附件 ID 创建诊断任务并获取结果。 */
    async fetchLatest(attachmentId) {
      const targetId = attachmentId || this.selectedMatFile
      if (!targetId || !this.contextComplete) {
        this.clearDiagnosis()
        return
      }
      const sequence = ++this.requestSequence
      this.polling = true
      this.contextError = false
      this.contextNotice = '正在分析所选诊断文件…'
      this.clearDiagnosis()
      this.resultState = 'running'
      try {
        const res = await inferWithAttachment({
          ...this.phmRequestPayload(),
          attachmentId: targetId,
          analysisMode: 'latest',
          modelVersion: this.selectedModelVersion
        }, this.currentServiceBaseURL)
        if (sequence !== this.requestSequence) return
        const data = this.normalizeAnalyzeResponse(res)
        if (!data || !Object.keys(data).length) {
          this.clearDiagnosis()
          this.$message.warning('分析完成但未返回有效数据')
          return
        }
        this.applyDiagnosis(data)
        this.contextNotice = ''
        this.lastAnalyzeResultText = data.diagnosisResult || data.label || ''
        this.appendLocalHistory(data)
        // 如果是从上传弹窗触发的，关闭弹窗
        if (this.uploadDialogVisible) {
          this.uploadDialogVisible = false
          this.$message.success('文件已提交分析')
        }
      } catch (error) {
        if (sequence !== this.requestSequence) return
        this.clearDiagnosis()
        this.resultState = 'failed'
        this.contextError = true
        this.contextNotice = (error && error.message) || '分析失败，请检查文件或推理服务。'
        if (error && error.response && error.response.status !== 500) {
          console.error('获取最新推理结果失败', error)
        }
        this.$message.error('分析失败，请检查附件或后端服务')
      } finally {
        if (sequence === this.requestSequence) this.polling = false
      }
    },

    /** 服务器文件选择只更新附件 ID，分析由显式按钮触发。 */
    handleSelectedMatFileChange(val) {
      const selected = String(val || '').trim()
      if (!selected) return
      this.selectedMatFile = val
      const file = this.matFileList.find(item => String(item.id) === selected)
      const pointId = String(this.activeMappingPointId || this.selectedPointIds[0])
      this.$set(this.pointFileMappings, pointId, {
        attachmentId: val,
        attachmentName: file ? file.name : '',
        sourceType: file ? file.sourceType : 'MAT_TCP'
      })
      this.$delete(this.pendingUploadFiles, pointId)
      this.pendingUploadFile = null
      this.userManualMode = true
    },

    analyzeSelectedServerFile() {
      if (!this.selectedMatFile) {
        this.$message.warning('请选择服务器文件')
        return
      }
      this.startBatchAnalysis()
    },

    // -----------------------------------------------------------------------
    // 文件上传方法
    // -----------------------------------------------------------------------

    /** 本地文件选择（native input change 事件，直接拿到 File 对象） */
    handleNativeFileChange(e) {
      const files = e.target && e.target.files
      if (files && files.length > 0) {
        this.selectPendingUploadFile(files[0])
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
        this.selectPendingUploadFile(files[0])
      }
    },

    selectPendingUploadFile(file) {
      const suffix = String(file && file.name || '').toLowerCase()
      if (!suffix.endsWith('.mat') && !suffix.endsWith('.npy')) {
        this.pendingUploadFile = null
        this.$message.error('仅支持 .mat 或 .npy 文件')
        return
      }
      if (Number(file.size || 0) <= 0 || Number(file.size) > 128 * 1024 * 1024) {
        this.pendingUploadFile = null
        this.$message.error('文件必须大于 0 且不能超过 128MB')
        return
      }
      this.pendingUploadFile = file
      const pointId = String(this.activeMappingPointId || this.selectedPointIds[0])
      this.$set(this.pendingUploadFiles, pointId, file)
      this.$set(this.pointFileMappings, pointId, {
        localFile: file,
        attachmentId: '',
        attachmentName: file.name,
        sourceType: 'BROWSER_UPLOAD'
      })
      this.selectedMatFile = ''
    },

    async uploadPointFile(pointId, file) {
      const formData = new FormData()
      formData.append('file', file)
      const context = this.phmRequestPayload(pointId)
      formData.append('device_code', context.deviceCode)
      formData.append('point_id', context.pointId)
      if (context.channelId != null) formData.append('channel_id', context.channelId)
      const response = await uploadDiagnosisToInferenceService(formData, this.currentServiceBaseURL, this.selectedModelVersion)
      const uploaded = this.normalizeAnalyzeResponse(response)
      if (!uploaded || !uploaded.attachmentId) throw new Error(`测点 ${pointId} 上传成功但未返回附件 ID`)
      this.$set(this.pointFileMappings, String(pointId), {
        attachmentId: uploaded.attachmentId,
        attachmentName: uploaded.filename || file.name,
        sourceType: 'BROWSER_UPLOAD'
      })
      return uploaded
    },

    async startBatchAnalysis() {
      if (!this.contextComplete || !this.fileMappingComplete) {
        this.$message.warning('请先为每个测点配置一个数据文件')
        return
      }
      this.uploading = true
      this.polling = true
      this.contextError = false
      this.resultState = 'running'
      this.contextNotice = '正在保存测点文件映射…'
      try {
        for (const row of this.mappingRows) {
          if (row.localFile) await this.uploadPointFile(row.id, row.localFile)
        }
        const items = this.selectedPointIds.map(pointId => ({
          pointId,
          attachmentId: this.pointFileMappings[String(pointId)].attachmentId
        }))
        if (!this.multiPointEnabled) {
          const item = items[0]
          const response = await inferWithAttachment({
            ...this.phmRequestPayload(item.pointId),
            attachmentId: item.attachmentId,
            modelVersion: this.selectedModelVersion,
            analysisMode: 'manual'
          }, this.currentServiceBaseURL)
          const result = this.normalizeAnalyzeResponse(response)
          this.applyDiagnosis(result)
          this.appendLocalHistory(result)
          this.resultState = 'done'
          this.polling = false
          this.contextNotice = ''
          this.uploadDialogVisible = false
          this.$message.success('诊断完成')
          return
        }
        const requestId = typeof crypto !== 'undefined' && crypto.randomUUID
          ? crypto.randomUUID()
          : `batch-${Date.now()}-${Math.random().toString(16).slice(2)}`
        const response = await createDiagnosisBatch({
          clientRequestId: requestId,
          deviceCode: this.selectedDeviceCode,
          modelType: this.selectedModelType,
          modelVersion: this.selectedModelVersion,
          items
        })
        const batch = this.normalizeAnalyzeResponse(response)
        this.applyBatchSummary(batch)
        this.uploadDialogVisible = false
        this.contextNotice = '多测点诊断已开始，可在测点矩阵中查看进度。'
        this.pollDiagnosisBatch()
      } catch (error) {
        this.polling = false
        this.resultState = 'failed'
        this.contextError = true
        this.contextNotice = (error && error.message) || '创建诊断批次失败'
        this.$message.error(this.contextNotice)
      } finally {
        this.uploading = false
      }
    },

    applyBatchSummary(batch) {
      if (!batch) return
      this.diagnosisBatchId = batch.id || this.diagnosisBatchId
      this.diagnosisBatchStatus = batch.status || ''
      this.diagnosisBatchItems = Array.isArray(batch.items) ? batch.items : []
      const active = this.diagnosisBatchItems.find(item => String(item.pointId) === String(this.activePointId))
      const firstSucceeded = this.diagnosisBatchItems.find(item => item.status === 'SUCCEEDED' && item.result)
      if (active && active.result) this.applyDiagnosis(active.result)
      else if (firstSucceeded) this.selectBatchPoint(firstSucceeded)
      const terminal = ['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(this.diagnosisBatchStatus)
      this.resultState = terminal
        ? (this.diagnosisBatchStatus === 'FAILED' ? 'failed' : 'done')
        : 'running'
      this.polling = !terminal
      if (terminal) {
        if (this.diagnosisBatchStatus === 'PARTIAL') this.contextNotice = '批次已完成，部分测点失败，可重试失败项。'
        else if (this.diagnosisBatchStatus === 'FAILED') this.contextNotice = '批次执行失败，请查看各测点原因。'
        else this.contextNotice = ''
      }
    },

    async pollDiagnosisBatch(manual = false) {
      if (!this.diagnosisBatchId) return
      if (this.batchPollingTimer) clearTimeout(this.batchPollingTimer)
      try {
        const response = await getDiagnosisBatch(this.diagnosisBatchId)
        this.applyBatchSummary(this.normalizeAnalyzeResponse(response))
      } catch (error) {
        if (manual) this.$message.error('批次状态刷新失败')
      }
      if (!['SUCCEEDED', 'PARTIAL', 'FAILED'].includes(this.diagnosisBatchStatus)) {
        this.batchPollingTimer = setTimeout(() => this.pollDiagnosisBatch(), 1000)
      }
    },

    selectBatchPoint(item) {
      if (!item) return
      this.activePointId = String(item.pointId)
      this.updateTrustedPointContext()
      if (item.result) {
        this.applyDiagnosis(item.result)
        this.appendLocalHistory(item.result)
      } else {
        this.clearDiagnosis()
        this.resultState = item.status === 'FAILED' || item.status === 'INVALID' ? 'failed' : 'running'
      }
    },

    async retryFailedPoints() {
      if (!this.diagnosisBatchId || !this.batchHasFailures) return
      this.polling = true
      try {
        const response = await retryDiagnosisBatch(this.diagnosisBatchId)
        this.applyBatchSummary(this.normalizeAnalyzeResponse(response))
        this.contextNotice = '失败测点已重新排队。'
        this.pollDiagnosisBatch()
      } catch (error) {
        this.polling = false
        this.$message.error((error && error.message) || '重试失败')
      }
    },

    batchItemStatusText(status) {
      if (status === 'SUCCEEDED') return '完成'
      if (status === 'RUNNING') return '分析中'
      if (status === 'PENDING') return '排队中'
      if (status === 'INVALID') return '输入无效'
      if (status === 'FAILED') return '失败'
      return '待配置'
    },

    /** 上传到 Java 安全附件存储，再用返回的附件 ID 创建异步诊断任务。 */
    async uploadSelectedFile(file = this.pendingUploadFile) {
      if (!this.contextComplete) {
        this.$message.warning('请先完成设备、测点、模型类型和版本选择')
        return
      }
      if (!file) {
        this.$message.warning('请先选择本机文件')
        return
      }
      const suffix = String(file.name || '').toLowerCase()
      if (!suffix.endsWith('.mat') && !suffix.endsWith('.npy')) {
        this.$message.error('仅支持 .mat 或 .npy 文件')
        return
      }
      this.userManualMode = true
      this.uploading = true
      this.polling = true
      this.resultState = 'running'
      let attachmentStored = false
      try {
        const formData = new FormData()
        formData.append('file', file)
        const phmPayload = this.phmRequestPayload()
        if (phmPayload.deviceCode) formData.append('device_code', phmPayload.deviceCode)
        if (phmPayload.channelId) formData.append('channel_id', phmPayload.channelId)
        if (phmPayload.pointId) formData.append('point_id', phmPayload.pointId)
        const response = await uploadDiagnosisToInferenceService(formData, this.currentServiceBaseURL, this.selectedModelVersion)
        const uploaded = this.normalizeAnalyzeResponse(response)
        if (!uploaded || !uploaded.attachmentId) throw new Error('上传成功但未返回附件 ID')
        attachmentStored = true
        this.selectedMatFile = uploaded.attachmentId
        const inferenceResponse = await inferWithAttachment({
          ...phmPayload,
          attachmentId: uploaded.attachmentId,
          analysisMode: 'manual',
          modelVersion: this.selectedModelVersion
        }, this.currentServiceBaseURL)
        const data = this.normalizeAnalyzeResponse(inferenceResponse)
        if (!data || !Object.keys(data).length) throw new Error('分析完成但未返回有效结果')
        this.applyDiagnosis(data)
        this.appendLocalHistory(data)
        this.contextNotice = ''
        this.pendingUploadFile = null
        try {
          await this.fetchMatFiles()
        } catch (refreshError) {
          console.warn('分析成功，但附件列表刷新失败', refreshError)
        }
        this.selectedMatFile = uploaded.attachmentId
        this.uploadDialogVisible = false
        this.$message.success('文件已上传并完成分析')
      } catch (error) {
        this.clearDiagnosis()
        if (error && error.response && error.response.status !== 500) {
          console.error('文件上传失败', error)
        }
        if (attachmentStored) this.$message.error('文件已上传，但诊断任务执行失败')
        else this.$message.error('文件上传失败')
      } finally {
        this.uploading = false
        this.polling = false
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
