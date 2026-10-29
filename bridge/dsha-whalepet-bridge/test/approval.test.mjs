import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'

/**
 * 审批链路：DSH 的 `approval/request` → bridge 挂起 → 手机查/决定 → 回答 DSH。
 *
 * 这是安全敏感路径，几条硬要求：
 *   1. 手机点"允许" → 必须回 `allowed-once`（只放行这一次）
 *   2. 点"拒绝" → `rejected`
 *   3. 超时/没人接 → 必须交还控制权（`unavailable`），**绝不自动放行**
 *   4. 同一时刻只挂一条，第二条交给别的回答者（next()）
 */
test('审批：挂起 → 允许/拒绝/超时', async () => {
  const { apply } = await import(new URL('../lib/index.js', import.meta.url).href)

  const routes = new Map()
  const handlers = new Map()
  const ctx = {
    get(name) {
      if (name === 'webServer') {
        return {
          register(route) {
            routes.set(route.path, route.handler)
            return () => routes.delete(route.path)
          },
        }
      }
      return undefined
    },
    on(event, cb) {
      const list = handlers.get(event) || []
      list.push(cb)
      handlers.set(event, list)
    },
    effect(fn) {
      try { fn() } catch { /* ignore */ }
    },
  }
  apply(ctx)

  const approvalHandler = handlers.get('approval/request')
  assert.ok(Array.isArray(approvalHandler) && approvalHandler.length === 1, '应注册了 approval/request 回答者')

  const server = http.createServer((req, res) => {
    const h = routes.get(new URL(req.url, 'http://x').pathname)
    if (!h) { res.writeHead(404); res.end('{}'); return }
    h(req, res)
  })
  await new Promise((r) => server.listen(18081, '127.0.0.1', r))

  const get = async (p) => (await (await fetch(`http://127.0.0.1:18081${p}`)).json())
  const post = async (p, body) => (await (await fetch(`http://127.0.0.1:18081${p}`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  })).json())

  // 0) 桌宠先"报到"一次（在线判定：最近 5s 内来查过才把审批交给它）
  let st = await get('/api/whalepet/approval')
  assert.equal(st.ok, true)
  assert.equal(st.pending, null)

  // 2) 发起一条审批 → 手机能查到
  const [answerer] = approvalHandler
  const decision = answerer({ toolName: 'bash', reason: 'rm -rf /tmp/x' }, () => 'unavailable')
  st = await get('/api/whalepet/approval')
  assert.ok(st.pending && st.pending.toolName === 'bash', JSON.stringify(st))

  // 3) 点允许 → allowed-once（只放行这一次）
  const okRes = await post('/api/whalepet/approval', { id: st.pending.id, decision: 'allow' })
  assert.equal(okRes.ok, true)
  assert.equal(await decision, 'allowed-once')

  // 4) 拒绝
  const d2 = answerer({ toolName: 'write', reason: '覆盖文件' }, () => 'unavailable')
  const st2 = await get('/api/whalepet/approval')
  await post('/api/whalepet/approval', { id: st2.pending.id, decision: 'deny' })
  assert.equal(await d2, 'rejected')

  // 5) 过期的 id 不能生效
  const d3 = answerer({ toolName: 'bash' }, () => 'unavailable')
  const st3 = await get('/api/whalepet/approval')
  const wrong = await post('/api/whalepet/approval', { id: 'ap999', decision: 'allow' })
  assert.equal(wrong.ok, false, '错误 id 不应被接受')
  await post('/api/whalepet/approval', { id: st3.pending.id, decision: 'deny' })
  assert.equal(await d3, 'rejected')

  server.close()
})
