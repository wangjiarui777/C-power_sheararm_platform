<template>
  <div class="access-page">
    <header class="workbench-head">
      <div>
        <span class="eyebrow">MAT 接入与通道绑定</span>
        <h1>MAT 接入配置</h1>
        <p>将 8888 MAT 协议中的物理通道绑定到设备测点，形成可追溯的诊断信号链。</p>
      </div>
      <div class="head-actions">
        <span class="live-indicator"><i />{{ enabledCount }} / {{ total }} 通道在线</span>
        <el-button icon="el-icon-refresh" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button v-hasPermi="['sensor:channel:add']" type="primary" icon="el-icon-plus" @click="openChannel()">新增绑定</el-button>
      </div>
    </header>

    <section class="signal-rail" aria-label="采集信号链">
      <div class="rail-node"><span>01</span><small>MAT 采集端</small><strong>TCP : 8888</strong></div>
      <i class="rail-line" />
      <div class="rail-node"><span>02</span><small>采集终端</small><strong>{{ selectedTerminal }}</strong></div>
      <i class="rail-line" />
      <div class="rail-node is-accent"><span>03</span><small>设备测点</small><strong>{{ selectedPointName }}</strong></div>
      <i class="rail-line" />
      <div class="rail-node"><span>04</span><small>诊断信号</small><strong>{{ activeFilterLabel }}</strong></div>
    </section>

    <section class="filter-panel">
      <div class="filter-title"><span>接入范围</span><small>数据范围已在服务端过滤</small></div>
      <el-select v-model="query.deviceId" clearable filterable placeholder="全部设备" @change="onQueryDeviceChange">
        <el-option v-for="item in devices" :key="item.id" :label="`${item.deviceName} · ${item.deviceCode}`" :value="item.id" />
      </el-select>
      <el-select v-model="query.pointId" clearable filterable placeholder="全部测点" @change="loadChannels">
        <el-option v-for="item in queryPoints" :key="item.id" :label="`${item.pointName} · ${item.pointCode}`" :value="item.id" />
      </el-select>
      <el-button type="primary" plain icon="el-icon-search" @click="loadChannels">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </section>

    <section v-loading="loading" class="terminal-panel">
      <div class="panel-heading">
        <div><span class="section-index">02</span><div><h2>MAT 通道总览</h2><p>每一行对应一个设备内唯一的物理通道。</p></div></div>
        <div class="legend"><span><i class="on" />启用</span><span><i />停用</span><span><i class="free" />未绑定</span></div>
      </div>

      <div v-if="rows.length" class="terminal-list">
        <article v-for="row in rows" :key="row.id" class="terminal-row" :class="{ 'is-disabled': !row.enabled, 'is-unbound': !row.pointId }">
          <div class="terminal-port"><span>CH</span><strong>{{ pad(row.channelNo) }}</strong><small>MAT</small></div>
          <div class="wire"><i /><span /></div>
          <div class="channel-source"><small>MAT 来源</small><strong>{{ row.deviceCode }}</strong><span>8888 / CWRU_MAT_V2</span></div>
          <div class="channel-metric"><small>采样率</small><strong>{{ formatRate(row.sampleRate) }}</strong><span>{{ row.signalType || '未定义信号' }}</span></div>
          <div class="channel-target"><small>{{ deviceLabel(row.deviceId) }}</small><strong>{{ pointLabel(row.pointId) }}</strong><span>{{ row.pointCode || '等待测点绑定' }} · {{ row.unit || '--' }}</span></div>
          <div class="channel-actions">
            <el-switch v-hasPermi="['sensor:channel:edit']" v-model="row.enabled" active-color="#22d3ee" aria-label="启停通道" @change="toggleChannel(row)" />
            <el-tag v-if="!row.pointId" size="mini" type="warning">未绑定</el-tag>
            <el-button v-hasPermi="['sensor:channel:edit']" type="text" @click="openChannel(row)">编辑</el-button>
            <el-button v-hasPermi="['sensor:channel:remove']" type="text" class="danger" @click="removeChannel(row)">删除</el-button>
          </div>
        </article>
      </div>
      <div v-else class="empty-state"><i class="el-icon-connection" /><strong>当前范围暂无采集通道</strong><span>可创建首个端子绑定，或调整设备与测点筛选。</span></div>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="loadChannels" />
    </section>

    <el-dialog :title="form.id ? '编辑通道绑定' : '新增通道绑定'" :visible.sync="dialogVisible" width="760px" append-to-body custom-class="industrial-dialog">
      <div class="dialog-signal"><span>MAT 8888</span><i /><span>采集通道</span><i /><span>设备测点</span></div>
      <el-form ref="channelForm" :model="form" :rules="rules" label-width="96px">
        <el-row :gutter="16">
          <el-col :sm="6" :xs="12"><el-form-item label="通道号" prop="channelNo"><el-input-number v-model="form.channelNo" :min="1" :max="64" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="设备" prop="deviceId"><el-select v-model="form.deviceId" filterable @change="onFormDeviceChange"><el-option v-for="item in devices" :key="item.id" :label="`${item.deviceName} · ${item.deviceCode}`" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="测点"><el-select v-model="form.pointId" clearable filterable @change="syncPoint"><el-option v-for="item in formPoints" :key="item.id" :label="`${item.pointName} · ${item.pointCode}`" :value="item.id" /></el-select></el-form-item></el-col>
          <el-col :sm="8" :xs="24"><el-form-item label="采样率"><el-input-number v-model="form.sampleRate" :min="0" :precision="2" :controls="false" /></el-form-item></el-col>
          <el-col :sm="8" :xs="24"><el-form-item label="单位"><el-input v-model="form.unit" placeholder="如 mm/s" /></el-form-item></el-col>
          <el-col :sm="8" :xs="24"><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item></el-col>
          <el-col :sm="8" :xs="24"><el-form-item label="比例系数"><el-input-number v-model="form.scaleFactor" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :sm="8" :xs="24"><el-form-item label="偏移量"><el-input-number v-model="form.offsetValue" :precision="6" :controls="false" /></el-form-item></el-col>
          <el-col :sm="8" :xs="24"><el-form-item label="质量窗口"><el-input-number v-model="form.qualityPolicySeconds" :min="1" /><span class="unit-suffix">秒</span></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="传感器型号"><el-input v-model="form.sensorModel" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="安装位置"><el-input v-model="form.mountPosition" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item></el-col>
        </el-row>
      </el-form>
      <span slot="footer"><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitChannel">保存绑定</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { listAcquisitionChannels, getAcquisitionOptions, addAcquisitionChannel, updateAcquisitionChannel, removeAcquisitionChannel } from '@/api/sensor/access'

