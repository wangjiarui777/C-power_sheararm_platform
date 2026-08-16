<template>
  <div class="navbar" :class="'nav' + navType">
    <hamburger id="hamburger-container" :is-active="sidebar.opened" class="hamburger-container" @toggleClick="toggleSideBar" />

    <breadcrumb v-if="navType == 1" id="breadcrumb-container" class="breadcrumb-container" />
    <top-nav v-if="navType == 2" id="topmenu-container" class="topmenu-container" />
    <template v-if="navType == 3">
      <logo v-show="showLogo" :collapse="false"></logo>
      <top-bar id="topbar-container" class="topbar-container" />
    </template>
    <div class="right-menu">
      <template v-if="device!=='mobile'">
        <search id="header-search" class="right-menu-item" />

        <screenfull id="screenfull" class="right-menu-item hover-effect" />

        <el-tooltip content="布局大小" effect="dark" placement="bottom">
          <size-select id="size-select" class="right-menu-item hover-effect" />
        </el-tooltip>

        <el-tooltip content="PHM告警" effect="dark" placement="bottom">
          <div class="right-menu-item hover-effect phm-alarm-bell" @click="goPhmAlarms">
            <el-badge :value="phmAlarmCount" :hidden="phmAlarmCount === 0" :max="99">
              <i class="el-icon-bell"></i>
            </el-badge>
          </div>
        </el-tooltip>

      </template>

      <el-dropdown class="avatar-container right-menu-item hover-effect" trigger="hover">
        <div class="avatar-wrapper">
          <img :src="avatar" class="user-avatar">
          <span class="user-nickname"> {{ nickName }} </span>
        </div>
        <el-dropdown-menu slot="dropdown">
          <router-link to="/user/profile">
            <el-dropdown-item>个人中心</el-dropdown-item>
          </router-link>
          <el-dropdown-item @click.native="setLayout" v-if="setting">
            <span>布局设置</span>
          </el-dropdown-item>
          <el-dropdown-item @click.native="lockScreen">
            <span>锁定屏幕</span>
          </el-dropdown-item>
          <el-dropdown-item divided @click.native="logout">
            <span>退出登录</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import Breadcrumb from '@/components/Breadcrumb'
import TopNav from './TopNav'
import TopBar from './TopBar'
import Logo from './Sidebar/Logo'
import Hamburger from '@/components/Hamburger'
import Screenfull from '@/components/Screenfull'
import SizeSelect from '@/components/SizeSelect'
import Search from '@/components/HeaderSearch'
import sensorWebSocket from '@/utils/sensor-websocket'
import { listSystemConfig } from '@/api/phm'

