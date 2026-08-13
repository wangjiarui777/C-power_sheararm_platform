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
  symbol.setAttribute('viewBox', root.getAttribute('viewBox') || '0 0 1024 1024')
  Array.from(root.childNodes).forEach(node => symbol.appendChild(document.importNode(node, true)))
  sprite.appendChild(symbol)
}

const icons = require.context('./svg', false, /\.svg$/)
icons.keys().forEach(key => registerIcon(key, icons(key)))
document.body.appendChild(sprite)
