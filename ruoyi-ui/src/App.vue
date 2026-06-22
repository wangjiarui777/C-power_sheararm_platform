<template>
  <div id="app">
    <router-view />
    <theme-picker />
  </div>
</template>

<script>
import ThemePicker from "@/components/ThemePicker"

export default {
  name: "App",
  components: { ThemePicker },
  computed: {
    appearanceMode() {
      return this.$store.state.settings.appearanceMode
    }
  },
  watch: {
    appearanceMode: {
      immediate: true,
      handler(value) {
        document.documentElement.setAttribute('data-theme', value || 'industrial')
        this.$nextTick(() => {
          window.dispatchEvent(new CustomEvent('appearance-mode-change', { detail: value }))
        })
      }
    }
  }
}
</script>
<style scoped>
#app .theme-picker {
  display: none;
}
</style>
