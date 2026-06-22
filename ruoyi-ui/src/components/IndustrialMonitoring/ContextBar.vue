<template>
  <header class="context-bar">
    <div class="context-title">
      <span class="eyebrow">{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
      <p>{{ device ? `${device.organization || '未分组'} / ${device.line || '未设置位置'} / ${device.deviceName}` : '请选择设备' }}</p>
    </div>
    <div class="context-meta">
      <div><span>班次</span><strong>{{ shiftName }}</strong></div>
      <div><span>最后采样</span><strong>{{ formatTime(latestSampleTime) }}</strong></div>
      <div><span>数据延迟</span><strong>{{ delayText }}</strong></div>
      <el-tag size="mini" :type="connectionState === 'online' ? 'success' : 'info'">
        {{ connectionState === 'online' ? '实时连接' : '连接中断' }}
      </el-tag>
      <el-button size="mini" icon="el-icon-refresh" @click="$emit('refresh')">刷新快照</el-button>
    </div>
  </header>
</template>

<script>
export default {
  name: 'MonitoringContextBar',
  props: {
    eyebrow: { type: String, default: '设备状态监测' },
    title: { type: String, required: true },
    device: { type: Object, default: null },
    latestSampleTime: { type: [String, Date], default: null },
    delaySeconds: { type: [Number, String], default: null },
    connectionState: { type: String, default: 'offline' }
  },
  computed: {
    shiftName() {
      const hour = new Date().getHours()
      if (hour >= 8 && hour < 16) return '白班'
      if (hour >= 16 && hour < 24) return '中班'
      return '夜班'
    },
    delayText() {
      if (this.delaySeconds === null || this.delaySeconds === undefined) return '--'
      const value = Number(this.delaySeconds)
      return value < 60 ? `${value}s` : `${Math.floor(value / 60)}m ${value % 60}s`
    }
  },
  methods: {
    formatTime(value) {
      if (!value) return '--'
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return String(value)
      return date.toLocaleString('zh-CN', { hour12: false })
    }
  }
}
</script>

<style scoped>
.context-bar { display:flex; align-items:center; justify-content:space-between; gap:20px; padding:14px 18px; border:1px solid #273846; border-radius:10px; background:#15212c; color:#e9f0f5; }
.context-title h1 { margin:2px 0 3px; font-size:22px; line-height:1.1; letter-spacing:.02em; }
.context-title p,.eyebrow,.context-meta span { margin:0; color:#8ea2b3; font-size:12px; }
.eyebrow { letter-spacing:.12em; text-transform:uppercase; }
.context-meta { display:flex; align-items:center; justify-content:flex-end; gap:16px; flex-wrap:wrap; }
.context-meta div { display:grid; gap:3px; min-width:80px; }
.context-meta strong { font-family:"DIN Alternate",Consolas,monospace; font-size:13px; font-weight:600; }
@media(max-width:1000px){.context-bar{align-items:flex-start;flex-direction:column}.context-meta{justify-content:flex-start}}
</style>
