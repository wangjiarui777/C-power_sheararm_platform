import echarts from '@/utils/echarts'
import { GaugeChart, PieChart, RadarChart } from 'echarts/charts'

echarts.use([GaugeChart, PieChart, RadarChart])

export default echarts
