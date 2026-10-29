/**
 * ============================================================================
 * dsh-whalepet-bridge —— 只提供数据接口的宿主插件（不带任何 UI / 宠物 / 通知）
 * ============================================================================
 *
 * 给一个安卓 App 轮询用。App 每几秒打一次下面三个只读 GET 接口，就能画出
 * 「大肥鱼现在在干什么 / 今天花了多少钱」：
 *
 *   GET /api/whalepet/ping    存活探针（App 用它判断 dsh 起没起）
 *   GET /api/whalepet/status  状态机 + 今日汇总
 *   GET /api/whalepet/usage   分时段用量账本快照
 *   GET /api/whalepet/approval 待确认的命令（POST 回传允许/拒绝）
 *   GET/POST /api/whalepet/log  App 端诊断上报（无 logcat 时排查用）
 *
 * 几个刻意的设计：
 *
 * 1) **不在模块级声明 `inject`**。模块级 inject 是硬依赖，缺服务就让 entry 永远
 *    pending，dsh 会判定「1 entry did not activate」→ 整棵插件树加载失败，Web 起不来
 *    （内置的 device-shell-guide 0.1.9 栽过这一次）。这里全程用 `ctx.get('xxx')`
 *    可选取服务，拿不到就降级：没有 webServer 就没有路由（但不会崩），
 *    没有 sessionPersistence 就只统计本次运行（不补扫历史）。
 *
 * 2) **一切回调 try/catch 吞异常**。这是个「顺带好看」的旁路功能，绝不能影响
 *    agent 主链路 —— session/event 回调里抛一次异常就可能中断模型流。
 *
 * 3) **状态机是纯函数**（lib/status.js），可以脱离宿主单测；这里只负责
 *    接线：事件折叠、账本增量、路由。
 *
 * 4) **路由用 ctx.effect 注册**，插件卸载时由 cordis 自动注销。
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createServer } from 'node:http'
import { createSocket } from 'node:dgram'
import { randomBytes, randomUUID } from 'node:crypto'
import { createUsageLedger, DEFAULT_WINDOW_DAYS } from './usage-ledger.js'
import { emptyStatus, overlayPending, reduce } from './status.js'

export const name = 'dsh-whalepet-bridge'

/** 与 package.json 的 version 保持一致（改版本时两处一起改）。 */
// 版本号直接读 package.json，避免"清单写 0.2.0、ping 报 0.1.0"这种漂移
let VERSION = '0.0.0'
try {
  VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version
} catch { /* 打包形态异常时保留占位值 */ }

/** 路由路径。 */
const PATH_PING = '/api/whalepet/ping'
const PATH_USAGE = '/api/whalepet/usage'

/** 查询参数默认值：非法值一律回落到这里，**不返回 400**。 */
const DEFAULT_DAYS = 7
const DEFAULT_HOURS = 24

/** 启动后延时补扫历史，避开宿主启动关键路径；服务没起来就按这几档重试。 */
const SCAN_RETRY_MS = [1500, 6000, 20000]

/** 状态机空闲心跳：15 分钟太久，20 秒足够让 /status 及时变成 idle。 */
const IDLE_TICK_MS = 20_000

/** webServer 服务晚到时的重试间隔（ms）。 */
const ROUTE_RETRY_MS = [300, 1500, 5000]

const round4 = (value) => {
  const n = Number(value)
  return Number.isFinite(n) ? Math.round(n * 1e4) / 1e4 : 0
}

const toInt = (value) => {
  const n = Number(value)
  return Number.isFinite(n) ? Math.round(n) : 0
}

function errorMessage(error) {
  return error && typeof error.message === 'string' ? error.message : String(error)
}

/** 会话 id：与账本 fold 的取法（session.id）优先对齐，再兜底其他形状。 */
function sessionIdOf(session) {
  try {
    if (!session || typeof session !== 'object') return ''
    const raw = session.id ?? session.sessionId ?? session.key ?? ''
    return raw === null || raw === undefined ? '' : String(raw)
  } catch {
    return ''
  }
}

/** ?days=1..30 / ?hours=1..24；非法就返回默认值（不 400）。 */
function readInt(raw, min, max, fallback) {
  const n = Number(raw)
  if (!Number.isFinite(n)) return fallback
  const value = Math.floor(n)
  return value >= min && value <= max ? value : fallback
}

/** 一组会话累计 → 本轮增量（账本 sessionCost 的字段口径）。 */
function deltaUsage(before, after) {
  if (!after) return null
  const num = (value) => {
    const n = Number(value)
    return Number.isFinite(n) ? n : 0
  }
  const diff = (key) => num(after[key]) - num(before && before[key])
  const promptIn = diff('input') + diff('cacheRead') + diff('cacheWrite')
  const tokens = promptIn + diff('output')
  const cost = diff('hit') + diff('miss') + diff('out')
  return {
    costCny: round4(Math.max(0, cost)),
    tokens: Math.max(0, toInt(tokens)),
    cacheHitPercent: promptIn > 0 ? Math.round((diff('cacheRead') / promptIn) * 1000) / 10 : null,
  }
}

