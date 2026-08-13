<template>
  <section class="status-rail">
    <div class="rail-head"><strong>设备状态轨</strong><span>断流、预警、告警与恢复</span></div>
    <div class="rail-line">
      <div v-if="!events.length" class="rail-empty">当前时间窗内无状态事件</div>
      <button
        v-for="(item, index) in events"
        :key="`${item.time}-${index}`"
        class="rail-event"
        :class="item.type"
        :style="{ left: `${position(index)}%` }"
        :title="`${item.label || ''} ${formatTime(item.time)}`"
        @click="$emit('select', item)"
      >
        <i></i><span>{{ item.label }}</span>
      </button>
    </div>
  </section>
</template>

<script>
export default {
  name: 'MonitoringStatusRail',
  props: { events: { type: Array, default: () => [] } },
  methods: {
    position(index) { return this.events.length <= 1 ? 50 : 5 + (index / (this.events.length - 1)) * 90 },
    formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '--' }
  }
}
</script>

<style scoped>
.status-rail{padding:14px 16px;border:1px solid var(--color-border);border-radius:var(--radius-lg);background:var(--color-surface)}
.rail-head{display:flex;justify-content:space-between;color:var(--color-heading)}.rail-head span{color:var(--color-muted);font-size:12px}
.rail-line{position:relative;height:74px;margin-top:12px;border-top:2px solid var(--color-border-strong)}
.rail-event{position:absolute;top:-7px;display:grid;justify-items:center;gap:5px;max-width:110px;padding:0;border:0;background:transparent;color:var(--color-muted);transform:translateX(-50%);cursor:pointer}
.rail-event i{width:12px;height:12px;border:3px solid var(--color-surface);border-radius:50%;background:var(--color-muted);box-shadow:0 0 0 1px var(--color-muted)}.rail-event span{overflow:hidden;max-width:105px;text-overflow:ellipsis;white-space:nowrap;font-size:11px}
.rail-event.warning i{background:var(--color-warning);box-shadow:0 0 0 1px var(--color-warning)}.rail-event.alarm i{background:var(--color-danger);box-shadow:0 0 0 1px var(--color-danger)}.rail-event.recovered i{background:var(--color-success);box-shadow:0 0 0 1px var(--color-success)}
.rail-empty{padding-top:18px;color:var(--color-muted);text-align:center;font-size:12px}
</style>
