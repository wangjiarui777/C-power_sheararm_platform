<template>
  <div class="login">
    <div class="login-stage">
      <!-- 品牌区：平台身份 + 振动信号母题 -->
      <section class="brand-panel">
        <div class="brand-head">
          <span class="brand-eyebrow">PHM / CONDITION MONITORING</span>
          <h1 class="brand-title">{{ title }}</h1>
          <p class="brand-subtitle">振动监测、模型诊断与告警处置。</p>
        </div>
      </section>

      <!-- 登录表单区 -->
      <section class="form-panel">
        <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="auth-form">
          <h3 class="title">账号登录</h3>
          <p class="form-hint">使用平台账号进入控制台</p>
          <el-form-item prop="username">
            <el-input
              v-model="loginForm.username"
              type="text"
              auto-complete="off"
              placeholder="账号"
            >
              <svg-icon slot="prefix" icon-class="user" class="el-input__icon input-icon" />
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              auto-complete="off"
              placeholder="密码"
              @keyup.enter.native="handleLogin"
            >
              <svg-icon slot="prefix" icon-class="password" class="el-input__icon input-icon" />
            </el-input>
          </el-form-item>
          <el-form-item prop="code" v-if="captchaEnabled">
            <div class="code-row">
              <el-input
                v-model="loginForm.code"
                auto-complete="off"
                placeholder="验证码"
                @keyup.enter.native="handleLogin"
              >
                <svg-icon slot="prefix" icon-class="validCode" class="el-input__icon input-icon" />
              </el-input>
              <div class="login-code">
                <img :src="codeUrl" @click="getCode" class="login-code-img" title="点击刷新验证码" />
              </div>
            </div>
          </el-form-item>
          <el-checkbox v-model="loginForm.rememberMe" style="margin:0px 0px 25px 0px;">记住账号</el-checkbox>
          <el-form-item style="width:100%;">
            <el-button
              :loading="loading"
              size="medium"
              type="primary"
              style="width:100%;"
              @click.native.prevent="handleLogin"
            >
              <span v-if="!loading">登 录</span>
              <span v-else>登 录 中...</span>
            </el-button>
          </el-form-item>
        </el-form>
      </section>
    </div>

    <!--  底部  -->
    <div class="el-login-footer">
      <span>{{ footerContent }}</span>
    </div>
  </div>
</template>

<script>
import { getCodeImg } from "@/api/login"
import Cookies from "js-cookie"
import defaultSettings from '@/settings'
import { getErrorMessage, isRuoyiRequestError } from '@/utils/request'

export default {
  name: "Login",
  data() {
    return {
      title: process.env.VUE_APP_TITLE,
      footerContent: '',
      codeUrl: "",
      loginForm: {
        username: "",
        password: "",
        rememberMe: false,
        code: "",
        uuid: ""
      },
      loginRules: {
        username: [
          { required: true, trigger: "blur", message: "请输入您的账号" }
        ],
        password: [
          { required: true, trigger: "blur", message: "请输入您的密码" }
        ],
        code: [{ required: true, trigger: "change", message: "请输入验证码" }]
      },
      loading: false,
      // 验证码开关
      captchaEnabled: true,
      // 注册开关
      redirect: undefined
    }
  },
  watch: {
    $route: {
      handler: function(route) {
        this.redirect = route.query && route.query.redirect
      },
      immediate: true
    }
  },
  created() {
    this.getCode()
    this.getCookie()
  },
  methods: {
    getCode() {
      getCodeImg().then(res => {
        this.captchaEnabled = res.captchaEnabled === undefined ? true : res.captchaEnabled
        if (this.captchaEnabled) {
          this.codeUrl = "data:image/gif;base64," + res.img
          this.loginForm.uuid = res.uuid
        }
      }).catch(() => {
        // request interceptor already shows the connection error; keep the
        // login page from surfacing an unhandled rejection in dev overlay.
      })
    },
    getCookie() {
      const username = Cookies.get("username")
      const rememberMe = Cookies.get('rememberMe')
      this.loginForm = {
        username: username === undefined ? this.loginForm.username : username,
        password: "",
        rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
      }
    },
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (valid) {
          this.loading = true
          if (this.loginForm.rememberMe) {
            Cookies.set("username", this.loginForm.username, { expires: 30 })
            Cookies.set('rememberMe', this.loginForm.rememberMe, { expires: 30 })
          } else {
            Cookies.remove("username")
            Cookies.remove("password")
            Cookies.remove('rememberMe')
          }
          this.$store.dispatch("Login", this.loginForm).then(() => {
            this.$router.push({ path: this.redirect || "/" }).catch(()=>{})
          }).catch(error => {
            this.loading = false
            // 登录接口的业务错误（验证码、账号或密码错误）由响应拦截器
            // 转换成 Error 后返回；这里必须显示出来，否则用户只会看到
            // 验证码刷新，误以为登录按钮没有响应。
            if (!isRuoyiRequestError(error) && error && error.message) {
              this.$message.error(getErrorMessage(error))
            }
            if (this.captchaEnabled) {
              this.getCode()
            }
          })
        }
      })
    }
  }
}
</script>

<style rel="stylesheet/scss" lang="scss" scoped>
.login {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100%;
  padding: 48px 24px;
}

/* 双栏舞台：品牌区 + 表单区 */
.login-stage {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 400px;
  width: min(860px, 100%);
  min-height: 460px;
  overflow: hidden;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-float);
}

/* ---- 品牌区 ---- */
.brand-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px;
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
}

.brand-eyebrow {
  color: var(--color-accent);
  font-family: var(--font-data);
  font-size: 12px;
  letter-spacing: 0.24em;
}

.brand-title {
  margin: 12px 0 10px;
  color: var(--color-heading);
  font-size: 34px;
  font-weight: 600;
  letter-spacing: 0.02em;
  line-height: 1.25;
}

.brand-subtitle {
  margin: 0;
  max-width: 28ch;
  color: var(--color-muted);
  font-size: 14px;
  line-height: 1.6;
}

/* ---- 表单区 ---- */
.form-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 40px;
  background: var(--color-surface-soft);
}

.auth-form {
  width: 100%;

  .el-input {
    height: 40px;

    input {
      height: 40px;
    }
  }

  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 2px;
  }
}

.title {
  margin: 0 0 8px;
  color: var(--color-heading);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.form-hint {
  margin: 0 0 26px;
  color: var(--color-muted);
  font-size: 13px;
}

.code-row {
  display: flex;
  gap: 10px;

  .el-input {
    flex: 1;
    min-width: 0;
  }
}

.login-code {
  flex: none;
  width: 108px;
  height: 40px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface-soft);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    cursor: pointer;
    vertical-align: middle;
  }
}

.login-code-img {
  height: 40px;
}

.el-login-footer {
  position: fixed;
  bottom: 0;
  width: 100%;
  height: 40px;
  line-height: 40px;
  text-align: center;
  color: var(--color-muted);
  font-family: Arial, sans-serif;
  font-size: 12px;
  letter-spacing: 1px;
}

/* 小屏：折叠品牌区，聚焦登录任务 */
@media (max-width: 900px) {
  .login {
    padding: 28px 16px;
  }

  .login-stage {
    grid-template-columns: minmax(0, 1fr);
    min-height: 0;
  }

  .brand-panel {
    gap: 14px;
    padding: 28px 26px 22px;
    border-right: none;
    border-bottom: 1px solid var(--color-border);
  }

  .brand-title {
    font-size: 24px;
  }

  .form-panel {
    padding: 30px 26px 26px;
  }
}

</style>
