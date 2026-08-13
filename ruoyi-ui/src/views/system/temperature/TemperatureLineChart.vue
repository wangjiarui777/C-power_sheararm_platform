<template>
  <div class="chart-wrapper" v-loading="loading">
    <div ref="chart" class="chart" />
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'TemperatureLineChart',
  props: {
    chartData: {
      type: Object,
      default: () => ({ xData: [], yData: [] })
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      chart: null
    }
  },
  watch: {
    chartData: {
      deep: true,
      handler() {
        this.renderChart()
      }
    }
  },
  mounted() {
    this.chart = echarts.init(this.$refs.chart)
    this.renderChart()
    window.addEventListener('resize', this.resizeChart)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeChart)
    if (this.chart) {
      this.chart.dispose()
      this.chart = null
    }
  },
  methods: {
    renderChart() {
      if (!this.chart) return
      const { xData, yData } = this.chartData
      const theme = industrialChartTheme
      this.chart.setOption({
        tooltip: {
          trigger: 'axis',
          backgroundColor: theme.tooltipBg,
          borderColor: theme.tooltipBorder,
          textStyle: { color: theme.text }
        },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: xData || [],
          axisLine: { lineStyle: { color: theme.border } },
          axisLabel: { color: theme.muted }
        },
        yAxis: {
          type: 'value',
          scale: true,
          splitLine: { show: false },
          axisLine: { lineStyle: { color: theme.border } },
          axisLabel: { color: theme.muted }
        },
        series: [{
          name: 'Temperature Value',
          type: 'line',
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 3, color: theme.temperature },
          itemStyle: { color: theme.temperature },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(20, 184, 166, 0.28)' },
                { offset: 1, color: 'rgba(20, 184, 166, 0)' }
              ]
            }
          },
          data: yData || []
        }]
      })
    },
    resizeChart() {
      if (this.chart) this.chart.resize()
    }
  }
}
</script>

<style scoped>
.chart-wrapper { width: 100%; }
.chart { width: 100%; height: 320px; }
</style>
