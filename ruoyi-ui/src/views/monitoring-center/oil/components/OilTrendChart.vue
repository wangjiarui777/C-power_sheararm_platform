<template>
  <section class="oil-trend-card">
    <header class="trend-head">
      <div>
        <span>历史趋势</span>
        <h3>{{ metricLabel }}</h3>
      </div>
      <div class="trend-meta">{{ rows.length ? `${rows.length} 个采样点` : '暂无采样' }}</div>
    </header>
    <div class="trend-stage">
      <div ref="chart" class="oil-trend-chart"></div>
      <div v-if="!rows.length" class="trend-empty">
        <i class="el-icon-data-line"></i>
        <strong>当前条件没有历史数据</strong>
        <span>请选择设备、时间和指标后查询。</span>
      </div>
    </div>
  </section>
</template>

<script>
import echarts from '@/utils/echarts'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'OilTrendChart',
  props: {
    rows: { type: Array, default: () => [] },
    metricLabel: { type: String, default: '请选择指标' },
    unit: { type: String, default: '' },
    thresholds: { type: Object, default: () => ({}) }
  },
  data() {
    return { chart: null }
  },
  watch: {
    rows: { deep: true, handler: 'renderChart' },
    metricLabel: 'renderChart',
    unit: 'renderChart',
    thresholds: { deep: true, handler: 'renderChart' }
  },
  mounted() {
    this.chart = echarts.init(this.$refs.chart)
    window.addEventListener('resize', this.resize)
    window.addEventListener('appearance-mode-change', this.renderChart)
    this.renderChart()
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resize)
    window.removeEventListener('appearance-mode-change', this.renderChart)
    if (this.chart) this.chart.dispose()
  },
  methods: {
    renderChart() {
      this.$nextTick(() => {
        if (!this.chart) return
        const rows = this.rows || []
        this.chart.setOption({
          animation: false,
          backgroundColor: 'transparent',
          color: [industrialChartTheme.oil],
          tooltip: {
            trigger: 'axis',
            backgroundColor: industrialChartTheme.tooltipBg,
            borderColor: industrialChartTheme.tooltipBorder,
            textStyle: { color: industrialChartTheme.text },
            valueFormatter: value => `${value === null || value === undefined ? '--' : value}${this.unit ? ` ${this.unit}` : ''}`
          },
          grid: { left: 62, right: 30, top: 34, bottom: 70 },
          dataZoom: [
            { type: 'inside', filterMode: 'none' },
            {
              type: 'slider',
              height: 18,
              bottom: 14,
              borderColor: industrialChartTheme.border,
              backgroundColor: 'transparent',
              fillerColor: 'rgba(240, 180, 77, .16)',
              handleStyle: { color: industrialChartTheme.oil, borderColor: industrialChartTheme.oil },
              textStyle: { color: industrialChartTheme.muted }
            }
          ],
          xAxis: {
            type: 'category',
            boundaryGap: false,
            data: rows.map(row => this.axisTime(row.time)),
            axisLabel: { color: industrialChartTheme.axis },
            axisLine: { lineStyle: { color: industrialChartTheme.border } }
          },
          yAxis: {
            type: 'value',
            name: this.unit,
            scale: true,
            nameTextStyle: { color: industrialChartTheme.muted },
            axisLabel: { color: industrialChartTheme.axis },
            splitLine: { lineStyle: { color: industrialChartTheme.grid } }
          },
          series: [{
            name: this.metricLabel,
            type: 'line',
            smooth: false,
            showSymbol: rows.length < 80,
            symbolSize: 5,
            connectNulls: false,
            data: rows.map(row => row.value),
            lineStyle: { color: industrialChartTheme.oil, width: 2.4 },
            itemStyle: { color: industrialChartTheme.oil },
            areaStyle: { color: 'rgba(240, 180, 77, .08)' },
            markLine: this.thresholdLines()
          }]
        }, true)
      })
    },
    thresholdLines() {
      const thresholds = this.thresholds || {}
      const warning = thresholds.warning !== undefined ? thresholds.warning : thresholds.high
      const alarm = thresholds.alarm !== undefined ? thresholds.alarm : thresholds.highHigh
      const data = []
      if (warning !== null && warning !== undefined) {
        data.push({ name: '预警线', yAxis: warning, lineStyle: { color: industrialChartTheme.warning } })
      }
      if (alarm !== null && alarm !== undefined) {
        data.push({ name: '报警线', yAxis: alarm, lineStyle: { color: industrialChartTheme.danger } })
      }
      return {
        symbol: 'none',
        label: { color: industrialChartTheme.muted },
        data
      }
    },
    axisTime(value) {
      if (!value) return ''
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) return String(value)
      return date.toLocaleString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
      })
    },
    resize() {
      if (this.chart) this.chart.resize()
    }
  }
}
</script>

<style scoped>
.oil-trend-card {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}
.trend-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border);
}
.trend-head span,
.trend-meta {
  color: var(--color-muted);
  font-size: 12px;
}
h3 {
  margin: 4px 0 0;
  color: var(--color-heading);
  font-size: 16px;
}
.trend-stage { position: relative; }
.oil-trend-chart { height: 430px; }
.trend-empty {
  position: absolute;
  inset: 120px 0 auto;
  display: grid;
  justify-items: center;
  gap: 7px;
  color: var(--color-muted);
  pointer-events: none;
}
.trend-empty i { color: #d6a14a; font-size: 28px; }
.trend-empty strong { color: var(--color-text); font-size: 14px; }
.trend-empty span { font-size: 12px; }
@media (max-width: 900px) {
  .oil-trend-chart { height: 350px; }
}
</style>
