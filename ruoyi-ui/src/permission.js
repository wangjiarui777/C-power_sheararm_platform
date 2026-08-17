import router from './router'
import store from './store'
import Message from 'element-ui/lib/message'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { isPathMatch } from '@/utils/validate'
import { getErrorMessage, isRelogin, isRuoyiRequestError } from '@/utils/request'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login']

const isWhiteList = (path) => {
  return whiteList.some(pattern => isPathMatch(pattern, path))
}

const passwordChangeRoute = () => ({
  name: 'Profile',
  params: { activeTab: 'resetPwd' },
  replace: true
})

const requiresPasswordRedirect = (to) => {
  return store.getters.passwordChangeRequired && to.name !== 'Profile'
}

const isPasswordChangeError = (error) => {
  return error && (error.message === 'PASSWORD_CHANGE_REQUIRED'
    || (error.response && error.response.status === 428))
}

router.beforeEach((to, from, next) => {
  NProgress.start()
  if (store.getters.authenticated || store.getters.roles.length > 0) {
    to.meta.title && store.dispatch('settings/setTitle', to.meta.title)
    const isLock = store.getters.isLock
    /* has token*/
    if (to.path === '/login') {
      next({ path: '/' })
      NProgress.done()
    } else if (isWhiteList(to.path)) {
      next()
    } else if (isLock && to.path !== '/lock') {
      next({ path: '/lock' })
      NProgress.done()
    } else if (!isLock && to.path === '/lock') {
      next({ path: '/' })
      NProgress.done()
    } else {
      if (store.getters.roles.length === 0) {
        isRelogin.show = true
        // 判断当前用户是否已拉取完user_info信息
        store.dispatch('GetInfo').then(() => {
          isRelogin.show = false
          if (store.getters.passwordChangeRequired) {
            next(requiresPasswordRedirect(to) ? passwordChangeRoute() : { ...to, replace: true })
            return
          }
          store.dispatch('GenerateRoutes').then(accessRoutes => {
            // 根据roles权限生成可访问的路由表
            router.addRoutes(accessRoutes) // 动态添加可访问路由表
            next(requiresPasswordRedirect(to) ? passwordChangeRoute() : { ...to, replace: true })
          }).catch(err => {
            if (isPasswordChangeError(err)) {
              next(passwordChangeRoute())
              return
            }
            store.dispatch('LogOut').then(() => {
              if (!isRuoyiRequestError(err)) Message.error(getErrorMessage(err))
              next({ path: '/' })
            }).catch(() => {
              next({ path: '/' })
            })
          })
        }).catch(err => {
            store.dispatch('LogOut').then(() => {
              if (!isRuoyiRequestError(err)) Message.error(getErrorMessage(err))
              next({ path: '/' })
            }).catch(() => {
              next({ path: '/' })
            })
          })
      } else if (requiresPasswordRedirect(to)) {
        next(passwordChangeRoute())
      } else {
        next()
      }
    }
  } else if (!isWhiteList(to.path)) {
    isRelogin.show = true
    store.dispatch('GetInfo').then(() => {
      isRelogin.show = false
      if (store.getters.passwordChangeRequired) {
        next(requiresPasswordRedirect(to) ? passwordChangeRoute() : { ...to, replace: true })
        return
      }
      store.dispatch('GenerateRoutes').then(accessRoutes => {
        router.addRoutes(accessRoutes)
        next(requiresPasswordRedirect(to) ? passwordChangeRoute() : { ...to, replace: true })
      }).catch(err => {
        if (isPasswordChangeError(err)) {
          next(passwordChangeRoute())
          return
        }
        isRelogin.show = false
        next(`/login?redirect=${encodeURIComponent(to.fullPath)}`)
      })
    }).catch(() => {
      isRelogin.show = false
      next(`/login?redirect=${encodeURIComponent(to.fullPath)}`)
      NProgress.done()
    })
  } else {
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      next()
    } else {
      next(`/login?redirect=${encodeURIComponent(to.fullPath)}`) // 否则全部重定向到登录页
      NProgress.done()
    }
  }
})

router.afterEach(() => {
  NProgress.done()
})
