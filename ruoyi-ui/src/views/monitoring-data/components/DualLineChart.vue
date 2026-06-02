<template>
  <div ref="chart" class="dual-chart" v-loading="loading" />
</template>

<script>
import * as echarts from 'echarts'

export default {
  name: 'DualLineChart',
  props: {
    chartData: {
      type: Object,
      default: () => ({ xData: [], vibrationData: [], temperatureData: [] })
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return { chart: null }
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
      const { xData, vibrationData, temperatureData } = this.chartData
      this.chart.setOption({
        tooltip: { trigger: 'axis' },
        legend: { data: ['Vibration', 'Temperature'] },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: { type: 'category', boundaryGap: false, data: xData || [] },
        yAxis: { type: 'value', scale: true },
        series: [
          {
            name: 'Vibration',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            lineStyle: { width: 3 },
            areaStyle: { opacity: 0.12 },
            data: vibrationData || []
          },
          {
            name: 'Temperature',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            lineStyle: { width: 3 },
            areaStyle: { opacity: 0.12 },
            data: temperatureData || []
          }
        ]
      })
    },
    resizeChart() {
      if (this.chart) this.chart.resize()
    }
  }
}
</script>

<style scoped>
.dual-chart { width: 100%; height: 360px; }
</style>
