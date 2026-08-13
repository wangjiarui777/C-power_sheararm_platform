<template>
  <section class="oil-metric-card" :class="[`tone-${tone}`, `state-${normalizedStatus}`]">
    <header class="metric-card-head">
      <div>
        <span class="metric-eyebrow">{{ eyebrow }}</span>
        <h3>{{ title }}</h3>
      </div>
      <span class="metric-status">{{ statusText }}</span>
    </header>

    <div class="metric-card-grid" :class="{ compact: compact }">
      <div v-for="item in items" :key="item.code" class="metric-reading">
        <span>{{ item.label }}</span>
        <div class="metric-number">
          <strong>{{ valueText(item.value, item.precision) }}</strong>
          <small v-if="item.unit">{{ item.unit }}</small>
        </div>
      </div>
    </div>
  </section>
</template>

<script>
export default {
  name: 'OilMetricCard',
  props: {
    title: { type: String, required: true },
    eyebrow: { type: String, default: '油液指标' },
    items: { type: Array, default: () => [] },
    status: { type: String, default: 'UNKNOWN' },
    tone: { type: String, default: 'oil' },
    compact: { type: Boolean, default: false }
  },
  computed: {
    normalizedStatus() {
      return String(this.status || 'UNKNOWN').toLowerCase()
    },
    statusText() {
      return {
        NORMAL: '正常',
        GOOD: '正常',
        WARNING: '预警',
        ALARM: '报警',
        BAD: '异常',
        OFFLINE: '离线',
        UNKNOWN: '待接入'
      }[String(this.status || 'UNKNOWN').toUpperCase()] || String(this.status)
    }
  },
  methods: {
    valueText(value, precision) {
      if (value === null || value === undefined || value === '') return '--'
      const number = Number(value)
      if (Number.isNaN(number)) return String(value)
      const digits = precision === undefined ? 2 : Number(precision)
      return number.toFixed(digits)
    }
  }
}
</script>

<style scoped>
.oil-metric-card {
  position: relative;
  min-width: 0;
  padding: 16px;
  overflow: hidden;
  border: 1px solid var(--chart-border, #263645);
  border-top: 3px solid var(--oil-card-accent, #f0b44d);
  border-radius: 14px;
  background: var(--chart-panel, #17212b);
  color: var(--chart-text, #e6edf3);
}
.oil-metric-card::after {
  content: "";
  position: absolute;
  top: -54px;
  right: -42px;
  width: 112px;
  height: 112px;
  border-radius: 50%;
  background: var(--oil-card-glow, rgba(240, 180, 77, 0.08));
  pointer-events: none;
}
.tone-oil { --oil-card-accent: #f0b44d; --oil-card-glow: rgba(240, 180, 77, 0.10); }
.tone-water { --oil-card-accent: #38bdf8; --oil-card-glow: rgba(56, 189, 248, 0.09); }
.tone-state { --oil-card-accent: #10b981; --oil-card-glow: rgba(16, 185, 129, 0.09); }
.state-warning { --oil-card-accent: #f59e0b; }
.state-alarm,
.state-bad { --oil-card-accent: #ef4444; }
.state-offline,
.state-unknown { --oil-card-accent: #64748b; }
.metric-card-head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--chart-border, #263645);
}
.metric-eyebrow {
  display: block;
  margin-bottom: 4px;
  color: var(--chart-muted, #8ea0b5);
  font-size: 11px;
  letter-spacing: .12em;
}
h3 {
  margin: 0;
  color: var(--chart-text, #e6edf3);
  font-size: 16px;
  line-height: 1.2;
}
.metric-status {
  flex: none;
  padding: 4px 8px;
  border: 1px solid var(--chart-border, #263645);
  border-radius: 999px;
  color: var(--chart-muted, #8ea0b5);
  font-size: 11px;
}
.state-normal .metric-status,
.state-good .metric-status { border-color: rgba(16, 185, 129, .38); color: #10b981; }
.state-warning .metric-status { border-color: rgba(245, 158, 11, .38); color: #f59e0b; }
.state-alarm .metric-status,
.state-bad .metric-status { border-color: rgba(239, 68, 68, .38); color: #ef4444; }
.metric-card-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 10px;
  margin-top: 16px;
}
.metric-card-grid.compact { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.metric-reading {
  min-width: 0;
  padding-right: 8px;
}
.metric-reading > span {
  display: block;
  overflow: hidden;
  color: var(--chart-muted, #8ea0b5);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.metric-number {
  display: flex;
  align-items: baseline;
  gap: 5px;
  min-width: 0;
  margin-top: 8px;
}
.metric-number strong {
  overflow: hidden;
  color: var(--chart-text, #e6edf3);
  font-family: "Bahnschrift SemiBold", "DIN Alternate", Consolas, monospace;
  font-size: 24px;
  font-weight: 600;
  line-height: 1;
  text-overflow: ellipsis;
}
.metric-number small {
  flex: none;
  color: var(--chart-muted, #8ea0b5);
  font-size: 11px;
}
@media (max-width: 900px) {
  .metric-card-grid.compact { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
