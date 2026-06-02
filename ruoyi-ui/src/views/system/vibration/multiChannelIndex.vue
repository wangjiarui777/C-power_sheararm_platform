<template>
  <div class="page-container">
    <multi-channel-monitor />
  </div>
</template>

<script>
import MultiChannelMonitor from './multiChannelMonitor.vue'

export default {
  name: 'MultiChannelIndex',
  components: {
    MultiChannelMonitor
  }
}
</script>

<style scoped>
.page-container {
  padding: 12px;
}
</style>
