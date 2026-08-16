import request from '@/utils/request'

export function bootstrapCsrf() {
  return request({ url: '/csrf', method: 'get' }).then(res => {
    // 开发代理可能不会把后端的 XSRF-TOKEN Cookie 写回浏览器，
    // 因此将 /csrf 返回的令牌显式放入 Axios 默认请求头。
    if (res && res.token) {
      const headerName = res.headerName || 'X-XSRF-TOKEN'
      request.defaults.headers.common[headerName] = res.token
    }
    return res
  })
}

// 登录方法
export function login(username, password, code, uuid) {
  const data = {
    username,
    password,
    code,
    uuid
  }
  return request({
    url: '/login',
    headers: {
      isToken: false,
      repeatSubmit: false
    },
    method: 'post',
    data: data
  })
}

// 获取用户详细信息
export function getInfo() {
  return request({
    url: '/getInfo',
    method: 'get'
  })
}

// 解锁屏幕
export function unlockScreen(password) {
  return request({
    url: '/unlockscreen',
    method: 'post',
    data: { password }
  })
}

// 退出方法
export function logout() {
  return request({
    url: '/logout',
    method: 'post'
  })
}

// 获取验证码
export function getCodeImg() {
  return request({
    url: '/captchaImage',
    headers: {
      isToken: false
    },
    method: 'get',
    timeout: 20000
  })
}