export default {
  components: {
    Breadcrumb,
    Logo,
    TopNav,
    TopBar,
    Hamburger,
    Screenfull,
    SizeSelect,
    Search
  },
  data() {
    return {
      unsubscribePhmAlarm: null,
      phmAlarmCount: 0,
      phmReconnectTimer: null,
      phmAlarmSoundEnabled: true
    }
  },
  computed: {
    ...mapGetters([
      'sidebar',
      'avatar',
      'device',
      'nickName',
      'passwordChangeRequired'
    ]),
    setting: {
      get() {
        return this.$store.state.settings.showSettings
      }
    },
    navType: {
      get() {
        return this.$store.state.settings.navType
      }
    },
    showLogo: {
      get() {
        return this.$store.state.settings.sidebarLogo
      }
    }
  },
  created() {
    if (this.passwordChangeRequired) return
    this.loadPhmAlarmSettings()
    this.connectPhmAlarmSocket()
  },
  beforeDestroy() {
    if (this.unsubscribePhmAlarm) {
      this.unsubscribePhmAlarm()
      this.unsubscribePhmAlarm = null
    }
    if (this.phmReconnectTimer) {
      clearTimeout(this.phmReconnectTimer)
      this.phmReconnectTimer = null
    }
  },
  methods: {
    async loadPhmAlarmSettings() {
      try {
        const res = await listSystemConfig()
        const configs = res.data || []
        const soundConfig = configs.find(item => item.configKey === 'alarm.sound.enabled')
        if (soundConfig) {
          this.phmAlarmSoundEnabled = String(soundConfig.configValue).toLowerCase() !== 'false'
        }
      } catch (error) {
        this.phmAlarmSoundEnabled = true
      }
    },
    connectPhmAlarmSocket() {
      this.unsubscribePhmAlarm = sensorWebSocket.subscribe((event, payload) => {
        if (event === 'open') {
          sensorWebSocket.send({ type: 'subscribe', channel: 'phm_alarm' })
          return
        }
        if (event === 'close') {
          this.schedulePhmReconnect()
          return
        }
        if (event !== 'message' || !payload || payload.type !== 'phm_alarm') return
        const detail = this.parseAlarmPayload(payload)
        if (payload.event === 'changed') {
          this.$notify.success({
            title: 'PHM告警已更新',
            message: `${detail.deviceName || detail.deviceCode || '设备'}：${this.phmAlarmStatusText(detail.status)}`,
            duration: 3600
          })
          return
        }
        this.phmAlarmCount += 1
        this.playPhmAlarmSound()
        this.$notify.warning({
          title: 'PHM告警',
          message: `${detail.deviceName || detail.deviceCode || '设备'}：${detail.diagnosisResult || payload.diagnosisResult || '产生新告警'}`,
          duration: 6000
        })
      })
      sensorWebSocket.connect('/ws/sensor')
      sensorWebSocket.send({ type: 'subscribe', channel: 'phm_alarm' })
    },
    schedulePhmReconnect() {
      if (this.phmReconnectTimer) return
      this.phmReconnectTimer = setTimeout(() => {
        this.phmReconnectTimer = null
        sensorWebSocket.connect('/ws/sensor')
        sensorWebSocket.send({ type: 'subscribe', channel: 'phm_alarm' })
      }, 3000)
    },
    parseAlarmPayload(payload) {
      try {
        return typeof payload.message === 'string' ? JSON.parse(payload.message) : (payload.message || {})
      } catch (e) {
        return {}
      }
    },
    phmAlarmStatusText(status) {
      return { handled: '已处理', ignored: '已忽略', unhandled: '未处理' }[status] || '状态已变更'
    },
    playPhmAlarmSound() {
      if (!this.phmAlarmSoundEnabled || typeof window === 'undefined') return
      try {
        const AudioContext = window.AudioContext || window.webkitAudioContext
        if (!AudioContext) return
        const context = new AudioContext()
        const oscillator = context.createOscillator()
        const gain = context.createGain()
        oscillator.type = 'sine'
        oscillator.frequency.value = 880
        gain.gain.setValueAtTime(0.0001, context.currentTime)
        gain.gain.exponentialRampToValueAtTime(0.08, context.currentTime + 0.02)
        gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.22)
        oscillator.connect(gain)
        gain.connect(context.destination)
        oscillator.start()
        oscillator.stop(context.currentTime + 0.24)
        oscillator.onended = () => context.close()
      } catch (error) {
        // Browser autoplay policies may block sound before user interaction.
      }
    },
    goPhmAlarms() {
      this.phmAlarmCount = 0
      this.$router.push('/phm/alarms')
    },
    toggleSideBar() {
      this.$store.dispatch('app/toggleSideBar')
    },
    setLayout(event) {
      this.$emit('setLayout')
    },
    lockScreen() {
      const currentPath = this.$route.fullPath
      this.$store.dispatch('lock/lockScreen', currentPath).then(() => {
        this.$router.push('/lock')
      })
    },
    logout() {
      this.$confirm('确定注销并退出系统吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        this.$store.dispatch('LogOut').then(() => {
          location.href = '/index'
        })
      }).catch(() => {})
    }
  }
}
</script>

<style lang="scss" scoped>
.navbar.nav3 {
  .hamburger-container {
    display: none !important;
  }
}

.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  background: rgba(17, 28, 48, .96);
  border-bottom: 1px solid var(--color-border);
  box-shadow: none;
  display: flex;
  align-items: center;
  // padding: 0 8px;
  box-sizing: border-box;

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    cursor: pointer;
    transition: background .3s;
    -webkit-tap-highlight-color:transparent;
    display: flex;
    align-items: center;
    flex-shrink: 0;
    margin-right: 8px;

    &:hover {
      background: rgba(0, 0, 0, .025)
    }
  }

  .breadcrumb-container {
    flex-shrink: 0;
  }

  .topmenu-container {
    position: absolute;
    left: 50px;
  }

  .topbar-container {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    overflow: hidden;
    margin-left: 8px;
  }

  .right-menu {
    height: 100%;
    line-height: 50px;
    display: flex;
    align-items: center;
    margin-left: auto;

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-block;
      padding: 0 8px;
      height: 100%;
      font-size: 18px;
      color: var(--color-text);
      vertical-align: text-bottom;

      &.hover-effect {
        cursor: pointer;
        transition: background .3s;

        &:hover {
          background: var(--color-accent-soft)
        }
      }
    }

    .phm-alarm-bell {
      display: flex;
      align-items: center;
      justify-content: center;

      .el-icon-bell {
        font-size: 19px;
        color: var(--color-text);
      }
    }

    .avatar-container {
      margin-right: 0px;
      padding-right: 0px;

      .avatar-wrapper {
        margin-top: 10px;
        right: 8px;
        position: relative;

        .user-avatar {
          cursor: pointer;
          width: 30px;
          height: 30px;
          border-radius: 50%;
        }

        .user-nickname{
          position: relative;
          bottom: 10px;
          left: 2px;
          font-size: 14px;
          font-weight: bold;
          color: var(--color-text);
        }

        .el-icon-caret-bottom {
          cursor: pointer;
          position: absolute;
          right: -20px;
          top: 25px;
          font-size: 12px;
        }
      }
    }
  }
}
</style>
