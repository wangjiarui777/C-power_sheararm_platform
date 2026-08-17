import axios from 'axios'
import Notification from 'element-ui/lib/notification'
import MessageBox from 'element-ui/lib/message-box'
import Message from 'element-ui/lib/message'
import Loading from 'element-ui/lib/loading'
import store from '@/store'
import errorCode from '@/utils/errorCode'
import { tansParams, blobValidate } from "@/utils/chuangli"
import cache from '@/plugins/cache'
import { saveAs } from 'file-saver'
import Cookies from 'js-cookie'

let downloadLoadingInstance
let passwordChangeRedirecting = false
let csrfBootstrapPromise = null
export const RUOYI_REQUEST_ERROR = '__ruoyiRequestError'
export const RUOYI_REQUEST_ERROR_NOTIFIED = '__ruoyiRequestErrorNotified'

const REQUEST_NOTIFICATION_DEDUPE_WINDOW = 1500
const requestNotificationCache = new Map()

export function isRuoyiRequestError(error) {
  return Boolean(error && error[RUOYI_REQUEST_ERROR])
}

export function isRuoyiRequestErrorNotified(error) {
  return Boolean(error && error[RUOYI_REQUEST_ERROR_NOTIFIED])
}

export function getErrorMessage(error, fallback = errorCode['default']) {
  if (!error) return fallback
  if (typeof error === 'string') return error
  const responseData = error.response && error.response.data
  if (responseData && typeof responseData === 'object' && responseData.msg) {
    return String(responseData.msg)
  }
  if (error.message && error.message !== 'Network Error') return String(error.message)
  return fallback
}

function createHandledError(message, source, response) {
  const safeError = new Error(String(message || errorCode['default']))
  safeError[RUOYI_REQUEST_ERROR] = true
  if (source && source.code) safeError.code = source.code
  if (source && source.config) safeError.config = source.config
  if (response) safeError.response = response
  else if (source && source.response) safeError.response = source.response
  return safeError
}

function notifyRequestError(type, message, options = {}) {
  const safeMessage = String(message || errorCode['default'])
  const key = `${type}:${safeMessage}`
  const now = Date.now()
  const previous = requestNotificationCache.get(key)
  if (previous && now - previous < REQUEST_NOTIFICATION_DEDUPE_WINDOW) return
  requestNotificationCache.set(key, now)

  requestNotificationCache.forEach((timestamp, cacheKey) => {
    if (now - timestamp >= REQUEST_NOTIFICATION_DEDUPE_WINDOW) requestNotificationCache.delete(cacheKey)
  })

  if (type === 'notification') {
    Notification.error({ title: safeMessage, ...options })
    return
  }
  Message({ message: safeMessage, type, duration: options.duration || 5 * 1000 })
}

function markNotified(error) {
  if (error) error[RUOYI_REQUEST_ERROR_NOTIFIED] = true
  return error
}

function rejectHandledError(message, source, response, type, options) {
  if (type) notifyRequestError(type, message, options)
  return Promise.reject(markNotified(createHandledError(message, source, response)))
}

function responseMessage(response, fallback) {
  const data = response && response.data
  const code = data && data.code
  return errorCode[code] || (data && data.msg) || fallback || errorCode['default']
}

function showLoginExpiredDialog() {
  if (isRelogin.show) return
  isRelogin.show = true
  MessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', {
    confirmButtonText: '重新登录',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    isRelogin.show = false
    store.dispatch('LogOut').then(() => {
      location.href = '/index'
    }).catch(() => {
      store.dispatch('FedLogOut').finally(() => {
        location.href = '/index'
      })
    })
  }).catch(() => {
    isRelogin.show = false
  })
}

function rejectHttpError(error) {
  if (isRuoyiRequestError(error)) return Promise.reject(error)

  const response = error && error.response
  const status = response ? response.status : null
  const rawMessage = error && error.message ? String(error.message) : ''

  if (status === 428) {
    redirectToPasswordChange()
    const passwordError = createHandledError('PASSWORD_CHANGE_REQUIRED', error, response)
    passwordError.passwordChangeRequired = true
    return Promise.reject(markNotified(passwordError))
  }
  if (status === 401) {
    showLoginExpiredDialog()
    return Promise.reject(markNotified(createHandledError('无效的会话，或者会话已过期，请重新登录。', error, response)))
  }
  if (status === 403) {
    return rejectHandledError(errorCode['403'] || '当前操作没有权限', error, response, 'warning', { duration: 5000 })
  }

  let message = rawMessage || errorCode['default']
  if (rawMessage === 'Network Error') {
    message = '后端接口连接异常'
  } else if (/timeout/i.test(rawMessage)) {
    message = '系统接口请求超时'
  } else if (status) {
    message = responseMessage(response, `系统接口${status}异常`)
  }
  return rejectHandledError(message, error, response, 'error')
}

