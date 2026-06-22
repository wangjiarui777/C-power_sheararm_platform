<template>
  <button class="point-card" :class="[statusClass, { active }]" @click="$emit('select', point)">
    <div class="point-top">
      <span class="point-name">{{ point.pointName }}</span>
      <span class="quality" :class="qualityClass">{{ qualityText }}</span>
    </div>
    <div class="point-value">
      <strong>{{ valueText }}</strong><small>{{ point.unit }}</small>
    </div>
    <div class="threshold">
      <span>高限 {{ metric(point.thresholds && point.thresholds.high) }}</span>
      <span>高高限 {{ metric(point.thresholds && point.thresholds.highHigh) }}</span>
    </div>
    <div class="point-foot">
      <span>{{ point.pointCode }}</span>
      <span>{{ timeText }}</span>
    </div>
  </button>
</template>

<script>
export default {
  name: 'MonitoringPointCard',
  props: {
    point: { type: Object, required: true },
    active: { type: Boolean, default: false }
  },
  computed: {
    statusClass() { return String(this.point.status || 'UNKNOWN').toLowerCase() },
    qualityClass() { return String(this.point.quality || 'OFFLINE').toLowerCase() },
    qualityText() {
      return { GOOD: '数据正常', STALE: '数据延迟', BAD: '质量异常', OFFLINE: '数据离线' }[this.point.quality] || this.point.quality
    },
    valueText() { return this.metric(this.point.value) },
    timeText() {
      if (!this.point.sampleTime) return '无采样'
      const date = new Date(this.point.sampleTime)
      return Number.isNaN(date.getTime()) ? String(this.point.sampleTime) : date.toLocaleTimeString('zh-CN', { hour12: false })
    }
  },
  methods: {
    metric(value) {
      if (value === null || value === undefined || value === '') return '--'
      const number = Number(value)
      return Number.isNaN(number) ? value : number.toFixed(2)
    }
  }
}
</script>

<style scoped>
.point-card{display:grid;gap:10px;min-height:154px;padding:13px;border:1px solid #283a48;border-top:3px solid #71808d;border-radius:9px;background:#101a23;color:#dce7ee;text-align:left;cursor:pointer}
.point-card.active{border-color:#4a90e2;box-shadow:0 0 0 1px rgba(74,144,226,.22)}.point-card.normal{border-top-color:#32b67a}.point-card.warning{border-top-color:#e7a23b}.point-card.alarm{border-top-color:#e05252}
.point-top,.threshold,.point-foot{display:flex;align-items:center;justify-content:space-between;gap:8px}.point-name{font-weight:700}
.quality{padding:2px 6px;border-radius:10px;background:#253543;color:#aebdca;font-size:11px}.quality.good{color:#77d7aa}.quality.stale{color:#f0bd68}.quality.bad,.quality.offline{color:#f08a8a}
.point-value strong{font-family:"DIN Alternate",Consolas,monospace;font-size:30px;line-height:1}.point-value small{margin-left:6px;color:#8ea2b3}
.threshold,.point-foot{color:#8195a6;font-size:11px}.point-foot{padding-top:8px;border-top:1px solid #22323e}
</style>
