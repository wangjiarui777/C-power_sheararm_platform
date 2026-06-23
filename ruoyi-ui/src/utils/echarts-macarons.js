import echarts from '@/utils/echarts'

echarts.registerTheme('macarons', {
  color: [
    '#2ec7c9', '#b6a2de', '#5ab1ef', '#ffb980', '#d87a80',
    '#8d98b3', '#e5cf0d', '#97b552', '#95706d', '#dc69aa'
  ],
  title: { textStyle: { fontWeight: 'normal', color: '#008acd' } },
  tooltip: {
    borderWidth: 0,
    backgroundColor: 'rgba(15, 23, 42, 0.88)',
    textStyle: { color: '#fff' },
    axisPointer: { lineStyle: { color: '#22d3ee' } }
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#008acd' } },
    splitLine: { lineStyle: { color: ['rgba(148,163,184,.18)'] } }
  },
  valueAxis: {
    axisLine: { lineStyle: { color: '#008acd' } },
    splitLine: { lineStyle: { color: ['rgba(148,163,184,.18)'] } }
  }
})

export default echarts
