<template>
  <div class="app-container config-page">
    <section class="page-head">
      <div>
        <h2>配置管理</h2>
        <p>维护设备资产、测点、告警规则与系统展示配置。</p>
      </div>
      <el-button type="primary" icon="el-icon-refresh" size="small" @click="loadAll">刷新</el-button>
    </section>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="设备管理" name="devices">
        <div class="toolbar"><el-button type="primary" size="small" @click="openDevice()">新增设备</el-button></div>
        <el-table :data="devices" stripe>
          <el-table-column prop="deviceName" label="设备名称" min-width="160" />
          <el-table-column prop="deviceCode" label="编码" width="130" />
          <el-table-column prop="deviceType" label="类型" width="120" />
          <el-table-column prop="orgName" label="所属节点" min-width="160" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="healthIndex" label="健康指数" width="100" />
          <el-table-column label="操作" width="150">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="openDevice(scope.row)">编辑</el-button>
              <el-button type="text" size="mini" @click="removeDevice(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="测点管理" name="points">
        <div class="toolbar">
          <el-select v-model="pointDeviceId" placeholder="选择设备" size="small" clearable @change="loadPoints">
            <el-option v-for="item in devices" :key="item.id" :label="item.deviceName" :value="item.id" />
          </el-select>
          <el-button type="primary" size="small" @click="openPoint()">新增测点</el-button>
        </div>
        <el-table :data="points" stripe>
          <el-table-column prop="pointName" label="测点名称" min-width="160" />
          <el-table-column prop="deviceCode" label="设备编码" width="130" />
          <el-table-column prop="channelId" label="通道" width="80" />
          <el-table-column prop="signalType" label="信号类型" width="110" />
          <el-table-column prop="featureCodes" label="显示特征值" min-width="180" />
          <el-table-column label="操作" width="150">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="openPoint(scope.row)">编辑</el-button>
              <el-button type="text" size="mini" @click="removePoint(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="告警规则" name="rules">
        <div class="toolbar"><el-button type="primary" size="small" @click="openRule()">新增规则</el-button></div>
        <el-table :data="rules" stripe>
          <el-table-column prop="ruleName" label="规则名称" min-width="160" />
          <el-table-column prop="featureCode" label="特征值" width="100" />
          <el-table-column prop="alarmType" label="类型" width="100" />
          <el-table-column prop="highLimit" label="高报" width="100" />
          <el-table-column prop="highHighLimit" label="高高报" width="100" />
          <el-table-column prop="deviceAlarmLevel" label="设备等级" width="100" />
          <el-table-column prop="actionAdvice" label="处理措施" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="150">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="openRule(scope.row)">编辑</el-button>
              <el-button type="text" size="mini" @click="removeRule(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="特征值配置" name="features">
        <div class="toolbar"><el-button type="primary" size="small" @click="openFeature()">新增特征值</el-button></div>
        <el-table :data="features" stripe>
          <el-table-column prop="featureName" label="特征值名称" min-width="150" />
          <el-table-column prop="featureCode" label="编码" width="130" />
          <el-table-column prop="unit" label="单位" width="90" />
          <el-table-column prop="signalType" label="信号类型" width="110" />
          <el-table-column prop="displayOrder" label="排序" width="80" />
          <el-table-column prop="enabled" label="启用" width="80">
            <template slot-scope="scope"><el-tag size="mini" :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="150">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="openFeature(scope.row)">编辑</el-button>
              <el-button type="text" size="mini" @click="removeFeature(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="图片管理" name="images">
        <div class="toolbar">
          <el-select v-model="attachmentDeviceId" placeholder="关联设备" size="small" clearable>
            <el-option v-for="item in devices" :key="item.id" :label="item.deviceName" :value="item.id" />
          </el-select>
          <el-upload
            :action="uploadUrl"
            :headers="uploadHeaders"
            accept=".jpg,.jpeg,.png,.webp"
            :show-file-list="false"
            :on-success="handleImageUploadSuccess"
          >
            <el-button type="primary" size="small">上传图片</el-button>
          </el-upload>
          <el-button size="small" @click="loadAttachments">刷新</el-button>
        </div>
        <el-table :data="attachments" stripe>
          <el-table-column label="预览" width="100">
            <template slot-scope="scope">
              <img v-if="scope.row.fileUrl" :src="scope.row.fileUrl" class="attachment-thumb" alt="图片预览">
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
          <el-table-column prop="bizType" label="类型" width="110" />
          <el-table-column label="关联设备" min-width="150">
            <template slot-scope="scope">{{ deviceName(scope.row.bizId) }}</template>
          </el-table-column>
          <el-table-column prop="fileUrl" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="操作" width="170">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="copyImageUrl(scope.row)">复制地址</el-button>
              <el-button type="text" size="mini" @click="removeAttachment(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="系统配置" name="system">
        <el-table :data="configs" stripe>
          <el-table-column prop="configName" label="配置名称" min-width="160" />
          <el-table-column prop="configKey" label="键" min-width="160" />
          <el-table-column label="值" min-width="220">
            <template slot-scope="scope">
              <el-switch
                v-if="isBooleanConfig(scope.row)"
                v-model="scope.row.configValue"
                active-value="true"
                inactive-value="false"
              />
              <el-input-number
                v-else-if="isNumberConfig(scope.row)"
                :value="Number(scope.row.configValue || 0)"
                :min="0"
                size="mini"
                @change="value => scope.row.configValue = String(value == null ? 0 : value)"
              />
              <el-select v-else-if="scope.row.configKey === 'default.display.mode'" v-model="scope.row.configValue" size="mini">
                <el-option label="列表模式" value="list" />
                <el-option label="卡片模式" value="card" />
              </el-select>
              <div v-else-if="scope.row.configKey === 'system.logo'" class="config-logo-cell">
                <el-input v-model="scope.row.configValue" size="mini" placeholder="Logo 图片地址" />
                <el-upload
                  :action="uploadUrl"
                  :headers="uploadHeaders"
                  accept=".jpg,.jpeg,.png,.webp,.svg"
                  :show-file-list="false"
                  :on-success="res => handleSystemLogoUploadSuccess(res, scope.row)"
                >
                  <el-button size="mini">上传</el-button>
                </el-upload>
                <img v-if="scope.row.configValue" :src="scope.row.configValue" class="config-logo-preview" alt="Logo预览">
              </div>
              <el-input v-else v-model="scope.row.configValue" size="mini" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="saveConfig(scope.row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="deviceForm.id ? '编辑设备' : '新增设备'" :visible.sync="deviceVisible" width="640px">
      <el-form :model="deviceForm" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="设备编码"><el-input v-model="deviceForm.deviceCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="设备名称"><el-input v-model="deviceForm.deviceName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="设备类型"><el-input v-model="deviceForm.deviceType" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="所属节点"><el-input v-model="deviceForm.orgName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="状态"><el-select v-model="deviceForm.status"><el-option label="正常" value="normal" /><el-option label="停机" value="stopped" /><el-option label="1级告警" value="level1" /><el-option label="2级告警" value="level2" /><el-option label="3级告警" value="level3" /></el-select></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="健康指数"><el-input-number v-model="deviceForm.healthIndex" :min="0" :max="100" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="形貌图地址"><el-input v-model="deviceForm.morphologyUrl" /></el-form-item></el-col>
          <el-col :span="24">
            <el-form-item label="上传形貌图">
              <el-upload
                :action="uploadUrl"
                :headers="uploadHeaders"
                :limit="1"
                accept=".jpg,.jpeg,.png,.webp"
                :show-file-list="true"
                :on-success="handleMorphologyUploadSuccess"
              >
                <el-button size="small" type="primary" plain>选择并上传</el-button>
                <div slot="tip" class="el-upload__tip">上传设备形貌图后会自动回填地址，测点坐标在“测点管理”中维护。</div>
              </el-upload>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer"><el-button @click="deviceVisible = false">取消</el-button><el-button type="primary" @click="saveDeviceForm">保存</el-button></div>
    </el-dialog>

    <el-dialog :title="pointForm.id ? '编辑测点' : '新增测点'" :visible.sync="pointVisible" width="620px">
      <el-form :model="pointForm" label-width="100px">
        <el-form-item label="所属设备">
          <el-select v-model="pointForm.deviceId" filterable @change="syncPointDevice">
            <el-option v-for="item in devices" :key="item.id" :label="item.deviceName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="测点编码"><el-input v-model="pointForm.pointCode" /></el-form-item>
        <el-form-item label="测点名称"><el-input v-model="pointForm.pointName" /></el-form-item>
        <el-form-item label="通道号"><el-input-number v-model="pointForm.channelId" :min="1" :max="8" /></el-form-item>
        <el-form-item label="信号类型"><el-select v-model="pointForm.signalType"><el-option label="振动" value="vibration" /><el-option label="温度" value="temperature" /></el-select></el-form-item>
        <el-form-item label="特征值"><el-input v-model="pointForm.featureCodes" placeholder="vibration,temperature,rms,peak" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="测点X(%)"><el-input-number v-model="pointForm.pointX" :min="0" :max="100" :precision="2" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="测点Y(%)"><el-input-number v-model="pointForm.pointY" :min="0" :max="100" :precision="2" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="卡片X(%)"><el-input-number v-model="pointForm.cardX" :min="0" :max="100" :precision="2" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="卡片Y(%)"><el-input-number v-model="pointForm.cardY" :min="0" :max="100" :precision="2" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <div slot="footer"><el-button @click="pointVisible = false">取消</el-button><el-button type="primary" @click="savePointForm">保存</el-button></div>
    </el-dialog>

    <el-dialog :title="ruleForm.id ? '编辑规则' : '新增规则'" :visible.sync="ruleVisible" width="620px">
      <el-form :model="ruleForm" label-width="110px">
        <el-form-item label="规则名称"><el-input v-model="ruleForm.ruleName" /></el-form-item>
        <el-form-item label="特征值"><el-select v-model="ruleForm.featureCode"><el-option label="振动速度" value="vibration" /><el-option label="温度" value="temperature" /><el-option label="加速度" value="acceleration" /></el-select></el-form-item>
        <el-form-item label="规则类型"><el-select v-model="ruleForm.alarmType"><el-option label="阈值报警" value="threshold" /><el-option label="趋势报警" value="trend" /></el-select></el-form-item>
        <el-form-item label="高报阈值"><el-input-number v-model="ruleForm.highLimit" :precision="4" /></el-form-item>
        <el-form-item label="高高报阈值"><el-input-number v-model="ruleForm.highHighLimit" :precision="4" /></el-form-item>
        <el-form-item label="设备等级"><el-input-number v-model="ruleForm.deviceAlarmLevel" :min="1" :max="5" /></el-form-item>
        <el-form-item label="处理措施"><el-input v-model="ruleForm.actionAdvice" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="ruleVisible = false">取消</el-button><el-button type="primary" @click="saveRuleForm">保存</el-button></div>
    </el-dialog>

    <el-dialog :title="featureForm.id ? '编辑特征值' : '新增特征值'" :visible.sync="featureVisible" width="560px">
      <el-form :model="featureForm" label-width="100px">
        <el-form-item label="特征值编码"><el-input v-model="featureForm.featureCode" placeholder="如 vibration/rms/temperature" /></el-form-item>
        <el-form-item label="特征值名称"><el-input v-model="featureForm.featureName" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="featureForm.unit" placeholder="mm/s、℃、g" /></el-form-item>
        <el-form-item label="信号类型">
          <el-select v-model="featureForm.signalType">
            <el-option label="振动" value="vibration" />
            <el-option label="温度" value="temperature" />
            <el-option label="加速度" value="acceleration" />
          </el-select>
        </el-form-item>
        <el-form-item label="展示顺序"><el-input-number v-model="featureForm.displayOrder" :min="0" /></el-form-item>
        <el-form-item label="是否启用"><el-switch v-model="featureForm.enabled" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="featureForm.remark" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="featureVisible = false">取消</el-button><el-button type="primary" @click="saveFeatureForm">保存</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listPhmDevices, savePhmDevice, deletePhmDevice,
  listMeasurePoints, saveMeasurePoint, deleteMeasurePoint,
  listAlarmRules, saveAlarmRule, deleteAlarmRule,
  listFeatures, saveFeature, deleteFeature,
  listAttachments, saveAttachment, deleteAttachment,
  listSystemConfig, saveSystemConfig
} from '@/api/phm'
import { getToken } from '@/utils/auth'

