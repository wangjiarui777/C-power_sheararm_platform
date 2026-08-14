import Vue from 'vue'
import SvgIcon from '@/components/SvgIcon'

Vue.component('svg-icon', SvgIcon)

const sprite = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
sprite.setAttribute('aria-hidden', 'true')
sprite.setAttribute('focusable', 'false')
sprite.style.position = 'absolute'
sprite.style.width = '0'
sprite.style.height = '0'
sprite.style.overflow = 'hidden'

function registerIcon(key, moduleValue) {
  const source = typeof moduleValue === 'string' ? moduleValue : moduleValue.default
  const parsed = new DOMParser().parseFromString(source, 'image/svg+xml')
  const root = parsed.documentElement
  if (!root || root.nodeName.toLowerCase() !== 'svg' || parsed.querySelector('parsererror')) return

  root.querySelectorAll('script,foreignObject,iframe,object,embed').forEach(node => node.remove())
  root.querySelectorAll('*').forEach(node => {
    Array.from(node.attributes).forEach(attribute => {
      const name = attribute.name.toLowerCase()
      const value = attribute.value.trim().toLowerCase()
      if (name.startsWith('on') || ((name === 'href' || name === 'xlink:href') && !value.startsWith('#'))) {
        node.removeAttribute(attribute.name)
      }
    })
  })

  const symbol = document.createElementNS('http://www.w3.org/2000/svg', 'symbol')
  symbol.id = `icon-${key.replace(/^\.\//, '').replace(/\.svg$/, '')}`
  const viewBox = root.getAttribute('viewBox')
  if (viewBox) {
    symbol.setAttribute('viewBox', viewBox)
  } else {
    // 旧版图标通常只有 width/height（例如 128×128），
    // 若直接回退到 1024×1024，图形会缩小到几乎不可见。
    const dimension = value => {
      const match = String(value || '').match(/^\s*([0-9]+(?:\.[0-9]+)?)/)
      return match ? Number(match[1]) : 0
    }
    const width = dimension(root.getAttribute('width'))
    const height = dimension(root.getAttribute('height'))
    symbol.setAttribute('viewBox', width > 0 && height > 0
      ? `0 0 ${width} ${height}`
      : '0 0 1024 1024')
  }
  Array.from(root.childNodes).forEach(node => symbol.appendChild(document.importNode(node, true)))
  sprite.appendChild(symbol)
}

const icons = require.context('./svg', false, /\.svg$/)
icons.keys().forEach(key => registerIcon(key, icons(key)))
document.body.appendChild(sprite)
