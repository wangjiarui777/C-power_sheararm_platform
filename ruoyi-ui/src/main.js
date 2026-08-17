import Vue from 'vue'

import './plugins/element'
import './assets/styles/element-variables.scss'

import '@/assets/styles/index.scss' // global css
import '@/assets/styles/ruoyi.scss' // RuoYi base layout css
import '@/assets/styles/industrial-theme.scss' // unified industrial theme
import App from './App'
import store from './store'
import router from './router'
import directive from './directive' // directive
import plugins from './plugins' // plugins
import { download, isRuoyiRequestError } from '@/utils/request'

import './assets/icons' // icon
import './permission' // permission control
import { getDicts } from "@/api/system/dict/data"
import { getConfigKey } from "@/api/system/config"
import { parseTime, resetForm, addDateRange, selectDictLabel, selectDictLabels, handleTree } from "@/utils/ruoyi"
// 分页组件
import Pagination from "@/components/Pagination"
// 自定义表格工具组件
import RightToolbar from "@/components/RightToolbar"
// 字典标签组件
import DictTag from '@/components/DictTag'
// 字典数据组件
import DictData from '@/components/DictData'

// 全局方法挂载
Vue.prototype.getDicts = getDicts
Vue.prototype.getConfigKey = getConfigKey
Vue.prototype.parseTime = parseTime
Vue.prototype.resetForm = resetForm
Vue.prototype.addDateRange = addDateRange
Vue.prototype.selectDictLabel = selectDictLabel
Vue.prototype.selectDictLabels = selectDictLabels
Vue.prototype.download = download
Vue.prototype.handleTree = handleTree

// 全局组件挂载
Vue.component('DictTag', DictTag)
Vue.component('Pagination', Pagination)
Vue.component('RightToolbar', RightToolbar)
Vue.component('FileUpload', () => import(/* webpackChunkName: "component-file-upload" */ '@/components/FileUpload'))
Vue.component('ImageUpload', () => import(/* webpackChunkName: "component-image-upload" */ '@/components/ImageUpload'))
Vue.component('ImagePreview', () => import(/* webpackChunkName: "component-image-preview" */ '@/components/ImagePreview'))

Vue.use(directive)
Vue.use(plugins)
DictData.install()

/**
 * If you don't want to use mock-server
 * you want to use MockJs for mock api
 * you can execute: mockXHR()
 *
 * Currently MockJs will be used in the production environment,
 * please remove it before going online! ! !
 */

Vue.config.productionTip = false

// 428 是服务端的首次改密门禁，组件在切换到个人中心的瞬间可能仍有
// 一个并发请求返回该状态；它不是应用崩溃，不应被 webpack overlay 显示。
window.addEventListener('unhandledrejection', event => {
  const reason = event && event.reason
  if (reason && (isRuoyiRequestError(reason)
    || reason.passwordChangeRequired
    || reason.message === 'PASSWORD_CHANGE_REQUIRED')) {
    event.preventDefault()
  }
})

new Vue({
  el: '#app',
  router,
  store,
  render: h => h(App)
})
