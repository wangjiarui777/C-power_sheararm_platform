<template>
  <div class="app-container">
    <h4 class="form-header h4">用户设备权限</h4>
    <el-form label-width="80px">
      <el-form-item label="用户昵称">
        <el-input v-model="form.nickName" disabled />
      </el-form-item>
      <el-form-item label="登录账号">
        <el-input v-model="form.userName" disabled />
      </el-form-item>
    </el-form>

    <el-alert
      title="未勾选设备的普通用户无法访问该设备及其测点、通道、诊断、告警和报表数据。"
      type="info"
      :closable="false"
      show-icon
      class="mb16"
    />

    <el-transfer
      v-model="deviceIds"
      :data="deviceOptions"
      filterable
      filter-placeholder="搜索设备"
      :titles="['未授权设备', '已授权设备']"
      :button-texts="['移除', '授权']"
      :props="{ key: 'key', label: 'label' }"
      class="device-transfer"
    />

    <div class="dialog-footer">
      <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      <el-button @click="close">返回</el-button>
    </div>
  </div>
</template>

<script>
import { getDeviceAuth, updateDeviceAuth } from '@/api/system/user'

export default {
  name: 'DeviceAuth',
  data() {
    return {
      userId: null,
      form: {},
      deviceIds: [],
      deviceOptions: [],
      saving: false
    }
  },
  created() {
    this.userId = this.$route.params && this.$route.params.userId
    this.load()
  },
  methods: {
    load() {
      getDeviceAuth(this.userId).then(response => {
        const devices = response.devices || []
        this.form = response.user || {}
        this.deviceOptions = devices.map(device => ({
          key: device.id,
          label: `${device.deviceName || device.deviceCode} (${device.deviceCode})`
        }))
        this.deviceIds = response.deviceIds || []
      })
    },
    submitForm() {
      this.saving = true
      updateDeviceAuth({ userId: this.userId, deviceIds: this.deviceIds }).then(() => {
        this.$modal.msgSuccess('设备权限保存成功')
        this.close()
      }).finally(() => {
        this.saving = false
      })
    },
    close() {
      this.$tab.closeOpenPage({ path: '/system/user' })
    }
  }
}
</script>

<style scoped>
.device-transfer { margin: 24px 0; }
.dialog-footer { text-align: center; margin-top: 28px; }
</style>
