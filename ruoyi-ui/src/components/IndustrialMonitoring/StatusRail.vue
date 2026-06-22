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
.status-rail{padding:12px 14px;border:1px solid #273846;border-radius:9px;background:#111c25}
.rail-head{display:flex;justify-content:space-between;color:#dce7ee}.rail-head span{color:#8195a6;font-size:12px}
.rail-line{position:relative;height:74px;margin-top:12px;border-top:2px solid #314654}
.rail-event{position:absolute;top:-7px;display:grid;justify-items:center;gap:5px;max-width:110px;padding:0;border:0;background:transparent;color:#9eb0be;transform:translateX(-50%);cursor:pointer}
.rail-event i{width:12px;height:12px;border:3px solid #111c25;border-radius:50%;background:#71808d;box-shadow:0 0 0 1px #71808d}.rail-event span{overflow:hidden;max-width:105px;text-overflow:ellipsis;white-space:nowrap;font-size:11px}
.rail-event.warning i{background:#e7a23b;box-shadow:0 0 0 1px #e7a23b}.rail-event.alarm i{background:#e05252;box-shadow:0 0 0 1px #e05252}.rail-event.recovered i{background:#32b67a;box-shadow:0 0 0 1px #32b67a}
.rail-empty{padding-top:18px;color:#718696;text-align:center;font-size:12px}
</style>
