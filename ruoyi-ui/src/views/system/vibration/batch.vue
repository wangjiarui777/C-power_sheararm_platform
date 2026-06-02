<template>
  <div class="app-container">
    <el-card shadow="never" class="mb16">
      <div slot="header" class="clearfix">
        <span>历史批次管理</span>
      </div>

      <el-form
        :model="queryParams"
        ref="queryForm"
        size="small"
        :inline="true"
        v-show="showSearch"
        label-width="90px"
      >
        <el-form-item label="设备编号" prop="deviceCode">
          <el-input
            v-model="queryParams.deviceCode"
            placeholder="请输入设备编号"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <el-form-item label="批次ID" prop="batchId">
          <el-input
            v-model="queryParams.batchId"
            placeholder="请输入批次ID"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <el-form-item label="采样频率" prop="sampleRate">
          <el-input
            v-model="queryParams.sampleRate"
            placeholder="请输入采样频率"
            clearable
            @keyup.enter.native="handleQuery"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">查询</el-button>
          <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-row :gutter="10" class="mb8">
        <el-col :span="1.5">
          <el-button type="success" plain icon="el-icon-refresh" size="mini" @click="getList">刷新</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd">新增批次</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="warning" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate">修改</el-button>
        </el-col>
        <el-col :span="1.5">
          <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete">删除</el-button>
        </el-col>
        <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
      </el-row>

      <el-table v-loading="loading" :data="batchList" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" align="center" />
        <el-table-column label="批次ID" align="center" prop="batchId" width="120" />
        <el-table-column label="设备编号" align="center" prop="deviceCode" />
        <el-table-column label="采样频率(Hz)" align="center" prop="sampleRate" width="130" />
        <el-table-column label="采样点数" align="center" prop="sampleCount" width="120" />
        <el-table-column label="采集时间" align="center" prop="collectTime" width="180">
          <template slot-scope="scope">
            <span>{{ parseTime(scope.row.collectTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" align="center" prop="status" width="100">
          <template slot-scope="scope">
            <el-tag :type="statusTag(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="360" class-name="small-padding fixed-width">
          <template slot-scope="scope">
            <el-button type="text" size="mini" icon="el-icon-view" @click="handleDetail(scope.row)">查看分析详情</el-button>
            <el-button type="text" size="mini" icon="el-icon-refresh-right" @click="handleReplay(scope.row)">一键回放波形</el-button>
            <el-button type="text" size="mini" icon="el-icon-edit" @click="handleUpdate(scope.row)">修改</el-button>
            <el-button type="text" size="mini" icon="el-icon-delete" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination
        v-show="total > 0"
        :total="total"
        :page.sync="queryParams.pageNum"
        :limit.sync="queryParams.pageSize"
        @pagination="getList"
      />
    </el-card>

    <el-dialog :title="title" :visible.sync="open" width="620px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="设备编号" prop="deviceCode">
          <el-input v-model="form.deviceCode" placeholder="请输入设备编号" />
        </el-form-item>
        <el-form-item label="批次ID" prop="batchId">
          <el-input v-model="form.batchId" placeholder="系统生成时可不填" />
        </el-form-item>
        <el-form-item label="采样频率(Hz)" prop="sampleRate">
          <el-input v-model="form.sampleRate" placeholder="请输入采样频率" />
        </el-form-item>
        <el-form-item label="采样点数" prop="sampleCount">
          <el-input v-model="form.sampleCount" placeholder="请输入采样点数" />
        </el-form-item>
        <el-form-item label="采集时间" prop="collectTime">
          <el-date-picker
            v-model="form.collectTime"
            style="width: 100%"
            value-format="yyyy-MM-dd HH:mm:ss"
            type="datetime"
            placeholder="请选择采集时间"
          />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" placeholder="请选择状态" style="width: 100%;">
            <el-option label="正常" :value="0" />
            <el-option label="预警" :value="1" />
            <el-option label="报警" :value="2" />
          </el-select>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="分析详情" :visible.sync="detailOpen" width="860px" append-to-body>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="批次ID">{{ detail.batchId }}</el-descriptions-item>
        <el-descriptions-item label="设备编号">{{ detail.deviceCode }}</el-descriptions-item>
        <el-descriptions-item label="采样频率">{{ detail.sampleRate }}</el-descriptions-item>
        <el-descriptions-item label="采样点数">{{ detail.sampleCount }}</el-descriptions-item>
        <el-descriptions-item label="采集时间">{{ parseTime(detail.collectTime) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusText(detail.status) }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">诊断结果</el-divider>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="RMS">{{ detail.rms }}</el-descriptions-item>
        <el-descriptions-item label="峰值">{{ detail.peak }}</el-descriptions-item>
        <el-descriptions-item label="峰值因子">{{ detail.crestFactor }}</el-descriptions-item>
        <el-descriptions-item label="峭度">{{ detail.kurtosis }}</el-descriptions-item>
        <el-descriptions-item label="重心频率">{{ detail.centroidFrequency }}</el-descriptions-item>
        <el-descriptions-item label="均方根频率">{{ detail.rmsFrequency }}</el-descriptions-item>
        <el-descriptions-item label="诊断结论" :span="3">{{ detail.diagnosisResult }}</el-descriptions-item>
      </el-descriptions>

      <el-row :gutter="16" style="margin-top: 16px;">
        <el-col :span="12">
          <div ref="detailTimeChart" class="detail-chart"></div>
        </el-col>
        <el-col :span="12">
          <div ref="detailFreqChart" class="detail-chart"></div>
        </el-col>
      </el-row>
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import {
  listVibrationBatch,
  getVibrationBatch,
  addVibrationBatch,
  updateVibrationBatch,
  delVibrationBatch
} from '@/api/system/vibrationAnalysis'

export default {
  name: 'VibrationBatch',
  data() {
    return {
      loading: true,
      showSearch: true,
      total: 0,
      batchList: [],
      ids: [],
      single: true,
      multiple: true,
      title: '',
      open: false,
      detailOpen: false,
      detail: {},
      detailTimeChart: null,
      detailFreqChart: null,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        batchId: undefined,
        deviceCode: undefined,
        sampleRate: undefined
      },
      form: {},
      rules: {
        deviceCode: [{ required: true, message: '设备编号不能为空', trigger: 'blur' }],
        collectTime: [{ required: true, message: '采集时间不能为空', trigger: 'change' }],
        status: [{ required: true, message: '状态不能为空', trigger: 'change' }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listVibrationBatch(this.queryParams).then(res => {
        const rows = res.rows || res.data || []
        this.batchList = Array.isArray(rows) ? rows : []
        this.total = res.total || this.batchList.length
      }).finally(() => {
        this.loading = false
      })
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.queryParams.batchId = undefined
      this.queryParams.deviceCode = undefined
      this.queryParams.sampleRate = undefined
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.batchId)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = '新增历史批次'
    },
    handleUpdate(row) {
      this.reset()
      const batchId = row.batchId || this.ids[0]
      getVibrationBatch(batchId).then(res => {
        this.form = res.data || res
        this.open = true
        this.title = '修改历史批次'
      })
    },
    handleDelete(row) {
      const batchId = row.batchId || this.ids[0]
      this.$modal.confirm(`是否确认删除批次ID为"${batchId}"的数据项？`).then(() => {
        return delVibrationBatch(batchId)
      }).then(() => {
        this.$modal.msgSuccess('删除成功')
        this.getList()
      }).catch(() => {})
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        batchId: undefined,
        deviceCode: undefined,
        sampleRate: undefined,
        sampleCount: undefined,
        collectTime: undefined,
        status: 0
      }
      this.resetForm('form')
    },
    submitForm() {
      this.$refs.form.validate(valid => {
        if (!valid) return
        const action = this.form.batchId ? updateVibrationBatch : addVibrationBatch
        action(this.form).then(() => {
          this.$modal.msgSuccess(this.form.batchId ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        })
      })
    },
    handleDetail(row) {
      getVibrationBatch(row.batchId).then(res => {
        const data = res.data || res
        this.detail = data || {}
        this.detailOpen = true
        this.$nextTick(() => {
          this.initDetailCharts()
        })
      })
    },
    handleReplay(row) {
      getVibrationBatch(row.batchId).then(res => {
        const data = res.data || res || {}
        this.detail = data
        this.detailOpen = true
        this.$nextTick(() => {
          this.initDetailCharts()
          this.$modal.msgSuccess('已加载该批次波形，可直接回放查看')
        })
      })
    },
    initDetailCharts() {
      if (!this.detailTimeChart) {
        this.detailTimeChart = echarts.init(this.$refs.detailTimeChart)
      }
      if (!this.detailFreqChart) {
        this.detailFreqChart = echarts.init(this.$refs.detailFreqChart)
      }

      const wave = this.safeParseArray(this.detail.waveJson)
      const spectrum = this.safeParseArray(this.detail.spectrumJson)
      const timeData = wave.map((v, i) => [i, v])
      const freqAxis = spectrum.map((_, i) => i)

      this.detailTimeChart.setOption({
        title: { text: '时域波形' },
        tooltip: { trigger: 'axis' },
        grid: { left: 45, right: 20, top: 45, bottom: 35 },
        xAxis: { type: 'value', name: '点位' },
        yAxis: { type: 'value', name: '幅值' },
        series: [{ type: 'line', smooth: true, showSymbol: false, data: timeData }]
      })

      this.detailFreqChart.setOption({
        title: { text: '频域频谱' },
        tooltip: { trigger: 'axis' },
        grid: { left: 45, right: 20, top: 45, bottom: 35 },
        xAxis: { type: 'value', name: '频率' },
        yAxis: { type: 'value', name: '幅值' },
        series: [{ type: 'line', smooth: true, showSymbol: false, data: freqAxis.map((f, i) => [f, spectrum[i]]) }]
      })
    },
    safeParseArray(json) {
      if (!json) return []
      if (Array.isArray(json)) return json
      try {
        const parsed = JSON.parse(json)
        return Array.isArray(parsed) ? parsed : []
      } catch (e) {
        return []
      }
    },
    statusText(status) {
      if (status === 1) return '预警'
      if (status === 2) return '报警'
      return '正常'
    },
    statusTag(status) {
      if (status === 1) return 'warning'
      if (status === 2) return 'danger'
      return 'success'
    }
  },
  beforeDestroy() {
    if (this.detailTimeChart) {
      this.detailTimeChart.dispose()
      this.detailTimeChart = null
    }
    if (this.detailFreqChart) {
      this.detailFreqChart.dispose()
      this.detailFreqChart = null
    }
  }
}
</script>

<style scoped>
.mb16 {
  margin-bottom: 16px;
}
.detail-chart {
  width: 100%;
  height: 320px;
}
</style>
