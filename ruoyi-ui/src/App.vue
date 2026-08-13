<template>
  <div id="app">
    <router-view />
  </div>
</template>

<script>
export default {
  name: "App",
  created() {
    document.documentElement.setAttribute('data-theme', 'industrial')
  }
}
</script>
