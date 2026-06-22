<template>
  <el-radio-group :value="value" size="mini" @input="$emit('input', $event)">
    <el-radio-button label="realtime">实时</el-radio-button>
    <el-radio-button label="15m">15 分钟</el-radio-button>
    <el-radio-button label="1h">1 小时</el-radio-button>
    <el-radio-button label="8h">8 小时</el-radio-button>
    <el-radio-button label="24h">24 小时</el-radio-button>
  </el-radio-group>
</template>

<script>
export default { name: 'MonitoringTimeRangeSelect', props: { value: { type: String, default: '15m' } } }
</script>

<style scoped>
:deep(.el-radio-button__inner){border-color:#2b3d4b;background:#101a23;color:#aebdca}
:deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner){border-color:#4a90e2;background:#4a90e2;color:#fff;box-shadow:none}
</style>
