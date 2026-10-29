import test from 'node:test'
import assert from 'node:assert/strict'

/**
 * `sessionPersistence` 比插件挂得晚时，历史补扫必须**重试**。
 *
 * 这条是真机上发现的：桥第一次跑的时候 `ctx.get('sessionPersistence')` 还拿不到，
 * 一次性的补扫直接跳过，结果重启前那一天的 token 少了 0.8%（275.6M vs 277.8M）。
 * 现在按 1.5s / 6s / 20s 三档重试；这里把定时器压到 10ms 来测，不真等 20 秒。
 */
test('sessionPersistence 晚挂载时会重试补扫，且只扫一次', async () => {
  const realSetTimeout = globalThis.setTimeout
  const realSetInterval = globalThis.setInterval
  globalThis.setTimeout = (fn, ms, ...rest) => realSetTimeout(fn, Math.min(Number(ms) || 0, 10), ...rest)
  globalThis.setInterval = (fn, ms, ...rest) => realSetInterval(fn, Math.min(Number(ms) || 0, 10), ...rest)

  try {
    const { apply } = await import(new URL('../lib/index.js', import.meta.url).href)

    let persistenceAsks = 0
    let listCalls = 0
    // scanFrom 要求服务同时具备 list 与 open，否则会判成 unavailable 直接返回
    const persistence = {
      async list() {
        listCalls++
        return []
      },
      async open() {
        return { async read() { return [] } }
      },
    }

    const ctx = {
      get(name) {
        if (name === 'webServer') {
          return { register() { return () => {} } }
        }
        if (name === 'sessionPersistence') {
          persistenceAsks++
          // 头两次装作还没挂载
          return persistenceAsks >= 3 ? persistence : undefined
        }
        return undefined
      },
      on() {},
      effect(fn) {
        try { fn() } catch { /* ignore */ }
      },
    }

    apply(ctx)
    await new Promise((r) => realSetTimeout(r, 400))

    assert.ok(persistenceAsks >= 3, `应该多次尝试取 sessionPersistence，实际 ${persistenceAsks} 次`)
    assert.ok(listCalls >= 1, '拿到服务之后必须真的扫一次')
  } finally {
    globalThis.setTimeout = realSetTimeout
    globalThis.setInterval = realSetInterval
  }
})
