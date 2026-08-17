<template>
  <div class="app-container history-data-page">
    <el-card shadow="never" class="history-card">
      <div slot="header" class="page-header">
        <div>
          <span class="eyebrow">HISTORY DATA</span>
          <h2>历史数据下载</h2>
          <p>查询指定时间范围内的诊断记录，并导出为 CSV 文件。</p>
        </div>
        <el-button v-hasPermi="['sensor:history:export']" type="primary" icon="el-icon-download" :loading="exporting" :disabled="!rows.length" @click="exportCsv">
          导出 CSV
        </el-button>
      </div>

      <el-form :inline="true" size="small" class="filter-form" @submit.native.prevent="loadHistory">
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            value-format="yyyy-MM-dd HH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :default-time="['00:00:00', '23:59:59']"
            style="width: 380px"
          />
        </el-form-item>
        <el-form-item label="设备编码">
          <el-input v-model.trim="deviceCode" clearable placeholder="可选" style="width: 190px" />
        </el-form-item>
        <el-form-item label="测点">
          <el-select v-model="pointId" clearable filterable :loading="pointsLoading" placeholder="全部测点" style="width: 240px">
            <el-option v-for="item in pointOptions" :key="item.id" :label="pointLabel(item)" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" :loading="loading" @click="loadHistory">查询</el-button>
          <el-button icon="el-icon-refresh" @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" class="history-error" />

      <el-table v-loading="loading" :data="rows" border stripe empty-text="当前时间范围内暂无诊断记录" class="history-table">
        <el-table-column prop="deviceCode" label="设备编码" min-width="140" show-overflow-tooltip>
          <template slot-scope="scope">{{ valueOf(scope.row, 'deviceCode', 'device_code') || '--' }}</template>
        </el-table-column>
        <el-table-column label="测点" min-width="180" show-overflow-tooltip>
          <template slot-scope="scope">{{ pointLabelById(valueOf(scope.row, 'pointId', 'point_id')) }}</template>
        </el-table-column>
        <el-table-column label="诊断结果" min-width="140" show-overflow-tooltip>
          <template slot-scope="scope">{{ valueOf(scope.row, 'diagnosisResult', 'diagnosis_result') || '--' }}</template>
        </el-table-column>
        <el-table-column label="健康指数" width="100">
          <template slot-scope="scope">{{ valueOf(scope.row, 'healthIndex', 'health_index') == null ? '--' : valueOf(scope.row, 'healthIndex', 'health_index') }}</template>
        </el-table-column>
        <el-table-column label="风险等级" width="100">
          <template slot-scope="scope">{{ valueOf(scope.row, 'riskLevel', 'risk_level') || '--' }}</template>
        </el-table-column>
        <el-table-column label="置信度" width="100">
          <template slot-scope="scope">{{ formatPercent(valueOf(scope.row, 'confidence')) }}</template>
        </el-table-column>
        <el-table-column label="采样时间" min-width="170" show-overflow-tooltip>
          <template slot-scope="scope">{{ valueOf(scope.row, 'sampleTime', 'sample_time') || '--' }}</template>
        </el-table-column>
        <el-table-column label="诊断详情" min-width="220" show-overflow-tooltip>
          <template slot-scope="scope">{{ valueOf(scope.row, 'diagnosisDetail', 'diagnosis_detail') || '--' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script>
import { fetchHistory, getDiagnosisOptions } from '@/api/system/bearingDiagnosis'

function defaultDateRange() {
  const end = new Date()
  const start = new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000)
  const format = value => {
    const pad = number => String(number).padStart(2, '0')
    return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
  }
  return [format(start), format(end)]
}

export default {
  name: 'HistoryData',
  data() {
    return {
      dateRange: defaultDateRange(),
      deviceCode: '',
      pointId: null,
      pointOptions: [],
      pointsLoading: false,
      rows: [],
      loading: false,
      exporting: false,
      errorMessage: ''
    }
  },
  created() {
    this.loadPointOptions()
    this.loadHistory()
  },
  methods: {
    async loadPointOptions() {
      this.pointsLoading = true
      try {
        const response = await getDiagnosisOptions()
        this.pointOptions = response.data && response.data.points ? response.data.points : []
      } catch (error) {
        this.pointOptions = []
      } finally {
        this.pointsLoading = false
      }
    },
    pointLabel(item) {
      if (!item) return '--'
      const name = item.pointName || item.pointCode || `测点 ${item.id}`
      const code = item.pointCode && item.pointCode !== name ? ` · ${item.pointCode}` : ''
      return `${name}${code}`
    },
    pointLabelById(pointId) {
      if (pointId === null || pointId === undefined || pointId === '') return '--'
      const point = this.pointOptions.find(item => String(item.id) === String(pointId))
      return point ? this.pointLabel(point) : `测点 ${pointId}`
    },
    valueOf(row, ...keys) {
      for (const key of keys) {
        if (row && row[key] !== undefined && row[key] !== null) return row[key]
      }
      return null
    },
    formatPercent(value) {
      if (value === null || value === undefined || value === '') return '--'
      const number = Number(value)
      if (!Number.isFinite(number)) return String(value)
      return `${number <= 1 ? (number * 100).toFixed(2) : number.toFixed(2)}%`
    },
    async loadHistory() {
      if (!this.dateRange || this.dateRange.length !== 2) {
        this.$message.warning('请选择时间范围')
        return
      }
      this.loading = true
      this.errorMessage = ''
      try {
        const response = await fetchHistory({
          start_time: this.dateRange[0],
          end_time: this.dateRange[1],
          device_code: this.deviceCode || undefined,
          point_id: this.pointId || undefined
        })
        this.rows = response.data || []
      } catch (error) {
        this.rows = []
        this.errorMessage = '历史数据加载失败，请检查接口权限或后端服务。'
      } finally {
        this.loading = false
      }
    },
    resetFilters() {
      this.dateRange = defaultDateRange()
      this.deviceCode = ''
      this.pointId = null
      this.loadHistory()
    },
    csvValue(row, ...keys) {
      const value = this.valueOf(row, ...keys)
      if (value === null || value === undefined) return ''
      const text = String(value)
      return /[,"\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
    },
    async exportCsv() {
      if (!this.rows.length) return
      this.exporting = true
      try {
        const columns = [
          ['设备编码', 'deviceCode', 'device_code'],
          ['诊断结果', 'diagnosisResult', 'diagnosis_result'],
          ['健康指数', 'healthIndex', 'health_index'],
          ['风险等级', 'riskLevel', 'risk_level'],
          ['置信度', 'confidence'],
          ['采样时间', 'sampleTime', 'sample_time'],
          ['诊断详情', 'diagnosisDetail', 'diagnosis_detail']
        ]
        const lines = ['\ufeff' + columns.map(column => column[0]).join(',')]
        this.rows.forEach(row => lines.push(columns.map(column => this.csvValue(row, ...column.slice(1))).join(',')))
        const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = `历史数据_${Date.now()}.csv`
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)
        this.$message.success(`已导出 ${this.rows.length} 条记录`)
      } finally {
        this.exporting = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.history-card {
  background: var(--color-surface, #111c30);
  border-color: rgba(126, 160, 195, 0.2);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;

  h2 { margin: 4px 0 6px; color: var(--color-text, #e6edf3); }
  p { margin: 0; color: var(--color-muted, #8ea0b5); }
}

.eyebrow { color: var(--color-accent, #22d3ee); font: 700 11px/1 var(--font-data, Consolas, monospace); letter-spacing: .12em; }
.filter-form { margin: 20px 0 8px; }
.history-error { margin: 12px 0; }
.history-table { margin-top: 12px; }
</style>
