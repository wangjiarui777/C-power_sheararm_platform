const fs = require('fs')
const path = require('path')

const dist = path.resolve(__dirname, '../dist/static')
const entryBudget = Number(process.env.ENTRY_BUDGET_KIB || 1200) * 1024
const asyncBudget = Number(process.env.ASYNC_BUDGET_KIB || 500) * 1024

function files(dir) {
  return fs.readdirSync(dir).map(name => ({
    name,
    size: fs.statSync(path.join(dir, name)).size
  }))
}

const js = files(path.join(dist, 'js')).filter(item => item.name.endsWith('.js'))
const css = files(path.join(dist, 'css')).filter(item => item.name.endsWith('.css'))
const initial = [...js, ...css].filter(item =>
  /^(app|chunk-libs|chunk-elementUI)\./.test(item.name))
const entrySize = initial.reduce((sum, item) => sum + item.size, 0)
const largestAsync = js.filter(item => !initial.includes(item))
  .sort((a, b) => b.size - a.size)[0]

if (entrySize > entryBudget) {
  throw new Error(`Entry bundle ${(entrySize / 1024).toFixed(1)} KiB exceeds ${entryBudget / 1024} KiB`)
}
if (largestAsync && largestAsync.size > asyncBudget) {
  throw new Error(`Async chunk ${largestAsync.name} ${(largestAsync.size / 1024).toFixed(1)} KiB exceeds ${asyncBudget / 1024} KiB`)
}
console.log(`Bundle budget passed: entry ${(entrySize / 1024).toFixed(1)} KiB, largest async ${largestAsync ? (largestAsync.size / 1024).toFixed(1) : 0} KiB`)