/**
 * 今日汇总（status 路由的 `today`）。
 *
 * 优先把 `snapshot.hours` 里属于今天的桶加总（需求口径：今日小时桶汇总），
 * 小时轴缺失时退回 `snapshot.totals.today`（日桶）。
 *
 * **账本不可用时返回 null 而不是 0**：App 要能区分「没数据」和「今天确实花了 0 元」。
 *
 * @param snapshot - `ledger.snapshot(...)` 的结果，或 null。
 * @returns `{costCny, tokens, cacheHitPercent, calls}` 或 null。
 */
export function todayFromSnapshot(snapshot) {
  try {
    if (!snapshot || typeof snapshot !== 'object') return null

    const days = Array.isArray(snapshot.days) ? snapshot.days : []
    const todayRow = days.length > 0 ? days[days.length - 1] : null
    const dayKey = todayRow && typeof todayRow.date === 'string' ? todayRow.date : ''
    const hours = Array.isArray(snapshot.hours) ? snapshot.hours : []
    const rows = dayKey ? hours.filter((row) => row && row.day === dayKey) : []

    if (rows.length === 0) {
      const bucket = snapshot.totals && snapshot.totals.today
      if (!bucket) return null
      const tokens = bucket.tokens || {}
      return {
        costCny: round4(bucket.costCny),
        tokens: toInt(tokens.total),
        cacheHitPercent: bucket.cacheHitPercent === undefined ? null : bucket.cacheHitPercent,
        calls: toInt(bucket.calls),
      }
    }

    let input = 0
    let cacheRead = 0
    let cacheWrite = 0
    let output = 0
    let cost = 0
    let calls = 0
    for (const row of rows) {
      const tokens = row.tokens || {}
      input += toInt(tokens.input)
      cacheRead += toInt(tokens.cacheRead)
      cacheWrite += toInt(tokens.cacheWrite)
      output += toInt(tokens.output)
      cost += Number(row.costCny) || 0
      calls += toInt(row.calls)
    }
    const promptIn = input + cacheRead + cacheWrite
    return {
      costCny: round4(cost),
      tokens: promptIn + output,
      cacheHitPercent: promptIn > 0 ? Math.round((cacheRead / promptIn) * 1000) / 10 : null,
      calls,
    }
  } catch {
    return null
  }
}

