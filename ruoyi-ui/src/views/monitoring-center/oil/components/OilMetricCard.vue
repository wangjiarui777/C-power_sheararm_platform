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
import { oilStatusText } from '@/utils/industrialLabels'

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
    statusText() { return oilStatusText(this.status) }
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
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-top: 2px solid var(--oil-card-accent, var(--color-accent));
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text);
}
.tone-oil { --oil-card-accent: #d6a14a; }
.tone-water { --oil-card-accent: var(--color-info); }
.tone-state { --oil-card-accent: var(--color-success); }
.state-warning { --oil-card-accent: #f59e0b; }
.state-alarm,
.state-bad { --oil-card-accent: #ef4444; }
.state-offline,
.state-unknown { --oil-card-accent: #64748b; }
.metric-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--color-border);
}
.metric-eyebrow {
  display: block;
  margin-bottom: 4px;
  color: var(--color-muted);
  font-size: 11px;
  letter-spacing: .12em;
}
h3 {
  margin: 0;
  color: var(--color-heading);
  font-size: 16px;
  line-height: 1.2;
}
.metric-status {
  flex: none;
  padding: 4px 8px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  color: var(--color-muted);
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
  color: var(--color-muted);
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
  color: var(--color-text);
  font-family: var(--font-data);
  font-size: 24px;
  font-weight: 600;
  line-height: 1;
  text-overflow: ellipsis;
}
.metric-number small {
  flex: none;
  color: var(--color-muted);
  font-size: 11px;
}
@media (max-width: 900px) {
  .metric-card-grid.compact { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
