/**
 * ============================================================================
 * dsh-whalepet-bridge / lib/status.js —— 纯状态机（无 IO、无定时器、无依赖）
 * ============================================================================
 *
 * 【职责】
 *   把 DSH 的 `session/event` 折叠成**一份全局状态**，供安卓 App 轮询
 *   `GET /api/whalepet/status` 显示「它现在在干什么」。
 *
 * 【纯函数】
 *   `reduce(state, event, now)` 不改入参、不读时钟（`now` 必须由调用方传入），
 *   所以可以直接用 `node --test` 覆盖每一条迁移，不需要起宿主。
 *
 * 【状态字段】
 *   - status  idle | thinking | working | result | waiting | error | done
 *   - text    给 App 显示的一行摘要（压成单行；正文最多 200 字）
 *   - since   最近一次事件的时间（ms）——「多久没动静」就看它
 *   - session 最近活跃的会话 id（多会话时取最后一个有事件的那个）
 *   - lastDone 上一轮的结果 { at, durSec, costCny, tokens, cacheHitPercent } | null
 *   - startedAt 内部字段：本轮 turn/start 的时刻，turn/end 用它算 durSec
 *
 * 【为什么 `since` 就是「最后事件时间」而不是「当前状态开始时间」】
 *   需求里的空闲判定是「超过 5 分钟**没有任何事件** → idle」。只有把 since
 *   维护成最后事件时间，才能既满足 `turn/start → since 重置`，又不用另开一个
 *   时间戳字段（durSec 用的 startedAt 已经单独留了）。
 *
 * 【资金/ token 为什么不在这里算】
 *   账本是宿主侧有状态的对象（fold / snapshot），塞进纯函数就没法单测了。
 *   `turn/end` 只给出时间与结构，花费由 index.js 用账本的每会话增量补上；
 *   拿不到账本时这三个字段保持 null —— 让 App 能区分「没数据」与「花了 0 元」。
 */

/** 超过这么久没有任何事件 → idle。 */
export const IDLE_MS = 5 * 60 * 1000

/** 正文摘要的最大长度（字）。 */
export const MAX_TEXT = 200

/** 工具摘要里参数部分的最大长度（命令原文往往很长）。 */
export const MAX_DETAIL = 160

/** 工具名 → 人话（命中越具体的放前面；没命中兜底成「正在使用 X」）。 */
const TOOL_LABELS = [
  [/bash|shell|command|exec|terminal/i, '⚙ 正在执行命令'],
  [/write|create.*file|edit|patch|apply/i, '⚙ 正在修改文件'],
  [/read|cat|view|open.*file/i, '⚙ 正在读取文件'],
  [/glob|grep|search|find/i, '⚙ 正在搜索'],
  [/fetch|web|http|browse|url/i, '⚙ 正在联网查资料'],
  [/todo|plan/i, '⚙ 正在整理任务清单'],
  [/task|agent|subagent|dispatch/i, '⚙ 正在派子任务'],
  [/image|screenshot|vision/i, '⚙ 正在看图'],
  [/notify|toast|share|clip/i, '⚙ 正在调用手机功能'],
]

/** 工具名 → 一行中文标签。 */
export function toolLabel(name) {
  const n = oneLine(name)
  if (!n) return '⚙ 正在使用工具'
  for (const [re, label] of TOOL_LABELS) {
    if (re.test(n)) return label
  }
  return '⚙ 正在使用 ' + n
}

/** 工具参数里挑一个值得显示的键：命令原文最有用，其次路径 / 模式 / URL。 */
const ARG_KEYS = ['command', 'cmd', 'script', 'path', 'file_path', 'filePath',
  'pattern', 'query', 'url', 'prompt', 'description']

/**
 * 从 tool/call 的 arguments（JSON 字符串或对象）里取出可显示的一段。
 * 模型可能吐出半截非法 JSON —— 解析失败就返回空串，绝不抛。
 */