// The login page bootstraps CSRF once, but a browser can restore a valid
// RUOYI_SESSION after a full-page refresh without restoring Axios' in-memory
// header.  Spring Security then rejects the first role/user/menu mutation with
// a bare 403.  Refresh the token lazily for every state-changing request so
// this does not depend on the login page having been visited in this tab.
function ensureCsrfHeader(config) {
  const method = String(config.method || 'get').toLowerCase()
  const stateChanging = ['post', 'put', 'patch', 'delete'].includes(method)
  if (!stateChanging || config.url === '/csrf') return Promise.resolve(config)

  const headerName = 'X-XSRF-TOKEN'
  // The cookie is the source of truth.  Axios defaults survive SPA
  // navigation and can contain a token from a previous backend instance;
  // preferring that stale value causes Spring Security to return 403 because
  // the request header no longer matches the current XSRF-TOKEN cookie.
  const cookieToken = Cookies.get('XSRF-TOKEN')
  const existing = cookieToken
    || (config.headers && (config.headers[headerName] || config.headers[headerName.toLowerCase()]))
    || service.defaults.headers.common[headerName]
  if (existing) {
    config.headers = config.headers || {}
    config.headers[headerName] = existing
    return Promise.resolve(config)
  }

  return refreshCsrfToken().then(token => {
    if (token) {
      config.headers = config.headers || {}
      config.headers[headerName] = token
    }
    return config
  })
}

function refreshCsrfToken() {
  if (!csrfBootstrapPromise) {
    csrfBootstrapPromise = service.get('/csrf', {
      headers: { isToken: false, repeatSubmit: false }
    }).then(data => {
      const token = data && data.token ? data.token : Cookies.get('XSRF-TOKEN')
      if (token) service.defaults.headers.common['X-XSRF-TOKEN'] = token
      return token
    }).finally(() => {
      csrfBootstrapPromise = null
    })
  }
  return csrfBootstrapPromise
}

function isStateChanging(config) {
  return ['post', 'put', 'patch', 'delete'].includes(String(config && config.method || 'get').toLowerCase())
}

function shouldRetryCsrf(error) {
  const response = error && error.response
  const config = error && error.config
  if (!response || response.status !== 403 || !config || !isStateChanging(config) || config._csrfRetried) return false

  // Spring's CSRF rejection is normally an empty/non-RuoYi response. A RuoYi
  // permission response already contains an AjaxResult body and should not be
  // retried as a token problem.
  const data = response.data
  return !data || typeof data !== 'object' || typeof data.code === 'undefined'
}

function redirectToPasswordChange() {
  if (passwordChangeRedirecting) return
  passwordChangeRedirecting = true
  MessageBox.alert('首次登录或密码已被管理员重置，请先修改密码。', '安全提示', {
    confirmButtonText: '去修改',
    closeOnClickModal: false,
    closeOnPressEscape: false,
    type: 'warning'
  }).then(() => {
    // 项目使用 history 路由，不能写入 hash；直接导航也能清理旧的
    // 动态路由状态，进入个人中心的修改密码页。
    window.location.assign('/user/profile?activeTab=resetPwd')
  }).catch(() => {}).finally(() => {
    passwordChangeRedirecting = false
  })
}
// 是否显示重新登录
export let isRelogin = { show: false }

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
// 创建axios实例
const service = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: process.env.VUE_APP_BASE_API,
  // 超时（默认 30s，长耗时操作请通过请求配置覆盖）
  timeout: 30000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN'
})