const emptyForm = () => ({ channelNo: 1, deviceId: null, pointId: null, sampleRate: 25600, unit: '', scaleFactor: 1, offsetValue: 0, qualityPolicySeconds: 300, enabled: true })

export default {
  name: 'SensorAccessPoints',
  data() {
    return {
      loading: false, submitting: false, dialogVisible: false, rows: [], total: 0,
      devices: [], points: [], form: emptyForm(),
      query: { pageNum: 1, pageSize: 10, deviceId: null, pointId: null },
      rules: {
        channelNo: [{ required: true, message: '请输入通道号', trigger: 'change' }],
        deviceId: [{ required: true, message: '请选择设备', trigger: 'change' }]
      }
    }
  },
  computed: {
    queryPoints() { return this.points.filter(item => !this.query.deviceId || String(item.deviceId) === String(this.query.deviceId)) },
    formPoints() { return this.points.filter(item => String(item.deviceId) === String(this.form.deviceId)) },
    enabledCount() { return this.rows.filter(item => item.enabled).length },
    selectedTerminal() { return this.rows.length === 1 ? `CH${this.pad(this.rows[0].channelNo)}` : `${this.rows.length} 个通道` },
    selectedPointName() { return this.query.pointId ? this.pointLabel(this.query.pointId) : '全部测点' },
    activeFilterLabel() { return this.query.deviceId ? this.deviceLabel(this.query.deviceId) : '数据范围内全部设备' }
  },
  created() { this.loadAll() },
  methods: {
    async loadAll() {
      this.loading = true
      try {
        const options = await getAcquisitionOptions()
        const data = options.data || {}
        this.devices = data.devices || []; this.points = data.points || []
        await this.loadChannels(false)
      } finally { this.loading = false }
    },
    async loadChannels(showLoading = true) {
      if (showLoading) this.loading = true
      try { const res = await listAcquisitionChannels(this.query); this.rows = res.rows || []; this.total = res.total || 0 } finally { if (showLoading) this.loading = false }
    },
    onQueryDeviceChange() { this.query.pointId = null; this.query.pageNum = 1; this.loadChannels() },
    resetQuery() { this.query = { pageNum: 1, pageSize: 10, deviceId: null, pointId: null }; this.loadChannels() },
    openChannel(row) {
      this.form = row ? Object.assign({}, row) : emptyForm()
      if (!row && this.query.deviceId) this.form.deviceId = this.query.deviceId
      this.dialogVisible = true
      this.$nextTick(() => this.$refs.channelForm && this.$refs.channelForm.clearValidate())
    },
    onFormDeviceChange() { this.form.pointId = null; this.form.unit = '' },
    syncPoint(id) { const point = this.points.find(item => String(item.id) === String(id)); if (point) this.form.unit = point.unit || this.form.unit },
    submitChannel() {
      this.$refs.channelForm.validate(async valid => {
        if (!valid || this.submitting) return
        this.submitting = true
        try {
          await (this.form.id ? updateAcquisitionChannel(this.form) : addAcquisitionChannel(this.form))
          this.$modal.msgSuccess('通道绑定已保存'); this.dialogVisible = false; await this.loadChannels()
        } finally { this.submitting = false }
      })
    },
    async toggleChannel(row) {
      try { await updateAcquisitionChannel(Object.assign({}, row)); this.$modal.msgSuccess(row.enabled ? '通道已启用' : '通道已停用') } catch (error) { row.enabled = !row.enabled }
    },
    async removeChannel(row) { await this.$modal.confirm(`确认删除 ${row.deviceCode} / CH${row.channelNo} 的绑定？`); await removeAcquisitionChannel(row.id); this.$modal.msgSuccess('通道绑定已删除'); await this.loadChannels() },
    pad(value) { return String(value == null ? '--' : value).padStart(2, '0') },
    deviceLabel(id) { const item = this.devices.find(value => String(value.id) === String(id)); return item ? item.deviceName : '未知设备' },
    pointLabel(id) { const item = this.points.find(value => String(value.id) === String(id)); return item ? item.pointName : '未绑定测点' },
    formatRate(value) { return value == null ? '--' : `${Number(value).toLocaleString()} Hz` }
  }
}
</script>