export function toolDetail(args) {
  try {
    if (args === null || args === undefined || args === '') return ''
    const parsed = typeof args === 'string' ? JSON.parse(args) : args
    if (!parsed || typeof parsed !== 'object') return ''
    for (const key of ARG_KEYS) {
      const value = parsed[key]
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
    // 没命中已知键名 → 退而取第一个非空字符串（新工具也能显示点东西）
    for (const value of Object.values(parsed)) {
      if (typeof value === 'string' && value.trim()) return value.trim()
    }
    return ''
  } catch {
    return ''
  }
}

/** 压成单行（换行/连续空白 → 一个空格）。 */
export function oneLine(value) {
  return String(value === null || value === undefined ? '' : value)
    .replace(/\s+/g, ' ')
    .trim()
}

/** 压成单行并保留**末尾** max 字（流式正文要看到最新的那句）。 */
export function tail(value, max = MAX_TEXT) {
  const one = oneLine(value)
  return one.length > max ? one.slice(one.length - max) : one
}

/** 压成单行并保留**开头** max 字（命令原文的开头信息量最大）。 */
export function clip(value, max = MAX_DETAIL) {
  const one = oneLine(value)
  return one.length > max ? one.slice(0, max - 1) + '…' : one
}

/** 新会话 / 空状态。 */
export function emptyStatus(now = Date.now()) {
  return {
    status: 'idle', text: '', since: now, session: '', lastDone: null, startedAt: 0,
    // 「DSH 正在等用户回应」：审批之外的人机交互（ask_user_question / exit_plan_mode）
    needUser: false, ask: '',
  }
}

/** 需要用户回应的工具：调用即阻塞等人。 */
const NEEDS_USER = /^(ask_user_question|exit_plan_mode)$/i

/** 从工具参数里取一句能显示的问题。 */
export function askText(args) {
  try {
    const parsed = typeof args === 'string' ? JSON.parse(args) : args
    if (!parsed || typeof parsed !== 'object') return ''
    const qs = Array.isArray(parsed.questions) ? parsed.questions : []
    for (const q of qs) {
      if (q && typeof q === 'object') {
        if (typeof q.question === 'string' && q.question.trim()) return oneLine(q.question)
        if (typeof q.header === 'string' && q.header.trim()) return oneLine(q.header)
      }
    }
    for (const key of ['plan', 'question', 'prompt', 'reason']) {
      if (typeof parsed[key] === 'string' && parsed[key].trim()) return oneLine(parsed[key])
    }
    return ''
  } catch {
    return ''
  }
}

/**
 * 把「有审批挂起」盖到对外状态上：idle/thinking/working → waiting。
 *
 * 为什么不放进 reduce：状态机是纯函数，审批是 index.js 里的有状态对象；
 * 混进来就没法单测。done/error 不覆盖 —— 一轮已经收工/失败，再显示
 * 「在等你确认」只会让桌宠反复切动作。
 *
 * @param state - reduce 给出的状态（不会改它）。
 * @param pending - 待审批视图 { toolName, ... } 或 null。
 */
export function overlayPending(state, pending) {
  if (!pending || typeof pending !== 'object') return state
  if (!state || typeof state !== 'object') return state
  if (state.status === 'done' || state.status === 'error') return state
  const toolName = typeof pending.toolName === 'string' ? oneLine(pending.toolName) : ''
  return {
    ...state,
    status: 'waiting',
    text: toolName ? '在等你确认：' + clip(toolName, MAX_DETAIL) : '在等你确认',
  }
}

/** 把任何输入兜底成合法状态（reduce 的入口防御；合法状态原样返回）。 */
function normalize(state, now) {
  if (state && typeof state === 'object'
    && typeof state.status === 'string'
    && typeof state.text === 'string'
    && Number.isFinite(state.since)
    && typeof state.session === 'string'
    && (state.lastDone === null || typeof state.lastDone === 'object')
    && Number.isFinite(state.startedAt)) {
    return state
  }
  if (!state || typeof state !== 'object') return emptyStatus(now)
  const status = typeof state.status === 'string' ? state.status : 'idle'
  return {
    status,
    text: typeof state.text === 'string' ? state.text : '',
    since: Number.isFinite(state.since) ? state.since : now,
    session: typeof state.session === 'string' ? state.session : '',
    lastDone: state.lastDone && typeof state.lastDone === 'object' ? state.lastDone : null,
    startedAt: Number.isFinite(state.startedAt) ? state.startedAt : 0,
  }
}

/** 事件里带的会话 id（形状随 cordis 版本略有差异，全都试一遍）。 */
function sessionOf(event) {
  if (!event || typeof event !== 'object') return ''
  const raw = event.session ?? event.sessionId ?? event.session_id ?? ''
  return typeof raw === 'string' ? raw : (raw === null || raw === undefined ? '' : String(raw))
}

/** 从错误事件里挤出一句人话。 */
function errorText(data) {
  const candidates = [data.message, data.error && data.error.message, data.error,
    data.reason, data.text, data.detail]
  for (const value of candidates) {
    if (typeof value === 'string' && value.trim()) return clip(value, MAX_TEXT)
  }
  return '⚠ 出错了'
}

/**
 * 状态迁移（纯函数）。
 *
 * @param state - 当前状态（非法值会被兜底成空状态）。
 * @param event - `{ type, data, session? }`；`type` 为 'tick' 或空时只做空闲判定。
 * @param now - 当前时间（ms）。必须由调用方传入，测试可注入。
 * @returns 新状态；`tick` 且无需迁移时原样返回入参（保持引用相等）。
 */
export function reduce(state, event, now = Date.now()) {
  const at = Number.isFinite(now) ? now : Date.now()
  const prev = normalize(state, at)
  const type = event && typeof event.type === 'string' ? event.type : ''
  const data = event && event.data && typeof event.data === 'object' ? event.data : {}

  // ---- 无事件 / 心跳：只做「5 分钟没动静 → idle」----------------------------
  if (!type || type === 'tick' || type === 'idle') {
    if (prev.status !== 'idle' && at - prev.since >= IDLE_MS) {
      return { ...prev, status: 'idle', text: '' }
    }
    return prev
  }

  const session = sessionOf(event) || prev.session
  const active = { ...prev, since: at, session }

  // ---- 错误：任何带 error 的事件（turn/error、llm/error …）------------------
  if (type.includes('error')) {
    return { ...active, status: 'error', text: errorText(data), needUser: false, ask: '' }
  }

  // ---- 开工 -----------------------------------------------------------------
  if (type === 'turn/start') {
    return { ...active, status: 'thinking', text: '', startedAt: at }
  }

  // ---- 流式正文 -------------------------------------------------------------
  if (type === 'assistant/chunk') {
    const chunk = data.chunk && typeof data.chunk === 'object' ? data.chunk : {}
    // 思考增量（reasoning-delta）不进正文，只算「还活着」
    if (chunk.type !== 'text-delta') return active
    const piece = typeof chunk.text === 'string' ? chunk.text : oneLine(chunk.text)
    if (!piece) return active
    // 工具调用之后又开始吐正文 → 从新的一段开始，不能接在工具摘要后面
    const base = prev.status === 'thinking' ? prev.text : ''
    return { ...active, status: 'thinking', text: tail(base + piece) }
  }

  // ---- 工具调用 -------------------------------------------------------------
  if (type === 'tool/call') {
    const name = oneLine(data.name)
    if (NEEDS_USER.test(name)) {
      const ask = clip(askText(data.arguments))
      return {
        ...active,
        status: 'waiting',
        text: ask ? '🙋 需要你回应：' + ask : '🙋 需要你回应',
        needUser: true,
        ask,
      }
    }
    const label = toolLabel(data.name)
    const detail = toolDetail(data.arguments)
    return {
      ...active,
      status: 'working',
      text: detail ? label + ': ' + clip(detail) : label,
      needUser: false,
      ask: '',
    }
  }

  // ---- 工具返回：整理结果（六档里的 result，对齐上游 dsh-pet） -------------
  if (type === 'tool/result') {
    return { ...active, status: 'result', text: '', needUser: false, ask: '' }
  }

  // ---- 收工 -----------------------------------------------------------------
  if (type === 'turn/end') {
    const began = prev.startedAt > 0 ? prev.startedAt : prev.since
    const durSec = Math.max(1, Math.round(Math.max(0, at - began) / 1000))
    const lastDone = {
      at,
      durSec,
      // 花费 / token 由 index.js 用账本增量填；纯函数里没有账本 → null
      costCny: null,
      tokens: null,
      cacheHitPercent: null,
    }
    // 一轮**真的失败**时 turn/end 的 reason 形如 { kind: 'error', error: { message, code } }
    // （见 @deepseek-ai/dsh-agent-loop 的 turnEnds）。以前一律当 done，桌宠永远播不到
    // 「玩游戏气急败坏」—— 正常收工和出错必须分开，才谈得上状态联动完整。
    const reason = data.reason && typeof data.reason === 'object' ? data.reason : null
    if (reason !== null && reason.kind === 'error') {
      const detail = reason.error && typeof reason.error.message === 'string'
        ? reason.error.message
        : ''
      return {
        ...active,
        status: 'error',
        startedAt: 0,
        lastDone,
        needUser: false,
        ask: '',
        text: detail ? clip(detail) : '⚠ 这一轮出错了',
      }
    }
    return { ...active, status: 'done', startedAt: 0, lastDone, needUser: false, ask: '' }
  }

  // ---- 其他事件：只算「有动静」，不改状态 ------------------------------------
  return active
}
