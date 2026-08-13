<template>
  <section class="asset-nav">
    <div class="asset-tools">
      <el-input v-model="keyword" size="mini" clearable prefix-icon="el-icon-search" placeholder="设备、编码或测点" />
      <el-select v-model="status" size="mini" placeholder="全部状态">
        <el-option label="全部状态" value="" />
        <el-option label="告警" value="ALARM" />
        <el-option label="预警" value="WARNING" />
        <el-option label="正常" value="NORMAL" />
        <el-option label="未知/离线" value="UNKNOWN" />
      </el-select>
    </div>
    <div class="asset-list">
      <div v-for="org in filteredAssets" :key="org.id" class="org-group">
        <div class="org-title"><span>{{ org.label }}</span><small>{{ org.children.length }} 台</small></div>
        <button
          v-for="device in org.children"
          :key="device.id"
          class="device-row"
          :class="{ active: device.deviceCode === activeDeviceCode }"
          @click="$emit('select-device', device)"
        >
          <i :class="['state-dot', statusClass(device.status)]"></i>
          <span><strong>{{ device.label }}</strong><small>{{ device.deviceCode }} · {{ device.children.length }} 测点</small></span>
          <i class="el-icon-arrow-right"></i>
        </button>
      </div>
      <div v-if="!filteredAssets.length" class="empty">没有符合条件的设备</div>
    </div>
  </section>
</template>

<script>
export default {
  name: 'AssetNavigator',
  props: {
    assets: { type: Array, default: () => [] },
    activeDeviceCode: { type: String, default: '' }
  },
  data() {
    return { keyword: '', status: '' }
  },
  computed: {
    filteredAssets() {
      const keyword = this.keyword.trim().toLowerCase()
      return this.assets.map(org => ({
        ...org,
        children: (org.children || []).filter(device => {
          const matchesStatus = !this.status || device.status === this.status
          const haystack = `${device.label} ${device.deviceCode} ${(device.children || []).map(p => p.label).join(' ')}`.toLowerCase()
          return matchesStatus && (!keyword || haystack.includes(keyword))
        })
      })).filter(org => org.children.length)
    }
  },
  methods: {
    statusClass(status) {
      return String(status || 'UNKNOWN').toLowerCase()
    }
  }
}
</script>

<style scoped>
.asset-nav{height:100%;min-height:0;padding:12px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}
.asset-tools{display:grid;grid-template-columns:minmax(0,1fr) 108px;gap:8px;margin-bottom:12px}
.asset-list{display:grid;gap:16px;max-height:calc(100vh - 260px);overflow:auto;padding-right:3px}
.org-title{display:flex;justify-content:space-between;margin:0 4px 7px;color:var(--color-muted);font-size:12px}
.device-row{display:grid;grid-template-columns:9px minmax(0,1fr) 14px;align-items:center;gap:10px;width:100%;margin:6px 0;padding:10px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:var(--color-surface-soft);color:var(--color-text);text-align:left;cursor:pointer}
.device-row.active{border-color:var(--color-accent-strong);box-shadow:inset 3px 0 var(--color-accent)}
.device-row span,.device-row strong,.device-row small{display:block;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.device-row small{margin-top:4px;color:var(--color-muted)}
.state-dot{width:8px;height:8px;border-radius:50%;background:var(--color-muted)}.state-dot.normal{background:var(--color-success)}.state-dot.warning{background:var(--color-warning)}.state-dot.alarm{background:var(--color-danger)}
.empty{padding:24px 10px;color:var(--color-muted);text-align:center}
::v-deep .el-input__inner,::v-deep .el-select .el-input__inner{border-color:var(--color-border);background:var(--color-surface-soft);color:var(--color-text)}
</style>
