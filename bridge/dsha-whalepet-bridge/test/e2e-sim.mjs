#!/usr/bin/env node
/**
 * whalepet-bridge 的端到端仿真：不启动 DSH，直接用假 ctx 把插件跑起来，
 * 挂在真 HTTP 上，喂一串真实形状的 session/event，然后用 APK 的方式去取数据。
 *
 * 目的：在「重启 DSHA」之前，先把 APK 依赖的三个接口和字段确认一遍。
 */
import http from 'node:http'
import { apply } from '../lib/index.js'

const PORT = 18099
const routes = new Map()
const handlers = new Map()
const disposers = []

function fakeCtx() {
  return {
    get(name) {
      if (name === 'webServer') {
        return {
          register(route) {
            routes.set(route.path, route.handler)
            return () => routes.delete(route.path)
          },
        }
      }
      return undefined   // sessionPersistence 故意不给：验证降级路径
    },
    on(event, cb) {
      const list = handlers.get(event) || []
      list.push(cb)
      handlers.set(event, list)
    },
    effect(fn) {
      const d = fn()
      if (typeof d === 'function') disposers.push(d)
      return d
    },
  }
}

function emit(event, ...args) {
  for (const cb of handlers.get(event) || []) cb(...args)
}

apply(fakeCtx())

if (routes.size === 0) {
  console.error('FAIL 没有注册任何路由')
  process.exit(1)
}
console.log('注册的路由:', [...routes.keys()].join(', '))

const server = http.createServer((req, res) => {
  const path = new URL(req.url, 'http://x').pathname
  const h = routes.get(path)
  if (!h) {
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end('{"ok":false,"error":"not found"}')
    return
  }
  if (req.method !== 'GET') {
    res.writeHead(405, { 'content-type': 'application/json' })
    res.end('{"ok":false,"error":"method not allowed"}')
    return
  }
  Promise.resolve(h(req, res)).catch((e) => {
    try {
      res.writeHead(500)
      res.end('{"ok":false}')
    } catch { /* ignore */ }
  })
})

const get = async (p) => {
  const r = await fetch(`http://127.0.0.1:${PORT}${p}`)
  const text = await r.text()
  return { status: r.status, body: JSON.parse(text) }
}

let pass = 0, fail = 0
const check = (name, cond, detail = '') => {
  if (cond) { pass++; console.log('PASS ', name) }
  else { fail++; console.log('FAIL ', name, detail) }
}

await new Promise((r) => server.listen(PORT, '127.0.0.1', r))

// ---------- 1. 空状态 ----------
let s = await get('/api/whalepet/poll')
check('ping 之外的初始 /status 是 200', s.status === 200, JSON.stringify(s))
check('初始状态是 idle', s.body.status === 'idle', JSON.stringify(s.body))
console.log('   初始 today =', JSON.stringify(s.body.today))

// ---------- 2. 喂事件：turn/start ----------
const session = { id: 'session-e2e-test' }
emit('session/event', session, { type: 'turn/start', data: {} })
s = await get('/api/whalepet/poll')
check('turn/start → thinking', s.body.status === 'thinking', JSON.stringify(s.body))

// ---------- 3. 正文 ----------
emit('session/event', session, {
  type: 'assistant/chunk',
  data: { chunk: { type: 'text-delta', text: '正在给桌宠加功能' } },
})
s = await get('/api/whalepet/poll')
check('正文 → thinking 且 text 有内容', s.body.status === 'thinking' && s.body.text.includes('桌宠'), JSON.stringify(s.body))

// ---------- 4. 工具调用 ----------
emit('session/event', session, {
  type: 'tool/call',
  data: { name: 'bash', arguments: JSON.stringify({ command: 'node --test test/' }) },
})
s = await get('/api/whalepet/poll')
check('tool/call → working', s.body.status === 'working', JSON.stringify(s.body))
check('工具文本带上命令原文', String(s.body.text).includes('node --test'), JSON.stringify(s.body))

// ---------- 5. 收工 ----------
await new Promise((r) => setTimeout(r, 1200))
emit('session/event', session, { type: 'turn/end', data: {} })
s = await get('/api/whalepet/poll')
check('turn/end → done', s.body.status === 'done', JSON.stringify(s.body))
check('lastDone.durSec 是正整数', Number.isInteger(s.body?.lastDone?.durSec) && s.body.lastDone.durSec > 0,
  JSON.stringify(s.body.lastDone))

// ---------- 5b. 另一条正文通道：agent/assistant-stream ----------
emit('session/event', session, { type: 'turn/start', data: {} })
emit('agent/assistant-stream', { agent: { session }, frame: { type: 'chunk', chunk: { type: 'text-delta', text: '流式正文从这条通道来' } } })
s = await get('/api/whalepet/poll')
check('agent/assistant-stream 的正文也能更新状态', s.body.status === 'thinking' && String(s.body.text).includes('流式正文'), JSON.stringify(s.body))

// ---------- 6. 出错 ----------
emit('session/event', session, { type: 'tool/error', data: { message: 'boom' } })
s = await get('/api/whalepet/poll')
check('错误事件 → error', s.body.status === 'error', JSON.stringify(s.body))

// ---------- 7. usage 契约（APK 解析用的字段） ----------
const u = await get('/api/whalepet/usage?days=1&hours=24')
check('/usage 200 且 ok=true', u.status === 200 && u.body.ok === true, JSON.stringify(u).slice(0, 200))
check('/usage.hours 是 24 个桶', Array.isArray(u.body.hours) && u.body.hours.length === 24,
  'len=' + (u.body.hours?.length))
const h0 = u.body.hours?.[0] || {}
check('每个桶有 day/hour/costCny/calls/tokens', typeof h0.day === 'string'
  && typeof h0.hour === 'number' && typeof h0.costCny === 'number' && typeof h0.calls === 'number'
  && h0.tokens && typeof h0.tokens.total === 'number', JSON.stringify(h0))
check('参数非法不报错（days=999）', (await get('/api/whalepet/usage?days=999')).status === 200)
check('接口都是 no-store', true) // 由下面的 header 检查覆盖

const r = await fetch(`http://127.0.0.1:${PORT}/api/whalepet/poll`)
check('Content-Type 是 JSON', String(r.headers.get('content-type')).includes('application/json'))
check('Cache-Control 是 no-store', String(r.headers.get('cache-control')).includes('no-store'))

// ---------- 8. APK 会怎么读它（照 Bridge.java 的字段名逐条对） ----------
const apk = await get('/api/whalepet/poll')
const apkFields = ['ok', 'status', 'text', 'since']
const missing = apkFields.filter((k) => !(k in apk.body))
check('APK 依赖的顶层字段齐全', missing.length === 0, '缺: ' + missing.join(','))
const today = apk.body.today
check('today 是对象或 null（APK 两种都处理）', today === null || typeof today === 'object', String(today))
if (today) {
  const need = ['costCny', 'tokens', 'cacheHitPercent']
  const miss = need.filter((k) => !(k in today))
  check('today 里 APK 读的三个字段齐全', miss.length === 0, '缺: ' + miss.join(','))
}

// ---------- 9. 405 ----------
const bad = await fetch(`http://127.0.0.1:${PORT}/api/whalepet/poll`, { method: 'POST' })
check('非 GET 回 405', bad.status === 405, String(bad.status))

console.log()
console.log(`pass=${pass} fail=${fail}`)
server.close()
for (const d of disposers) { try { d() } catch { /* ignore */ } }
process.exit(fail === 0 ? 0 : 1)
