/**
 * 宿主侧接线单测：用假 ctx / 假 webServer / 假 req-res 验证三个路由，
 * 以及「账本不可用 → today 为 null」和「没有 webServer → 不抛异常」这两条降级路径。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { apply, todayFromSnapshot } from '../lib/index.js'

/** 假宿主：记录路由、事件监听和 effect。 */
function createHarness({ withWebServer = true } = {}) {
  const routes = new Map()
  const listeners = new Map()
  const webServer = withWebServer
    ? {
      register({ kind, path, handler }) {
        routes.set(path, { kind, path, handler })
        return () => routes.delete(path)
      },
    }
    : undefined

  const ctx = {
    get(name) {
      return name === 'webServer' ? webServer : undefined
    },
    on(event, fn) {
      const list = listeners.get(event) ?? []
      list.push(fn)
      listeners.set(event, list)
      return () => {}
    },
    effect(fn) {
      return fn()
    },
  }

  const emit = (session, event) => {
    for (const fn of listeners.get('session/event') ?? []) fn(session, event)
  }

  return { ctx, routes, emit }
}

/** 调用一个路由处理器，返回 {status, headers, json}。 */
function call(handler, url = '/', method = 'GET') {
  const out = { status: 0, headers: {}, json: undefined }
  const res = {
    writeHead(status, headers) {
      out.status = status
      out.headers = headers || {}
    },
    end(body) {
      out.json = body === undefined ? undefined : JSON.parse(body)
    },
  }
  handler({ url, method }, res)
  return out
}

/** 一带而过地跑一轮完整 turn（真实的 session/event 形状）。 */
function runTurn(harness, time = Date.now(), sessionId = 'session-1') {
  const session = { id: sessionId }
  harness.emit(session, { type: 'turn/start', seq: 1, time, data: {} })
  harness.emit(session, {
    type: 'assistant/chunk',
    seq: 2,
    time,
    data: { chunk: { type: 'text-delta', text: '正在干活' } },
  })
  harness.emit(session, {
    type: 'tool/call',
    seq: 3,
    time,
    data: { name: 'bash', arguments: '{"command":"npm test"}' },
  })
  harness.emit(session, {
    type: 'assistant/message',
    seq: 4,
    time,
    data: {
      turn: 1,
      step: 1,
      message: { id: 'msg-1' },
      usage: { inputTokens: 400, cacheReadTokens: 400, cacheWriteTokens: 0, outputTokens: 100 },
    },
  })
  harness.emit(session, { type: 'turn/end', seq: 5, time, data: { turn: 1 } })
}

test('注册八个 exact 路由（含审批、自检、日志、长轮询与 SSE），拿不到 webServer 时不抛异常', () => {
  const harness = createHarness()
  assert.doesNotThrow(() => apply(harness.ctx))
  assert.deepEqual([...harness.routes.keys()].sort(), [
    '/api/whalepet/approval',
    '/api/whalepet/approval/test',
    '/api/whalepet/events',
    '/api/whalepet/log',
    '/api/whalepet/ping',
    '/api/whalepet/poll',
    '/api/whalepet/usage',
    '/api/whalepet/wait',
  ])
  for (const route of harness.routes.values()) assert.equal(route.kind, 'exact')

  const noServer = createHarness({ withWebServer: false })
  assert.doesNotThrow(() => apply(noServer.ctx))
  assert.equal(noServer.routes.size, 0)
})

test('GET /api/whalepet/ping 回 ok/name/version/now，且 no-store', () => {
  const harness = createHarness()
  apply(harness.ctx)
  const res = call(harness.routes.get('/api/whalepet/ping').handler)
  assert.equal(res.status, 200)
  assert.equal(res.headers['cache-control'], 'no-store')
  assert.equal(res.json.ok, true)
  assert.equal(res.json.name, 'whalepet-bridge')
  // 版本号来自 package.json，断言"是版本串"而不是写死某个数字
  assert.match(res.json.version, /^\d+\.\d+\.\d+$/)
  assert.equal(typeof res.json.now, 'number')
})

