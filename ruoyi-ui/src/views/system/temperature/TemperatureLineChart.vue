<template>
  <div class="chart-wrapper" v-loading="loading">
    <div ref="chart" class="chart" />
  </div>
</template>

<script>
import * as echarts from 'echarts'

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
      this.chart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: xData || [],
          axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } },
          axisLabel: { color: 'rgba(235,255,255,0.72)' }
        },
        yAxis: {
          type: 'value',
          scale: true,
          splitLine: { show: false },
          axisLine: { lineStyle: { color: 'rgba(0,255,255,0.35)' } },
          axisLabel: { color: 'rgba(235,255,255,0.72)' }
        },
        series: [{
          name: 'Temperature Value',
          type: 'line',
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 3, color: '#00FFFF' },
          itemStyle: { color: '#00FFFF' },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(0,255,255,0.42)' },
                { offset: 1, color: 'rgba(0,255,255,0)' }
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
