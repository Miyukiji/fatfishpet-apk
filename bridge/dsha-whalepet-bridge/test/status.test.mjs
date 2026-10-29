/**
 * lib/status.js 状态机单测（纯函数，直接 node --test）。
 * 每个用例只依赖传入的 now，不读真实时钟。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  IDLE_MS,
  MAX_TEXT,
  clip,
  emptyStatus,
  overlayPending,
  reduce,
  tail,
  toolDetail,
  toolLabel,
} from '../lib/status.js'

const T0 = Date.UTC(2026, 9, 17, 4, 0, 0) // 任意固定时刻

/** turn 事件的小工具。 */
const turnStart = (t, session = 's1') => reduce(emptyStatus(t), { type: 'turn/start', session }, t)
const textDelta = (state, t, text) => reduce(state, {
  type: 'assistant/chunk',
  data: { chunk: { type: 'text-delta', text } },
  session: 's1',
}, t)

test('初始状态是 idle，lastDone 为 null', () => {
  const state = emptyStatus(T0)
  assert.equal(state.status, 'idle')
  assert.equal(state.text, '')
  assert.equal(state.since, T0)
  assert.equal(state.session, '')
  assert.equal(state.lastDone, null)
})

test('turn/start → thinking，since 重置，正文清空', () => {
  let state = reduce(emptyStatus(T0), {
    type: 'assistant/chunk',
    data: { chunk: { type: 'text-delta', text: '旧的一轮' } },
    session: 's1',
  }, T0)
  assert.equal(state.text, '旧的一轮')

  const t1 = T0 + 10_000
  state = reduce(state, { type: 'turn/start', session: 's1' }, t1)
  assert.equal(state.status, 'thinking')
  assert.equal(state.text, '')
  assert.equal(state.since, t1)
  assert.equal(state.session, 's1')
  assert.equal(state.startedAt, t1)
})

test('assistant/chunk(text-delta) → thinking，正文压成一行', () => {
  let state = turnStart(T0)
  state = textDelta(state, T0 + 100, '第一行\n第二行')
  assert.equal(state.status, 'thinking')
  assert.equal(state.text, '第一行 第二行')
  state = textDelta(state, T0 + 200, '  继续')
  assert.equal(state.text, '第一行 第二行 继续')
})

test('正文超过 200 字只保留末尾 200 字', () => {
  let state = turnStart(T0)
  const first = 'a'.repeat(150)
  const second = 'b'.repeat(150)
  state = textDelta(state, T0 + 100, first)
  state = textDelta(state, T0 + 200, second)
  assert.equal(state.text.length, MAX_TEXT)
  // 150 + 150 → 只留末尾 200 字：50 个 a + 150 个 b
  assert.equal(state.text, 'a'.repeat(50) + 'b'.repeat(150))
  // tail 辅助函数本身也只保留末尾
  assert.equal(tail(`${'x'.repeat(300)}END`), `${'x'.repeat(MAX_TEXT - 3)}END`)
})

test('reasoning-delta 只算「有动静」，不覆盖正文', () => {
  let state = turnStart(T0)
  state = textDelta(state, T0 + 100, '正文')
  const t1 = T0 + 500
  state = reduce(state, {
    type: 'assistant/chunk',
    data: { chunk: { type: 'reasoning-delta', text: '心里在想' } },
    session: 's1',
  }, t1)
  assert.equal(state.text, '正文')
  assert.equal(state.status, 'thinking')
  assert.equal(state.since, t1)
})

test('tool/call → working，带命令原文（JSON 字符串参数）', () => {
  let state = turnStart(T0)
  state = reduce(state, {
    type: 'tool/call',
    data: { name: 'bash', arguments: '{"command":"ls -la /tmp"}' },
    session: 's1',
  }, T0 + 1000)
  assert.equal(state.status, 'working')
  assert.equal(state.text, '⚙ 正在执行命令: ls -la /tmp')
  assert.equal(state.since, T0 + 1000)
})

