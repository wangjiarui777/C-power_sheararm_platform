import Cookies from 'js-cookie'

export function getCsrfHeaders() {
  const token = Cookies.get('XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': token } : {}
}
