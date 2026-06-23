<template>
  <div class="app-container report-page">
    <section class="page-head">
      <div>
        <h2>报表中心</h2>
        <p>查看实时报表、历史运行报表，并登记诊断/运行服务报告。</p>
      </div>
      <el-button type="primary" icon="el-icon-download" size="small" @click="exportCurrent">导出当前表</el-button>
    </section>

    <el-tabs v-model="activeTab" @tab-click="loadData">
      <el-tab-pane label="实时报表" name="realtime">
        <section class="filter-bar">
          <el-input v-model="query.deviceCode" placeholder="设备编码（可选）" size="small" clearable @keyup.enter.native="loadRealtime" />
          <el-button size="small" @click="loadRealtime">查询</el-button>
        </section>
        <el-table v-loading="loading" :data="realtimeRows" stripe>
          <el-table-column prop="deviceName" label="设备名称" min-width="160" />
          <el-table-column prop="deviceCode" label="设备编码" width="140" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="healthIndex" label="健康指数" width="100" />
          <el-table-column prop="vibration" label="振动" width="100" />
          <el-table-column prop="temperature" label="温度" width="100" />
          <el-table-column prop="sampleTime" label="采集时间" width="170">
            <template slot-scope="scope">{{ parseTime(scope.row.sampleTime) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="历史报表" name="history">
        <section class="filter-bar">
          <el-input v-model="query.orgName" placeholder="组织/节点" size="small" clearable />
          <el-input v-model="query.deviceCode" placeholder="设备编码" size="small" clearable />
          <el-button size="small" @click="loadHistory">查询</el-button>
        </section>
        <section class="summary-strip">
          <div><span>设备数量</span><strong>{{ historySummary.total || 0 }}</strong></div>
          <div><span>运行设备</span><strong>{{ historySummary.running || 0 }}</strong></div>
          <div><span>停机</span><strong>{{ historySummary.stopped || 0 }}</strong></div>
          <div><span>告警设备</span><strong>{{ historySummary.alarming || 0 }}</strong></div>
        </section>
        <el-table v-loading="loading" :data="historyRows" stripe>
          <el-table-column prop="orgName" label="节点" min-width="160" />
          <el-table-column prop="deviceName" label="设备名称" min-width="160" />
          <el-table-column prop="deviceCode" label="设备编码" width="140" />
          <el-table-column prop="deviceType" label="设备类型" width="130" />
          <el-table-column prop="status" label="运行状态" width="100" />
          <el-table-column prop="diagnosisResult" label="诊断结论" min-width="150" />
          <el-table-column prop="alarmCount" label="报警次数" width="100" />
          <el-table-column prop="runHours" label="运行小时" width="110" />
          <el-table-column prop="healthIndex" label="健康指数" width="100" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="服务报告" name="service">
        <section class="filter-bar">
          <el-select v-model="query.reportType" placeholder="报告类型" size="small" clearable @change="loadReports">
            <el-option label="诊断报告" value="diagnosis" />
            <el-option label="运行报告" value="run" />
          </el-select>
          <el-button size="small" @click="reportVisible = true">登记报告</el-button>
        </section>
        <el-table v-loading="loading" :data="reports" stripe>
          <el-table-column prop="fileName" label="报告名称" min-width="220" />
          <el-table-column prop="reportType" label="类型" width="110" />
          <el-table-column prop="fileExt" label="格式" width="90" />
          <el-table-column prop="uploadBy" label="上传人" width="120" />
          <el-table-column prop="createTime" label="上传时间" width="170">
            <template slot-scope="scope">{{ parseTime(scope.row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="190">
            <template slot-scope="scope">
              <el-link :href="fileHref(scope.row.fileUrl)" target="_blank" type="primary">查看</el-link>
              <el-link type="primary" class="table-link" @click="downloadReport(scope.row)">下载</el-link>
              <el-link type="danger" class="table-link" @click="removeReport(scope.row)">删除</el-link>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog title="登记服务报告" :visible.sync="reportVisible" width="520px">
      <el-form :model="reportForm" label-width="90px">
        <el-form-item label="报告类型">
          <el-select v-model="reportForm.reportType">
            <el-option label="诊断报告" value="diagnosis" />
            <el-option label="运行报告" value="run" />
          </el-select>
        </el-form-item>
        <el-form-item label="报告名称">
          <el-input v-model="reportForm.fileName" />
        </el-form-item>
        <el-form-item label="文件地址">
          <el-input v-model="reportForm.fileUrl" placeholder="/profile/upload/xxx.pdf 或 http(s) 地址" />
        </el-form-item>
        <el-form-item label="上传 PDF">
          <el-upload
            :action="uploadUrl"
            :headers="uploadHeaders"
            :limit="1"
            accept=".pdf"
            :show-file-list="true"
            :on-success="handleReportUploadSuccess"
          >
            <el-button size="small" type="primary" plain>选择并上传</el-button>
            <div slot="tip" class="el-upload__tip">支持上传 PDF 服务报告，上传成功后自动回填文件地址。</div>
          </el-upload>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reportForm.remark" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="reportVisible = false">取消</el-button>
        <el-button type="primary" @click="saveReport">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { getRealtimeReport, getHistoryReport, listServiceReports, saveServiceReport, deleteAttachment } from '@/api/phm'
import { getToken } from '@/utils/auth'

export default {
  name: 'PhmReports',
  data() {
    return {
      loading: false,
      activeTab: 'realtime',
      query: { deviceCode: '', orgName: '', reportType: '' },
      realtimeRows: [],
      historyRows: [],
      historySummary: {},
      reports: [],
      reportVisible: false,
      reportForm: { reportType: 'diagnosis', fileName: '', fileUrl: '', fileExt: 'pdf', remark: '' },
      uploadUrl: process.env.VUE_APP_BASE_API + '/common/upload',
      uploadHeaders: { Authorization: 'Bearer ' + getToken() }
    }
  },
  created() {
    this.loadRealtime()
  },
  methods: {
    loadData() {
      if (this.activeTab === 'realtime') this.loadRealtime()
      if (this.activeTab === 'history') this.loadHistory()
      if (this.activeTab === 'service') this.loadReports()
    },
    async loadRealtime() {
      this.loading = true
      try {
        const res = await getRealtimeReport({ deviceCode: this.query.deviceCode })
        this.realtimeRows = res.data || []
      } finally {
        this.loading = false
      }
    },
    async loadHistory() {
      this.loading = true
      try {
        const res = await getHistoryReport({ orgName: this.query.orgName, deviceCode: this.query.deviceCode })
        const data = res.data || {}
        this.historySummary = data.summary || {}
        this.historyRows = data.devices || []
      } finally {
        this.loading = false
      }
    },
    async loadReports() {
      this.loading = true
      try {
        const res = await listServiceReports({ reportType: this.query.reportType })
        this.reports = res.data || []
      } finally {
        this.loading = false
      }
    },
    async saveReport() {
      await saveServiceReport(this.reportForm)
      this.$message.success('服务报告已登记')
      this.reportVisible = false
      this.reportForm = { reportType: 'diagnosis', fileName: '', fileUrl: '', fileExt: 'pdf', remark: '' }
      this.loadReports()
    },
    handleReportUploadSuccess(res, file) {
      if (res.code === 200) {
        this.reportForm.fileUrl = res.url
        this.reportForm.fileName = this.reportForm.fileName || res.originalFilename || file.name
        this.reportForm.fileExt = 'pdf'
        this.$message.success('PDF 上传成功')
      } else {
        this.$message.error(res.msg || '上传失败')
      }
    },
    exportCurrent() {
      if (this.activeTab === 'realtime') {
        return this.download('/phm/reports/realtime/export', { deviceCode: this.query.deviceCode }, `PHM实时报表_${Date.now()}.csv`)
      }
      if (this.activeTab === 'history') {
        return this.download('/phm/reports/history/export', { orgName: this.query.orgName, deviceCode: this.query.deviceCode }, `PHM历史报表_${Date.now()}.csv`)
      }
      const rows = this.activeTab === 'history' ? this.historyRows : this.activeTab === 'service' ? this.reports : this.realtimeRows
      if (!rows.length) return this.$message.warning('当前无可导出数据')
      const headers = Object.keys(rows[0])
      const csv = [headers.join(',')].concat(rows.map(row => headers.map(key => `"${row[key] == null ? '' : String(row[key]).replace(/"/g, '""')}"`).join(','))).join('\n')
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = `PHM_${this.activeTab}_${Date.now()}.csv`
      link.click()
      URL.revokeObjectURL(link.href)
    },
    fileHref(url) {
      if (!url) return ''
      if (/^(https?:)?\/\//.test(url)) return url
      const base = process.env.VUE_APP_BASE_API || ''
      return url.indexOf('/') === 0 ? base + url : url
    },
    downloadReport(row) {
      if (!row.fileUrl) return this.$message.warning('报告文件地址为空')
      const link = document.createElement('a')
      link.href = this.fileHref(row.fileUrl)
      link.download = row.fileName || `PHM服务报告_${Date.now()}.${row.fileExt || 'pdf'}`
      link.target = '_blank'
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
    },
    removeReport(row) {
      this.$confirm(`确认删除服务报告“${row.fileName || row.id}”？不会删除服务器上的原始文件。`, '提示', {
        type: 'warning'
      }).then(async() => {
        await deleteAttachment(row.id)
        this.$message.success('服务报告已删除')
        this.loadReports()
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.report-page { min-height: calc(100vh - 84px); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 6px; }
.page-head p { margin: 0; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; }
.filter-bar .el-input, .filter-bar .el-select { width: 220px; }
.table-link { margin-left: 10px; }
.summary-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.summary-strip div { padding: 12px 14px; border-radius: 14px; }
.summary-strip span { font-size: 12px; }
.summary-strip strong { display: block; margin-top: 6px; font-size: 22px; }
@media (max-width: 780px) {
  .page-head, .filter-bar { flex-direction: column; align-items: stretch; }
  .filter-bar .el-input, .filter-bar .el-select { width: 100%; }
  .summary-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
