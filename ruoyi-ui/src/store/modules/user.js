import store from '@/store'
import router from '@/router'
import cache from '@/plugins/cache'
import MessageBox from 'element-ui/lib/message-box'
import { bootstrapCsrf, login, logout, getInfo } from '@/api/login'
import { isHttp, isEmpty } from "@/utils/validate"
import defAva from '@/assets/images/profile.jpg'

const user = {
  state: {
    authenticated: false,
    passwordChangeRequired: false,
    id: '',
    name: '',
    nickName: '',
    avatar: '',
    roles: [],
    permissions: []
  },

  mutations: {
    SET_AUTHENTICATED: (state, authenticated) => {
      state.authenticated = authenticated
    },
    SET_PASSWORD_CHANGE_REQUIRED: (state, required) => {
      state.passwordChangeRequired = required
    },
    SET_ID: (state, id) => {
      state.id = id
    },
    SET_NAME: (state, name) => {
      state.name = name
    },
    SET_NICK_NAME: (state, nickName) => {
      state.nickName = nickName
    },
    SET_AVATAR: (state, avatar) => {
      state.avatar = avatar
    },
    SET_ROLES: (state, roles) => {
      state.roles = roles
    },
    SET_PERMISSIONS: (state, permissions) => {
      state.permissions = permissions
    }
  },

  actions: {
    // 登录
    Login({ commit }, userInfo) {
      const username = userInfo.username.trim()
      const password = userInfo.password
      const code = userInfo.code
      const uuid = userInfo.uuid
      return new Promise((resolve, reject) => {
        bootstrapCsrf().then(() => login(username, password, code, uuid)).then(() => {
          commit('SET_AUTHENTICATED', true)
          // 登录成功后强制重新拉取用户信息和动态路由，避免上一个账号
          // 留下的角色/权限状态绕过首次改密门禁并触发 428。
          commit('SET_PASSWORD_CHANGE_REQUIRED', false)
          commit('SET_ROLES', [])
          commit('SET_PERMISSIONS', [])
          store.dispatch('lock/unlockScreen')
          resolve()
        }).catch(error => {
          reject(error)
        })
      })
    },

    // 获取用户信息
    GetInfo({ commit, state }) {
      return new Promise((resolve, reject) => {
        getInfo().then(res => {
          commit('SET_AUTHENTICATED', true)
          commit('SET_PASSWORD_CHANGE_REQUIRED', Boolean(res.passwordChangeRequired))
          const user = res.user
          let avatar = user.avatar || ""
          if (!isHttp(avatar)) {
            avatar = (isEmpty(avatar)) ? defAva : process.env.VUE_APP_BASE_API + avatar
          }
          if (res.roles && res.roles.length > 0) { // 验证返回的roles是否是一个非空数组
            commit('SET_ROLES', res.roles)
            commit('SET_PERMISSIONS', res.permissions)
          } else {
            commit('SET_ROLES', ['ROLE_DEFAULT'])
          }
          commit('SET_ID', user.userId)
          commit('SET_NAME', user.userName)
          commit('SET_NICK_NAME', user.nickName)
          commit('SET_AVATAR', avatar)
          cache.session.set('pwrChrtype', res.pwdChrtype)
          /* 初始密码提示 */
          if(res.passwordChangeRequired) {
            MessageBox.alert('首次登录或密码已被管理员重置，请先修改密码。', '安全提示', {
              confirmButtonText: '去修改',
              closeOnClickModal: false,
              closeOnPressEscape: false
            }).catch(() => {})
          }
          /* 过期密码提示 */
          if(!res.passwordChangeRequired && res.isPasswordExpired) {
            MessageBox.confirm('您的密码已过期，请尽快修改密码！',  '安全提示', {  confirmButtonText: '确定',  cancelButtonText: '取消',  type: 'warning' }).then(() => {
              router.push({ name: 'Profile', params: { activeTab: 'resetPwd' } })
            }).catch(() => {})
          }
          resolve(res)
        }).catch(error => {
          reject(error)
        })
      })
    },

    // 退出系统
    LogOut({ commit, state }) {
      return new Promise((resolve, reject) => {
        logout().then(() => {
          commit('SET_AUTHENTICATED', false)
          commit('SET_PASSWORD_CHANGE_REQUIRED', false)
          commit('SET_ROLES', [])
          commit('SET_PERMISSIONS', [])
          resolve()
        }).catch(error => {
          reject(error)
        })
      })
    },

    // 前端 登出
    FedLogOut({ commit }) {
      return new Promise(resolve => {
        commit('SET_AUTHENTICATED', false)
        commit('SET_PASSWORD_CHANGE_REQUIRED', false)
        commit('SET_ID', '')
        commit('SET_NAME', '')
        commit('SET_NICK_NAME', '')
        commit('SET_AVATAR', '')
        commit('SET_ROLES', [])
        commit('SET_PERMISSIONS', [])
        resolve()
      })
    }
  }
}

export default user
