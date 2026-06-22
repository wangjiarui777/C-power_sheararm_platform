import Vue from 'vue'
import Vuex from 'vuex'
import app from './modules/app'
import lock from './modules/lock'
import dict from './modules/dict'
import user from './modules/user'
import tagsView from './modules/tagsView'
import permission from './modules/permission'
import settings from './modules/settings'
import monitoring from './modules/monitoring'
import getters from './getters'

Vue.use(Vuex)

const store = new Vuex.Store({
  modules: {
    app,
    lock,
    dict,
    user,
    tagsView,
    permission,
    settings,
    monitoring
  },
  getters
})

export default store
