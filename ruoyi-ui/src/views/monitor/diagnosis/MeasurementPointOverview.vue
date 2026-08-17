<template>
  <main class="point-overview-page">
    <header class="overview-hero">
      <div class="hero-copy">
        <span class="hero-eyebrow">振动测点总览</span>
        <h1>测点诊断总览</h1>
        <p>按部门和设备定位振动测点，查看最近一次诊断状态并进入详细分析。</p>
      </div>
      <div class="hero-stats" aria-label="测点统计">
        <div><strong>{{ summary.departmentCount }}</strong><span>部门</span></div>
        <div><strong>{{ summary.deviceCount }}</strong><span>设备</span></div>
        <div><strong>{{ summary.pointCount }}</strong><span>振动测点</span></div>
      </div>
    </header>

    <section class="overview-toolbar" aria-label="测点筛选">
      <el-input
        v-model.trim="keyword"
        clearable
        prefix-icon="el-icon-search"
        placeholder="搜索部门、设备或测点"
        aria-label="搜索部门、设备或测点"
      />
      <div class="toolbar-actions">
        <span v-if="keyword" class="match-count">找到 {{ filteredPointCount }} 个测点</span>
        <el-button type="text" :disabled="loading || Boolean(keyword)" @click="toggleAll">
          {{ allVisibleExpanded ? '全部收起' : '全部展开' }}
        </el-button>
        <el-button type="primary" plain icon="el-icon-refresh" :loading="loading" @click="loadOverview">刷新</el-button>
      </div>
    </section>

    <section v-if="errorMessage" class="overview-state state-error" role="alert">
      <i class="el-icon-warning-outline" />
      <div>
        <strong>测点总览加载失败</strong>
        <p>{{ errorMessage }}</p>
      </div>
      <el-button type="primary" plain @click="loadOverview">重新加载</el-button>
    </section>

    <section v-else-if="loading" class="overview-loading" aria-label="正在加载测点">
      <div v-for="index in 6" :key="index" class="loading-card">
        <span /><strong /><small />
      </div>
    </section>

    <section v-else-if="!filteredDepartments.length" class="overview-state state-empty">
      <i class="el-icon-data-analysis" />
      <div>
        <strong>{{ keyword ? '没有匹配的测点' : '暂无可诊断的振动测点' }}</strong>
        <p>{{ keyword ? '请尝试设备编码、测点名称或所属部门。' : '请先为有权限的设备配置并启用振动测点。' }}</p>
      </div>
      <el-button v-if="keyword" type="primary" plain @click="keyword = ''">清除搜索</el-button>
    </section>

    <section v-else class="department-stack" aria-live="polite">
      <article v-for="department in filteredDepartments" :key="departmentKey(department)" class="department-panel">
        <button
          type="button"
          class="department-heading"
          :aria-expanded="String(isExpanded(department))"
          @click="toggleDepartment(department)"
        >
          <span class="department-rail" aria-hidden="true"><i /></span>
          <span class="department-title">
            <small>所属部门</small>
            <strong>{{ department.deptName || '未分配部门' }}</strong>
          </span>
          <span class="department-meta">
            {{ department.devices.length }} 台设备 · {{ departmentPointCount(department) }} 个测点
          </span>
          <i class="el-icon-arrow-down department-chevron" :class="{ 'is-open': isExpanded(department) }" />
        </button>

        <div v-show="isExpanded(department)" class="department-content">
          <section v-for="device in department.devices" :key="device.id || device.deviceCode" class="device-section">
            <header class="device-heading">
              <div>
                <span class="device-status" :class="deviceTone(device.status)" aria-hidden="true" />
                <h2>{{ device.deviceName || '未命名设备' }}</h2>
                <code>{{ device.deviceCode }}</code>
              </div>
              <p>
                <span v-if="device.deviceType">{{ device.deviceType }}</span>
                <span v-if="device.orgName">{{ device.orgName }}</span>
                <span v-if="device.location">{{ device.location }}</span>
              </p>
            </header>

            <div class="point-grid">
              <button
                v-for="point in device.points"
                :key="point.id"
                type="button"
                class="point-card"
                :class="riskTone(point.latestDiagnosis)"
                :aria-label="`${point.pointName || point.pointCode}，进入诊断分析`"
                @click="openDiagnosis(device, point)"
              >
                <span class="risk-rail" aria-hidden="true" />
                <span class="point-card-head">
                  <span class="channel-label">CH {{ point.channelId == null ? '--' : point.channelId }}</span>
                  <span class="risk-label">{{ riskLabel(point.latestDiagnosis) }}</span>
                </span>
                <strong class="point-name">{{ point.pointName || point.pointCode || `测点 ${point.id}` }}</strong>
                <span class="point-code">{{ point.pointCode || '未设置测点编码' }}</span>

                <span v-if="hasDiagnosis(point.latestDiagnosis)" class="diagnosis-summary">
                  {{ point.latestDiagnosis.diagnosisResult || '已完成诊断' }}
                </span>
                <span v-else class="diagnosis-summary is-empty">尚无诊断记录</span>

                <span class="point-metrics">
                  <span>
                    <small>健康度</small>
                    <b>{{ metricValue(point.latestDiagnosis, 'healthIndex') }}</b>
                  </span>
                  <span>
                    <small>置信度</small>
                    <b>{{ percentageValue(point.latestDiagnosis && point.latestDiagnosis.confidence) }}</b>
                  </span>
                </span>
                <span class="point-card-foot">
                  <time>{{ diagnosisTime(point.latestDiagnosis) }}</time>
                  <span>进入分析 <i class="el-icon-right" /></span>
                </span>
              </button>
            </div>
          </section>
        </div>
      </article>
    </section>
  </main>