test('tool/call 参数非法 JSON / 路径参数都不抛，且能取到 detail', () => {
  const state = reduce(emptyStatus(T0), {
    type: 'tool/call',
    data: { name: 'read', arguments: '{"path":"/root/a.txt"}' },
  }, T0)
  assert.equal(state.text, '⚙ 正在读取文件: /root/a.txt')
  assert.equal(toolDetail('{"path": "x"},'), '')

  const broken = reduce(emptyStatus(T0), {
    type: 'tool/call',
    data: { name: 'bash', arguments: '{not json' },
  }, T0)
  assert.equal(broken.status, 'working')
  assert.equal(broken.text, '⚙ 正在执行命令')

  const unknown = reduce(emptyStatus(T0), {
    type: 'tool/call',
    data: { name: 'mystery-tool', arguments: { url: 'http://example.com' } },
  }, T0)
  assert.equal(unknown.text, '⚙ 正在使用 mystery-tool: http://example.com')
  assert.equal(toolLabel(''), '⚙ 正在使用工具')
  assert.equal(clip('y'.repeat(300)).length, 160)
})

test('工具调用后的新正文另起一段，不接在工具摘要后面', () => {
  let state = turnStart(T0)
  state = reduce(state, {
    type: 'tool/call',
    data: { name: 'bash', arguments: '{"command":"make"}' },
  }, T0 + 1000)
  state = textDelta(state, T0 + 2000, '命令跑完了')
  assert.equal(state.status, 'thinking')
  assert.equal(state.text, '命令跑完了')
})

test('turn/end → done，lastDone 用本轮起点算整数秒，费用字段为 null', () => {
  let state = turnStart(T0)
  state = textDelta(state, T0 + 1000, '干活中')
  const endAt = T0 + 6200
  state = reduce(state, { type: 'turn/end', session: 's1' }, endAt)
  assert.equal(state.status, 'done')
  assert.equal(state.since, endAt)
  assert.ok(state.lastDone, 'lastDone 必须存在')
  assert.equal(state.lastDone.at, endAt)
  assert.ok(Number.isInteger(state.lastDone.durSec), 'durSec 必须是整数')
  assert.ok(state.lastDone.durSec > 0, 'durSec 必须是正数')
  assert.equal(state.lastDone.durSec, 6)
  assert.equal(state.lastDone.costCny, null)
  assert.equal(state.lastDone.tokens, null)
  assert.equal(state.lastDone.cacheHitPercent, null)
  // 正文保留，App 可以继续显示最后一句
  assert.equal(state.text, '干活中')
})

test('turn/end 没见到 turn/start 时也不崩，durSec 至少 1 秒', () => {
  const state = reduce(emptyStatus(T0), { type: 'turn/end' }, T0)
  assert.equal(state.status, 'done')
  assert.equal(state.lastDone.durSec, 1)
})

test('ask_user_question 调用 → waiting + needUser + 问题文本', () => {
  let state = turnStart(T0)
  state = reduce(state, {
    type: 'tool/call',
    data: {
      name: 'ask_user_question',
      arguments: JSON.stringify({ questions: [{ id: 'x', question: '选哪个方案？', options: [{ label: 'A' }] }] }),
    },
  }, T0 + 500)
  assert.equal(state.status, 'waiting')
  assert.equal(state.needUser, true)
  assert.equal(state.ask, '选哪个方案？')
})

test('exit_plan_mode 也算等人回应', () => {
  let state = turnStart(T0)
  state = reduce(state, { type: 'tool/call', data: { name: 'exit_plan_mode', arguments: '{}' } }, T0 + 100)
  assert.equal(state.needUser, true)
  assert.equal(state.status, 'waiting')
})

test('tool/result 之后 needUser 清掉，进入 result（整理六档之一）', () => {
  let state = turnStart(T0)
  state = reduce(state, { type: 'tool/call', data: { name: 'ask_user_question', arguments: '{"questions":[{"question":"Q"}]}' } }, T0 + 100)
  state = reduce(state, { type: 'tool/result', data: { callId: 'c1' } }, T0 + 900)
  assert.equal(state.needUser, false)
  assert.equal(state.status, 'result')
})

