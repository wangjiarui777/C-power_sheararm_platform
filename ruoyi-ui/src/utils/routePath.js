// Browser-safe equivalent of the small subset of node:path used by the router UI.
// Using node's path-browserify here evaluates process.cwd() in the dev bundle,
// which is not available in browsers and causes the webpack runtime overlay.
export function resolveRoutePath(basePath = '/', routePath = '') {
  const route = String(routePath || '')
  if (route.startsWith('/')) {
    return normalize(route)
  }
  const base = String(basePath || '')
  return normalize(`${base}/${route}`)
}

function normalize(value) {
  const normalized = String(value || '').replace(/\\/g, '/').replace(/\/+/g, '/')
  const withLeadingSlash = normalized.startsWith('/') ? normalized : `/${normalized}`
  const result = withLeadingSlash.length > 1
    ? withLeadingSlash.replace(/\/$/, '')
    : withLeadingSlash
  return result || '/'
}