</template>

<script>
import { getDiagnosisOverview } from '@/api/system/bearingDiagnosis'
import { getErrorMessage } from '@/utils/request'

export default {
  name: 'MeasurementPointOverview',
  data() {
    return {
      loading: false,
      errorMessage: '',
      keyword: '',
      departments: [],
      summary: {
        departmentCount: 0,
        deviceCount: 0,
        pointCount: 0
      },
      expandedDepartments: {}
    }
  },
  computed: {
    filteredDepartments() {
      const query = this.keyword.toLowerCase()
      if (!query) return this.departments
      return this.departments.reduce((departments, department) => {
        const departmentMatches = this.includes(department.deptName, query)
        const devices = (department.devices || []).reduce((rows, device) => {
          const deviceMatches = departmentMatches ||
            this.includes(device.deviceName, query) ||
            this.includes(device.deviceCode, query) ||
            this.includes(device.deviceType, query) ||
            this.includes(device.orgName, query) ||
            this.includes(device.location, query)
          const points = (device.points || []).filter(point => deviceMatches ||
            this.includes(point.pointName, query) ||
            this.includes(point.pointCode, query) ||
            this.includes(point.channelId, query))
          if (points.length) rows.push(Object.assign({}, device, { points }))
          return rows
        }, [])
        if (devices.length) departments.push(Object.assign({}, department, { devices }))
        return departments
      }, [])
    },
    filteredPointCount() {
      return this.filteredDepartments.reduce((total, department) =>
        total + this.departmentPointCount(department), 0)
    },
    allVisibleExpanded() {
      return this.filteredDepartments.length > 0 &&
        this.filteredDepartments.every(department => this.isExpanded(department))
    }
  },
  watch: {
    keyword(value) {
      if (!value) return
      this.filteredDepartments.forEach(department => {
        this.$set(this.expandedDepartments, this.departmentKey(department), true)
      })
    }
  },
  created() {
    this.loadOverview()
  },
  methods: {
    async loadOverview() {
      this.loading = true
      this.errorMessage = ''
      try {
        const response = await getDiagnosisOverview()
        const data = response.data || response || {}
        this.departments = Array.isArray(data.departments) ? data.departments : []
        this.summary = {
          departmentCount: Number(data.departmentCount) || this.departments.length,
          deviceCount: Number(data.deviceCount) || 0,
          pointCount: Number(data.pointCount) || 0
        }
        const expanded = {}
        this.departments.forEach(department => {
          expanded[this.departmentKey(department)] = true
        })
        this.expandedDepartments = expanded
      } catch (error) {
        this.departments = []
        this.summary = { departmentCount: 0, deviceCount: 0, pointCount: 0 }
        this.errorMessage = getErrorMessage(error, '请确认账号具有诊断查看权限，然后重试。')
      } finally {
        this.loading = false
      }
    },
    includes(value, query) {
      return String(value == null ? '' : value).toLowerCase().includes(query)
    },
    departmentKey(department) {
      return department.deptId == null ? 'unassigned' : `dept-${department.deptId}`
    },
    departmentPointCount(department) {
      return (department.devices || []).reduce((total, device) =>
        total + (device.points || []).length, 0)
    },
    isExpanded(department) {
      return this.keyword ? true : this.expandedDepartments[this.departmentKey(department)] !== false
    },
    toggleDepartment(department) {
      if (this.keyword) return
      const key = this.departmentKey(department)
      this.$set(this.expandedDepartments, key, !this.isExpanded(department))
    },
    toggleAll() {
      const next = !this.allVisibleExpanded
      this.filteredDepartments.forEach(department => {
        this.$set(this.expandedDepartments, this.departmentKey(department), next)
      })
    },
    openDiagnosis(device, point) {
      this.$router.push({
        path: this.$route.path,
        query: {
          view: 'detail',
          deviceCode: device.deviceCode,
          pointId: String(point.id),
          channelId: point.channelId == null ? undefined : point.channelId
        }
      })
    },
    hasDiagnosis(diagnosis) {
      return diagnosis && diagnosis.dataStatus === 'available'
    },
    riskLabel(diagnosis) {
      if (!this.hasDiagnosis(diagnosis)) return '未诊断'
      const level = diagnosis.riskLevel || diagnosis.alarmLevel
      return level ? `风险 ${level}` : '已诊断'
    },
    riskTone(diagnosis) {
      if (!this.hasDiagnosis(diagnosis)) return 'risk-empty'
      const level = String(diagnosis.riskLevel || diagnosis.alarmLevel || '').toLowerCase()
      if (level === '高' || level === 'alarm' || level === 'danger') return 'risk-high'
      if (level === '中' || level === 'warning' || level === 'attention') return 'risk-medium'
      return 'risk-low'
    },
    deviceTone(status) {
      const value = String(status || '').toLowerCase()
      if (value === 'normal' || value === 'running' || value === 'active') return 'is-running'
      if (value === 'stopped' || value === 'offline') return 'is-stopped'
      return value ? 'is-warning' : 'is-unknown'
    },
    metricValue(diagnosis, field) {
      const value = diagnosis && diagnosis[field]
      return value == null || value === '' ? '--' : `${value}%`
    },
    percentageValue(value) {
      if (value == null || value === '') return '--'
      const numeric = Number(value)
      if (!Number.isFinite(numeric)) return '--'
      const percentage = numeric <= 1 ? numeric * 100 : numeric
      return `${percentage.toFixed(0)}%`
    },
    diagnosisTime(diagnosis) {
      if (!this.hasDiagnosis(diagnosis) || !diagnosis.diagnosisTime) return '等待首次诊断'
      return this.parseTime(diagnosis.diagnosisTime, '{y}-{m}-{d} {h}:{i}')
    }
  }
}
</script>

