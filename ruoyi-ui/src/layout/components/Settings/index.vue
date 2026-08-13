<template>
  <el-drawer size="280px" :visible="showSettings" :with-header="false" :append-to-body="true" :before-close="closeSetting" :lock-scroll="false">
    <div class="drawer-container">
      <div>
        <div class="setting-drawer-content">
          <div class="setting-drawer-title">
            <h3 class="drawer-title">菜单导航设置</h3>
          </div>
          <div class="nav-wrap">
            <el-tooltip content="左侧菜单" placement="bottom">
              <div class="item left" @click="handleNavType(1)" :class="{ activeItem: navType == 1 }">
                <b></b><b></b>
              </div>
            </el-tooltip>

            <el-tooltip content="混合菜单" placement="bottom">
              <div class="item mix" @click="handleNavType(2)" :class="{ activeItem: navType == 2 }">
                <b></b><b></b>
              </div>
            </el-tooltip>
            <el-tooltip content="顶部菜单" placement="bottom">
              <div class="item top" @click="handleNavType(3)" :class="{ activeItem: navType == 3 }">
                <b></b><b></b>
              </div>
            </el-tooltip>
          </div>
          <div class="setting-theme-note">
            <i class="el-icon-monitor"></i>
            <div>
              <strong>工业深色外观</strong>
              <span>已为长时间监测和值守场景统一启用</span>
            </div>
          </div>
        </div>

        <el-divider/>

        <h3 class="drawer-title">系统布局配置</h3>

        <div class="drawer-item">
          <span>开启页签</span>
          <el-switch v-model="tagsView" class="drawer-switch" />
        </div>

        <div class="drawer-item">
          <span>持久化标签页</span>
          <el-switch v-model="tagsViewPersist" :disabled="!tagsView" class="drawer-switch" />
        </div>

        <div class="drawer-item">
          <span>显示页签图标</span>
          <el-switch v-model="tagsIcon" :disabled="!tagsView" class="drawer-switch" />
        </div>

        <div class="drawer-item">
          <span>标签页样式</span>
          <el-radio-group v-model="tagsViewStyle" :disabled="!tagsView" size="mini" class="drawer-switch">
            <el-radio-button label="card">卡片</el-radio-button>
            <el-radio-button label="chrome">谷歌</el-radio-button>
          </el-radio-group>
        </div>

        <div class="drawer-item">
          <span>固定 Header</span>
          <el-switch v-model="fixedHeader" class="drawer-switch" />
        </div>

        <div class="drawer-item">
          <span>显示 Logo</span>
          <el-switch v-model="sidebarLogo" class="drawer-switch" />
        </div>

        <div class="drawer-item">
          <span>动态标题</span>
          <el-switch v-model="dynamicTitle" class="drawer-switch" />
        </div>

        <div class="drawer-item">
          <span>底部版权</span>
          <el-switch v-model="footerVisible" class="drawer-switch" />
        </div>

        <el-divider/>

        <el-button size="small" type="primary" plain icon="el-icon-document-add" @click="saveSetting">保存配置</el-button>
        <el-button size="small" plain icon="el-icon-refresh" @click="resetSetting">重置配置</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script>