export default {
  name: 'PhmConfig',
  data() {
    return {
      activeTab: 'devices',
      devices: [],
      points: [],
      rules: [],
      features: [],
      attachments: [],
      configs: [],
      pointDeviceId: '',
      attachmentDeviceId: '',
      deviceVisible: false,
      pointVisible: false,
      ruleVisible: false,
      featureVisible: false,
      deviceForm: {},
      pointForm: {},
      ruleForm: {},
      featureForm: {},
      uploadUrl: process.env.VUE_APP_BASE_API + '/common/upload',
      uploadHeaders: { Authorization: 'Bearer ' + getToken() }
    }
  },
  created() {
    this.loadAll()
  },
  methods: {
    loadAll() {
      this.loadDevices()
      this.loadPoints()
      this.loadRules()
      this.loadFeatures()
      this.loadAttachments()
      this.loadConfigs()
    },
    async loadDevices() {
      const res = await listPhmDevices()
      this.devices = res.data || []
    },
    async loadPoints() {
      const res = await listMeasurePoints({ deviceId: this.pointDeviceId })
      this.points = res.data || []
    },
    async loadRules() {
      const res = await listAlarmRules()
      this.rules = res.data || []
    },
    async loadFeatures() {
      const res = await listFeatures()
      this.features = res.data || []
    },
    async loadAttachments() {
      const res = await listAttachments({ bizType: 'morphology' })
      this.attachments = res.data || []
    },
    async loadConfigs() {
      const res = await listSystemConfig()
      this.configs = res.data || []
    },
    openDevice(row) {
      this.deviceForm = row ? Object.assign({}, row) : { status: 'normal', healthIndex: 100 }
      this.deviceVisible = true
    },
    async saveDeviceForm() {
      await savePhmDevice(this.deviceForm)
      this.$message.success('设备已保存')
      this.deviceVisible = false
      this.loadDevices()
    },
    handleMorphologyUploadSuccess(res) {
      if (res.code === 200) {
        this.deviceForm.morphologyUrl = res.url
        this.$message.success('形貌图上传成功')
      } else {
        this.$message.error(res.msg || '上传失败')
      }
    },
    removeDevice(row) {
      this.$confirm('确认删除该设备？').then(async() => {
        await deletePhmDevice(row.id)
        this.$message.success('设备已删除')
        this.loadDevices()
      })
    },
    openPoint(row) {
      this.pointForm = row ? Object.assign({}, row) : { deviceId: this.pointDeviceId, channelId: 1, signalType: 'vibration', featureCodes: 'vibration,temperature,rms,peak' }
      this.syncPointDevice(this.pointForm.deviceId)
      this.pointVisible = true
    },
    syncPointDevice(deviceId) {
      const device = this.devices.find(item => item.id === deviceId)
      if (device) this.pointForm.deviceCode = device.deviceCode
    },
    async savePointForm() {
      await saveMeasurePoint(this.pointForm)
      this.$message.success('测点已保存')
      this.pointVisible = false
      this.loadPoints()
    },
    removePoint(row) {
      this.$confirm('确认删除该测点？').then(async() => {
        await deleteMeasurePoint(row.id)
        this.$message.success('测点已删除')
        this.loadPoints()
      })
    },
    openRule(row) {
      this.ruleForm = row ? Object.assign({}, row) : { featureCode: 'vibration', alarmType: 'threshold', highLimit: 0.2, highHighLimit: 0.3, deviceAlarmLevel: 2, enabled: true }
      this.ruleVisible = true
    },
    async saveRuleForm() {
      await saveAlarmRule(this.ruleForm)
      this.$message.success('规则已保存')
      this.ruleVisible = false
      this.loadRules()
    },
    removeRule(row) {
      this.$confirm('确认删除该规则？').then(async() => {
        await deleteAlarmRule(row.id)
        this.$message.success('规则已删除')
        this.loadRules()
      })
    },
    openFeature(row) {
      this.featureForm = row ? Object.assign({}, row) : { signalType: 'vibration', displayOrder: 0, enabled: true }
      this.featureVisible = true
    },
    async saveFeatureForm() {
      if (!this.featureForm.featureCode || !this.featureForm.featureName) {
        return this.$message.warning('请填写特征值编码和名称')
      }
      await saveFeature(this.featureForm)
      this.$message.success('特征值配置已保存')
      this.featureVisible = false
      this.loadFeatures()
    },
    removeFeature(row) {
      this.$confirm('确认删除该特征值配置？').then(async() => {
        await deleteFeature(row.id)
        this.$message.success('特征值配置已删除')
        this.loadFeatures()
      })
    },
    async handleImageUploadSuccess(res, file) {
      if (res.code !== 200) {
        return this.$message.error(res.msg || '上传失败')
      }
      await saveAttachment({
        bizType: 'morphology',
        bizId: this.attachmentDeviceId || null,
        fileName: res.fileName || file.name,
        fileUrl: res.url,
        fileExt: this.fileExt(res.fileName || file.name),
        remark: '配置管理上传'
      })
      this.$message.success('图片已上传并归档')
      this.loadAttachments()
    },
    removeAttachment(row) {
      this.$confirm('确认删除该图片归档？不会删除服务器上的原始文件。').then(async() => {
        await deleteAttachment(row.id)
        this.$message.success('图片归档已删除')
        this.loadAttachments()
      })
    },
    copyImageUrl(row) {
      const text = row.fileUrl || ''
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text)
        this.$message.success('图片地址已复制')
        return
      }
      const input = document.createElement('input')
      input.value = text
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      document.body.removeChild(input)
      this.$message.success('图片地址已复制')
    },
    deviceName(deviceId) {
      if (!deviceId) return '--'
      const device = this.devices.find(item => String(item.id) === String(deviceId))
      return device ? device.deviceName : deviceId
    },
    fileExt(name) {
      const index = name ? name.lastIndexOf('.') : -1
      return index >= 0 ? name.substring(index + 1) : ''
    },
    async saveConfig(row) {
      await saveSystemConfig(row)
      this.$message.success('配置已保存')
    },
    isBooleanConfig(row) {
      return row.configType === 'boolean' || row.configKey === 'alarm.sound.enabled'
    },
    isNumberConfig(row) {
      return row.configType === 'number' || row.configKey === 'refresh.interval'
    },
    handleSystemLogoUploadSuccess(res, row) {
      if (res.code === 200) {
        row.configValue = res.url
        this.$message.success('Logo 上传成功，请点击保存使配置生效')
      } else {
        this.$message.error(res.msg || '上传失败')
      }
    }
  }
}
</script>

<style scoped>
.config-page { background: #f6f8fb; min-height: calc(100vh - 84px); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 6px; color: #0f172a; }
.page-head p { margin: 0; color: #64748b; }
.toolbar { display: flex; gap: 10px; margin-bottom: 12px; }
.toolbar .el-select { width: 260px; }
.attachment-thumb { width: 70px; height: 46px; object-fit: cover; border-radius: 6px; border: 1px solid #e5e7eb; background: #fff; }
.config-logo-cell { display: flex; align-items: center; gap: 8px; }
.config-logo-cell .el-input { max-width: 320px; }
.config-logo-preview { width: 38px; height: 38px; object-fit: contain; border: 1px solid #e5e7eb; border-radius: 6px; background: #fff; }
</style>