test('turn/end 的 reason.kind=error → error（不是 done），并带错误信息', () => {
  let state = turnStart(T0)
  state = textDelta(state, T0 + 1000, '尝试中')
  const endAt = T0 + 3100
  state = reduce(state, {
    type: 'turn/end',
    data: { reason: { kind: 'error', error: { message: '上游 529\n过载', code: 'X' } } },
    session: 's1',
  }, endAt)
  assert.equal(state.status, 'error')
  assert.equal(state.text, '上游 529 过载')
  assert.equal(state.lastDone.durSec, 3)
  assert.equal(state.startedAt, 0)
})

test('turn/end 的 reason 不是 error（completed/aborted）仍然是 done', () => {
  for (const kind of ['completed', 'aborted', 'max-tokens', 'interrupted']) {
    const state = reduce(turnStart(T0), {
      type: 'turn/end',
      data: { reason: { kind } },
      session: 's1',
    }, T0 + 2000)
    assert.equal(state.status, 'done', `${kind} 应当收工成 done`)
  }
})

test('overlayPending：审批挂起时 idle/thinking/working 都变 waiting', () => {
  const pending = { toolName: 'bash' }
  const busy = turnStart(T0)
  const over = overlayPending(busy, pending)
  assert.equal(over.status, 'waiting')
  assert.equal(over.text, '在等你确认：bash')
  // 不改入参
  assert.equal(busy.status, 'thinking')
  // 没审批 / 已收工 / 已出错都不覆盖
  assert.equal(overlayPending(busy, null), busy)
  const done = reduce(busy, { type: 'turn/end' }, T0 + 1000)
  assert.equal(overlayPending(done, pending).status, 'done')
  const failed = reduce(busy, { type: 'turn/end', data: { reason: { kind: 'error' } } }, T0 + 1000)
  assert.equal(overlayPending(failed, pending).status, 'error')
})

test('含 error 的事件 → error，并带上错误信息', () => {
  let state = turnStart(T0)
  state = reduce(state, {
    type: 'turn/error',
    data: { message: '模型调用失败\n请重试' },
    session: 's1',
  }, T0 + 10)
  assert.equal(state.status, 'error')
  assert.equal(state.text, '模型调用失败 请重试')
})

test('5 分钟内 tick 不变；超过 5 分钟 → idle 且清空正文', () => {
  let state = turnStart(T0)
  state = textDelta(state, T0 + 1000, '有点慢')
  const same = reduce(state, { type: 'tick' }, T0 + IDLE_MS - 1)
  assert.equal(same, state, '未超时时应原样返回（引用相等）')
  assert.equal(same.status, 'thinking')

  const idle = reduce(state, { type: 'tick' }, T0 + 1000 + IDLE_MS)
  assert.equal(idle.status, 'idle')
  assert.equal(idle.text, '')
  assert.equal(idle.lastDone, state.lastDone)
})

test('多会话时 session 跟着最后有事件的会话走', () => {
  let state = turnStart(T0, 'session-a')
  assert.equal(state.session, 'session-a')
  state = reduce(state, { type: 'assistant/chunk', data: { chunk: { type: 'text-delta', text: 'B 在说话' } }, session: 'session-b' }, T0 + 10)
  assert.equal(state.session, 'session-b')
  assert.equal(state.status, 'thinking')
  // 事件不带 session 时保留上一个
  state = reduce(state, { type: 'assistant/chunk', data: { chunk: { type: 'text-delta', text: '继续' } } }, T0 + 20)
  assert.equal(state.session, 'session-b')
  assert.equal(state.text, 'B 在说话继续')
})

test('非法输入不抛：null 状态 / 空事件 / 未知事件类型', () => {
  const state = reduce(null, { type: 'turn/start' }, T0)
  assert.equal(state.status, 'thinking')
  const unchanged = reduce(state, null, T0 + 1)
  assert.equal(unchanged.status, 'thinking')
  const unknown = reduce(state, { type: 'message/user', session: 's9' }, T0 + 2)
  assert.equal(unknown.status, 'thinking')
  assert.equal(unknown.session, 's9')
  assert.equal(unknown.since, T0 + 2)
})