export default {
  expose: ['openSetting'],
  data() {
    return {
      navType: this.$store.state.settings.navType,
      showSettings: false
    }
  },
  computed: {
    fixedHeader: {
      get() {
        return this.$store.state.settings.fixedHeader
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'fixedHeader',
          value: val
        })
      }
    },
    tagsViewPersist: {
      get() {
        return this.$store.state.settings.tagsViewPersist
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'tagsViewPersist',
          value: val
        })
      }
    },
    tagsView: {
      get() {
        return this.$store.state.settings.tagsView
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'tagsView',
          value: val
        })
      }
    },
    tagsIcon: {
      get() {
        return this.$store.state.settings.tagsIcon
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'tagsIcon',
          value: val
        })
      }
    },
    tagsViewStyle: {
      get() {
        return this.$store.state.settings.tagsViewStyle
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'tagsViewStyle',
          value: val
        })
      }
    },
    sidebarLogo: {
      get() {
        return this.$store.state.settings.sidebarLogo
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'sidebarLogo',
          value: val
        })
      }
    },
    dynamicTitle: {
      get() {
        return this.$store.state.settings.dynamicTitle
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'dynamicTitle',
          value: val
        })
        this.$store.dispatch('settings/setTitle', this.$store.state.settings.title)
      }
    },
    footerVisible: {
      get() {
        return this.$store.state.settings.footerVisible
      },
      set(val) {
        this.$store.dispatch('settings/changeSetting', {
          key: 'footerVisible',
          value: val
        })
      }
    }
  },
  watch: {
    navType: {
      handler(val) {
        if (val == 1) {
          this.$store.dispatch("app/toggleSideBarHide", false)
        }
        if (val == 2) {
        }
        if (val == 3) {
          this.$store.dispatch("app/toggleSideBarHide", true)
        }
        if ([1, 3].includes(val)) {
          this.$store.commit("SET_SIDEBAR_ROUTERS",this.$store.state.permission.defaultRoutes)
        }
      },
      immediate: true,
      deep: true
    }
  },
  methods: {
    handleNavType(val) {
      this.$store.dispatch('settings/changeSetting', {
        key: 'navType',
        value: val
      })
      this.navType = val
    },
    openSetting() {
      this.showSettings = true
    },
    closeSetting(){
      this.showSettings = false
    },
    saveSetting() {
      this.$modal.loading("正在保存到本地，请稍候...")
      if (!this.tagsViewPersist) {
        this.$cache.local.remove('tags-view-visited')
      }
      this.$cache.local.set(
        "layout-setting",
        `{
            "navType":${this.navType},
            "tagsView":${this.tagsView},
            "tagsIcon":${this.tagsIcon},
            "tagsViewStyle":"${this.tagsViewStyle}",
            "tagsViewPersist":${this.tagsViewPersist},
            "fixedHeader":${this.fixedHeader},
            "sidebarLogo":${this.sidebarLogo},
            "dynamicTitle":${this.dynamicTitle},
            "footerVisible":${this.footerVisible}
          }`
      )
      setTimeout(this.$modal.closeLoading(), 1000)
    },
    resetSetting() {
      this.$modal.loading("正在清除设置缓存并刷新，请稍候...")
      this.$cache.local.remove('tags-view-visited')
      this.$cache.local.remove("layout-setting")
      setTimeout("window.location.reload()", 1000)
    }
  }
}
</script>

<style lang="scss" scoped>
.setting-drawer-content {
  .setting-drawer-title {
    margin-bottom: 12px;
    color: var(--color-heading);
    font-size: 14px;
    line-height: 22px;
    font-weight: bold;
  }

}

.drawer-container {
  padding: 20px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;

  .drawer-title {
    margin-bottom: 12px;
    color: var(--color-heading);
    font-size: 14px;
    line-height: 22px;
  }

  .drawer-item {
    color: var(--color-text);
    font-size: 14px;
    padding: 12px 0;
  }

  .drawer-switch {
    float: right
  }
}

// 导航模式
.nav-wrap {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  margin-top: 10px;
  margin-bottom: 20px;

  .activeItem {
    border: 2px solid var(--color-accent) !important;
  }

  .item {
    position: relative;
    margin-right: 16px;
    cursor: pointer;
    width: 56px;
    height: 48px;
    border-radius: 4px;
    background: var(--color-surface-soft);
    border: 2px solid transparent;
  }

  .left {
    b:first-child {
      display: block;
      height: 30%;
      background: var(--color-border);
    }
    b:last-child {
      width: 30%;
      background: var(--color-surface-raised);
      position: absolute;
      height: 100%;
      top: 0;
      border-radius: 4px 0 0 4px;
    }
  }
  .mix {
    b:first-child {
      border-radius: 4px 4px 0 0;
      display: block;
      height: 30%;
      background: var(--color-surface-raised);
    }
    b:last-child {
      width: 30%;
      background: var(--color-surface-raised);
      position: absolute;
      height: 70%;
      border-radius: 0 0 0 4px;
    }
  }
  .top {
    b:first-child {
      display: block;
      height: 30%;
      background: var(--color-surface-raised);
      border-radius: 4px 4px 0 0;
    }
  }
}

.setting-theme-note {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 20px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);

  > i {
    color: var(--color-accent);
    font-size: 22px;
  }

  strong,
  span {
    display: block;
  }

  strong {
    color: var(--color-heading);
  }

  span {
    margin-top: 3px;
    color: var(--color-muted);
    font-size: 12px;
  }
}
</style>
