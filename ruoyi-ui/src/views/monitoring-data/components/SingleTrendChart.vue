<template>
  <div ref="chart" class="single-chart" v-loading="loading" />
</template>

<script>
import echarts from '@/utils/echarts'

export default {
  name: 'SingleTrendChart',
  props: {
    chartData: {
      type: Object,
      default: () => ({ xData: [], yData: [] })
    },
    seriesName: {
      type: String,
      default: 'Value'
    },
    loading: {
      type: Boolean,
      default: false
    },
    yAxisName: {
      type: String,
      default: ''
    }
  },
  data() {
    return { chart: null }
  },
  watch: {
    chartData: { deep: true, handler() { this.renderChart() } }
  },
  mounted() {
    this.chart = echarts.init(this.$refs.chart)
    this.renderChart()
    window.addEventListener('resize', this.resizeChart)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.resizeChart)
    if (this.chart) this.chart.dispose()
  },
  methods: {
    renderChart() {
      if (!this.chart) return
      const { xData, yData } = this.chartData
      this.chart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: { type: 'category', boundaryGap: false, data: xData || [] },
        yAxis: { type: 'value', scale: true, name: this.yAxisName },
        series: [{
          name: this.seriesName,
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 3 },
          areaStyle: { opacity: 0.12 },
          data: yData || []
        }]
      })
    },
    resizeChart() { if (this.chart) this.chart.resize() }
  }
}
</script>

<style scoped>
.single-chart { width: 100%; height: 320px; }
</style>
