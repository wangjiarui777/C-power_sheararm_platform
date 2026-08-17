<template>
  <div class="industrial-home">
    <header class="home-hero">
      <div>
        <span class="home-kicker">采煤机摇臂 · 状态监测控制台</span>
        <h1>设备运行总览</h1>
        <p>从设备状态到告警处置，快速进入当前班次的监测任务。</p>
      </div>
      <div class="home-connection" :class="connectionState">
        <i />
        <span>{{ connectionState === 'online' ? '实时数据已连接' : '实时数据连接中断' }}</span>
        <small>最后采样 {{ formatTime(summary.latestSampleTime) }}</small>
      </div>
    </header>

    <section class="home-kpis">
      <article><span>在线设备</span><strong>{{ summary.onlineDevices || 0 }}</strong><small>当前授权范围</small></article>
      <article class="warning"><span>异常设备</span><strong>{{ summary.abnormalDevices || 0 }}</strong><small>需要优先复核</small></article>
      <article class="danger"><span>待处置告警</span><strong>{{ summary.unacknowledgedAlarms || 0 }}</strong><small>等待确认或指派</small></article>
      <article><span>数据延迟</span><strong>{{ delayText }}</strong><small>最后采样至今</small></article>
    </section>

    <main class="home-grid">
      <section class="home-panel workflow-panel">
        <div class="panel-head"><div><strong>值班工作入口</strong><span>按现场处置顺序进入功能</span></div></div>
        <div class="workflow-list">
          <button v-for="item in workflows" :key="item.path" type="button" @click="$router.push(item.path)">
            <i :class="item.icon" /><span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span><i class="el-icon-arrow-right" />
          </button>
        </div>
      </section>

      <section class="home-panel device-panel">
        <div class="panel-head"><div><strong>当前设备</strong><span>{{ activeDevice ? activeDevice.deviceName || activeDevice.deviceCode : '请选择设备' }}</span></div><el-button size="mini" type="primary" plain @click="$router.push('/monitoring-center/index')">进入实时监测</el-button></div>
        <div v-if="activeDevice" class="device-summary">
          <div><span>设备编码</span><strong>{{ activeDevice.deviceCode || '--' }}</strong></div>
          <div><span>健康指数</span><strong>{{ activeDevice.healthIndex == null ? '--' : `${activeDevice.healthIndex}%` }}</strong></div>
          <div><span>振动</span><strong>{{ activeDevice.latestVibration || '--' }}</strong></div>
          <div><span>温度</span><strong>{{ activeDevice.latestTemperature || '--' }}</strong></div>
        </div>
        <div v-else class="home-empty">暂无当前设备，请先进入实时监测选择设备。</div>
      </section>

      <section class="home-panel alarm-panel">
        <div class="panel-head"><div><strong>最近待处置告警</strong><span>先确认，再执行现场处置</span></div><el-button size="mini" type="text" @click="$router.push('/phm/alarms')">查看全部</el-button></div>
        <button v-for="alarm in alarms.slice(0, 4)" :key="alarm.id" type="button" class="alarm-row" @click="$router.push({ path: '/phm/alarms', query: { id: alarm.id } })">
          <i :class="alarm.alarmLevel >= 3 ? 'is-danger' : 'is-warning'" /><span><strong>{{ alarm.pointName || alarm.deviceName || '设备告警' }}</strong><small>{{ alarm.diagnosisResult || '监测指标触发告警规则' }} · {{ formatTime(alarm.alarmTime) }}</small></span><i class="el-icon-arrow-right" />
        </button>
        <div v-if="!alarms.length" class="home-empty">当前没有待处置告警。</div>
      </section>
    </main>
  </div>
</template>

<script>
import { mapState } from 'vuex'

export default {
  name: 'Index',
  data() {
    return {
      workflows: [
        { path: '/monitoring-center/index', icon: 'el-icon-data-line', title: '实时监测', description: '查看设备、测点和最新采样质量' },
        { path: '/system/vibration', icon: 'el-icon-pie-chart', title: '振动分析', description: '查看趋势、波形、频谱和文件数据' },
        { path: '/monitor/diagnosis', icon: 'el-icon-cpu', title: '模型诊断', description: '选择模型版本并执行可追溯诊断' },
        { path: '/phm/alarms', icon: 'el-icon-warning-outline', title: '告警处置', description: '确认、指派、处理和关闭告警' }
      ]
    }
  },
  computed: {
    ...mapState('monitoring', ['workbench', 'connectionState']),
    summary() { return this.workbench.summary || {} },
    alarms() { return this.workbench.alarms || [] },
    activeDevice() { return this.workbench.device },
    delayText() {
      const value = this.summary.dataDelaySeconds
      if (value === null || value === undefined) return '--'
      return Number(value) < 60 ? `${value} 秒` : `${Math.floor(value / 60)} 分钟`
    }
  },
  async created() {
    try {
      await this.$store.dispatch('monitoring/loadWorkbench')
      this.$store.dispatch('monitoring/connect')
    } catch (error) {
      // 首页保留静态入口，监测接口不可用时仍允许进入其他功能页。
    }
  },
  beforeDestroy() {
    this.$store.dispatch('monitoring/disconnect')
  },
  methods: {
    formatTime(value) {
      if (!value) return '--'
      const date = new Date(value)
      return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
    }
  }
}
</script>