<style scoped lang="scss">
.point-overview-page {
  --overview-bg: #07131f;
  --overview-panel: #0a1c2d;
  --overview-panel-soft: #0d2438;
  --overview-line: rgba(87, 209, 255, .18);
  --overview-cyan: #57d1ff;
  --overview-text: #e9f6ff;
  --overview-muted: #7f9db2;
  min-height: calc(100vh - 84px);
  padding: 18px 22px 34px;
  color: var(--overview-text);
  background:
    linear-gradient(rgba(87, 209, 255, .028) 1px, transparent 1px),
    linear-gradient(90deg, rgba(87, 209, 255, .028) 1px, transparent 1px),
    linear-gradient(180deg, #07131f 0%, #0a1c2d 100%);
  background-size: 32px 32px, 32px 32px, auto;
}

.overview-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  padding: 20px 22px;
  border: 1px solid var(--overview-line);
  border-radius: 14px;
  background: linear-gradient(110deg, rgba(9, 31, 48, .96), rgba(8, 23, 36, .88));
  box-shadow: 0 18px 50px rgba(0, 0, 0, .18);
}

.hero-eyebrow {
  display: block;
  margin-bottom: 8px;
  color: #8adfff;
  font: 800 11px/1.3 "Arial Narrow", "Microsoft YaHei", sans-serif;
  letter-spacing: .16em;
}

.hero-copy h1 {
  margin: 0;
  font: 700 28px/1.2 "Microsoft YaHei", "PingFang SC", sans-serif;
  letter-spacing: .02em;
}

.hero-copy p {
  margin: 9px 0 0;
  color: #91adbf;
  font-size: 13px;
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(76px, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--overview-line);
  border-radius: 10px;
  background: var(--overview-line);
}

.hero-stats div {
  min-width: 88px;
  padding: 10px 16px;
  text-align: center;
  background: rgba(7, 19, 31, .94);
}

.hero-stats strong {
  display: block;
  color: #f4fbff;
  font: 700 22px/1.1 Consolas, "SFMono-Regular", monospace;
}

