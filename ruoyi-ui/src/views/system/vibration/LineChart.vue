<template>
  <div class="chart-container" v-loading="loading">
    <div ref="chart" class="chart" />
  </div>
</template>

<script>
import echarts from '@/utils/echarts'
import { industrialChartTheme } from '@/utils/industrialTheme'

export default {
  name: 'LineChart',
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
        this.$nextTick(() => {
          this.initChart()
        })
      }
    }
  },
  mounted() {
    this.initChart()
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
    initChart() {
      if (!this.$refs.chart) return
      if (!this.chart) {
        this.chart = echarts.init(this.$refs.chart)
      }
      const theme = industrialChartTheme
      const option = {
        tooltip: {
          trigger: 'axis',
          backgroundColor: theme.tooltipBg,
          borderColor: theme.tooltipBorder,
          textStyle: { color: theme.text }
        },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: {
          type: 'category',
          data: this.chartData.xData || [],
          boundaryGap: false,
          axisLine: { lineStyle: { color: theme.border } },
          axisLabel: { color: theme.muted }
        },
        yAxis: {
          type: 'value',
          name: '振动值',
          axisLine: { lineStyle: { color: theme.border } },
          splitLine: { show: false },
          axisLabel: { color: theme.muted }
        },
        series: [
          {
            name: '振动值',
            type: 'line',
            smooth: true,
            showSymbol: false,
            data: this.chartData.yData || [],
            lineStyle: {
              width: 3,
              color: theme.vibration
            },
            itemStyle: {
              color: theme.vibration
            },
            areaStyle: {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: 'rgba(56, 189, 248, 0.28)' },
                  { offset: 1, color: 'rgba(56, 189, 248, 0)' }
                ]
              }
            }
          }
        ]
      }
      this.chart.setOption(option, true)
    },
    resizeChart() {
      if (this.chart) {
        this.chart.resize()
      }
    }
  }
}
</script>

<style scoped>
.chart-container {
  width: 100%;
  height: 360px;
}
.chart {
  width: 100%;
  height: 100%;
}
</style>
