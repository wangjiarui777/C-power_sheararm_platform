import echarts from '@/utils/echarts'
import { GaugeChart, PieChart, RadarChart } from 'echarts/charts'
import '@/utils/echarts-macarons'

echarts.use([GaugeChart, PieChart, RadarChart])

export default echarts