.hero-stats span {
  display: block;
  margin-top: 4px;
  color: var(--overview-muted);
  font-size: 11px;
}

.overview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 14px 0;
  padding: 10px 14px;
  border: 1px solid rgba(87, 209, 255, .12);
  border-radius: 12px;
  background: rgba(7, 19, 31, .88);
}

.overview-toolbar ::v-deep .el-input {
  width: min(440px, 50vw);
}

.overview-toolbar ::v-deep .el-input__inner {
  color: #dff5ff;
  border-color: rgba(87, 209, 255, .2);
  background: #071722;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.match-count {
  color: var(--overview-muted);
  font-size: 12px;
}

.department-stack {
  display: grid;
  gap: 12px;
}

.department-panel {
  overflow: hidden;
  border: 1px solid rgba(87, 209, 255, .14);
  border-radius: 14px;
  background: rgba(8, 24, 38, .88);
}

.department-heading {
  display: grid;
  grid-template-columns: 28px minmax(180px, 1fr) auto 24px;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px 18px;
  color: inherit;
  text-align: left;
  border: 0;
  background: linear-gradient(90deg, rgba(87, 209, 255, .055), transparent 45%);
  cursor: pointer;
}

.department-heading:focus-visible,
.point-card:focus-visible {
  outline: 2px solid var(--overview-cyan);
  outline-offset: -3px;
}

.department-rail {
  position: relative;
  display: block;
  width: 24px;
  height: 24px;
}

.department-rail::before,
.department-rail::after {
  content: "";
  position: absolute;
  left: 11px;
  background: rgba(87, 209, 255, .42);
}

.department-rail::before {
  top: -16px;
  width: 1px;
  height: 56px;
}

.department-rail::after {
  top: 11px;
  width: 13px;
  height: 1px;
}

.department-rail i {
  position: absolute;
  top: 7px;
  left: 7px;
  width: 9px;
  height: 9px;
  border: 2px solid #57d1ff;
  border-radius: 50%;
  background: #0a1c2d;
  box-shadow: 0 0 12px rgba(87, 209, 255, .55);
}

.department-title small {
  display: block;
  margin-bottom: 3px;
  color: #5f879e;
  font: 700 9px/1.2 Consolas, monospace;
  letter-spacing: .13em;
}

.department-title strong {
  font-size: 16px;
}

.department-meta {
  color: var(--overview-muted);
  font-size: 12px;
}

.department-chevron {
  color: #6b91a7;
  transition: transform .2s ease;
}

.department-chevron.is-open {
  transform: rotate(180deg);
}

.department-content {
  padding: 0 18px 18px 58px;
}

.device-section {
  position: relative;
  padding-top: 18px;
  border-top: 1px solid rgba(87, 209, 255, .09);
}

.device-section + .device-section {
  margin-top: 18px;
}

.device-section::before {
  content: "";
  position: absolute;
  top: -1px;
  left: -29px;
  bottom: -18px;
  width: 1px;
  background: rgba(87, 209, 255, .19);
}

.device-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 12px;
}