// request拦截器
service.interceptors.request.use(config => {
  return ensureCsrfHeader(config).then(() => {
  // 是否需要防止数据重复提交
  const isRepeatSubmit = (config.headers || {}).repeatSubmit === false
  // 间隔时间(ms)，小于此时间视为重复提交
  const interval = (config.headers || {}).interval || 1000
  // get请求映射params参数
  if (config.method === 'get' && config.params) {
    let url = config.url + '?' + tansParams(config.params)
    url = url.slice(0, -1)
    config.params = {}
    config.url = url
  }
  if (!isRepeatSubmit && (config.method === 'post' || config.method === 'put')) {
    const requestObj = {
      url: config.url,
      data: typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
      time: new Date().getTime()
    }
    const requestSize = Object.keys(JSON.stringify(requestObj)).length // 请求数据大小
    const limitSize = 5 * 1024 * 1024 // 限制存放数据5M
    if (requestSize >= limitSize) {
      console.warn(`[${config.url}]: ` + '请求数据大小超出允许的5M限制，无法进行防重复提交验证。')
      return config
    }
    const sessionObj = cache.session.getJSON('sessionObj')
    if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
      cache.session.setJSON('sessionObj', requestObj)
    } else {
      const s_url = sessionObj.url                  // 请求地址
      const s_data = sessionObj.data                // 请求数据
      const s_time = sessionObj.time                // 请求时间
      if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
        const message = '数据正在处理，请勿重复提交'
        console.warn(`[${s_url}]: ` + message)
        return Promise.reject(new Error(message))
      } else {
        cache.session.setJSON('sessionObj', requestObj)
      }
    }
  }
    return config
  })
}, error => {
    console.error('[request config]', error)
    return rejectHttpError(error)
})

// 响应拦截器
service.interceptors.response.use(res => {
    // 未设置状态码则默认成功状态
    const code = (res.data && res.data.code) || 200
    // 获取错误信息
    const msg = responseMessage(res, errorCode['default'])
    // 二进制数据则直接返回
    if (res.request.responseType ===  'blob' || res.request.responseType ===  'arraybuffer') {
      return res.data
    }
    if (code === 428) {
      redirectToPasswordChange()
      const passwordError = createHandledError('PASSWORD_CHANGE_REQUIRED', null, res)
      passwordError.passwordChangeRequired = true
      return Promise.reject(markNotified(passwordError))
    } else if (code === 401) {
      showLoginExpiredDialog()
      return Promise.reject(markNotified(createHandledError('无效的会话，或者会话已过期，请重新登录。', null, res)))
    } else if (code === 500) {
      return rejectHandledError(msg, null, res, 'error')
    } else if (code === 601) {
      return rejectHandledError(msg, null, res, 'warning')
    } else if (code !== 200) {
      return rejectHandledError(msg, null, res, 'notification')
    } else {
      return res.data
    }
  },
  error => {
    console.error('[request]', error)
    const status = error && error.response ? error.response.status : null
    if (shouldRetryCsrf(error)) {
      const retryConfig = error.config
      retryConfig._csrfRetried = true
      return refreshCsrfToken().then(token => {
        if (!token) return rejectHttpError(error)
        retryConfig.headers = retryConfig.headers || {}
        retryConfig.headers['X-XSRF-TOKEN'] = token
        retryConfig.headers.repeatSubmit = false
        return service(retryConfig)
      }).catch(retryError => {
        if (isRuoyiRequestError(retryError)) return Promise.reject(retryError)
        return rejectHttpError(retryError && retryError.config && retryError.config._csrfRetried ? retryError : error)
      })
    }
    return rejectHttpError(error)
  }
)

// 通用下载方法
export function download(url, params, filename, config) {
  downloadLoadingInstance = Loading.service({ text: "正在下载数据，请稍候", spinner: "el-icon-loading", background: "rgba(0, 0, 0, 0.7)", })
  return service.post(url, params, {
    transformRequest: [(params) => { return tansParams(params) }],
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    responseType: 'blob',
    ...config
  }).then(async (data) => {
    const isBlob = blobValidate(data)
    if (isBlob) {
      const blob = new Blob([data])
      saveAs(blob, filename)
    } else {
      const resText = await data.text()
      const rspObj = JSON.parse(resText)
      const errMsg = errorCode[rspObj.code] || rspObj.msg || errorCode['default']
      Message.error(errMsg)
    }
    downloadLoadingInstance.close()
  }).catch((r) => {
    console.error(r)
    Message.error('下载文件出现错误，请联系管理员！')
    downloadLoadingInstance.close()
  })
}

export default service
