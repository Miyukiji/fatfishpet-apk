# dsh-whalepet-bridge

DSH 宿主侧**纯数据桥**：注册只读 GET 接口（含长轮询与 SSE），给一个安卓 App 轮询
「鲸鱼娘现在在干什么 / 今天花了多少钱」。

**不带 UI、不带宠物、不带通知、不往手机推任何东西、不写任何文件。**
界面完全由 App 自己画，插件只提供数据。

- 插件名：`dsh-whalepet-bridge`，版本 `0.2.7`，MIT
- 宿主半侧：`lib/index.js`（Cordis 插件：`name` / `apply`）
- 状态机：`lib/status.js`（纯函数，可单测）
- 用量账本：`lib/usage.js` + `lib/usage-ledger.js`（原样复制自
  [dsh-whale-girl-pet](https://github.com/yanzwzz/dsh-whale-girl-pet) 的 MIT 代码，
  口径与它的 `/api/whale-pet/usage` 完全一致）
- 单测：`test/status.test.mjs`（状态机）、`test/routes.test.mjs`（路由接线与降级）、
  `test/approval.test.mjs`（审批）、`test/scan-retry.test.mjs`（补扫重试），共 29 项；
  `npm test` 已带 `--test-force-exit`（插件会留着 UDP/HTTP 句柄，否则 runner 不退出）
- 依赖：只用 node 内建。没有 npm 依赖，也没有 import 任何 `@deepseek-ai/*` 包。

## 接口

路由都是 `kind: 'exact'`、只接受 `GET`（审批回答是 `POST`）、响应
`content-type: application/json; charset=utf-8` 且 `cache-control: no-store`。
非 GET 回 `405`（JSON）。参数非法**不报 400**，直接回落到默认值。

### 本地令牌（0.2.7 起）

Android 的回环端口是**所有 App 共享**的，`127.0.0.1` 本身不是鉴权边界：没有令牌时，
任意本机应用都能 `GET /approval` 拿到 id 再 `POST` 放行工具调用。因此：

- 除 `GET /ping` 外，所有请求都要带 `x-whalepet-token: <32 位 hex>`；
- 令牌由桥生成并持久化在 `$DSH_HOME/storages/whalepet-bridge-token.txt`（0600），重启不变；
- App 通过 UDP 发现拿到令牌（不回退到无令牌模式）；
- 令牌不匹配回 `401`，`Host` 不是回环或带浏览器 `Origin`/`Sec-Fetch-Site` 的请求回 `403`。

人工调试：

```sh
TOKEN=$(cat "$DSH_HOME/storages/whalepet-bridge-token.txt")
curl -H "x-whalepet-token: $TOKEN" http://127.0.0.1:3080/api/whalepet/status
```

### UDP 发现（App 自动找端口）

桥在 `127.0.0.1:47777` 监听 UDP。发送文本 `WHALEPET_DISCOVER`，会收到：

```json
{ "ok": true, "name": "whalepet-bridge", "version": "0.2.7", "port": 3080, "token": "…32hex…" }
```

`port` 是桥当前 HTTP 端口（优先 3080，被占用则随机）。App 先发现、失败再回退 3080（仍要带令牌）。

### `GET /api/whalepet/ping`

存活探针，App 用它判断 dsh 起没起。

```json
{ "ok": true, "name": "whalepet-bridge", "version": "0.2.7", "now": 1763000000000 }
```

### `GET /api/whalepet/status`

状态机 + 今日汇总。示例（假数据）：

```json
{
  "ok": true,
  "status": "working",
  "text": "⚙ 正在执行命令: npm test",
  "since": 1763000000123,
  "session": "session-1",
  "lastDone": {
    "at": 1762999990000,
    "durSec": 42,
    "costCny": 0.0137,
    "tokens": 9123,
    "cacheHitPercent": 78.4
  },
  "today": { "costCny": 0.2841, "tokens": 152340, "cacheHitPercent": 81.2, "calls": 27 }
}
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `status` | `idle` / `thinking` / `working` / `waiting` / `error` / `done`。多会话时取最近有事件的那个会话。`waiting` 在有审批挂起（且当前状态不是 `done`/`error`）时由 `overlayPending()` 产出；`error` 来自 `turn/end` 的 `reason.kind === "error"`，其余 ending 仍是 `done` |
| `text` | 一行摘要（压成单行）。正文最多 200 字；工具调用形如 `⚙ 正在执行命令: <command>` |
| `since` | 最近一次事件的时间（ms）。`now - since > 300000` 即视为 idle |
| `session` | 最近活跃的会话 id（可能为空串） |
| `lastDone` | 上一轮结束时的结果：`at` 结束时刻、`durSec` 本轮整数秒、`costCny` 本轮花费、`tokens` 本轮 token、`cacheHitPercent` 本轮缓存命中率。没有上一轮 / 拿不到账本时为 `null` |
| `today` | 今日汇总（北京时间日）。账本不可用时为 `null`（而不是 0），App 据此区分「没数据」与「花了 0 元」 |

`status` 迁移：`turn/start → thinking`、`assistant/chunk(text-delta) → thinking`
（更新 `text`）、`tool/call → working`、`turn/end → done`（算 `lastDone`）、
事件类型里含 `error` 的（如 `turn/error`）→ `error`；超过 5 分钟没有任何事件 → `idle`。

### 其余路由

| 路由 | 用途 |
|---|---|
| `GET /api/whalepet/poll?since=` | 轮询状态（兼容旧客户端） |
| `GET /api/whalepet/wait?since=` | 长轮询，有变化或超时才返回 |
| `GET /api/whalepet/events` | SSE 推送（`text/event-stream`） |
| `GET /api/whalepet/approval` | 查当前待确认的审批 |
| `POST /api/whalepet/approval` | 手机点“允许/拒绝”，body `{id, allow}` |
| `POST /api/whalepet/approval/test` | 自检，走同一条 waterfall，不执行任何操作 |
| `GET/POST /api/whalepet/log` | App 诊断上报（200 行环形缓冲） |

审批只交给 5 秒内来查过的桌宠（`PET_ONLINE_MS`），否则直接 `next()` 让别的回答者处理；
90 秒无人回答则回 `unavailable`，绝不自动放行。

### `GET /api/whalepet/usage?days=1..30&hours=1..24`

直接返回 `ledger.snapshot({ hours, days })`，**字段与 dsh-whale-girl-pet 的
`/api/whale-pet/usage` 完全一致**，安卓端可以复用同一套解析。默认
`days=7&hours=24`；越界/非数字一律回落默认值。

```json
{
  "ok": true,
  "now": 1763000000000,
  "timeZone": "Asia/Shanghai",
  "windowDays": 7,
  "hours": [
    {
      "index": 489715,
      "hour": 14,
      "day": "2026-11-13",
      "current": true,
      "peak": true,
      "tokens": { "input": 1200, "cacheRead": 48000, "cacheWrite": 0, "output": 900, "inputTotal": 49200, "total": 50100 },
      "cacheHitPercent": 97.6,
      "costCny": 0.0139,
      "costHitCny": 0.0024,
      "costMissCny": 0.0018,
      "costOutCny": 0.0041,
      "costPeakCny": 0.0083,
      "costOffPeakCny": 0,
      "calls": 3,
      "models": ["deepseek-chat"]
    }
  ],
  "days": [
    {
      "index": 20408,
      "date": "2026-11-13",
      "current": true,
      "peak": true,
      "tokens": { "input": 1200, "cacheRead": 48000, "cacheWrite": 0, "output": 900, "inputTotal": 49200, "total": 50100 },
      "cacheHitPercent": 97.6,
      "costCny": 0.0139,
      "costHitCny": 0.0024,
      "costMissCny": 0.0018,
      "costOutCny": 0.0041,
      "costPeakCny": 0.0083,
      "costOffPeakCny": 0,
      "calls": 3,
      "models": ["deepseek-chat"]
    }
  ],
  "totals": {
    "today": { "tokens": { "input": 1200, "cacheRead": 48000, "cacheWrite": 0, "output": 900, "inputTotal": 49200, "total": 50100 }, "cacheHitPercent": 97.6, "costCny": 0.0139, "costHitCny": 0.0024, "costMissCny": 0.0018, "costOutCny": 0.0041, "costPeakCny": 0.0083, "costOffPeakCny": 0, "calls": 3, "models": ["deepseek-chat"] },
    "last24h": { "costCny": 0.0139, "calls": 3 },
    "window": { "costCny": 0.0139, "calls": 3 }
  },
  "calls": 3,
  "scan": { "status": "done", "sessions": 12, "events": 3456, "failed": 0, "ms": 120, "startedAt": 1762999998500, "finishedAt": 1762999998620, "error": null }
}
```

（`hours` 固定返回 `hours` 行、`days` 固定返回 `days` 行，空桶补零；上面只截了最后一行。
`totals.today` 是北京日桶，`totals.last24h` 是最近 24 个小时桶之和，`totals.window` 是整个日窗口之和。）

## 为什么不做 UI

- **职责分离**：界面在安卓 App 里，宿主插件只做数据源。插件不注册任何
  Slot / Client 入口，因此没有浏览器侧 bundle，也就不可能因为前端改版而崩。
- **零副作用**：不发通知、不读剪贴板、不写文件、不碰设备能力。
- **可轮询**：所有接口都是幂等的只读 GET + `no-store`，App 掉线重连后直接重拉即可。
- **不影响 agent**：所有 `session/event` 回调都包在 try/catch 里，异常一律吞掉。

## 降级行为（重要）

- **没有 `webServer` 服务**：插件照常加载（不声明 `inject`，不会让插件树 pending），
  只是没有路由。启动时会重试注册（300ms / 1.5s / 5s），并监听 `internal/status`
  再试；服务一直不来就一直没路由，但绝不抛异常。
- **没有 `sessionPersistence` 服务**：跳过历史补扫，只统计本次运行期间的事件。
  账本本身仍在，`/usage` 正常返回，只是重启前的用量不在桶里。
- **账本创建/快照失败**：`/status` 的 `today` 为 `null`，`lastDone` 的花费/token 为
  `null`；`/usage` 回 `503 {"ok":false,"error":"usage ledger unavailable"}` 或
  `500`，状态机不受影响。
- **历史补扫失败**（磁盘/权限问题）：静默，`/usage` 的 `scan.status` 会体现原因。
- **多会话**：全局状态只保留「最后有事件」的那个会话；账本本身按会话分别累计，
  互不干扰。
- **`turn/end` 的金额增量**：以 `turn/start` 时该会话的账本累计为基线取差值；
  如果插件是在一轮中途才加载（没见过 `turn/start`），该轮金额为 `null` 而不是瞎猜。

## 安装

本包**没有**装进任何 profile。要接进 profile 时，在 `package.json` 依赖里用
`link:/root/dsha-whalepet-bridge` 并让 bundle patch 生效即可；本包不提供 UI，
所以不需要浏览器侧入口。

## 单测

```bash
cd /root/dsha-whalepet-bridge
node --test test/*.mjs
```

## 许可

MIT。`lib/usage.js` 与 `lib/usage-ledger.js` 原样复制自 MIT 许可的
`dsh-whale-girl-pet`（作者 yanzwzz），未做任何逻辑修改。


## 与上游 dsh-pet 对接（可选）

同机装了 `dsh-pet` 时，本插件会自动把两样东西接到它的桌宠上：

- **本机消费**：写进它的「工作状态」气泡任务行（`本机 今日 ¥x · 累计 ¥y`），状态变化时刷新；空闲时清空
- **审批**：待确认时注册右键菜单「更多 → 允许一次 / 拒绝」，并尝试占用锁弹一句提醒气泡；点菜单即回传决定

上游没装（`/dsh-pet-7340/state` 404）就静默不动。环境变量：

- `WHALEPET_UPSTREAM=0` 关闭对接
- `WHALEPET_UPSTREAM_PET=main` 指定宠物 id（锁与气泡都作用于它）