.device-heading > div {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.device-heading h2 {
  margin: 0;
  font-size: 15px;
}

.device-heading code {
  padding: 2px 7px;
  color: #84a7bb;
  border: 1px solid rgba(127, 157, 178, .2);
  border-radius: 5px;
  background: rgba(3, 13, 21, .4);
  font-size: 10px;
}

.device-heading p {
  display: flex;
  gap: 14px;
  margin: 0;
  color: #688ba0;
  font-size: 11px;
}

.device-status {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
  background: #6b7d88;
}

.device-status.is-running { background: #45d49d; box-shadow: 0 0 10px rgba(69, 212, 157, .45); }
.device-status.is-warning { background: #f3bd54; }
.device-status.is-stopped { background: #748997; }

.point-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(248px, 1fr));
  gap: 11px;
}

.point-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 218px;
  overflow: hidden;
  padding: 14px 14px 12px 18px;
  color: #dff3ff;
  text-align: left;
  border: 1px solid rgba(87, 209, 255, .13);
  border-radius: 11px;
  background: linear-gradient(145deg, rgba(13, 36, 56, .96), rgba(7, 22, 34, .96));
  box-shadow: 0 10px 24px rgba(0, 0, 0, .12);
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.point-card:hover {
  transform: translateY(-2px);
  border-color: rgba(87, 209, 255, .34);
  box-shadow: 0 15px 30px rgba(0, 0, 0, .22);
}

.risk-rail {
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: #577080;
}

.risk-high .risk-rail { background: #ff647c; box-shadow: 0 0 16px rgba(255, 100, 124, .45); }
.risk-medium .risk-rail { background: #f3bd54; box-shadow: 0 0 16px rgba(243, 189, 84, .35); }
.risk-low .risk-rail { background: #45d49d; box-shadow: 0 0 16px rgba(69, 212, 157, .32); }

.point-card-head,
.point-card-foot,
.point-metrics {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.channel-label {
  color: #86ddff;
  font: 700 11px/1.2 Consolas, monospace;
  letter-spacing: .08em;
}

.risk-label {
  color: #7899ad;
  font-size: 10px;
}

.risk-high .risk-label { color: #ff8395; }
.risk-medium .risk-label { color: #f7ca75; }
.risk-low .risk-label { color: #68ddb2; }

.point-name {
  margin-top: 13px;
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.point-code {
  margin-top: 4px;
  color: #65879b;
  font: 10px/1.3 Consolas, monospace;
}

.diagnosis-summary {
  display: -webkit-box;
  min-height: 38px;
  margin: 14px 0 12px;
  overflow: hidden;
  color: #c6deec;
  font-size: 12px;
  line-height: 19px;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.diagnosis-summary.is-empty {
  color: #607f92;
}

.point-metrics {
  padding: 9px 0;
  border-top: 1px solid rgba(87, 209, 255, .08);
  border-bottom: 1px solid rgba(87, 209, 255, .08);
}

.point-metrics span {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.point-metrics small {
  color: #68899d;
  font-size: 10px;
}

.point-metrics b {
  color: #e8f8ff;
  font: 700 13px/1 Consolas, monospace;
}

.point-card-foot {
  margin-top: auto;
  padding-top: 11px;
  color: #66879b;
  font-size: 10px;
}

.point-card-foot > span {
  color: #80d9fb;
}

.overview-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  min-height: 260px;
  padding: 30px;
  border: 1px dashed rgba(87, 209, 255, .23);
  border-radius: 14px;
  background: rgba(7, 19, 31, .62);
}

.overview-state > i {
  color: #5dcff8;
  font-size: 34px;
}

.overview-state strong {
  font-size: 16px;
}

.overview-state p {
  margin: 6px 0 0;
  color: var(--overview-muted);
  font-size: 12px;
}

.state-error > i {
  color: #ff7185;
}

.overview-loading {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(248px, 1fr));
  gap: 12px;
  padding: 18px;
  border-radius: 14px;
  background: rgba(8, 24, 38, .7);
}

.loading-card {
  min-height: 180px;
  padding: 18px;
  border: 1px solid rgba(87, 209, 255, .08);
  border-radius: 11px;
  background: rgba(13, 36, 56, .8);
}

.loading-card span,
.loading-card strong,
.loading-card small {
  display: block;
  height: 12px;
  margin-bottom: 18px;
  border-radius: 5px;
  background: linear-gradient(90deg, rgba(87, 209, 255, .05), rgba(87, 209, 255, .16), rgba(87, 209, 255, .05));
  background-size: 200% 100%;
  animation: loading-sweep 1.4s ease infinite;
}

.loading-card strong { width: 68%; height: 18px; }
.loading-card small { width: 45%; }

@keyframes loading-sweep {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
}

@media (max-width: 900px) {
  .point-overview-page { padding: 12px; }
  .overview-hero { align-items: stretch; flex-direction: column; }
  .hero-stats { align-self: stretch; }
  .overview-toolbar { align-items: stretch; flex-direction: column; }
  .overview-toolbar ::v-deep .el-input { width: 100%; }
  .toolbar-actions { justify-content: flex-end; }
  .department-heading { grid-template-columns: 24px 1fr 20px; }
  .department-meta { display: none; }
  .department-content { padding-left: 42px; }
  .device-heading { align-items: flex-start; flex-direction: column; gap: 7px; }
  .device-heading p { flex-wrap: wrap; }
}

@media (max-width: 560px) {
  .overview-hero { padding: 17px; }
  .hero-copy h1 { font-size: 23px; }
  .hero-stats div { min-width: 0; padding: 9px 6px; }
  .department-heading { padding: 13px 11px; }
  .department-content { padding: 0 10px 12px 34px; }
  .point-grid { grid-template-columns: 1fr; }
  .toolbar-actions { flex-wrap: wrap; }
}

@media (prefers-reduced-motion: reduce) {
  .point-card,
  .department-chevron {
    transition: none;
  }
  .loading-card span,
  .loading-card strong,
  .loading-card small {
    animation: none;
  }
}
</style>
