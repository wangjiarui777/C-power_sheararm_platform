<template>
  <div class="app-container phm-page">
    <section class="phm-header">
      <div class="header-brand">
        <img v-if="systemLogo" :src="systemLogo" class="system-logo" alt="系统Logo">
        <div>
          <div class="eyebrow">PHM 设备健康管理</div>
          <h2>{{ systemName || '设备集群' }}</h2>
          <p>按组织、状态与关注设备查看整体运行情况，快速进入单台设备机器大脑。</p>
        </div>
      </div>
      <div class="header-actions">
        <el-tag v-if="refreshIntervalSeconds > 0" size="mini" type="info">自动刷新 {{ refreshIntervalSeconds }} 秒</el-tag>
        <el-switch v-model="query.favoriteOnly" active-text="仅关注" @change="loadCluster" />
        <el-button type="primary" icon="el-icon-refresh" size="small" @click="loadCluster">刷新</el-button>
      </div>
    </section>

    <section class="metric-grid">
      <div class="metric-card">
        <span>设备总数</span>
        <strong>{{ stats.total || 0 }}</strong>
      </div>
      <div class="metric-card">
        <span>运行设备</span>
        <strong>{{ stats.running || 0 }}</strong>
      </div>
      <div class="metric-card">
        <span>告警设备</span>
        <strong>{{ stats.alarming || 0 }}</strong>
      </div>
      <div class="metric-card">
        <span>运行率</span>
        <strong>{{ stats.runningRate || 0 }}%</strong>
      </div>
    </section>

    <section v-if="goodRateTrend.length" class="good-rate-panel">
      <div class="good-rate-head">
        <strong>近半年良好率</strong>
        <span>按每月告警设备数聚合</span>
      </div>
      <div class="good-rate-bars">
        <div v-for="item in goodRateTrend" :key="item.month" class="good-rate-item">
          <div class="bar-track">
            <div class="bar-fill" :style="{ height: `${Number(item.goodRate) || 0}%` }"></div>
          </div>
          <span>{{ item.monthLabel || item.month }}</span>
          <strong>{{ item.goodRate || 0 }}%</strong>
          <small>{{ item.alarmDeviceCount || 0 }} 台告警</small>
        </div>
      </div>
    </section>

    <section class="filter-bar">
      <el-input v-model="query.orgName" placeholder="组织/节点名称" clearable size="small" @keyup.enter.native="loadCluster" />
      <el-select v-model="query.status" placeholder="设备状态" clearable size="small" @change="loadCluster">
        <el-option label="正常" value="normal" />
        <el-option label="停机" value="stopped" />
        <el-option label="1级告警" value="level1" />
        <el-option label="2级告警" value="level2" />
        <el-option label="3级告警" value="level3" />
        <el-option label="4级告警" value="level4" />
        <el-option label="5级告警" value="level5" />
      </el-select>
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button label="list">列表</el-radio-button>
        <el-radio-button label="card">图示</el-radio-button>
      </el-radio-group>
    </section>

    <el-table v-if="viewMode === 'list'" v-loading="loading" :data="devices" stripe>
      <el-table-column width="58" align="center">
        <template slot-scope="scope">
          <el-button v-hasPermi="['phm:device:edit']" type="text" :icon="scope.row.favorite ? 'el-icon-star-on' : 'el-icon-star-off'" @click="toggleFavorite(scope.row)" />
        </template>
      </el-table-column>
      <el-table-column prop="deviceName" label="设备名称" min-width="170" />
      <el-table-column prop="deviceCode" label="设备编码" width="140" />
      <el-table-column prop="orgName" label="所属节点" min-width="170" />
      <el-table-column prop="statusText" label="状态" width="100">
        <template slot-scope="scope">
          <el-tag :type="statusTag(scope.row.status)" size="mini">{{ scope.row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="healthIndex" label="健康指数" width="110">
        <template slot-scope="scope">
          <el-progress :percentage="scope.row.healthIndex || 0" :show-text="false" :color="healthColor(scope.row.healthIndex)" />
        </template>
      </el-table-column>
      <el-table-column prop="faultType" label="故障类型" width="120" />
      <el-table-column prop="latestVibration" label="振动" width="100" />
      <el-table-column prop="latestTemperature" label="温度" width="100" />
      <el-table-column label="操作" fixed="right" width="130">
        <template slot-scope="scope">
          <el-button type="text" size="mini" @click="openBrain(scope.row)">机器大脑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <section v-else v-loading="loading" class="device-card-grid">
      <article v-for="item in devices" :key="item.id" class="device-card" :class="item.status">
        <div class="device-card-head">
          <el-button v-hasPermi="['phm:device:edit']" type="text" :icon="item.favorite ? 'el-icon-star-on' : 'el-icon-star-off'" @click="toggleFavorite(item)" />
          <el-tag :type="statusTag(item.status)" size="mini">{{ item.statusText }}</el-tag>
        </div>
        <h3>{{ item.deviceName }}</h3>
        <p>{{ item.orgName }}</p>
        <div class="mini-row">
          <span>健康度</span>
          <strong>{{ item.healthIndex || 0 }}%</strong>
        </div>
        <div class="mini-row">
          <span>振动 / 温度</span>
          <strong>{{ item.latestVibration || '--' }} / {{ item.latestTemperature || '--' }}</strong>
        </div>
        <el-button type="primary" plain size="mini" @click="openBrain(item)">进入机器大脑</el-button>
      </article>
    </section>
  </div>
</template>

<script>
import { getDeviceCluster, toggleDeviceFavorite, listSystemConfig } from '@/api/phm'

export default {
  name: 'PhmCluster',
  data() {
    return {
      loading: false,
      viewMode: 'list',
      systemName: '',
      systemLogo: '',
      refreshIntervalSeconds: 0,
      refreshTimer: null,
      query: {
        orgName: '',
        status: '',
        favoriteOnly: false
      },
      stats: {},
      goodRateTrend: [],
      devices: []
    }
  },
  created() {
    this.bootstrap()
  },
  beforeDestroy() {
    this.clearRefreshTimer()
  },
  methods: {
    async bootstrap() {
      await this.loadSystemConfig()
      this.loadCluster()
      this.setupRefreshTimer()
    },
    async loadSystemConfig() {
      try {
        const res = await listSystemConfig()
        const configs = res.data || []
        const findValue = key => {
          const item = configs.find(config => config.configKey === key)
          return item ? item.configValue : ''
        }
        this.systemName = findValue('system.name') || '设备集群'
        this.systemLogo = findValue('system.logo')
        const defaultMode = findValue('default.display.mode')
        if (defaultMode === 'list' || defaultMode === 'card') {
          this.viewMode = defaultMode
        }
        const interval = Number(findValue('refresh.interval'))
        this.refreshIntervalSeconds = Number.isFinite(interval) && interval > 0 ? interval : 0
      } catch (error) {
        this.systemName = '设备集群'
        this.systemLogo = ''
      }
    },
    setupRefreshTimer() {
      this.clearRefreshTimer()
      if (this.refreshIntervalSeconds <= 0) return
      this.refreshTimer = window.setInterval(() => {
        this.loadCluster()
      }, this.refreshIntervalSeconds * 1000)
    },
    clearRefreshTimer() {
      if (this.refreshTimer) {
        window.clearInterval(this.refreshTimer)
        this.refreshTimer = null
      }
    },
    async loadCluster() {
      this.loading = true
      try {
        const res = await getDeviceCluster(this.query)
        const data = res.data || {}
        this.devices = data.devices || []
        this.stats = data.stats || {}
        this.goodRateTrend = data.goodRateTrend || []
      } finally {
        this.loading = false
      }
    },
    async toggleFavorite(row) {
      const res = await toggleDeviceFavorite(row.id)
      row.favorite = !!res.data
      this.$message.success(row.favorite ? '已关注设备' : '已取消关注')
      this.loadCluster()
    },
    openBrain(row) {
      this.$router.push(`/phm/brain/${row.id}`)
    },
    statusTag(status) {
      if (status === 'normal') return 'success'
      if (status === 'stopped') return 'info'
      if (status === 'level1' || status === 'level2') return 'warning'
      return 'danger'
    },
    healthColor(value) {
      if (value >= 80) return '#22c55e'
      if (value >= 60) return '#eab308'
      return '#ef4444'
    }
  }
}
</script>

<style scoped>
.phm-page { min-height: calc(100vh - 84px); }
.phm-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.header-brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
.system-logo { width: 48px; height: 48px; object-fit: contain; padding: 6px; flex: none; }
.eyebrow { font-size: 12px; letter-spacing: .08em; text-transform: uppercase; }
.phm-header h2 { margin: 4px 0; }
.phm-header p { margin: 0; }
.header-actions, .filter-bar { display: flex; align-items: center; gap: 10px; }
.filter-bar { margin-bottom: 14px; }
.filter-bar .el-input { width: 220px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.metric-card { padding: 14px 16px; }
.metric-card span { font-size: 12px; }
.metric-card strong { display: block; margin-top: 8px; font-size: 26px; }
.good-rate-panel { display: flex; align-items: stretch; gap: 16px; margin-bottom: 14px; }
.good-rate-head { width: 126px; flex: none; display: flex; flex-direction: column; justify-content: center; gap: 4px; }
.good-rate-head span { font-size: 12px; line-height: 1.4; }
.good-rate-bars { display: grid; grid-template-columns: repeat(6, minmax(56px, 1fr)); flex: 1; gap: 10px; align-items: end; }
.good-rate-item { text-align: center; font-size: 12px; }
.bar-track { position: relative; height: 64px; margin: 0 auto 6px; width: 18px; overflow: hidden; border-radius: 999px; background: rgba(30, 41, 59, 0.86); }
.bar-fill { position: absolute; left: 0; right: 0; bottom: 0; border-radius: 999px; }
.good-rate-item strong { display: block; margin-top: 3px; }
.good-rate-item small { display: block; margin-top: 2px; }
.device-card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.device-card { border-left: 4px solid var(--ops-border-strong); border-radius: 14px; padding: 14px; }
.device-card.level1, .device-card.level2 { border-left-color: var(--ops-warning); }
.device-card.level3, .device-card.level4, .device-card.level5 { border-left-color: var(--ops-danger); }
.device-card.normal { border-left-color: var(--ops-success); }
.device-card-head, .mini-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.device-card h3 { margin: 8px 0 4px; font-size: 16px; }
.mini-row { margin: 10px 0; }
@media (max-width: 900px) {
  .phm-header, .filter-bar { align-items: stretch; flex-direction: column; }
  .header-brand { align-items: flex-start; }
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .good-rate-panel { flex-direction: column; }
  .good-rate-head { width: auto; }
  .good-rate-bars { grid-template-columns: repeat(3, minmax(56px, 1fr)); }
}
</style>
