<template>
  <div v-loading="loading" class="app-container config-page" data-testid="phm-config-page">
    <section class="page-head">
      <div>
        <h2>配置管理</h2>
        <p>维护设备资产、测点、告警规则与系统展示配置。</p>
      </div>
      <el-button type="primary" icon="el-icon-refresh" size="small" :loading="loading" @click="loadAll">刷新</el-button>
    </section>

    <el-alert
      v-if="loadErrors.length"
      class="load-alert"
      title="部分配置读取失败"
      :description="loadErrors.join('；')"
      type="error"
      show-icon
      :closable="false"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="设备管理" name="devices">
        <div class="toolbar"><el-button v-hasPermi="['phm:device:add']" type="primary" size="small" @click="openDevice()">新增设备</el-button></div>
        <el-table :data="devices" stripe>
          <el-table-column prop="deviceName" label="设备名称" min-width="160" />
          <el-table-column prop="deviceCode" label="编码" width="130" />
          <el-table-column prop="deviceType" label="类型" width="120" />
          <el-table-column prop="orgName" label="所属节点" min-width="160" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column prop="healthIndex" label="健康指数" width="100" />
          <el-table-column label="操作" width="150">
            <template slot-scope="scope">
              <el-button v-hasPermi="['phm:device:edit']" type="text" size="mini" @click="openDevice(scope.row)">编辑</el-button>
              <el-button v-hasPermi="['phm:device:remove']" type="text" size="mini" @click="removeDevice(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="测点管理" name="points">
        <div class="toolbar">
          <el-select v-model="pointDeviceId" placeholder="选择设备" size="small" clearable @change="loadPoints">
            <el-option v-for="item in devices" :key="item.id" :label="item.deviceName" :value="item.id" />
          </el-select>
          <el-button v-hasPermi="['phm:device:add']" type="primary" size="small" @click="openPoint()">新增测点</el-button>
        </div>
        <el-table :data="points" stripe>
          <el-table-column prop="pointName" label="测点名称" min-width="160" />
          <el-table-column prop="deviceCode" label="设备编码" width="130" />
          <el-table-column prop="channelId" label="通道" width="80" />
          <el-table-column prop="signalType" label="信号类型" width="110" />
          <el-table-column prop="featureCodes" label="显示特征值" min-width="180" />
          <el-table-column label="操作" width="150">
            <template slot-scope="scope">
              <el-button v-hasPermi="['phm:device:edit']" type="text" size="mini" @click="openPoint(scope.row)">编辑</el-button>
              <el-button v-hasPermi="['phm:device:remove']" type="text" size="mini" @click="removePoint(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="告警规则" name="rules">
        <div class="toolbar"><el-button v-hasPermi="['phm:config:add']" type="primary" size="small" @click="openRule()">新增规则</el-button></div>
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
              <el-button v-hasPermi="['phm:config:edit']" type="text" size="mini" @click="openRule(scope.row)">编辑</el-button>
              <el-button v-hasPermi="['phm:config:remove']" type="text" size="mini" @click="removeRule(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="特征值配置" name="features">
        <div class="toolbar"><el-button v-hasPermi="['phm:config:add']" type="primary" size="small" @click="openFeature()">新增特征值</el-button></div>
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
              <el-button v-hasPermi="['phm:config:edit']" type="text" size="mini" @click="openFeature(scope.row)">编辑</el-button>
              <el-button v-hasPermi="['phm:config:remove']" type="text" size="mini" @click="removeFeature(scope.row)">删除</el-button>
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
            v-hasPermi="['phm:config:add']"
            :action="attachmentUploadUrl"
            :headers="uploadHeaders"
            :with-credentials="true"
            :data="attachmentUploadData"
            accept=".jpg,.jpeg,.png,.webp"
            :show-file-list="false"
            :disabled="!attachmentDeviceId || uploadingAttachment"
            :before-upload="beforeAttachmentUpload"
            :on-success="handleImageUploadSuccess"
            :on-error="handleImageUploadError"
          >
            <el-button type="primary" size="small" :loading="uploadingAttachment" :disabled="!attachmentDeviceId">上传图片</el-button>
          </el-upload>
          <span v-if="!attachmentDeviceId" class="toolbar-hint">请先选择关联设备</span>
          <el-button size="small" @click="loadAttachments">刷新</el-button>
        </div>
        <el-table :data="attachments" stripe>
          <el-table-column label="预览" width="100">
            <template slot-scope="scope">
              <img v-if="attachmentPreviews[scope.row.id]" :src="attachmentPreviews[scope.row.id]" class="attachment-thumb" alt="图片预览">
              <span v-else>--</span>
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="文件名" min-width="180" show-overflow-tooltip />
          <el-table-column prop="bizType" label="类型" width="110" />
          <el-table-column label="关联设备" min-width="150">
            <template slot-scope="scope">{{ deviceName(scope.row.bizId) }}</template>
          </el-table-column>
          <el-table-column prop="scanStatus" label="安全状态" width="110" />
          <el-table-column label="操作" width="170">
            <template slot-scope="scope">
              <el-button type="text" size="mini" @click="downloadAttachment(scope.row)">下载</el-button>
              <el-button v-hasPermi="['phm:config:remove']" type="text" size="mini" @click="removeAttachment(scope.row)">删除</el-button>
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
                :value="numericConfigValue(scope.row.configValue)"
                :min="0"
                size="mini"
                @change="value => scope.row.configValue = value == null ? '' : String(value)"
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
                  :with-credentials="true"
                  accept=".jpg,.jpeg,.png,.webp,.svg"
                  :show-file-list="false"
                  :on-success="res => handleSystemLogoUploadSuccess(res, scope.row)"
                >
                  <el-button size="mini">上传</el-button>
                </el-upload>
                <img v-if="scope.row.configValue" :src="fileHref(scope.row.configValue)" class="config-logo-preview" alt="Logo预览">
              </div>
              <el-input v-else v-model="scope.row.configValue" size="mini" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template slot-scope="scope">
              <el-button v-hasPermi="['phm:config:edit']" type="text" size="mini" :loading="savingConfigId === scope.row.id" @click="saveConfig(scope.row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog :title="deviceForm.id ? '编辑设备' : '新增设备'" :visible.sync="deviceVisible" width="640px" append-to-body>
      <el-form ref="deviceForm" :model="deviceForm" :rules="deviceRules" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="设备编码" prop="deviceCode"><el-input v-model="deviceForm.deviceCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="设备名称" prop="deviceName"><el-input v-model="deviceForm.deviceName" /></el-form-item></el-col>
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
                :with-credentials="true"
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
      <div slot="footer"><el-button @click="deviceVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveDeviceForm">保存</el-button></div>
    </el-dialog>

    <el-dialog :title="pointForm.id ? '编辑测点' : '新增测点'" :visible.sync="pointVisible" width="620px" append-to-body>
      <el-form ref="pointForm" :model="pointForm" :rules="pointRules" label-width="100px">
        <el-form-item label="所属设备" prop="deviceId">
          <el-select v-model="pointForm.deviceId" filterable @change="syncPointDevice">
            <el-option v-for="item in devices" :key="item.id" :label="item.deviceName" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="测点编码" prop="pointCode"><el-input v-model="pointForm.pointCode" /></el-form-item>
        <el-form-item label="测点名称" prop="pointName"><el-input v-model="pointForm.pointName" /></el-form-item>
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
      <div slot="footer"><el-button @click="pointVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="savePointForm">保存</el-button></div>
    </el-dialog>

    <el-dialog :title="ruleForm.id ? '编辑规则' : '新增规则'" :visible.sync="ruleVisible" width="620px" append-to-body>
      <el-form ref="ruleForm" :model="ruleForm" :rules="ruleRules" label-width="110px">
        <el-form-item label="规则名称" prop="ruleName"><el-input v-model="ruleForm.ruleName" /></el-form-item>
        <el-form-item label="特征值" prop="featureCode"><el-select v-model="ruleForm.featureCode"><el-option label="振动速度" value="vibration" /><el-option label="温度" value="temperature" /><el-option label="加速度" value="acceleration" /></el-select></el-form-item>
        <el-form-item label="规则类型"><el-select v-model="ruleForm.alarmType"><el-option label="阈值报警" value="threshold" /><el-option label="趋势报警" value="trend" /></el-select></el-form-item>
        <el-form-item label="高报阈值"><el-input-number v-model="ruleForm.highLimit" :precision="4" /></el-form-item>
        <el-form-item label="高高报阈值"><el-input-number v-model="ruleForm.highHighLimit" :precision="4" /></el-form-item>
        <el-form-item label="设备等级"><el-input-number v-model="ruleForm.deviceAlarmLevel" :min="1" :max="5" /></el-form-item>
        <el-form-item label="处理措施"><el-input v-model="ruleForm.actionAdvice" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="ruleVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveRuleForm">保存</el-button></div>
    </el-dialog>

    <el-dialog :title="featureForm.id ? '编辑特征值' : '新增特征值'" :visible.sync="featureVisible" width="560px" append-to-body>
      <el-form ref="featureForm" :model="featureForm" :rules="featureRules" label-width="100px">
        <el-form-item label="特征值编码" prop="featureCode"><el-input v-model="featureForm.featureCode" placeholder="如 vibration/rms/temperature" /></el-form-item>
        <el-form-item label="特征值名称" prop="featureName"><el-input v-model="featureForm.featureName" /></el-form-item>
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
      <div slot="footer"><el-button @click="featureVisible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="saveFeatureForm">保存</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import {
  listPhmDevices, savePhmDevice, deletePhmDevice,
  listMeasurePoints, saveMeasurePoint, deleteMeasurePoint,
  listAlarmRules, saveAlarmRule, deleteAlarmRule,
  listFeatures, saveFeature, deleteFeature,
  listAttachments, getAttachmentContent, deleteAttachment,
  listSystemConfig, saveSystemConfig
} from '@/api/phm'
import { getCsrfHeaders } from '@/utils/csrf'

