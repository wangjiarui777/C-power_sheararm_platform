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
::v-deep .el-radio-button__inner{border-color:var(--color-border);background:var(--color-surface-soft);color:var(--color-muted)}
::v-deep .el-radio-button__orig-radio:checked + .el-radio-button__inner{border-color:var(--color-accent);background:var(--color-accent);color:var(--color-on-accent);box-shadow:none}
</style>
