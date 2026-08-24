<template>
  <section class="particle-card">
    <header class="particle-head">
      <div>
        <span>{{ eyebrow }}</span>
        <h3>{{ title }}</h3>
      </div>
      <div class="particle-total">
        <strong>{{ totalText }}</strong>
        <small>颗粒总数</small>
      </div>
    </header>

    <div class="particle-list">
      <div v-for="row in rows" :key="row.code || row.label" class="particle-row">
        <div class="particle-label">
          <span>{{ row.label }}</span>
          <strong>{{ countText(row.count) }}</strong>
        </div>
        <div class="particle-track" aria-hidden="true">
          <span :style="{ width: barWidth(row.count) }"></span>
        </div>
      </div>
    </div>
  </section>
</template>

<script>
export default {
  name: 'ParticleDistribution',
  props: {
    title: { type: String, required: true },
    eyebrow: { type: String, default: '颗粒分布 / μm' },
    rows: { type: Array, default: () => [] }
  },
  computed: {
    numericRows() {
      return this.rows
        .filter(row => row.count !== null && row.count !== undefined && row.count !== '')
        .map(row => Number(row.count))
        .filter(value => Number.isFinite(value))
    },
    maximum() {
      return this.numericRows.length ? Math.max(...this.numericRows, 1) : 1
    },
    totalText() {
      if (!this.numericRows.length) return '--'
      return this.numericRows.reduce((sum, value) => sum + value, 0).toLocaleString('zh-CN')
    }
  },
  methods: {
    countText(value) {
      if (value === null || value === undefined || value === '') return '--'
      const number = Number(value)
      return Number.isNaN(number) ? String(value) : number.toLocaleString('zh-CN')
    },
    barWidth(value) {
      const number = Number(value)
      if (!Number.isFinite(number) || number <= 0) return '0%'
      return `${Math.max(4, Math.round(number / this.maximum * 100))}%`
    }
  }
}
</script>

<style scoped>
.particle-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text);
}
.particle-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--color-border);
}
.particle-head span,
.particle-total small {
  display: block;
  color: var(--color-muted);
  font-size: 11px;
  letter-spacing: .08em;
}
h3 {
  margin: 4px 0 0;
  color: var(--color-heading);
  font-size: 16px;
}
.particle-total { text-align: right; }
.particle-total strong {
  display: block;
  margin-bottom: 2px;
  color: #d6a14a;
  font-family: var(--font-data);
  font-size: 22px;
}
.particle-list {
  display: grid;
  gap: 12px;
  margin-top: 16px;
}
.particle-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 12px;
}
.particle-label span { color: var(--color-muted); }
.particle-label strong {
  color: var(--color-text);
  font-family: var(--font-data);
}
.particle-track {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--color-surface-soft);
}
.particle-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #d6a14a;
  transition: width .24s ease;
}
@media (prefers-reduced-motion: reduce) {
  .particle-track span { transition: none; }
}
</style>