/** @param {import('@deepseek-ai/cordis').Context} ctx */
export function apply(ctx) {
  /** 全局状态（多会话时由「最后有事件的会话」覆盖）。 */
  let state = emptyStatus(Date.now())
  /** sessionId → 本轮 turn/start 时的账本累计（用于算 turn/end 的增量）。 */
  const turnBase = new Map()
  /** 所有本插件创建的延时定时器（dispose 时清掉）。 */
  const timers = new Set()
  let routesReady = false
  let disposed = false

  /**
   * 本地访问令牌：Android 的回环端口对所有 App 共享，光靠 127.0.0.1 不是鉴权边界；
   * 没有令牌时，任意本机应用都能 GET /approval 拿到 id 再 POST 放行工具调用。
   *
   * App 通过 UDP 发现拿到它（不落盘）；同时写一份到 $DSH_HOME/storages 供人用 curl 调试。
   * 文件存在就复用（DSH 重启后 App 不用重新发现），权限 0600。
   */
  const TOKEN = (() => {
    const base = process.env.DSH_HOME || `${process.env.HOME || '.'}/.dsh`
    const file = `${base}/storages/whalepet-bridge-token.txt`
    try {
      const saved = readFileSync(file, 'utf8').trim()
      if (/^[0-9a-f]{32}$/.test(saved)) return saved
    } catch { /* 第一次运行 */ }
    const fresh = randomBytes(16).toString('hex')
    try {
      mkdirSync(`${base}/storages`, { recursive: true })
      writeFileSync(file, fresh, { mode: 0o600 })
    } catch { /* 写不了就只放内存 */ }
    return fresh
  })()

  const now = () => Date.now()

  /** 可选的延时任务：unref，不让它拖住 dsh 退出。 */
  const later = (fn, ms) => {
    let timer
    timer = setTimeout(() => {
      timers.delete(timer)
      if (disposed) return
      try { fn() } catch { /* 旁路功能，失败静默 */ }
    }, ms)
    if (timer && typeof timer.unref === 'function') timer.unref()
    timers.add(timer)
    return timer
  }

  /** 可选取服务：没提供、提供者还没 active、ctx.get 本身异常 → undefined。 */
  const service = (name) => {
    try {
      return typeof ctx.get === 'function' ? ctx.get(name) : undefined
    } catch {
      return undefined
    }
  }

  // ---------------------------------------------------------------------------
  // 用量账本（复制自 dsh-whale-girl-pet，MIT）。创建失败=没有账本 → today 为 null。
  // ---------------------------------------------------------------------------
  let ledger = null
  try {
    ledger = createUsageLedger({ windowDays: DEFAULT_WINDOW_DAYS })
  } catch {
    ledger = null
  }

  const snapshotOf = (options) => {
    try {
      return ledger ? ledger.snapshot(options) : null
    } catch {
      return null
    }
  }

  const sessionCostOf = (sessionId) => {
    try {
      if (!ledger || typeof ledger.sessionCost !== 'function') return null
      const cost = ledger.sessionCost(sessionId)
      return cost && typeof cost === 'object' ? cost : null
    } catch {
      return null
    }
  }

  /** 把 turn/end 的账本增量补进 lastDone（拿不到就保持 null）。 */
  const fillTurnUsage = (next, sessionId) => {
    try {
      if (!next || !next.lastDone) return
      const before = sessionId ? turnBase.get(sessionId) : undefined
      if (sessionId) turnBase.delete(sessionId)
      const delta = deltaUsage(before, sessionCostOf(sessionId))
      if (delta) next.lastDone = { ...next.lastDone, ...delta }
    } catch {
      // 增量算不出来不影响「done + 用时」
    }
  }

  /** session/event 主回调：先折叠账本，再走状态机。两段各自 try/catch。 */
  const onSessionEvent = (session, event) => {
    try {
      if (ledger && typeof ledger.fold === 'function') ledger.fold(session, event)
    } catch {
      // 账本折叠失败只影响金额统计
    }
    try {
      const type = event && typeof event.type === 'string' ? event.type : ''
      const sessionId = sessionIdOf(session)
      if (type === 'turn/start' && sessionId) turnBase.set(sessionId, sessionCostOf(sessionId))
      const next = reduce(state, {
        type,
        data: event ? event.data : undefined,
        session: sessionId,
      }, now())
      state = next
      if (type === 'turn/end') fillTurnUsage(next, sessionId)
    } catch {
      // 状态机不能中断模型流
    }
  }

  // ---------------------------------------------------------------------------
  // HTTP 路由：全部 exact + GET + JSON + no-store
  // ---------------------------------------------------------------------------
  function writeJson(res, statusCode, body) {
    res.writeHead(statusCode, {
      'content-type': 'application/json; charset=utf-8',
      // 实时数据：绝不缓存（App 轮询必须看到新值）
      'cache-control': 'no-store',
    })
    res.end(JSON.stringify(body))
  }

  const methodAllowed = (req, res) => {
    if (req && req.method === 'GET') return true
    writeJson(res, 405, { ok: false, error: 'method not allowed' })
    return false
  }

  function handlePing(req, res) {
    try {
      if (!methodAllowed(req, res)) return
      writeJson(res, 200, {
        ok: true,
        name: 'whalepet-bridge',
        version: VERSION,
        now: now(),
      })
    } catch {
      // 响应写失败（客户端断连）无需处理
    }
  }

  /** 当前会话的累计花费（元）；账本可缺失。 */
  function sessionSpendCny(sessionId) {
    try {
      if (!ledger || typeof ledger.sessionCost !== 'function' || !sessionId) return null
      const c = ledger.sessionCost(sessionId)
      if (!c) return null
      const sum = (Number(c.hit) || 0) + (Number(c.miss) || 0) + (Number(c.out) || 0)
      return Math.round(sum * 1e4) / 1e4
    } catch {
      return null
    }
  }

  loadLifetime()

  function statusPayload() {
    // 顺手做一次空闲判定：即使心跳定时器被节流，读到 stale 状态也会变 idle
    try { state = reduce(state, { type: 'tick' }, now()) } catch { /* keep */ }
    const snapshot = snapshotOf({ hours: 24, days: 1 })
    // 有审批挂起时对外报 waiting：桌宠据此播「看表叹气」，面板仍由 pending 字段驱动
    const view = overlayPending(state, approvalView())
    return {
      ok: true,
      status: view.status,
      text: view.text,
      needUser: view.needUser === true,
      ask: typeof view.ask === 'string' ? view.ask : '',
      since: state.since,
      session: state.session,
      lastDone: state.lastDone,
      today: todayFromSnapshot(snapshot),
      sessionCostCny: sessionSpendCny(state.session),
      lifetimeCostCny: (syncLifetime(snapshot), lifetimeTotal()),
    }
  }


  /** 合并接口：状态 + 待审批，一次请求拿全（App 每 4 秒只打这一个）。 */
  function handlePoll(req, res) {
    try {
      if (!methodAllowed(req, res)) return
      approvalSeenAt = Date.now()
      writeJson(res, 200, { ...statusPayload(), pending: approvalView() })
    } catch {
      // 同上
    }
  }

  function handleUsage(req, res) {
    try {
      if (!methodAllowed(req, res)) return
      let url
      try {
        url = new URL(req.url ?? '/', 'http://localhost')
      } catch {
        url = null
      }
      const hours = readInt(url && url.searchParams.get('hours'), 1, 24, DEFAULT_HOURS)
      const days = readInt(url && url.searchParams.get('days'), 1, 30, DEFAULT_DAYS)

      if (!ledger) {
        writeJson(res, 503, { ok: false, error: 'usage ledger unavailable' })
        return
      }
      let data
      try {
        data = ledger.snapshot({ hours, days })
      } catch (error) {
        data = { ok: false, error: errorMessage(error) }
      }
      const ok = Boolean(data && data.ok)
      if (ok && data && typeof data === 'object') {
        // 顺手带上"本会话"和"永久累计"
        data.sessionCostCny = sessionSpendCny(state.session)
        syncLifetime(data)
        data.lifetimeCostCny = lifetimeTotal()
      }
      writeJson(res, ok ? 200 : 500, data)
    } catch {
      // 同上
    }
  }

  const ROUTES = [
    [PATH_PING, handlePing],
    [PATH_USAGE, handleUsage],
  ]

  /**
   * 注册路由。webServer 是可选的：拿不到就等（重试 + internal/status 事件），
   * 全程不抛异常。拿到后只注册一次。
   */
  /** POST /api/whalepet/approval：{id, decision:"allow"|"deny"} */
  const approvalPost = (req, res) => {
    let body = ''
    req.on('data', (chunk) => {
      body += chunk
      if (body.length > 8192) { try { req.destroy() } catch { /* ignore */ } }
    })
    req.on('end', () => {
      try {
        const data = JSON.parse(body || '{}')
        const ok = decideApproval(String(data.id || ''), data.decision === 'allow')
        writeJson(res, ok ? 200 : 409, { ok })
      } catch {
        writeJson(res, 400, { ok: false, error: 'bad json' })
      }
    })
  }

  function ensureRoutes() {
    if (routesReady || disposed) return
    const server = service('webServer')
    if (!server || typeof server.register !== 'function') return
    routesReady = true
    for (const [path, handler] of ROUTES) {
      try {
        ctx.effect(() => server.register({ kind: 'exact', path, handler }),
          `dsh-whalepet-bridge: ${path}`)
      } catch {
        // 单条路由注册失败不影响其他路由
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 独立本地端口：新版 DSH 给所有 HTTP 路由加了浏览器 Cookie 鉴权，
  // 桌宠 App 没有 Cookie 会拿到 401；这里用同一套 handler 另开 127.0.0.1:3080，
  // 只暴露 /api/whalepet/*，不经过 DSH 的鉴权层。
  // ---------------------------------------------------------------------------
  const STANDALONE_PORT = 3080
  const DISCOVERY_PORT = 47777
  let standalone = null
  let standalonePort = 0
  let discovery = null

  /** 只接受回环 Host（挡 DNS rebinding：攻击者域名解析到 127.0.0.1 时 Host 仍是他的域名）。 */
  function isLoopbackHost(host) {
    const h = String(host || '').toLowerCase()
    return /^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/.test(h)
  }

  function standaloneHandler(req, res) {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1')
      if (!isLoopbackHost(req.headers.host)) {
        writeJson(res, 403, { ok: false, error: 'forbidden' })
        return
      }
      // 浏览器请求一定带 Origin 或 Sec-Fetch-Site：拒绝网页 CSRF/读取审批
      if (req.headers.origin || req.headers['sec-fetch-site']) {
        writeJson(res, 403, { ok: false, error: 'forbidden' })
        return
      }
      // ping 只是存活/版本探针，不含敏感数据，保留无令牌可访问（方便 curl 自检）
      const isPing = req.method === 'GET' && url.pathname === PATH_PING
      if (!isPing && req.headers['x-whalepet-token'] !== TOKEN) {
        writeJson(res, 401, { ok: false, error: 'token required' })
        return
      }
      const route = ROUTES.find(([p]) => p === url.pathname)
      if (!route) {
        writeJson(res, 404, { ok: false, error: 'not found' })
        return
      }
      route[1](req, res)
    } catch {
      try { writeJson(res, 500, { ok: false, error: 'internal' }) } catch { /* ignore */ }
    }
  }

  function onStandaloneListening() {
    try {
      standalonePort = standalone.address().port
      startDiscovery()
    } catch { /* ignore */ }
  }

  /** UDP 发现：App 发 WHALEPET_DISCOVER，这里回 HTTP 端口。 */
  function startDiscovery() {
    if (discovery || disposed) return
    try {
      discovery = createSocket('udp4')
      discovery.on('error', () => { try { discovery.close() } catch { /* ignore */ } discovery = null })
      discovery.on('message', (msg, rinfo) => {
        try {
          if (String(msg).trim() !== 'WHALEPET_DISCOVER') return
          const out = Buffer.from(JSON.stringify({
            ok: true, name: 'whalepet-bridge', version: VERSION, port: standalonePort, token: TOKEN,
          }))
          discovery.send(out, rinfo.port, rinfo.address)
        } catch { /* ignore */ }
      })
      discovery.bind(DISCOVERY_PORT, '127.0.0.1')
      ctx.effect(() => () => {
        try { discovery.close() } catch { /* ignore */ }
        discovery = null
      }, 'dsh-whalepet-bridge: discovery port')
    } catch { /* ignore */ }
  }

  function startStandalone() {
    if (standalone || disposed) return
    try {
      standalone = createServer(standaloneHandler)
      standalone.on('error', () => {
        // 3080 被占用：换一个系统分配的端口，仍然能通过 UDP 发现
        try { standalone.close() } catch { /* ignore */ }
        standalone = createServer(standaloneHandler)
        standalone.on('error', () => { /* ignore */ })
        standalone.listen(0, '127.0.0.1', () => onStandaloneListening())
      })
      standalone.listen(STANDALONE_PORT, '127.0.0.1', () => onStandaloneListening())
      ctx.effect(() => () => {
        try { standalone.close() } catch { /* ignore */ }
        standalone = null
      }, 'dsh-whalepet-bridge: standalone port')
    } catch { /* ignore */ }
  }

  // ---------------------------------------------------------------------------
  // 上游 dsh-pet 对接（可选）：把「本机消费」写进它的工作状态气泡，把审批做成
  // 它的右键菜单项。上游没装就静默不动；装了才启用。环境变量可关：
  //   WHALEPET_UPSTREAM=0        关闭对接
  //   WHALEPET_UPSTREAM_PET=main 指定宠物 id
  // ---------------------------------------------------------------------------
  const UPSTREAM_PATH = '/dsh-pet-7340'
  const UPSTREAM_ON = process.env.WHALEPET_UPSTREAM !== '0'
  const UPSTREAM_PET = process.env.WHALEPET_UPSTREAM_PET || 'main'
  let upstreamReady = false
  let upstreamMenu = false
  let upstreamLock = null
  let lastPushedState = ''
  let lastPushedTask = ''

  function upstreamOrigin() {
    try {
      const ws = service('webServer')
      const origin = ws && ws.origin
      return typeof origin === 'string' && origin ? origin : null
    } catch { return null }
  }

  async function upstreamCall(path, init) {
    const origin = upstreamOrigin()
    if (!origin) return null
    try {
      const ctl = new AbortController()
      const timer = setTimeout(() => ctl.abort(), 3000)
      try {
        const res = await fetch(origin + UPSTREAM_PATH + path, {
          ...(init || {}),
          signal: ctl.signal,
          headers: { 'content-type': 'application/json', ...((init && init.headers) || {}) },
        })
        const text = await res.text()
        let body = null
        try { body = text ? JSON.parse(text) : null } catch { body = null }
        return { status: res.status, body }
      } finally {
        clearTimeout(timer)
      }
    } catch { return null }
  }

  async function detectUpstream() {
    const r = await upstreamCall('/state')
    upstreamReady = !!(r && r.status === 200)
  }

  function localUsageText() {
    try {
      const p = statusPayload()
      const today = p.today && typeof p.today.costCny === 'number' ? p.today.costCny : null
      const life = typeof p.lifetimeCostCny === 'number' ? p.lifetimeCostCny : null
      const fmt = (v) => (v === null ? '--' : v.toFixed(2))
      return '本机 今日 ¥' + fmt(today) + ' · 累计 ¥' + fmt(life)
    } catch { return '本机 今日 -- · 累计 --' }
  }

  async function pushWorkStatus() {
    if (!upstreamReady) { await detectUpstream(); if (!upstreamReady) return }
    try {
      const p = statusPayload()
      const state = p.status
      if (state === 'idle') {
        if (lastPushedState) {
          await upstreamCall('/work-status', { method: 'DELETE' })
          lastPushedState = ''
          lastPushedTask = ''
        }
        return
      }
      const map = { thinking: 'thinking', working: 'working', waiting: 'waiting', done: 'success', error: 'error' }
      const st = map[state] || 'working'
      const task = localUsageText()
      if (st === lastPushedState && task === lastPushedTask) return
      const r = await upstreamCall('/work-status', {
        method: 'POST',
        body: JSON.stringify({ state: st, task }),
      })
      if (r && (r.status === 200 || r.status === 204)) {
        lastPushedState = st
        lastPushedTask = task
      }
    } catch { /* ignore */ }
  }

  async function syncUpstreamApproval() {
    if (!upstreamReady) return
    try {
      const view = approvalView()
      if (view && !upstreamMenu) {
        const reg = await upstreamCall('/menu/item', {
          method: 'POST',
          body: JSON.stringify({ owner: 'whalepet', labels: ['允许一次', '拒绝'] }),
        })
        if (!reg || reg.status >= 300) return
        const lock = await upstreamCall('/lock?pet=' + encodeURIComponent(UPSTREAM_PET), {
          method: 'POST', body: '{}',
        })
        upstreamLock = lock && lock.body && lock.body.lockId ? lock.body.lockId : null
        if (upstreamLock) {
          await upstreamCall('/broadcast?pet=' + encodeURIComponent(UPSTREAM_PET)
              + '&lock=' + encodeURIComponent(upstreamLock), {
            method: 'POST',
            body: JSON.stringify({ text: '需要确认：' + (view.toolName || '命令') }),
          })
        }
        upstreamMenu = true
      }
      if (view && upstreamMenu) {
        const ev = await upstreamCall('/menu/events?owner=whalepet&wait=1500')
        const data = (ev && ev.body) || {}
        const clicked = Object.keys(data).find((k) => data[k] !== null && data[k] !== undefined)
        if (clicked) {
          decideApproval(view.id, clicked === '允许一次')
          await clearUpstreamApproval()
        }
      } else if (!view && upstreamMenu) {
        await clearUpstreamApproval()
      }
    } catch { /* ignore */ }
  }

  async function clearUpstreamApproval() {
    if (upstreamMenu) {
      await upstreamCall('/menu/item?owner=whalepet', { method: 'DELETE' })
      upstreamMenu = false
    }
    if (upstreamLock) {
      await upstreamCall('/lock/release?pet=' + encodeURIComponent(UPSTREAM_PET)
          + '&lock=' + encodeURIComponent(upstreamLock), { method: 'POST', body: '{}' })
      upstreamLock = null
    }
  }

  function startUpstream() {
    if (!UPSTREAM_ON || disposed) return
    detectUpstream()
    const t1 = setInterval(() => { if (!disposed) pushWorkStatus() }, 3000)
    const t2 = setInterval(() => { if (!disposed) syncUpstreamApproval() }, 2000)
    ctx.effect(() => () => {
      clearInterval(t1)
      clearInterval(t2)
      clearUpstreamApproval()
    }, 'dsh-whalepet-bridge: upstream dsh-pet')
  }

  // ---------------------------------------------------------------------------
  // 审批回答者：把 DSH 的 approval/request 转给手机上的桌宠，由用户点允许/拒绝
  // ---------------------------------------------------------------------------
  // 结局词表：allowed-once / rejected / cancelled / unavailable（见 dsh-user-approval）
  const APPROVAL_TIMEOUT_MS = 90_000
  /** 当前挂起的一条审批：{ id, toolName, reason, at, resolve } */
  let approval = null
  /** 桌宠最近一次来查审批的时间：决定"要不要把审批交给它"。 */
  let approvalSeenAt = 0
  const PET_ONLINE_MS = 5000

  const approvalView = () => approval === null ? null : {
    id: approval.id,
    toolName: approval.toolName || '',
    reason: approval.reason || '',
    at: approval.at,
  }

  try {
    ctx.on('approval/request', (request, next) => {
      try {
        if (approval !== null) return next()      // 已有挂起的一条，交给别的回答者
        if (Date.now() - approvalSeenAt > PET_ONLINE_MS) return next()   // 桌宠没在跑
        // 随机 id：递增编号可被同机进程猜中，配合无鉴权的回环端口就能代替用户放行
        const id = randomUUID()
        return new Promise((resolve) => {
          approval = {
            id,
            toolName: request && request.toolName,
            reason: request && request.reason,
            at: Date.now(),
            resolve,
          }
          const finish = (outcome) => {
            if (approval !== null && approval.id === id) approval = null
            resolve(outcome)
          }
          later(() => {
            // 超时：交还控制权（别的回答者/客户端仍可处理），绝不自动放行
            if (approval !== null && approval.id === id) {
              approval = null
              resolve('unavailable')
            }
          }, APPROVAL_TIMEOUT_MS)
          const signal = request && request.signal
          if (signal && typeof signal.addEventListener === 'function') {
            try {
              signal.addEventListener('abort', () => finish('cancelled'))
            } catch { /* ignore */ }
          }
        })
      } catch {
        return next()
      }
    })
  } catch { /* 宿主没有审批 seam 就跳过 */ }

  const decideApproval = (id, allow) => {
    if (approval === null || approval.id !== id) return false
    const resolve = approval.resolve
    approval = null
    resolve(allow ? 'allowed-once' : 'rejected')
    return true
  }

  /**
   * 自检：模拟一次审批，用来确认"桌宠弹面板 → 点允许/拒绝 → 回传"这条链路是通的。
   * 只走我们自己的回答者，不碰 DSH 的真实审批，也不会执行任何操作。
   */
  const approvalSelfTest = (req, res) => {
    const answerers = []
    try {
      // 直接触发本插件注册的回答者（与真实审批同一条代码路径）
      const outcome = (typeof ctx.waterfall === 'function')
        ? ctx.waterfall(ctx, 'approval/request', { toolName: 'selftest', reason: '验证桌宠确认面板' }, () => Promise.resolve('unavailable'))
        : Promise.resolve('unavailable')
      outcome.then((o) => writeJson(res, 200, { ok: true, outcome: o }))
        .catch(() => writeJson(res, 200, { ok: false, outcome: 'unavailable' }))
    } catch {
      writeJson(res, 500, { ok: false, error: 'waterfall unavailable' })
    }
    return answerers
  }

  /** GET：查有没有待确认的命令；POST：手机点"允许/拒绝"；POST .../test：自检。 */
  const approvalHandler = (req, res) => {
    const isTest = (req.url || '').includes('/test')
    if (req.method === 'POST' && isTest) return approvalSelfTest(req, res)
    if (req.method === 'POST') return approvalPost(req, res)
    if (req.method !== 'GET') {
      writeJson(res, 405, { ok: false, error: 'method not allowed' })
      return
    }
    approvalSeenAt = Date.now()
    writeJson(res, 200, { ok: true, pending: approvalView() })
  }

  // 路由表在 ensureRoutes() 之前追加（ensureRoutes 只在接线阶段调用一次）
  ROUTES.push(['/api/whalepet/approval', approvalHandler])
  ROUTES.push(['/api/whalepet/approval/test', approvalSelfTest])

  // ---------------------------------------------------------------------------
  // 永久累计：按天记在本机文件里，账本窗口滚出后也不丢
  // ---------------------------------------------------------------------------
  const LIFETIME_FILE = (() => {
    try {
      const base = process.env.DSH_HOME || `${process.env.HOME || '.'}/.dsh`
      return `${base}/storages/whalepet-bridge-lifetime.json`
    } catch {
      return null
    }
  })()
  /** { "2026-10-16": 4.2, ... } */
  let lifetimeDays = {}
  let lifetimeDirty = false
  let lifetimeSavedAt = 0

  function loadLifetime() {
    try {
      if (!LIFETIME_FILE) return
      const raw = readFileSync(LIFETIME_FILE, 'utf8')
      const data = JSON.parse(raw)
      if (data && typeof data.days === 'object' && data.days) lifetimeDays = data.days
    } catch {
      // 没有文件就是第一次
    }
  }

  function saveLifetime() {
    try {
      if (!LIFETIME_FILE || !lifetimeDirty) return
      const now2 = Date.now()
      if (now2 - lifetimeSavedAt < 30000) return
      lifetimeSavedAt = now2
      lifetimeDirty = false
      mkdirSync(LIFETIME_FILE.slice(0, LIFETIME_FILE.lastIndexOf('/')), { recursive: true })
      writeFileSync(LIFETIME_FILE, JSON.stringify({ days: lifetimeDays, updatedAt: now2 }))
    } catch {
      // 落盘失败不影响运行
    }
  }

  /** 把账本里的每日花费并入永久累计（同一天取较大值，避免回扫重复计数）。 */
  function syncLifetime(snapshot) {
    try {
      const rows = []
      if (snapshot && Array.isArray(snapshot.days)) rows.push(...snapshot.days)
      try {
        const all = ledger && typeof ledger.lifetimeRows === 'function' ? ledger.lifetimeRows() : []
        for (const r of all) {
          const d = new Date(r.index * 86400000).toISOString().slice(0, 10)
          rows.push({ date: d, costCny: r.costCny })
        }
      } catch { /* 老账本没有这个接口 */ }
      for (const row of rows) {
        const date = row && row.date
        const cost = Number(row.costCny) || 0
        if (!date || cost <= 0) continue
        const old = Number(lifetimeDays[date]) || 0
        if (cost > old) {
          lifetimeDays[date] = Math.round(cost * 1e4) / 1e4
          lifetimeDirty = true
        }
      }
      saveLifetime()
    } catch {
      // 忽略
    }
  }

  function lifetimeTotal() {
    let sum = 0
    for (const k of Object.keys(lifetimeDays)) sum += Number(lifetimeDays[k]) || 0
    return Math.round(sum * 1e4) / 1e4
  }

  // ---------------------------------------------------------------------------
  // 诊断日志：App 无法 logcat，把关键事件 POST 上来，容器里就能读
  // ---------------------------------------------------------------------------
  const diagRing = []
  const DIAG_MAX = 200

  const diagHandler = (req, res) => {
    if (req.method === 'GET') {
      writeJson(res, 200, { ok: true, lines: diagRing })
      return
    }
    let body = ''
    req.on('data', (c) => {
      body += c
      if (body.length > 16384) { try { req.destroy() } catch { /* ignore */ } }
    })
    req.on('end', () => {
      try {
        const d = JSON.parse(body || '{}')
        const line = `${new Date().toISOString()} [${d.tag || '?'}] ${d.msg || ''}`
        diagRing.push(line)
        while (diagRing.length > DIAG_MAX) diagRing.shift()
        writeJson(res, 200, { ok: true })
      } catch {
        writeJson(res, 400, { ok: false })
      }
    })
  }
  ROUTES.push(['/api/whalepet/log', diagHandler])
  ROUTES.push(['/api/whalepet/poll', handlePoll])

  /** 状态签名：状态/文本/开始时间/待审批，任一变化就算"有新东西"。 */
  function statusSignature() {
    const p = statusPayload()
    const a = approvalView()
    return [p.status, p.since, p.text || '', a ? a.id : '',
      p.needUser ? '1' : '0', p.ask || ''].join('|')
  }

  /** 长轮询：since 与当前签名一致就挂起，最多 waitMs。 */
  function handleWait(req, res) {
    try {
      if (!methodAllowed(req, res)) return
      approvalSeenAt = Date.now()
      const u = new URL(req.url, 'http://127.0.0.1')
      const since = u.searchParams.get('since') || ''
      const waitMs = Math.min(30000, Math.max(1000, Number(u.searchParams.get('timeout')) || 25000))

      const send = () => {
        if (res.writableEnded) return
        const sig = statusSignature()
        writeJson(res, 200, { ...statusPayload(), pending: approvalView(), seq: sig })
      }

      if (since !== statusSignature()) return send()

      const started = Date.now()
      const timer = setInterval(() => {
        if (res.writableEnded) { clearInterval(timer); return }
        approvalSeenAt = Date.now()      // 长轮询挂起中，桌宠仍然在线
        if (statusSignature() !== since || Date.now() - started >= waitMs) {
          clearInterval(timer)
          send()
        }
      }, 200)
      req.on('close', () => clearInterval(timer))
    } catch {
      // 出错就退回一次性响应
    }
  }

  ROUTES.push(['/api/whalepet/wait', handleWait])

  /** SSE：状态或审批一变就推一条，客户端断了自动结束。 */
  function handleEvents(req, res) {
    try {
      if (!methodAllowed(req, res)) return
      approvalSeenAt = Date.now()
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
      })
      let last = ''
      let closed = false
      const finish = () => {
        if (closed) return
        closed = true
        clearInterval(timer)
        try { res.end() } catch { /* ignore */ }
      }
      const push = () => {
        if (closed) return
        approvalSeenAt = Date.now()      // SSE 一直连着 = 桌宠一直在线（心跳也会刷新）
        try {
          const sig = statusSignature()
          if (sig === last) return
          last = sig
          const data = JSON.stringify({ ...statusPayload(), pending: approvalView(), seq: sig })
          res.write(`data: ${data}\n\n`)
        } catch {
          finish()
        }
      }
      const timer = setInterval(push, 300)
      // 心跳：状态没变时也要让连接活着，否则客户端 45 秒读超时会断开重连
      const beat = setInterval(() => {
        if (closed) return
        approvalSeenAt = Date.now()      // 心跳同样代表在线，否则审批会被判成"桌宠没在跑"
        try { res.write(': ping\n\n') } catch { finish() }
      }, 15000)
      push()
      req.on('close', () => { clearInterval(beat); finish() })
      req.on('error', () => { clearInterval(beat); finish() })
    } catch {
      // 出错就让客户端退回长轮询
    }
  }

  ROUTES.push(['/api/whalepet/events', handleEvents])

  // ---------------------------------------------------------------------------
  // 接线
  // ---------------------------------------------------------------------------
  try {
    ctx.on('session/event', onSessionEvent)
  } catch {
    // 没有事件总线就只剩路由里的静态数据（不应该发生）
  }

  // DSH 0.2.x 线的正文增量有的走 `agent/assistant-stream`（进程内流），不经过
  // `session/event`。只监听前者的话，状态会停在 turn/start 的 thinking，
  // 正文和工具文本永远不更新。两边都接，事件形状一样，走同一个折叠函数。
  try {
    ctx.on('agent/assistant-stream', (payload) => {
      try {
        const agent = payload && payload.agent
        const frame = payload && payload.frame
        const session = agent && agent.session
        if (!session || !frame) return
        if (frame.type === 'chunk' && frame.chunk) {
          onSessionEvent(session, { type: 'assistant/chunk', data: { chunk: frame.chunk } })
        }
      } catch {
        // 状态机不能中断模型流
      }
    })
  } catch { /* 宿主没有这个事件就跳过 */ }

  ensureRoutes()
  startStandalone()
  startUpstream()
  if (!routesReady) {
    for (const ms of ROUTE_RETRY_MS) later(ensureRoutes, ms)
    try {
      ctx.on('internal/status', () => { try { ensureRoutes() } catch { /* ignore */ } })
    } catch { /* ignore */ }
  }

  // 历史补扫：延时一拍，避开宿主启动；失败重试。
  //
  // **不能只试一次**：`sessionPersistence` 可能比本插件挂得晚（实测就有一次
  // `ctx.get` 还没返回、补扫直接 skipped，结果重启前那天的 token 少了 0.8%）。
  // 账本本身按 seq 去重，重复扫不会重复计数，所以多试几次是安全的。
  let scanStarted = false
  const attemptScan = (attempt) => {
    if (scanStarted || disposed) return
    try {
      const persistence = service('sessionPersistence')
      if (!persistence || !ledger || typeof ledger.scanFrom !== 'function') {
        const next = attempt + 1
        if (next < SCAN_RETRY_MS.length) later(() => attemptScan(next), SCAN_RETRY_MS[next])
        return
      }
      scanStarted = true
      const pending = ledger.scanFrom(persistence)
      if (pending && typeof pending.catch === 'function') {
        pending.catch(() => {
          // 扫到一半失败（文件被占用等）→ 允许下一档再试
          scanStarted = false
          const next = attempt + 1
          if (next < SCAN_RETRY_MS.length) later(() => attemptScan(next), SCAN_RETRY_MS[next])
        })
      }
    } catch {
      scanStarted = false
    }
  }
  later(() => attemptScan(0), SCAN_RETRY_MS[0])

  // 空闲心跳：无事件 5 分钟以上由状态机的 tick 迁到 idle
  let idleTimer
  try {
    idleTimer = setInterval(() => {
      if (disposed) return
      try {
        state = reduce(state, { type: 'tick' }, now())
      } catch { /* 状态机不会抛，兜底 */ }
    }, IDLE_TICK_MS)
    if (idleTimer && typeof idleTimer.unref === 'function') idleTimer.unref()
  } catch {
    idleTimer = undefined
  }

  try {
    ctx.on('dispose', () => {
      disposed = true
      try { if (idleTimer) clearInterval(idleTimer) } catch { /* ignore */ }
      for (const timer of timers) {
        try { clearTimeout(timer) } catch { /* ignore */ }
      }
      timers.clear()
      turnBase.clear()
    })
  } catch {
    // 没有 dispose 事件也不影响运行
  }
}
