<template>
  <div class="app-container alarm-page">
    <section class="page-head">
      <div>
        <h2>告警中心</h2>
        <p>统一查看设备告警与测点告警，完成确认、忽略和处置记录闭环。</p>
      </div>
      <el-button type="primary" icon="el-icon-refresh" size="small" @click="loadAlarms">刷新</el-button>
    </section>

    <section class="filter-bar">
      <el-input v-model="query.deviceCode" placeholder="设备编码" size="small" clearable @keyup.enter.native="loadAlarms" />
      <el-select v-model="query.status" placeholder="处理状态" size="small" clearable @change="loadAlarms">
        <el-option label="未处理" value="unhandled" />
        <el-option label="已处理" value="handled" />
        <el-option label="已忽略" value="ignored" />
        <el-option label="已过期" value="expired" />
      </el-select>
      <el-select v-model="query.alarmLevel" placeholder="告警等级" size="small" clearable @change="loadAlarms">
        <el-option v-for="level in [1,2,3,4,5]" :key="level" :label="`${level}级告警`" :value="level" />
      </el-select>
      <el-button size="small" @click="loadAlarms">查询</el-button>
    </section>

    <el-table v-loading="loading" :data="alarms" stripe @row-click="openDetail">
      <el-table-column prop="alarmNo" label="告警编号" width="160" />
      <el-table-column prop="deviceName" label="设备名称" min-width="160" />
      <el-table-column prop="pointName" label="测点" width="130" />
      <el-table-column prop="featureCode" label="特征值" width="100" />
      <el-table-column prop="alarmLevel" label="等级" width="90">
        <template slot-scope="scope">
          <el-tag :type="alarmTag(scope.row.alarmLevel)" size="mini">{{ scope.row.alarmLevel }}级</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="alarmValue" label="告警值" width="110" />
      <el-table-column prop="diagnosisResult" label="诊断/建议" min-width="180" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90">
        <template slot-scope="scope">
          <el-tag :type="statusTag(scope.row.status)" size="mini">{{ statusText(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="alarmTime" label="告警时间" width="160">
        <template slot-scope="scope">{{ parseTime(scope.row.alarmTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="150">
        <template slot-scope="scope">
          <el-button type="text" size="mini" @click.stop="openAction(scope.row, 'handle')">处理</el-button>
          <el-button type="text" size="mini" @click.stop="openAction(scope.row, 'ignore')">忽略</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :title="actionType === 'ignore' ? '忽略告警' : '处理告警'" :visible.sync="actionVisible" width="520px">
      <el-form :model="actionForm" label-width="90px">
        <el-form-item v-if="actionType === 'ignore'" label="忽略原因">
          <el-select v-model="actionForm.ignoreReason" placeholder="请选择">
            <el-option label="传感器异常" value="传感器异常" />
            <el-option label="告警规则有误" value="告警规则有误" />
            <el-option label="外部原因" value="外部原因" />
            <el-option label="设备已停机" value="设备已停机" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="actionForm.remark" type="textarea" :rows="4" maxlength="300" show-word-limit />
        </el-form-item>
        <el-form-item label="阈值调整">
          <div class="rule-inline">
            <span>{{ ruleForm.ruleName || '未匹配规则' }}</span>
            <el-input-number v-model="ruleForm.highLimit" :precision="4" size="mini" placeholder="高报" />
            <el-input-number v-model="ruleForm.highHighLimit" :precision="4" size="mini" placeholder="高高报" />
            <el-button size="mini" type="primary" plain :disabled="!ruleForm.id" @click="saveRuleThreshold">保存阈值</el-button>
          </div>
        </el-form-item>
        <div class="trend-preview">
          <div class="trend-title">告警测点趋势预览</div>
          <div ref="trendChart" class="trend-chart"></div>
        </div>
      </el-form>
      <div slot="footer">
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAction">确认</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { listAlarms, handleAlarm, ignoreAlarm, listAlarmRules, saveAlarmRule, getFeatureTrend } from '@/api/phm'
import sensorWebSocket from '@/utils/sensor-websocket'

export default {
  name: 'PhmAlarms',
  data() {
    return {
      loading: false,
      actionVisible: false,
      actionType: 'handle',
      currentAlarm: null,
      query: {
        deviceCode: '',
        status: '',
        alarmLevel: ''
      },
      actionForm: {
        ignoreReason: '传感器异常',
        remark: ''
      },
      rules: [],
      ruleForm: {},
      trendChart: null,
      alarms: [],
      unsubscribeAlarmWs: null
    }
  },
  created() {
    this.loadAlarms()
    this.loadRules()
    this.connectAlarmSocket()
  },
  beforeDestroy() {
    if (this.trendChart) this.trendChart.dispose()
    if (this.unsubscribeAlarmWs) {
      this.unsubscribeAlarmWs()
      this.unsubscribeAlarmWs = null
    }
  },
  methods: {
    connectAlarmSocket() {
      this.unsubscribeAlarmWs = sensorWebSocket.subscribe((event, payload) => {
        if (event === 'open') {
          sensorWebSocket.send({ type: 'subscribe', channel: 'phm_alarm' })
          return
        }
        if (event === 'message' && payload && payload.type === 'phm_alarm') {
          this.loadAlarms()
        }
      })
      sensorWebSocket.connect('/ws/sensor')
      sensorWebSocket.send({ type: 'subscribe', channel: 'phm_alarm' })
    },
    async loadAlarms() {
      this.loading = true
      try {
        const res = await listAlarms(this.query)
        this.alarms = res.data || []
      } finally {
        this.loading = false
      }
    },
    async loadRules() {
      const res = await listAlarmRules()
      this.rules = res.data || []
    },
    openDetail(row) {
      this.$alert(row.diagnosisResult || row.remark || '暂无详情', '告警详情', { confirmButtonText: '关闭' })
    },
    openAction(row, type) {
      this.currentAlarm = row
      this.actionType = type
      this.actionForm = { ignoreReason: '传感器异常', remark: '' }
      this.ruleForm = Object.assign({}, this.rules.find(rule => rule.featureCode === row.featureCode && (!rule.deviceId || rule.deviceId === row.deviceId)) || {})
      this.actionVisible = true
      this.$nextTick(() => this.loadTrendPreview(row))
    },
    async submitAction() {
      if (!this.currentAlarm) return
      if (this.actionType === 'ignore') {
        await ignoreAlarm(this.currentAlarm.id, this.actionForm)
      } else {
        await handleAlarm(this.currentAlarm.id, this.actionForm)
      }
      this.$message.success('告警状态已更新')
      this.actionVisible = false
      this.loadAlarms()
    },
    async saveRuleThreshold() {
      if (!this.ruleForm.id) return
      await saveAlarmRule(this.ruleForm)
      this.$message.success('阈值已更新')
      this.loadRules()
    },
    async loadTrendPreview(row) {
      if (!row || !row.pointId || !this.$refs.trendChart) return
      const res = await getFeatureTrend(row.pointId, { featureCode: row.featureCode || 'vibration' })
      const trend = res.data || []
      if (!this.trendChart) this.trendChart = echarts.init(this.$refs.trendChart)
      this.trendChart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 38, right: 12, top: 20, bottom: 24 },
        xAxis: { type: 'category', data: trend.map(item => this.parseTime(item.sampleTime, '{h}:{i}:{s}')) },
        yAxis: { type: 'value' },
        series: [{ type: 'line', smooth: true, showSymbol: false, data: trend.map(item => item.value), lineStyle: { color: '#2563eb', width: 2 } }]
      })
    },
    alarmTag(level) {
      return level >= 3 ? 'danger' : level === 2 ? 'warning' : 'info'
    },
    statusTag(status) {
      return status === 'unhandled' ? 'danger' : status === 'handled' ? 'success' : 'info'
    },
    statusText(status) {
      return { unhandled: '未处理', handled: '已处理', ignored: '已忽略', expired: '已过期' }[status] || status
    }
  }
}
</script>

<style scoped>
.alarm-page { background: #f6f8fb; min-height: calc(100vh - 84px); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 6px; color: #0f172a; }
.page-head p { margin: 0; color: #64748b; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; }
.filter-bar .el-input, .filter-bar .el-select { width: 180px; }
.rule-inline { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.rule-inline span { color: #475569; min-width: 92px; }
.trend-preview { margin-left: 90px; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px; }
.trend-title { margin-bottom: 6px; color: #475569; font-size: 12px; }
.trend-chart { height: 180px; }
@media (max-width: 780px) {
  .page-head, .filter-bar { flex-direction: column; align-items: stretch; }
  .filter-bar .el-input, .filter-bar .el-select { width: 100%; }
  .trend-preview { margin-left: 0; }
}
</style>