export default {
  name: 'PhmConfig',
  data() {
    return {
      activeTab: 'devices',
      loading: false,
      submitting: false,
      uploadingAttachment: false,
      savingConfigId: null,
      loadErrors: [],
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
      attachmentPreviews: {},
      uploadUrl: process.env.VUE_APP_BASE_API + '/common/upload',
      attachmentUploadUrl: process.env.VUE_APP_BASE_API + '/phm/attachments/upload',
      uploadHeaders: getCsrfHeaders(),
      deviceRules: {
        deviceCode: [{ required: true, message: '请输入设备编码', trigger: 'blur' }],
        deviceName: [{ required: true, message: '请输入设备名称', trigger: 'blur' }]
      },
      pointRules: {
        deviceId: [{ required: true, message: '请选择所属设备', trigger: 'change' }],
        pointCode: [{ required: true, message: '请输入测点编码', trigger: 'blur' }],
        pointName: [{ required: true, message: '请输入测点名称', trigger: 'blur' }]
      },
      ruleRules: {
        ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
        featureCode: [{ required: true, message: '请选择特征值', trigger: 'change' }]
      },
      featureRules: {
        featureCode: [{ required: true, message: '请输入特征值编码', trigger: 'blur' }],
        featureName: [{ required: true, message: '请输入特征值名称', trigger: 'blur' }]
      }
    }
  },
  created() {
    this.loadAll()
  },
  methods: {
    numericConfigValue(value) {
      if (value === null || value === undefined || value === '') return undefined
      const number = Number(value)
      return Number.isNaN(number) ? undefined : number
    },
    async loadAll() {
      this.loading = true
      this.loadErrors = []
      const loaders = [
        ['设备', this.loadDevices],
        ['测点', this.loadPoints],
        ['告警规则', this.loadRules],
        ['特征值', this.loadFeatures],
        ['图片', this.loadAttachments],
        ['系统配置', this.loadConfigs]
      ]
      await Promise.all(loaders.map(async([label, loader]) => {
        try {
          await loader.call(this)
        } catch (error) {
          this.loadErrors.push(`${label}：${this.errorMessage(error)}`)
        }
      }))
      this.loading = false
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
      await this.loadAttachmentPreviews()
    },
    async loadConfigs() {
      const res = await listSystemConfig()
      this.configs = res.data || []
    },
    openDevice(row) {
      this.deviceForm = row ? Object.assign({}, row) : { status: 'normal', healthIndex: null }
      this.deviceVisible = true
      this.clearValidation('deviceForm')
    },
    async saveDeviceForm() {
      if (!await this.validateForm('deviceForm')) return
      await this.submit(async() => {
        await savePhmDevice(this.deviceForm)
        this.$message.success('设备已保存')
        this.deviceVisible = false
        await this.loadDevices()
      })
    },
    handleMorphologyUploadSuccess(res) {
      if (res.code === 200) {
        this.deviceForm.morphologyUrl = res.url
        this.$message.success('形貌图上传成功')
      } else {
        this.$message.error(res.msg || '上传失败')
      }
    },
    async removeDevice(row) {
      await this.removeRecord({
        message: `确认删除设备“${row.deviceName}”？关联的测点、告警和事件也会删除。`,
        action: () => deletePhmDevice(String(row.id)),
        success: '设备已删除',
        reload: this.loadAll
      })
    },
    openPoint(row) {
      this.pointForm = row ? Object.assign({}, row) : { deviceId: this.pointDeviceId, channelId: 1, signalType: 'vibration', featureCodes: 'vibration,temperature,rms,peak' }
      this.syncPointDevice(this.pointForm.deviceId)
      this.pointVisible = true
      this.clearValidation('pointForm')
    },
    syncPointDevice(deviceId) {
      const device = this.devices.find(item => item.id === deviceId)
      if (device) this.pointForm.deviceCode = device.deviceCode
    },
    async savePointForm() {
      if (!await this.validateForm('pointForm')) return
      await this.submit(async() => {
        await saveMeasurePoint(this.pointForm)
        this.$message.success('测点已保存')
        this.pointVisible = false
        await this.loadPoints()
      })
    },
    async removePoint(row) {
      await this.removeRecord({
        message: `确认删除测点“${row.pointName}”？`,
        action: () => deleteMeasurePoint(String(row.id)),
        success: '测点已删除',
        reload: this.loadPoints
      })
    },
    openRule(row) {
      this.ruleForm = row ? Object.assign({}, row) : { featureCode: 'vibration', alarmType: 'threshold', highLimit: 0.2, highHighLimit: 0.3, deviceAlarmLevel: 2, enabled: true }
      this.ruleVisible = true
      this.clearValidation('ruleForm')
    },
    async saveRuleForm() {
      if (!await this.validateForm('ruleForm')) return
      if (this.ruleForm.highLimit != null && this.ruleForm.highHighLimit != null && Number(this.ruleForm.highLimit) > Number(this.ruleForm.highHighLimit)) {
        return this.$message.warning('高高报阈值必须大于或等于高报阈值')
      }
      await this.submit(async() => {
        await saveAlarmRule(this.ruleForm)
        this.$message.success('规则已保存')
        this.ruleVisible = false
        await this.loadRules()
      })
    },
    async removeRule(row) {
      await this.removeRecord({
        message: `确认删除告警规则“${row.ruleName}”？`,
        action: () => deleteAlarmRule(String(row.id)),
        success: '规则已删除',
        reload: this.loadRules
      })
    },
    openFeature(row) {
      this.featureForm = row ? Object.assign({}, row) : { signalType: 'vibration', displayOrder: 0, enabled: true }
      this.featureVisible = true
      this.clearValidation('featureForm')
    },
    async saveFeatureForm() {
      if (!await this.validateForm('featureForm')) return
      await this.submit(async() => {
        await saveFeature(this.featureForm)
        this.$message.success('特征值配置已保存')
        this.featureVisible = false
        await this.loadFeatures()
      })
    },
    async removeFeature(row) {
      await this.removeRecord({
        message: `确认删除特征值“${row.featureName}”？`,
        action: () => deleteFeature(String(row.id)),
        success: '特征值配置已删除',
        reload: this.loadFeatures
      })
    },
    beforeAttachmentUpload() {
      if (!this.attachmentDeviceId) {
        this.$message.warning('请先选择关联设备')
        return false
      }
      this.uploadingAttachment = true
      return true
    },
    async handleImageUploadSuccess(res) {
      this.uploadingAttachment = false
      if (res.code !== 200) {
        return this.$message.error(res.msg || '上传失败')
      }
      this.$message.success('图片已安全上传并归档')
      await this.loadAttachments()
    },
    handleImageUploadError(error) {
      this.uploadingAttachment = false
      this.$message.error(this.errorMessage(error) || '上传失败')
    },
    async removeAttachment(row) {
      await this.removeRecord({
        message: `确认删除图片“${row.fileName}”？数据库记录和安全存储文件都会删除。`,
        action: () => deleteAttachment(String(row.id)),
        success: '图片已删除',
        reload: this.loadAttachments
      })
    },
    async loadAttachmentPreviews() {
      Object.values(this.attachmentPreviews).forEach(url => URL.revokeObjectURL(url))
      const previews = {}
      await Promise.all(this.attachments.map(async item => {
        try {
          const blob = await getAttachmentContent(item.id)
          previews[item.id] = URL.createObjectURL(blob)
        } catch (error) {
          // The table still exposes the database record and its scan status.
        }
      }))
      this.attachmentPreviews = previews
    },
    async downloadAttachment(row) {
      try {
        const blob = await getAttachmentContent(row.id)
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = row.fileName || 'attachment'
        link.click()
        URL.revokeObjectURL(url)
      } catch (error) {
        this.$message.error(`下载失败：${this.errorMessage(error)}`)
      }
    },
    deviceName(deviceId) {
      if (!deviceId) return '--'
      const device = this.devices.find(item => String(item.id) === String(deviceId))
      return device ? device.deviceName : deviceId
    },
    fileHref(url) {
      if (!url) return ''
      if (/^(https?:)?\/\//.test(url)) return url
      const base = process.env.VUE_APP_BASE_API || ''
      return url.indexOf('/') === 0 ? base + url : url
    },
    async saveConfig(row) {
      if (!row.configKey || row.configValue === null || row.configValue === undefined || row.configValue === '') {
        return this.$message.warning('配置键和值不能为空')
      }
      this.savingConfigId = row.id
      try {
        await saveSystemConfig(row)
        this.$message.success('配置已保存到数据库')
        await this.loadConfigs()
      } catch (error) {
        this.$message.error(`保存失败：${this.errorMessage(error)}`)
      } finally {
        this.savingConfigId = null
      }
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
    },
    validateForm(ref) {
      return new Promise(resolve => {
        const form = this.$refs[ref]
        if (!form) return resolve(false)
        form.validate(valid => resolve(valid))
      })
    },
    clearValidation(ref) {
      this.$nextTick(() => {
        if (this.$refs[ref]) this.$refs[ref].clearValidate()
      })
    },
    async submit(action) {
      if (this.submitting) return
      this.submitting = true
      try {
        await action()
      } catch (error) {
        this.$message.error(`保存失败：${this.errorMessage(error)}`)
      } finally {
        this.submitting = false
      }
    },
    async removeRecord({ message, action, success, reload }) {
      try {
        await this.$confirm(message, '删除确认', {
          confirmButtonText: '确定删除',
          cancelButtonText: '取消',
          type: 'warning'
        })
        await action()
        this.$message.success(success)
        await reload.call(this)
      } catch (error) {
        if (error === 'cancel' || error === 'close') return
        this.$message.error(`删除失败：${this.errorMessage(error)}`)
      }
    },
    errorMessage(error) {
      if (!error) return '未知错误'
      return error.message || (error.response && error.response.data && error.response.data.msg) || String(error)
    }
  },
  computed: {
    attachmentUploadData() {
      return {
        purpose: 'MORPHOLOGY',
        bizType: 'morphology',
        bizId: this.attachmentDeviceId
      }
    }
  },
  beforeDestroy() {
    Object.values(this.attachmentPreviews).forEach(url => URL.revokeObjectURL(url))
  }
}
</script>

<style scoped>
.config-page { min-height: calc(100vh - 84px); }
.page-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 6px; }
.page-head p { margin: 0; }
.toolbar { display: flex; gap: 10px; margin-bottom: 12px; }
.toolbar-hint { align-self: center; color: #909399; font-size: 12px; }
.load-alert { margin-bottom: 14px; }
.toolbar .el-select { width: 260px; }
.attachment-thumb { width: 70px; height: 46px; object-fit: cover; }
.config-logo-cell { display: flex; align-items: center; gap: 8px; }
.config-logo-cell .el-input { max-width: 320px; }
.config-logo-preview { width: 38px; height: 38px; object-fit: contain; }
:deep(.el-tabs__header) { margin-bottom: 8px; }
:deep(.el-tabs__item) { font-weight: 600; }
:deep(.el-input-number) { width: 140px; }
</style>