<style scoped lang="scss">
.access-page { min-height: calc(100vh - 84px); padding: 24px; background: #08111d; color: #dbeafe; font-family: "Microsoft YaHei", sans-serif; }
.workbench-head { display: flex; justify-content: space-between; gap: 24px; align-items: flex-end; margin-bottom: 20px; }
.eyebrow { color: #22d3ee; font: 700 12px/1 Bahnschrift, sans-serif; letter-spacing: .16em; }
h1 { margin: 8px 0 6px; font-size: 28px; color: #f8fafc; } .workbench-head p { margin: 0; color: #8293ad; }
.head-actions { display: flex; align-items: center; gap: 10px; }.live-indicator { margin-right: 8px; color: #a8b8ce; font: 600 13px Bahnschrift, sans-serif; }.live-indicator i { display: inline-block; width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: #10b981; box-shadow: 0 0 10px #10b981; }
.signal-rail { display: grid; grid-template-columns: 1fr 50px 1fr 50px 1fr 50px 1fr; align-items: center; padding: 18px 20px; border: 1px solid #20314b; background: linear-gradient(90deg, #0d1929, #111c30); }
.rail-node { position: relative; padding-left: 42px; min-width: 0; }.rail-node > span { position: absolute; left: 0; top: 1px; display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid #40516c; border-radius: 50%; color: #7f92ae; font: 700 11px Bahnschrift; }.rail-node small { display: block; color: #63748d; font: 700 10px Bahnschrift; letter-spacing: .12em; }.rail-node strong { display: block; margin-top: 5px; overflow: hidden; color: #cbd5e1; text-overflow: ellipsis; white-space: nowrap; }.rail-node.is-accent > span { border-color: #22d3ee; color: #22d3ee; box-shadow: inset 0 0 10px rgba(34,211,238,.16); }.rail-node.is-accent strong { color: #67e8f9; }.rail-line { height: 1px; background: linear-gradient(90deg, #244059, #22d3ee, #244059); }
.filter-panel { display: flex; align-items: center; gap: 10px; margin: 16px 0; padding: 14px 16px; border: 1px solid #1d2b41; background: #0d1726; }.filter-title { margin-right: auto; }.filter-title span,.filter-title small { display: block; }.filter-title span { color: #e2e8f0; font-weight: 700; }.filter-title small { margin-top: 3px; color: #64748b; }.filter-panel .el-select { width: 220px; }
.terminal-panel { min-height: 350px; padding: 18px; border: 1px solid #20314b; background: #111c30; box-shadow: 0 20px 50px rgba(0,0,0,.18); }.panel-heading,.panel-heading > div,.legend { display: flex; align-items: center; }.panel-heading { justify-content: space-between; margin-bottom: 14px; }.section-index { margin-right: 12px; color: #22d3ee; font: 700 20px Bahnschrift; }.panel-heading h2 { margin: 0; color: #f1f5f9; font-size: 17px; }.panel-heading p { margin: 4px 0 0; color: #64748b; font-size: 12px; }.legend { gap: 15px; color: #7f91aa; font-size: 12px; }.legend i { display: inline-block; width: 8px; height: 8px; margin-right: 5px; border-radius: 2px; background: #475569; }.legend i.on { background: #10b981; }.legend i.free { border: 1px solid #f59e0b; background: transparent; }
.terminal-list { display: grid; gap: 8px; }.terminal-row { display: grid; grid-template-columns: 70px 72px 1.1fr .8fr 1.4fr auto; min-height: 78px; align-items: center; padding: 0 14px 0 0; border: 1px solid #243650; background: #0b1625; transition: border-color .18s, transform .18s; }.terminal-row:hover { border-color: #2c6d7c; transform: translateX(2px); }.terminal-row.is-disabled { opacity: .62; }.terminal-row.is-unbound { border-style: dashed; }.terminal-port { align-self: stretch; display: grid; grid-template-columns: 1fr 1fr; place-content: center; padding: 8px; border-right: 1px solid #29405b; background: #101e31; text-align: center; }.terminal-port span,.terminal-port small { color: #6d819c; font: 700 10px Bahnschrift; }.terminal-port strong { color: #22d3ee; font: 700 23px Bahnschrift; }.terminal-port small { grid-column: 1 / 3; }.wire { position: relative; height: 100%; }.wire i { position: absolute; left: 0; right: 13px; top: 50%; height: 1px; background: #22d3ee; }.wire span { position: absolute; right: 7px; top: calc(50% - 6px); width: 12px; height: 12px; border: 2px solid #22d3ee; border-radius: 50%; background: #0b1625; }.channel-source,.channel-metric,.channel-target { min-width: 0; padding: 0 12px; }.channel-source small,.channel-metric small,.channel-target small { display: block; color: #60728d; font-size: 11px; }.channel-source strong,.channel-metric strong,.channel-target strong { display: block; margin: 4px 0; overflow: hidden; color: #dce7f5; font: 600 14px Bahnschrift, sans-serif; text-overflow: ellipsis; white-space: nowrap; }.channel-source span,.channel-metric span,.channel-target span { color: #71839d; font-size: 11px; }.channel-target { border-left: 1px solid #1d2c42; }.channel-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }.danger { color: #f87171 !important; }
.empty-state { display: grid; place-items: center; padding: 70px 20px; color: #64748b; }.empty-state i { margin-bottom: 12px; color: #22d3ee; font-size: 34px; }.empty-state strong { color: #c3d0e2; }.empty-state span { margin-top: 7px; font-size: 12px; }.dialog-signal { display: flex; align-items: center; gap: 10px; margin: -6px 0 20px; color: #22d3ee; font: 700 11px Bahnschrift; letter-spacing: .1em; }.dialog-signal i { width: 70px; height: 1px; background: #22d3ee; }.unit-suffix { margin-left: 6px; color: #8492a6; }
::v-deep .el-input__inner,::v-deep .el-textarea__inner { border-color: #30435e; background: #0b1625; color: #dbeafe; }::v-deep .el-button--default { border-color: #30435e; background: #111c30; color: #cbd5e1; }::v-deep .pagination-container { background: transparent; }::v-deep .el-pagination button,::v-deep .el-pager li { background: #0b1625; color: #9fb0c7; }
@media (max-width: 980px) { .workbench-head { align-items: flex-start; flex-direction: column; }.signal-rail { grid-template-columns: 1fr; gap: 10px; }.rail-line { width: 1px; height: 12px; margin-left: 15px; }.filter-panel { align-items: stretch; flex-wrap: wrap; }.filter-title { width: 100%; }.terminal-row { grid-template-columns: 64px 52px 1fr 1fr; padding-right: 10px; }.channel-target { grid-column: 3 / 5; padding: 12px; border-top: 1px solid #1d2c42; }.channel-actions { grid-column: 1 / 5; justify-content: flex-end; padding: 8px 0; } }
@media (max-width: 600px) { .access-page { padding: 14px; }.head-actions { flex-wrap: wrap; }.filter-panel .el-select { width: 100%; }.terminal-row { grid-template-columns: 60px 42px 1fr; }.channel-metric { display: none; }.channel-target { grid-column: 3; }.channel-actions { grid-column: 1 / 4; }.legend { display: none; } }
@media (prefers-reduced-motion: reduce) { .terminal-row { transition: none; }.terminal-row:hover { transform: none; } }
</style>