test('GET /api/whalepet/poll 初始是 idle，字段与契约一致', () => {
  const harness = createHarness()
  apply(harness.ctx)
  const res = call(harness.routes.get('/api/whalepet/poll').handler)
  assert.equal(res.status, 200)
  assert.equal(res.headers['cache-control'], 'no-store')
  const body = res.json
  assert.equal(body.ok, true)
  assert.equal(body.status, 'idle')
  assert.equal(body.text, '')
  assert.equal(body.session, '')
  assert.equal(body.lastDone, null)
  assert.equal(typeof body.since, 'number')
  // 内部字段（startedAt）不许漏给 App
  assert.equal('startedAt' in body, false)
  // 账本可用但没有用量 → today 是对象且全为 0（区别于不可用的 null）
  assert.ok(body.today)
  assert.equal(body.today.costCny, 0)
  assert.equal(body.today.tokens, 0)
  assert.equal(body.today.calls, 0)
  assert.equal(body.today.cacheHitPercent, null)
})

test('完整 turn 后 status=done，lastDone 带用时/花费/token，today 有金额', () => {
  const harness = createHarness()
  apply(harness.ctx)
  runTurn(harness)
  const handler = harness.routes.get('/api/whalepet/poll').handler
  const body = call(handler).json
  assert.equal(body.status, 'done')
  assert.equal(body.session, 'session-1')
  assert.equal(body.text, '⚙ 正在执行命令: npm test')
  assert.ok(body.lastDone)
  assert.ok(Number.isInteger(body.lastDone.durSec) && body.lastDone.durSec > 0)
  assert.ok(body.lastDone.costCny > 0, 'turn/end 应补上本轮金额增量')
  assert.equal(body.lastDone.tokens, 900)
  assert.equal(body.lastDone.cacheHitPercent, 50)
  assert.ok(body.today)
  assert.equal(body.today.calls, 1)
  assert.equal(body.today.tokens, 900)
  assert.equal(body.today.cacheHitPercent, 50)
  assert.ok(body.today.costCny > 0)
})

test('GET /api/whalepet/usage 参数非法回落默认（days=7, hours=24），不 400', () => {
  const harness = createHarness()
  apply(harness.ctx)
  const handler = harness.routes.get('/api/whalepet/usage').handler

  const bad = call(handler, '/api/whalepet/usage?days=999&hours=0')
  assert.equal(bad.status, 200)
  assert.equal(bad.headers['cache-control'], 'no-store')
  assert.equal(bad.json.ok, true)
  assert.equal(bad.json.hours.length, 24)
  assert.equal(bad.json.days.length, 7)
  assert.equal(bad.json.timeZone, 'Asia/Shanghai')

  const exact = call(handler, '/api/whalepet/usage?days=3&hours=2')
  assert.equal(exact.status, 200)
  assert.equal(exact.json.hours.length, 2)
  assert.equal(exact.json.days.length, 3)

  const garbage = call(handler, '/api/whalepet/usage?days=abc&hours=')
  assert.equal(garbage.status, 200)
  assert.equal(garbage.json.hours.length, 24)
  assert.equal(garbage.json.days.length, 7)
})

test('非 GET 一律 405，且响应仍是 JSON + no-store', () => {
  const harness = createHarness()
  apply(harness.ctx)
  for (const path of ['/api/whalepet/ping', '/api/whalepet/poll', '/api/whalepet/usage']) {
    const res = call(harness.routes.get(path).handler, path, 'POST')
    assert.equal(res.status, 405)
    assert.equal(res.headers['cache-control'], 'no-store')
    assert.equal(res.json.ok, false)
  }
})

test('todayFromSnapshot：没数据 → null，有小时桶 → 汇总，小时轴缺失 → 退回日桶', () => {
  assert.equal(todayFromSnapshot(null), null)
  assert.equal(todayFromSnapshot({}), null)
  assert.equal(todayFromSnapshot({ hours: [], days: [], totals: {} }), null)

  const fromHours = todayFromSnapshot({
    days: [{ date: '2026-10-17' }],
    hours: [
      { day: '2026-10-16', costCny: 9, calls: 9, tokens: { input: 1, cacheRead: 0, cacheWrite: 0, output: 0 } },
      {
        day: '2026-10-17',
        costCny: 0.5,
        calls: 1,
        tokens: { input: 100, cacheRead: 100, cacheWrite: 0, output: 50 },
      },
    ],
  })
  assert.deepEqual(fromHours, { costCny: 0.5, tokens: 250, cacheHitPercent: 50, calls: 1 })

  const fallback = todayFromSnapshot({
    days: [{ date: '2026-10-17' }],
    totals: {
      today: {
        costCny: 0.25,
        calls: 2,
        cacheHitPercent: 75,
        tokens: { total: 40 },
      },
    },
  })
  assert.deepEqual(fallback, { costCny: 0.25, tokens: 40, cacheHitPercent: 75, calls: 2 })
})
