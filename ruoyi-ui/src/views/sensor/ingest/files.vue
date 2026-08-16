<template>
  <div class="ingest-page">
    <header class="ingest-head">
      <div>
        <span class="eyebrow">VIBRATION FILE INTAKE</span>
        <h1>振动文件接收</h1>
        <p>跟踪文件从到达、映射、校验到就绪的完整链路，异常记录可在此闭环。</p>
      </div>
      <el-button icon="el-icon-refresh" :loading="loading" @click="loadFiles">刷新台账</el-button>
    </header>

    <section class="pipeline" aria-label="文件接收状态流水线">
      <template v-for="(stage, index) in pipeline">
        <button :key="stage.value" class="pipeline-stage" :class="[{ active: query.status === stage.value }, stage.tone]" type="button" @click="filterStatus(stage.value)">
          <span class="stage-code">0{{ index + 1 }}</span>
          <span class="stage-copy"><small>{{ stage.en }}</small><strong>{{ stage.label }}</strong></span>
          <b>{{ statusCount(stage.value) }}</b>
        </button>
        <i v-if="index < pipeline.length - 1" :key="`${stage.value}-line`" class="pipeline-line"><span /></i>
      </template>
    </section>

    <section class="ledger-panel">
      <div class="ledger-toolbar">
        <div><span class="section-no">01</span><div><h2>接收台账</h2><p>未映射文件对具备列表权限的用户可见，完成映射后按设备数据范围隔离。</p></div></div>
        <div class="filters">
          <el-select v-model="query.status" clearable placeholder="全部状态" @change="search">
            <el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="query.sourceType" clearable placeholder="全部来源" @change="search">
            <el-option label="采集器上传" value="COLLECTOR" /><el-option label="人工上传" value="MANUAL" /><el-option label="低代码管道" value="PIPELINE" />
          </el-select>
          <el-input v-model.trim="query.keyword" clearable placeholder="文件 / 设备 / 测点" prefix-icon="el-icon-search" @keyup.enter.native="search" @clear="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="rows" class="ingest-table" row-key="id" empty-text="当前筛选条件下暂无接收记录">
        <el-table-column label="文件" min-width="240">
          <template slot-scope="scope"><div class="file-cell"><span class="file-ext">{{ (scope.row.fileExt || 'BIN').toUpperCase() }}</span><div><strong>{{ scope.row.fileName }}</strong><small>{{ formatSize(scope.row.fileSize) }} · {{ shortHash(scope.row.sha256) }}</small></div></div></template>
        </el-table-column>
        <el-table-column label="来源" width="130"><template slot-scope="scope"><span class="mono">{{ sourceLabel(scope.row.sourceType) }}</span><small class="cell-sub">{{ scope.row.sourceRef || '--' }}</small></template></el-table-column>
        <el-table-column label="信号映射" min-width="220"><template slot-scope="scope"><div v-if="scope.row.deviceId" class="mapping-cell"><strong>{{ scope.row.deviceCode || deviceLabel(scope.row.deviceId) }}</strong><span>/</span><strong>{{ scope.row.pointCode || pointLabel(scope.row.pointId) }}</strong><small>CH {{ scope.row.channelId == null ? '--' : pad(scope.row.channelId) }}</small></div><span v-else class="unmapped"><i />等待关联设备与测点</span></template></el-table-column>
        <el-table-column label="接收状态" width="142"><template slot-scope="scope"><span class="status-pill" :class="statusTone(scope.row.status)"><i />{{ statusLabel(scope.row.status) }}</span><small v-if="scope.row.retryCount" class="cell-sub">已重试 {{ scope.row.retryCount }} 次</small></template></el-table-column>
        <el-table-column label="接收时间" width="164"><template slot-scope="scope"><span class="mono time">{{ scope.row.receivedTime || scope.row.createTime || '--' }}</span></template></el-table-column>
        <el-table-column label="异常说明" min-width="180" show-overflow-tooltip><template slot-scope="scope"><span :class="{ 'error-text': scope.row.errorMessage }">{{ scope.row.errorMessage || operationHint(scope.row) }}</span></template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template slot-scope="scope">
            <el-button v-if="canAssociate(scope.row)" v-hasPermi="['sensor:ingest:associate']" type="text" @click="openAssociate(scope.row)">关联测点</el-button>
            <el-button v-if="scope.row.status === 'FAILED'" v-hasPermi="['sensor:ingest:retry']" type="text" class="retry" @click="retry(scope.row)">重新接收</el-button>
            <span v-if="!canAssociate(scope.row) && scope.row.status !== 'FAILED'" class="no-action">{{ operationHint(scope.row) }}</span>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="loadFiles" />
    </section>

    <el-dialog title="关联接收文件" :visible.sync="associateVisible" width="620px" append-to-body custom-class="industrial-dialog">
      <div class="associate-file"><span class="file-ext">{{ (current.fileExt || 'BIN').toUpperCase() }}</span><div><small>待关联文件</small><strong>{{ current.fileName }}</strong></div></div>
      <el-alert title="选择目标设备、测点以及与其绑定的采集通道。服务端会再次校验数据范围与映射一致性。" type="info" :closable="false" show-icon />
      <el-form ref="associateForm" :model="associateForm" :rules="associateRules" label-width="92px" class="associate-form">
        <el-form-item label="目标设备" prop="deviceId"><el-select v-model="associateForm.deviceId" filterable @change="onDeviceChange"><el-option v-for="item in devices" :key="item.id" :label="`${item.deviceName} · ${item.deviceCode}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="目标测点" prop="pointId"><el-select v-model="associateForm.pointId" filterable @change="onPointChange"><el-option v-for="item in associatePoints" :key="item.id" :label="`${item.pointName} · ${item.pointCode} · ${item.signalType || '--'}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="采集通道" prop="channelId"><el-select v-model="associateForm.channelId" filterable><el-option v-for="item in associateChannels" :key="item.id" :label="`${item.collectorId} / M${pad(item.moduleNo)} / CH${pad(item.channelNo)}${item.enabled ? '' : ' · 已停用'}`" :disabled="!item.enabled" :value="item.id" /></el-select><span v-if="associateForm.pointId && !associateChannels.length" class="field-warning">该测点尚未绑定采集通道，请先在“测点接入”中配置。</span></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="associateVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitAssociate">确认关联并校验</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { listIngestFiles, associateIngestFile, retryIngestFile, getAcquisitionOptions, listAcquisitionChannels } from '@/api/sensor/access'
import { checkPermi } from '@/utils/permission'

export default {
  name: 'SensorIngestFiles',
  data() {
    const statuses = [
      { value: 'RECEIVING', label: '接收中', en: 'RECEIVING', tone: 'cyan' },
      { value: 'UNMAPPED', label: '待映射', en: 'UNMAPPED', tone: 'amber' },
      { value: 'VALIDATING', label: '校验中', en: 'VALIDATING', tone: 'cyan' },
      { value: 'READY', label: '已就绪', en: 'READY', tone: 'green' },
      { value: 'FAILED', label: '接收失败', en: 'FAILED', tone: 'red' }
    ]
    return {
      statuses, loading: false, submitting: false, associateVisible: false, rows: [], total: 0,
      devices: [], points: [], channels: [], current: {}, pageStatusCounts: {},
      query: { pageNum: 1, pageSize: 10, status: null, sourceType: null, keyword: '' },
      associateForm: { deviceId: null, pointId: null, channelId: null },
      associateRules: {
        deviceId: [{ required: true, message: '请选择目标设备', trigger: 'change' }],
        pointId: [{ required: true, message: '请选择目标测点', trigger: 'change' }],
        channelId: [{ required: true, message: '请选择采集通道', trigger: 'change' }]
      }
    }
  },
  computed: {
    pipeline() { return this.statuses },
    associatePoints() { return this.points.filter(item => String(item.deviceId) === String(this.associateForm.deviceId)) },
    associateChannels() { return this.channels.filter(item => String(item.deviceId) === String(this.associateForm.deviceId) && String(item.pointId) === String(this.associateForm.pointId)) }
  },
  created() { this.loadAll() },
  methods: {
    async loadAll() {
      this.loading = true
      try {
        try {
          const options = await getAcquisitionOptions(); const data = options.data || {}
          this.devices = data.devices || []; this.points = data.points || []
        } catch (error) { this.devices = []; this.points = [] }
        if (checkPermi(['sensor:ingest:associate'])) {
          try {
            const channels = await listAcquisitionChannels({ pageNum: 1, pageSize: 10000 })
            this.channels = channels.rows || []
          } catch (error) { this.channels = [] }
        }
        await this.loadFiles(false)
      } finally { this.loading = false }
    },
    async loadFiles(showLoading = true) {
      if (showLoading) this.loading = true
      try {
        const res = await listIngestFiles(this.query)
        this.rows = res.rows || []; this.total = res.total || 0
        this.pageStatusCounts = this.rows.reduce((result, row) => { result[row.status] = (result[row.status] || 0) + 1; return result }, {})
      } finally { if (showLoading) this.loading = false }
    },
    search() { this.query.pageNum = 1; this.loadFiles() },
    filterStatus(status) { this.query.status = this.query.status === status ? null : status; this.search() },
    statusCount(status) { return this.pageStatusCounts[status] || 0 },
    openAssociate(row) {
      this.current = row; this.associateForm = { deviceId: null, pointId: null, channelId: null }; this.associateVisible = true
      this.$nextTick(() => this.$refs.associateForm && this.$refs.associateForm.clearValidate())
    },
    onDeviceChange() { this.associateForm.pointId = null; this.associateForm.channelId = null },
    onPointChange() { this.associateForm.channelId = null },
    submitAssociate() {
      this.$refs.associateForm.validate(async valid => {
        if (!valid || this.submitting) return
        this.submitting = true
        try { await associateIngestFile(this.current.id, this.associateForm); this.$modal.msgSuccess('关联已提交，文件进入校验队列'); this.associateVisible = false; await this.loadFiles() } finally { this.submitting = false }
      })
    },
    async retry(row) { await this.$modal.confirm(`确认重新接收“${row.fileName}”？`); await retryIngestFile(row.id); this.$modal.msgSuccess('已重新进入接收队列'); await this.loadFiles() },
    canAssociate(row) { return ['UNMAPPED', 'REJECTED'].includes(row.status) },
    operationHint(row) { return { RECEIVING: '接收完成后可操作', VALIDATING: '正在校验映射', READY: '已进入分析链路', REJECTED: '请重新关联', FAILED: '可重新接收' }[row.status] || '暂无可用操作' },
    statusLabel(value) { const item = this.statuses.find(status => status.value === value); return item ? item.label : (value === 'REJECTED' ? '校验拒绝' : value || '--') },
    statusTone(value) { return { READY: 'green', FAILED: 'red', REJECTED: 'red', UNMAPPED: 'amber', RECEIVING: 'cyan', VALIDATING: 'cyan' }[value] || '' },
    sourceLabel(value) { return { COLLECTOR: '采集器', MANUAL: '人工上传', PIPELINE: '诊断管道' }[value] || value || '--' },
    formatSize(value) { if (value == null) return '--'; if (value < 1024) return `${value} B`; if (value < 1048576) return `${(value / 1024).toFixed(1)} KB`; return `${(value / 1048576).toFixed(1)} MB` },
    shortHash(value) { return value ? `${value.slice(0, 8)}…${value.slice(-6)}` : 'HASH PENDING' },
    pad(value) { return String(value == null ? '--' : value).padStart(2, '0') },
    deviceLabel(id) { const item = this.devices.find(value => String(value.id) === String(id)); return item ? item.deviceName : id },
    pointLabel(id) { const item = this.points.find(value => String(value.id) === String(id)); return item ? item.pointName : id }
  }
}
</script>

<style scoped lang="scss">
.ingest-page { min-height: calc(100vh - 84px); padding: 24px; background: #08111d; color: #dbeafe; font-family: "Microsoft YaHei", sans-serif; }.ingest-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 22px; }.eyebrow { color: #22d3ee; font: 700 12px Bahnschrift; letter-spacing: .16em; }.ingest-head h1 { margin: 8px 0 6px; color: #f8fafc; font-size: 28px; }.ingest-head p { margin: 0; color: #8293ad; }
.pipeline { display: grid; grid-template-columns: 1fr 30px 1fr 30px 1fr 30px 1fr 30px 1fr; align-items: center; margin-bottom: 18px; }.pipeline-stage { position: relative; display: flex; min-width: 0; align-items: center; gap: 10px; height: 76px; padding: 12px; border: 1px solid #263850; background: #0d1929; color: #91a2ba; text-align: left; cursor: pointer; transition: border-color .18s, background .18s; }.pipeline-stage:hover,.pipeline-stage:focus-visible { border-color: #4a657f; outline: none; }.pipeline-stage.active { border-color: currentColor; background: #122438; box-shadow: inset 0 -2px currentColor; }.stage-code { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 auto; border: 1px solid currentColor; border-radius: 50%; font: 700 11px Bahnschrift; }.stage-copy { min-width: 0; }.stage-copy small,.stage-copy strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.stage-copy small { opacity: .6; font: 700 9px Bahnschrift; letter-spacing: .08em; }.stage-copy strong { margin-top: 5px; color: #dce7f5; font-size: 13px; }.pipeline-stage b { margin-left: auto; color: currentColor; font: 700 22px Bahnschrift; }.pipeline-stage.cyan { color: #22d3ee; }.pipeline-stage.amber { color: #f59e0b; }.pipeline-stage.green { color: #10b981; }.pipeline-stage.red { color: #ef4444; }.pipeline-line { position: relative; height: 1px; background: #2a3d56; }.pipeline-line span { position: absolute; right: -1px; top: -3px; width: 7px; height: 7px; border-top: 1px solid #64748b; border-right: 1px solid #64748b; transform: rotate(45deg); }
.ledger-panel { padding: 18px; border: 1px solid #20314b; background: #111c30; box-shadow: 0 22px 56px rgba(0,0,0,.2); }.ledger-toolbar,.ledger-toolbar > div,.filters { display: flex; align-items: center; }.ledger-toolbar { justify-content: space-between; gap: 20px; margin-bottom: 14px; }.section-no { margin-right: 12px; color: #22d3ee; font: 700 20px Bahnschrift; }.ledger-toolbar h2 { margin: 0; color: #f1f5f9; font-size: 17px; }.ledger-toolbar p { margin: 4px 0 0; color: #64748b; font-size: 12px; }.filters { gap: 8px; }.filters .el-select { width: 130px; }.filters .el-input { width: 210px; }
.file-cell,.mapping-cell,.associate-file { display: flex; align-items: center; gap: 10px; }.file-ext { display: grid; place-items: center; min-width: 42px; height: 38px; padding: 0 5px; border: 1px solid #27536a; background: #0b2230; color: #22d3ee; font: 700 10px Bahnschrift; }.file-cell strong,.file-cell small { display: block; }.file-cell strong { max-width: 210px; overflow: hidden; color: #dce7f5; text-overflow: ellipsis; white-space: nowrap; }.file-cell small,.cell-sub { display: block; margin-top: 4px; color: #64748b; font: 11px Bahnschrift; }.mono { font-family: Bahnschrift, Consolas, monospace; }.time { color: #9aabc1; font-size: 12px; }.mapping-cell { flex-wrap: wrap; gap: 5px; }.mapping-cell strong { color: #cbd5e1; font: 600 12px Bahnschrift; }.mapping-cell span { color: #42536a; }.mapping-cell small { width: 100%; color: #22d3ee; font: 11px Bahnschrift; }.unmapped { color: #d79b32; font-size: 12px; }.unmapped i { display: inline-block; width: 7px; height: 7px; margin-right: 6px; border: 1px solid #f59e0b; }.status-pill { display: inline-flex; align-items: center; gap: 6px; color: #94a3b8; font-size: 12px; }.status-pill i { width: 7px; height: 7px; border-radius: 50%; background: currentColor; box-shadow: 0 0 8px currentColor; }.status-pill.cyan { color: #22d3ee; }.status-pill.amber { color: #f59e0b; }.status-pill.green { color: #10b981; }.status-pill.red,.error-text { color: #f87171; }.no-action { color: #60728a; font-size: 11px; }.retry { color: #f59e0b !important; }.associate-file { margin: -6px 0 16px; padding: 12px; border: 1px solid #2b405b; background: #0c1828; }.associate-file small,.associate-file strong { display: block; }.associate-file small { color: #6c7f99; }.associate-file strong { margin-top: 4px; color: #dce7f5; }.associate-form { margin-top: 20px; }.associate-form .el-select { width: 100%; }.field-warning { display: block; color: #e6a23c; font-size: 12px; line-height: 1.5; }
::v-deep .el-table,::v-deep .el-table th,::v-deep .el-table tr { background: transparent; color: #9fb0c7; }::v-deep .el-table td,::v-deep .el-table th.is-leaf { border-color: #20314a; }::v-deep .el-table::before { background: #20314a; }::v-deep .el-table--enable-row-hover .el-table__body tr:hover > td { background: #14243a; }::v-deep .el-input__inner { border-color: #30435e; background: #0b1625; color: #dbeafe; }::v-deep .el-button--default { border-color: #30435e; background: #111c30; color: #cbd5e1; }::v-deep .pagination-container { background: transparent; }::v-deep .el-pagination button,::v-deep .el-pager li { background: #0b1625; color: #9fb0c7; }
@media (max-width: 1180px) { .pipeline { grid-template-columns: repeat(5, 1fr); gap: 7px; }.pipeline-line { display: none; }.pipeline-stage { height: 84px; }.ledger-toolbar { align-items: flex-start; flex-direction: column; }.filters { width: 100%; flex-wrap: wrap; } }
@media (max-width: 720px) { .ingest-page { padding: 14px; }.ingest-head { align-items: flex-start; flex-direction: column; }.pipeline { grid-template-columns: 1fr 1fr; }.pipeline-stage:last-of-type { grid-column: 1 / 3; }.stage-code { display: none; }.filters .el-select,.filters .el-input { width: 100%; }.ledger-panel { padding: 12px; } }
@media (prefers-reduced-motion: reduce) { .pipeline-stage { transition: none; } }
</style>
