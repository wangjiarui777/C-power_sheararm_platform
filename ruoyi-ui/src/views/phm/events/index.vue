<template>
  <div class="app-container event-page">
    <section class="page-head">
      <div>
        <h2>设备大事记</h2>
        <p>按设备和年份查看接入、维修、保养等生命周期事件，辅助设备健康决策。</p>
      </div>
      <el-button v-hasPermi="['phm:event:add']" type="primary" icon="el-icon-plus" size="small" :disabled="!selectedDevice" @click="openEvent()">新增大事记</el-button>
    </section>

    <section class="filter-bar">
      <el-select v-model="selectedDeviceId" placeholder="选择设备" filterable size="small" @change="loadEvents">
        <el-option v-for="item in devices" :key="item.id" :label="`${item.deviceName}（${item.deviceCode}）`" :value="item.id" />
      </el-select>
      <el-date-picker v-model="selectedYear" type="year" value-format="yyyy" size="small" placeholder="年份" @change="loadEvents" />
      <el-button size="small" icon="el-icon-refresh" @click="loadEvents">刷新</el-button>
    </section>

    <section v-if="selectedDevice" class="device-strip">
      <div>
        <span>当前设备</span>
        <strong>{{ selectedDevice.deviceName }}</strong>
      </div>
      <div><span>设备编码</span><strong>{{ selectedDevice.deviceCode }}</strong></div>
      <div><span>运行状态</span><strong>{{ statusText(selectedDevice.status) }}</strong></div>
      <div><span>健康指数</span><strong>{{ selectedDevice.healthIndex || 0 }}%</strong></div>
    </section>

    <section class="month-grid">
      <article
        v-for="month in months"
        :key="month.value"
        class="month-card"
        :class="{ active: activeMonth === month.value, hasEvent: monthEvents(month.value).length }"
        @click="activeMonth = month.value"
      >
        <div class="month-title">{{ month.label }}</div>
        <strong>{{ monthEvents(month.value).length }}</strong>
        <span>事件</span>
        <div class="event-dots">
          <i v-for="item in monthEvents(month.value).slice(0, 5)" :key="item.id" :class="item.eventType"></i>
        </div>
      </article>
    </section>

    <section class="content-grid">
      <div class="event-panel">
        <div class="panel-head">
          <h3>{{ activeMonth }} 月事件</h3>
          <el-tag size="mini">{{ filteredEvents.length }} 条</el-tag>
        </div>
        <el-timeline v-if="filteredEvents.length" class="event-timeline">
          <el-timeline-item v-for="item in filteredEvents" :key="item.id" :timestamp="parseTime(item.eventTime)" placement="top">
            <div class="event-card">
              <div class="event-card-head">
                <el-tag size="mini" :type="eventTag(item.eventType)">{{ eventTypeText(item.eventType) }}</el-tag>
                <div>
                  <el-button v-hasPermi="['phm:event:edit']" type="text" size="mini" @click="openEvent(item)">编辑</el-button>
                  <el-button v-hasPermi="['phm:event:remove']" type="text" size="mini" @click="removeEvent(item)">删除</el-button>
                </div>
              </div>
              <p>{{ item.eventContent }}</p>
              <small>操作人：{{ item.operatorName || '--' }}</small>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="本月暂无大事记" />
      </div>

      <div class="event-panel">
        <div class="panel-head">
          <h3>全年事件清单</h3>
          <el-button size="mini" type="primary" plain @click="exportEvents">导出CSV</el-button>
        </div>
        <el-table :data="events" height="420" size="mini" stripe>
          <el-table-column prop="eventTime" label="时间" width="150">
            <template slot-scope="scope">{{ parseTime(scope.row.eventTime) }}</template>
          </el-table-column>
          <el-table-column prop="eventType" label="类型" width="90">
            <template slot-scope="scope">
              <el-tag :type="eventTag(scope.row.eventType)" size="mini">{{ eventTypeText(scope.row.eventType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="eventContent" label="内容" min-width="220" show-overflow-tooltip />
        </el-table>
      </div>
    </section>

    <el-dialog :title="eventForm.id ? '编辑大事记' : '新增大事记'" :visible.sync="eventVisible" width="560px">
      <el-form :model="eventForm" label-width="90px">
        <el-form-item label="所属设备">
          <el-input :value="selectedDevice ? selectedDevice.deviceName : ''" disabled />
        </el-form-item>
        <el-form-item label="事件时间">
          <el-date-picker v-model="eventForm.eventTime" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-select v-model="eventForm.eventType">
            <el-option label="设备接入" value="access" />
            <el-option label="维修" value="repair" />
            <el-option label="保养" value="maintenance" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="事件记录">
          <el-input v-model="eventForm.eventContent" type="textarea" :rows="4" maxlength="300" show-word-limit />
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="eventVisible = false">取消</el-button>
        <el-button v-hasPermi="['phm:event:add', 'phm:event:edit']" type="primary" @click="saveEventForm">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listPhmDevices, listDeviceEvents, saveDeviceEvent, deleteDeviceEvent } from '@/api/phm'

export default {
  name: 'PhmEvents',
  data() {
    const year = String(new Date().getFullYear())
    return {
      devices: [],
      events: [],
      selectedDeviceId: '',
      selectedYear: year,
      activeMonth: new Date().getMonth() + 1,
      eventVisible: false,
      eventForm: {},
      months: Array.from({ length: 12 }).map((_, index) => ({ value: index + 1, label: `${index + 1}月` }))
    }
  },
  computed: {
    selectedDevice() {
      return this.devices.find(item => String(item.id) === String(this.selectedDeviceId))
    },
    filteredEvents() {
      return this.monthEvents(this.activeMonth)
    }
  },
  created() {
    this.loadDevices()
  },
  methods: {
    async loadDevices() {
      const res = await listPhmDevices()
      this.devices = res.data || []
      const queryDeviceId = this.$route.query.deviceId
      if (queryDeviceId && this.devices.some(item => String(item.id) === String(queryDeviceId))) {
        this.selectedDeviceId = queryDeviceId
      }
      if (!this.selectedDeviceId && this.devices.length) {
        this.selectedDeviceId = this.devices[0].id
      }
      this.loadEvents()
    },
    async loadEvents() {
      if (!this.selectedDeviceId) return
      const device = this.devices.find(item => String(item.id) === String(this.selectedDeviceId))
      const res = await listDeviceEvents({
        deviceId: this.selectedDeviceId,
        deviceCode: device ? device.deviceCode : '',
        year: this.selectedYear
      })
      this.events = res.data || []
    },
    monthEvents(month) {
      return this.events.filter(item => {
        if (!item.eventTime) return false
        return new Date(item.eventTime).getMonth() + 1 === month
      })
    },
    openEvent(row) {
      const device = this.selectedDevice
      this.eventForm = row ? Object.assign({}, row) : {
        deviceId: device ? device.id : '',
        deviceCode: device ? device.deviceCode : '',
        eventType: 'maintenance',
        eventTime: this.defaultEventTime(),
        eventContent: ''
      }
      this.eventVisible = true
    },
    async saveEventForm() {
      if (!this.eventForm.eventContent) {
        return this.$message.warning('请填写事件记录')
      }
      await saveDeviceEvent(this.eventForm)
      this.$message.success('大事记已保存')
      this.eventVisible = false
      this.loadEvents()
    },
    removeEvent(row) {
      this.$confirm('确认删除该大事记？').then(async() => {
        await deleteDeviceEvent(row.id)
        this.$message.success('大事记已删除')
        this.loadEvents()
      })
    },
    exportEvents() {
      if (!this.events.length) return this.$message.warning('当前无可导出数据')
      const headers = ['eventTime', 'eventType', 'eventContent', 'operatorName']
      const csv = [headers.join(',')].concat(this.events.map(row => headers.map(key => `"${row[key] == null ? '' : String(row[key]).replace(/"/g, '""')}"`).join(','))).join('\n')
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = `设备大事记_${this.selectedYear}_${Date.now()}.csv`
      link.click()
      URL.revokeObjectURL(link.href)
    },
    defaultEventTime() {
      const date = new Date()
      const pad = value => String(value).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    },
    statusText(status) {
      return { normal: '正常', stopped: '停机', level1: '1级告警', level2: '2级告警', level3: '3级告警', level4: '4级告警', level5: '5级告警' }[status] || status || '--'
    },
    eventTypeText(type) {
      return { access: '设备接入', repair: '维修', maintenance: '保养', diagnosis: '智能诊断', alarm_handle: '告警处置', other: '其他' }[type] || type
    },
    eventTag(type) {
      return { access: 'success', repair: 'danger', maintenance: 'warning', diagnosis: 'danger', alarm_handle: 'success', other: 'info' }[type] || 'info'
    }
  }
}
</script>

<style scoped>
.event-page { min-height: calc(100vh - 84px); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 6px; }
.page-head p { margin: 0; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; }
.filter-bar .el-select { width: 300px; }
.device-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.device-strip div { padding: 12px 14px; border-radius: 14px; }
.device-strip span { display: block; font-size: 12px; }
.device-strip strong { display: block; margin-top: 6px; }
.month-grid { display: grid; grid-template-columns: repeat(12, minmax(74px, 1fr)); gap: 8px; margin-bottom: 14px; }
.month-card { min-height: 102px; border-radius: 14px; padding: 12px; cursor: pointer; }
.month-title { font-size: 12px; }
.month-card strong { display: block; margin-top: 8px; font-size: 22px; }
.month-card span { font-size: 12px; }
.event-dots { display: flex; gap: 4px; margin-top: 8px; }
.event-dots i { width: 7px; height: 7px; border-radius: 50%; background: var(--ops-muted); }
.content-grid { display: grid; grid-template-columns: minmax(0, 1fr) 520px; gap: 14px; }
.event-panel { border-radius: 16px; padding: 16px; min-height: 360px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.panel-head h3 { margin: 0; }
.event-timeline { max-height: 430px; overflow: auto; padding-right: 10px; }
.event-card { border-radius: 12px; padding: 12px; background: rgba(8, 17, 30, 0.48); border: 1px solid rgba(120, 153, 186, 0.16); }
.event-card-head { display: flex; align-items: center; justify-content: space-between; }
.event-card p { margin: 8px 0; line-height: 1.6; color: var(--ops-text); }
.event-card small { color: var(--ops-muted); }
@media (max-width: 1200px) {
  .month-grid { grid-template-columns: repeat(6, minmax(0, 1fr)); }
  .content-grid, .device-strip { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .page-head, .filter-bar { flex-direction: column; align-items: stretch; }
  .filter-bar .el-select { width: 100%; }
  .month-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
</style>