<style scoped>
.industrial-home{min-height:calc(100vh - 84px);padding:24px;color:var(--color-text);background:linear-gradient(135deg,rgba(34,211,238,.04),transparent 32%)}
.home-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;padding:6px 0 22px;border-bottom:1px solid var(--color-border)}
.home-kicker{color:var(--color-accent);font-size:12px;letter-spacing:.08em}.home-hero h1{margin:8px 0 6px;font-size:30px;letter-spacing:.02em}.home-hero p{margin:0;color:var(--color-muted);font-size:14px}
.home-connection{display:grid;grid-template-columns:10px auto;align-items:center;gap:7px;color:var(--color-success);font-size:13px}.home-connection i{width:9px;height:9px;border-radius:50%;background:currentColor;box-shadow:0 0 12px currentColor}.home-connection small{grid-column:2;color:var(--color-muted)}.home-connection.offline{color:var(--color-danger)}
.home-kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0}.home-kpis article,.home-panel{border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface);box-shadow:var(--shadow-panel)}.home-kpis article{display:grid;gap:8px;padding:16px;border-top:3px solid var(--color-accent)}.home-kpis article.warning{border-top-color:var(--color-warning)}.home-kpis article.danger{border-top-color:var(--color-danger)}.home-kpis span,.home-kpis small{color:var(--color-muted);font-size:12px}.home-kpis strong{font:600 30px/1 var(--font-data);color:var(--color-accent)}.home-kpis article.warning strong{color:var(--color-warning)}.home-kpis article.danger strong{color:var(--color-danger)}
.home-grid{display:grid;grid-template-columns:1.1fr 1fr 1fr;gap:14px}.home-panel{min-height:250px;padding:18px}.panel-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.panel-head strong{display:block;font-size:16px}.panel-head span{display:block;margin-top:4px;color:var(--color-muted);font-size:12px}
.workflow-list,.alarm-row{display:grid;gap:8px}.workflow-list button,.alarm-row{display:grid;grid-template-columns:28px minmax(0,1fr) 16px;align-items:center;gap:12px;width:100%;padding:12px;border:1px solid transparent;border-radius:var(--radius-md);background:var(--color-surface-soft);color:var(--color-text);text-align:left;cursor:pointer}.workflow-list button:hover,.alarm-row:hover{border-color:var(--color-accent-strong);background:var(--color-surface-raised)}.workflow-list button>i:first-child{color:var(--color-accent);font-size:18px}.workflow-list span,.alarm-row span{min-width:0}.workflow-list strong,.workflow-list small,.alarm-row strong,.alarm-row small{display:block}.workflow-list small,.alarm-row small{margin-top:4px;color:var(--color-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.device-summary{display:grid;grid-template-columns:repeat(2,1fr);gap:12px}.device-summary div{padding:14px;background:var(--color-surface-soft);border-radius:var(--radius-md)}.device-summary span{display:block;color:var(--color-muted);font-size:12px}.device-summary strong{display:block;margin-top:8px;color:var(--color-accent);font:600 18px var(--font-data)}.home-empty{padding:28px 8px;color:var(--color-muted);text-align:center}.alarm-row{grid-template-columns:9px minmax(0,1fr) 16px;margin-bottom:8px}.alarm-row>i:first-child{width:9px;height:9px;border-radius:50%;background:var(--color-warning)}.alarm-row>i.is-danger{background:var(--color-danger)}.alarm-row>i.el-icon-arrow-right{width:auto;height:auto;background:none;color:var(--color-muted)}
@media(max-width:1100px){.home-grid{grid-template-columns:1fr 1fr}.alarm-panel{grid-column:1/-1}}@media(max-width:720px){.industrial-home{padding:16px}.home-hero{align-items:flex-start;flex-direction:column}.home-kpis{grid-template-columns:repeat(2,1fr)}.home-grid{grid-template-columns:1fr}.alarm-panel{grid-column:auto}}
@media(prefers-reduced-motion:reduce){.workflow-list button,.alarm-row{transition:none}}
</style>
