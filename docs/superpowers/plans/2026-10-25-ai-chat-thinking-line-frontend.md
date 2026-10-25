# F 段：EZ_frontend 思考线 + 对话全量对接 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task（本会话已由用户确认走内联执行，**不派子代理**）。Steps use checkbox (`- [ ]`) syntax for tracking.
> **本仓库的例外**：每完成一个任务就**提交并推送**（用户 2026-10-25 变更的约定），但必须用 pathspec：
> `git add <显式路径>` + `git commit -m … -- <同一批路径>`，**绝不 `git add -A`** ——
> `EZ_frontend` 工作树里常驻别人的未提交改动（当前是 `src/components/text/ShinyText.vue`、`src/views/Home.vue`）。

**Goal:** 让 `EZ_frontend` 的 `/ai` 页面真正跑起后端 `#1–#8` 八个接口：会话列表、流式正文、以及思考线（`thought_step` / `thought_end`）的实时渲染与停止/重试，并把模型输出按 markdown 安全渲染。

**Architecture:** 三层各管一件事，边界按"能不能纯函数化测试"来切。
`api/sse.ts` 只做字节流→帧→回调，不认 URL、不碰 Vue，是**唯一**手工解帧的地方；
`api/chat.ts` 是接口层，控制面复用既有 `request.ts` 的 `Result` 信封与错误类型；
`composables/useChat.ts` 是唯一持有流式状态的地方，组件只读它。
渲染侧 `MarkdownBody.vue` 用 `marked` + `DOMPurify`，流式期间按 `requestAnimationFrame` 合并重解析，
未闭合代码块先补围栏再解析。

**Tech Stack:** Vue 3.5 `<script setup>` + TypeScript、vue-router 5、pinia 4（登录态）、Tailwind 4 + 现有 CSS 变量主题、
`marked` + `dompurify`（本段**唯一新增依赖**）、Node 24 原生 type-stripping 跑 `node --test`（零测试框架）、
Vite dev proxy `/gw → http://127.0.0.1:8100`。

**Spec:** `docs/superpowers/specs/2026-10-24-ai-chat-thinking-line-design.md` §5（前端设计）
**后端契约依据：** `docs/superpowers/plans/2026-10-25-ai-chat-thinking-line-e2e-log.md`（真上游帧序与 `data:` 无空格等实测事实）
**上一期对应文档：** spec `docs/superpowers/specs/2026-10-23-ai-chat-conversation-design.md` §5.2/§6

## Global Constraints

1. **工作目录是 `F:/AAAJAVA/code/EZ_frontend`，不是后端仓库。** 它是独立 git 仓库（branch `dev`，origin `EZ_frontend`）。
   本计划里所有路径都相对这个仓库根。后端仓库只写文档，不改代码。
2. **每个任务收尾：提交 + 推送 `dev`**，用 pathspec 限定（见头部说明），提交后 `git show --stat` 复验只含自己的文件，
   并确认别人的暂存项仍在索引里。
3. **不引入测试框架**（spec §5 的决定：为一个帧解析器装 vitest 不值一套新栈）。
   纯函数用 `node --test <显式文件路径>`（Node 24 已实测支持 `.ts` 直接跑，零依赖）。
   **Windows 实测：目录形式 `node --test src/api/__tests__/` 会把整个目录当成 1 个用例并报 fail，
   必须逐个文件传路径** —— 本计划后续所有 `node --test` 命令按此执行。
   **组件与 `useChat` 的正确性只有浏览器取证**，报告里必须如实这么写，不许暗示有自动化覆盖。
4. **只加 `marked` 与 `dompurify` 两个依赖**，不加 axios、不加状态库、不加 UI 库。
5. **不改 `src/api/request.ts`** —— 它是全站共享传输层（登录/生图都在用），且 `TIMEOUT_MS=15000` 对长流式不适用；
   流式必须走自己的 `fetch`。需要它的地方只有 `apiUrl()`（纯字符串拼接）。
6. **中文文案透传**：业务拒绝条用后端 `Result.msg` 原文，不改写、不吞掉。
7. **不动用户的服务**：8105（AIModule）与 8100（网关）由用户启停。浏览器验收前需要用户重启 8105
   以载入 E 段代码 —— 这是关卡 GF2，不许用"起第二个实例注册进共享 Nacos"绕开。
8. **Windows 陷阱**：PowerShell 控制台是 GBK，判断中文别用 `cat`/`type`，用 `node`/Read 读文件；
   `npm` 命令一律在 `EZ_frontend` 目录下跑。
9. `tsconfig.app.json` 已 `exclude: ["src/**/__tests__/*"]` —— 测试文件放 `__tests__/`，
   这样 `npm run type-check` 不会去检查它们，`node --test` 又能直接跑。

---

## 文件结构

| 动作 | 路径 | 责任 |
|---|---|---|
| Create | `src/api/sse.ts` | 纯函数：字节缓冲→帧→类型化回调。唯一手工解帧处 |
| Create | `src/api/__tests__/sse.test.ts` | 帧切分/半帧/`data:` 空格规则/JSON 拒绝分支/ping 忽略 |
| Create | `src/api/chat.ts` | `#1–#6`、`#8` 的类型与调用；`streamChat` 发请求并把 Response 交给 `sse.ts` |
| Create | `src/composables/useChat.ts` | 全部流式与列表状态、两段式停止、重试复用 `clientMsgId` |
| Create | `src/components/AI/streamMarkdown.ts` | 纯函数 `closeOpenFence`（奇数围栏补齐） |
| Create | `src/components/AI/__tests__/streamMarkdown.test.ts` | 围栏补齐的表驱动用例 |
| Create | `src/components/AI/MarkdownBody.vue` | `marked` + `DOMPurify` 渲染，rAF 合并重解析 |
| Create | `src/components/AI/ConversationList.vue` | 会话列表 + 游标翻页 + 新建 |
| Create | `src/components/AI/MessageItem.vue` | 一条消息：ThoughtLine + 正文 + 错误/重试 + 边界显示 |
| Modify | `src/views/AI/Chat.vue` | 重写为真正的对话页接线（并**必须**把 `#1` 的 models/efforts 传给 PromptBar） |
| Modify | `package.json` / `package-lock.json` | 加 `marked`、`dompurify`（Task F4） |

**本段显式不做**（spec 未要求，YAGNI）：重命名会话（`#4` PATCH）、删除会话（`#5` DELETE）、
附件/语音、多实例停止路由、`Last-Event-ID` 断流续传、思考历史回放（reasoning 不落库，刷新后思考线消失是预期）、
移动端抽屉式列表的精细动画。`request.ts` 的 `RequestOptions.method` 只支持 GET/POST，
这也是把 PATCH/DELETE 留到下一段的现实理由 —— 不为两个用不到的按钮去改共享传输层。

---

## Task F1: 帧解析器（纯函数，可测）

**Files:**
- Create: `src/api/sse.ts`
- Test: `src/api/__tests__/sse.test.ts`

**Interfaces:**
- Consumes: 无（本任务是地基，且不 import `request.ts`，因此能在 Node 里跑）
- Produces:
  - `interface SseFrame { event: string; data: string }`
  - `splitFrames(buffer: string): { frames: SseFrame[]; rest: string }`
  - `parseBlock(block: string): SseFrame | null`
  - `interface MetaPayload { conversationId: number; messageId: number; modelKey: string; effort: string; supportsThinking: boolean }`
  - `interface UsagePayload { promptTokens: number | null; completionTokens: number | null; reasoningTokens: number | null }`
  - `interface ErrorPayload { errorCode: string; msg: string; retryable: boolean }`
  - `interface ChatStreamHandlers { onMeta; onThoughtStep; onThoughtEnd; onDelta; onUsage; onDone; onError }`（全部可选）
  - `dispatchFrame(frame: SseFrame, handlers: ChatStreamHandlers): void`
  - `readEventStream(res: ResponseLike, handlers: ChatStreamHandlers, onReject?: (msg: string) => void): Promise<void>`
  - `type ResponseLike = { headers: { get(k: string): string | null }; body: ReadableStream<Uint8Array> | null; json(): Promise<unknown>; text(): Promise<string> }`

- [x] **Step 1: 写失败测试**

`src/api/__tests__/sse.test.ts`。用例的期望值都按后端**实测**的线上形状手推：我们下游写 `data:{…}`（**无空格**），
上游 DeepSeek 写 `data: {…}`（有空格），解析器必须两种都吃；`ping` 在正文滚动时也会插进来，所以只能忽略不能当结束。

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { splitFrames, parseBlock, dispatchFrame, readEventStream, type SseFrame } from '../sse.ts'

test('splitFrames：完整帧切出来，半帧留在 rest 里', () => {
  const { frames, rest } = splitFrames('event:delta\ndata:{"text":"你"}\n\nevent:delta\ndata:{"te')
  assert.equal(frames.length, 1)
  assert.equal(frames[0].event, 'delta')
  assert.equal(frames[0].data, '{"text":"你"}')
  assert.equal(rest, 'event:delta\ndata:{"te')
})

test('splitFrames：CRLF 与多行 data 都归一', () => {
  const { frames } = splitFrames('event:delta\r\ndata:{"text":"a"}\r\n\r\n')
  assert.deepEqual(frames, [{ event: 'delta', data: '{"text":"a"}' }])
})

test('parseBlock：data: 后最多只去掉一个前导空格', () => {
  assert.equal(parseBlock('data:{"a":1}')?.data, '{"a":1}')
  assert.equal(parseBlock('data: {"a":1}')?.data, '{"a":1}')
  assert.equal(parseBlock('data:  {"a":1}')?.data, ' {"a":1}', '两个空格只吃掉一个')
})

test('parseBlock：忽略注释行与没有 event 名的帧', () => {
  assert.equal(parseBlock(': keep-alive'), null)
  assert.deepEqual(parseBlock('data:{"text":"x"}'), { event: 'message', data: '{"text":"x"}' })
})

test('dispatchFrame：七类事件各自进对应回调，ping 与未知事件什么都不做', () => {
  const seen: string[] = []
  const h = {
    onMeta: () => seen.push('meta'),
    onThoughtStep: () => seen.push('step'),
    onThoughtEnd: () => seen.push('end'),
    onDelta: () => seen.push('delta'),
    onUsage: () => seen.push('usage'),
    onDone: () => seen.push('done'),
    onError: () => seen.push('error')
  }
  const f = (event: string): SseFrame => ({ event, data: '{}' })
  for (const e of ['meta', 'thought_step', 'thought_end', 'delta', 'usage', 'ping', 'done', 'error'])
    dispatchFrame(f(e), h)
  assert.deepEqual(seen, ['meta', 'step', 'end', 'delta', 'usage', 'done', 'error'], 'ping 不产出，且顺序不乱')
})

test('dispatchFrame：thought_step 的 text 与 thought_end 的 seconds 取到值', () => {
  let step = ''
  let seconds = 0
  dispatchFrame({ event: 'thought_step', data: '{"text":"先算乘法。"}' }, { onThoughtStep: t => { step = t } })
  dispatchFrame({ event: 'thought_end', data: '{"seconds":8.4}' }, { onThoughtEnd: s => { seconds = s } })
  assert.equal(step, '先算乘法。')
  assert.equal(seconds, 8.4)
})

test('readEventStream：撕裂字节（7 字节一片）拼回完整帧，一个都不丢', async () => {
  const body =
    'event:meta\ndata:{"conversationId":1,"messageId":2,"modelKey":"deepseek-reasoner","effort":"Medium","supportsThinking":true}\n\n' +
    'event:thought_step\ndata:{"text":"先算乘法。"}\n\n' +
    'event:thought_end\ndata:{"seconds":0.4}\n\n' +
    'event:delta\ndata:{"text":"391"}\n\n' +
    'event:usage\ndata:{"promptTokens":44,"completionTokens":128,"reasoningTokens":55}\n\n' +
    'event:done\ndata:{"finishReason":"done"}\n\n'
  const bytes = new TextEncoder().encode(body)
  const chunks: Uint8Array[] = []
  for (let i = 0; i < bytes.length; i += 7) chunks.push(bytes.slice(i, i + 7))
  const res = {
    headers: { get: () => 'text/event-stream' },
    body: new ReadableStream<Uint8Array>({
      start(c) { for (const ch of chunks) c.enqueue(ch); c.close() }
    }),
    json: async () => ({}),
    text: async () => ''
  }
  const order: string[] = []
  let usage: { reasoningTokens: number | null } | null = null
  await readEventStream(res as never, {
    onMeta: () => order.push('meta'),
    onThoughtStep: () => order.push('step'),
    onThoughtEnd: () => order.push('end'),
    onDelta: () => order.push('delta'),
    onUsage: u => { order.push('usage'); usage = u },
    onDone: () => order.push('done')
  })
  assert.deepEqual(order, ['meta', 'step', 'end', 'delta', 'usage', 'done'])
  assert.equal((usage as unknown as { reasoningTokens: number | null }).reasoningTokens, 55)
})

test('readEventStream：content-type 是 JSON 时走 onReject，绝不进帧解析器', async () => {
  const res = {
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json;charset=UTF-8' : null) },
    body: null,
    json: async () => ({ code: 0, msg: '上一条回答仍在生成' }),
    text: async () => ''
  }
  let rejected = ''
  let delta = 0
  await readEventStream(res as never, { onDelta: () => { delta++ } }, msg => { rejected = msg })
  assert.equal(rejected, '上一条回答仍在生成')
  assert.equal(delta, 0)
})

test('readEventStream：error 帧不抛异常（HTTP 仍是 200），走 onError', async () => {
  const body = 'event:error\ndata:{"errorCode":"UPSTREAM_MODEL_ERROR","msg":"模型服务暂时不可用","retryable":true}\n\n'
  const res = {
    headers: { get: () => 'text/event-stream' },
    body: new ReadableStream<Uint8Array>({ start(c) { c.enqueue(new TextEncoder().encode(body)); c.close() } }),
    json: async () => ({}),
    text: async () => ''
  }
  let err: { retryable: boolean } | null = null
  await readEventStream(res as never, { onError: e => { err = e } })
  assert.equal((err as unknown as { retryable: boolean }).retryable, true)
})
```

- [x] **Step 2: 跑测试确认失败**

Run（在 `EZ_frontend` 根）：`node --test src/api/__tests__/sse.test.ts`
Expected: 模块不存在 → `Cannot find module '.../sse.ts'`，exit code 1。
Actual: RED 确认（`pass 0 / fail 1`）。目录形式在 Windows 下不可靠，见 Global Constraints #3 的偏差记录。

- [x] **Step 3: 写实现**

`src/api/sse.ts`：

```ts
// src/api/sse.ts
/**
 * 唯一的 SSE 手工解帧处。EventSource 用不了：它不能 POST、也不能带 Authorization。
 * 这个文件刻意不 import request.ts / 不用 import.meta.env，所以能被 node --test 直接跑。
 *
 * 后端契约（实测，见 E 段取证日志）：帧以 \n\n 分隔；我们下游写 data:{…}（无空格），
 * 上游 DeepSeek 写 data: {…}（有空格）；ping 在正文正常滚动时也会插进来。
 */

export interface SseFrame {
  event: string
  data: string
}

export interface MetaPayload {
  conversationId: number
  messageId: number
  modelKey: string
  effort: string
  supportsThinking: boolean
}

export interface UsagePayload {
  promptTokens: number | null
  completionTokens: number | null
  reasoningTokens: number | null
}

export interface ErrorPayload {
  errorCode: string
  msg: string
  retryable: boolean
}

export interface ChatStreamHandlers {
  onMeta?(meta: MetaPayload): void
  onThoughtStep?(text: string): void
  onThoughtEnd?(seconds: number): void
  onDelta?(text: string): void
  onUsage?(usage: UsagePayload): void
  onDone?(finishReason: string): void
  onError?(error: ErrorPayload): void
}

/** 只声明用到的部分，测试里能手搓一个假 Response。 */
export interface ResponseLike {
  headers: { get(name: string): string | null }
  body: ReadableStream<Uint8Array> | null
  json(): Promise<unknown>
  text(): Promise<string>
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function asNumberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function parseBlock(block: string): SseFrame | null {
  let event = ''
  const data: string[] = []
  for (const line of block.split('\n')) {
    // 空行与 ": keep-alive" 这类注释行都不是字段
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    // 只吃掉「最多一个」前导空格：后端无空格、上游有空格，两种都得正确
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  if (!event && data.length === 0) return null
  return { event: event || 'message', data: data.join('\n') }
}

export function splitFrames(buffer: string): { frames: SseFrame[]; rest: string } {
  const parts = buffer.replace(/\r\n/g, '\n').split('\n\n')
  // 最后一段一定是还没收 full 的尾巴（网络不按帧给你边界）
  const rest = parts.pop() ?? ''
  const frames: SseFrame[] = []
  for (const block of parts) {
    const frame = parseBlock(block)
    if (frame) frames.push(frame)
  }
  return { frames, rest }
}

export function dispatchFrame(frame: SseFrame, handlers: ChatStreamHandlers): void {
  let payload: Record<string, unknown> = {}
  if (frame.data) {
    try {
      const parsed: unknown = JSON.parse(frame.data)
      if (parsed && typeof parsed === 'object') payload = parsed as Record<string, unknown>
    } catch {
      return // 坏帧丢过去，不让一整条流卡死
    }
  }
  switch (frame.event) {
    case 'meta':
      handlers.onMeta?.({
        conversationId: asNumberOrNull(payload.conversationId) ?? 0,
        messageId: asNumberOrNull(payload.messageId) ?? 0,
        modelKey: asString(payload.modelKey),
        effort: asString(payload.effort),
        supportsThinking: payload.supportsThinking === true
      })
      return
    case 'thought_step':
      handlers.onThoughtStep?.(asString(payload.text))
      return
    case 'thought_end':
      handlers.onThoughtEnd?.(asNumberOrNull(payload.seconds) ?? 0)
      return
    case 'delta':
      handlers.onDelta?.(asString(payload.text))
      return
    case 'usage':
      handlers.onUsage?.({
        promptTokens: asNumberOrNull(payload.promptTokens),
        completionTokens: asNumberOrNull(payload.completionTokens),
        reasoningTokens: asNumberOrNull(payload.reasoningTokens)
      })
      return
    case 'done':
      handlers.onDone?.(asString(payload.finishReason) || 'done')
      return
    case 'error':
      handlers.onError?.({
        errorCode: asString(payload.errorCode),
        msg: asString(payload.msg),
        retryable: payload.retryable === true
      })
      return
    default:
      // ping 与将来新增的事件：忽略，而不是报错。前端不该因为后端多发一类帧就断流。
      return
  }
}

export async function readEventStream(
  res: ResponseLike,
  handlers: ChatStreamHandlers,
  onReject?: (msg: string) => void
): Promise<void> {
  const contentType = res.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    // 建流前的业务拒绝是 Result(JSON)。对着 JSON 跑帧解析器表现为「静默无响应」，极难排查，
    // 所以这一步必须在读 body 之前判掉。
    let msg = '请求被拒绝'
    try {
      const body: unknown = await res.json()
      if (body && typeof body === 'object') {
        const envelope = body as { msg?: unknown; message?: unknown }
        msg = asString(envelope.msg) || asString(envelope.message) || msg
      }
    } catch {
      // JSON 解析失败就用兜底文案
    }
    onReject?.(msg)
    return
  }
  if (!res.body) return

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    // { stream: true } 的语义：多字节字符被劈在两片之间时不乱码
    buffer += decoder.decode(value, { stream: true })
    const { frames, rest } = splitFrames(buffer)
    buffer = rest
    for (const frame of frames) dispatchFrame(frame, handlers)
  }
  buffer += decoder.decode()
  const { frames } = splitFrames(`${buffer}\n\n`)
  for (const frame of frames) dispatchFrame(frame, handlers)
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `node --test src/api/__tests__/sse.test.ts`（目录形式在 Windows 下会把整个目录当 1 个用例并报 fail，见 Global Constraints #3）
Expected: `pass 9, fail 0`（9 条）。
Actual: **`tests 10 / pass 10 / fail 0`** —— 比计划多 1 条，因为 Step 5 的变异 5 存活暴露了「收尾补空行」无人守护，补了第 10 条用例。

- [x] **Step 5: 变异取证**

1. 把 `if (value.startsWith(' ')) value = value.slice(1)` 改成 `value = value.replace(/^ +/, '')`（吃掉全部空格）
   → 期望 `parseBlock：data: 后最多只去掉一个前导空格` 红。
2. 把 `splitFrames` 里 `parts.pop()` 去掉（直接全当完整帧）
   → 期望半帧那条 + 撕裂字节那条一起红。
3. 把 `default: return` 改成 `default: handlers.onDone?.('done')`
   → 期望 `ping 与未知事件什么都不做` 那条红。
4. 把 `readEventStream` 开头的 content-type 判定删掉
   → 期望 JSON 拒绝那条红（`rejected` 为空）。
恢复后 `grep -rn "MUTATION" src/` 必须为 0（PowerShell GBK 只影响控制台，不影响匹配）。

**结果**（每条变异都单独打、单独恢复，恢复后复跑全绿；最终 `grep -rn "MUTATION" src/` = 0）：
- 变异 1 → 只有 `parseBlock：data: 后最多只去掉一个前导空格` 红，失败信息就是「两个空格只吃掉一个」那条断言。
- 变异 2 → 半帧那条 + 撕裂字节那条**一起红**（`pass 7 / fail 2`），与计划一致。
- 变异 3 → 只有 ping 那条红。
- 变异 4 → 只有 JSON 拒绝那条红。
- **变异 5（计划外，我自己补的）先存活**：把收尾 `splitFrames(\`${buffer}\n\n\`)` 改成 `splitFrames(buffer)`，9 条全绿
  ⇒ 「最后一帧不留空行也要发出来」这条规则当时没有守护。这是缺口不是噪声，处理方式是**补用例而不是删兜底**：
  新增第 10 条 `readEventStream：流结束时最后一帧没补空行也要发出来`，回打变异 5 后它单独红（`pass 9 / fail 1`），恢复后 10/10 绿。

- [x] **Step 6: 提交并推送**

```bash
git add src/api/sse.ts src/api/__tests__/sse.test.ts
git commit -m "思考线 F1：SSE 手工解帧纯函数（含半帧与双空格规则）" -- src/api/sse.ts src/api/__tests__/sse.test.ts
git show --stat --oneline HEAD
git push origin dev
```

**检查点**：`node --test src/api/__tests__/sse.test.ts` 全绿；`npm run type-check` 不因新文件报错（`__tests__` 被 exclude，`sse.ts` 要过）。
实测 `vue-tsc --build` 无输出（成功）。

**F1 执行记录**
- 实现与计划一致，唯一结构差异是 `ResponseLike` 只声明用到的成员（`headers.get` / `body` / `json` / `text`），
  测试仍用 `res as never` 传入 —— 留给 F2 收敛成真实的 `Response` 兼容形态。
- 与计划的偏差共 2 条，都已就地标明：① `node --test` 必须传显式文件路径；② 用例从 9 条增到 10 条（变异 5 存活所致）。
- 本任务由主会话实现并自审，**未做独立复核轮**（用户要求不派子代理）。

---

## Task F2: 接口层 `src/api/chat.ts`

**Files:**
- Create: `src/api/chat.ts`

**Interfaces:**
- Consumes: `request`/`apiUrl`（`src/api/request.ts`，**不改它**）；F1 的 `readEventStream`、`ChatStreamHandlers`、`MetaPayload`、`UsagePayload`
- Produces:
  - 类型 `ChatModel`、`ModelCatalog`、`Conversation`、`ConversationPage`、`ChatUsage`、`ChatMessage`、`MessagePage`、`StreamPayload`
  - `fetchModelCatalog(): Promise<ModelCatalog>`、`createConversation(body): Promise<Conversation>`、
    `listConversations(cursor?): Promise<ConversationPage>`、`listMessages(id, beforeId?): Promise<MessagePage>`、
    `stopMessage(id): Promise<null>`
  - `streamChat(payload, handlers: ChatStreamHandlers & { onReject?(msg: string): void }, token: string | null, signal?: AbortSignal): Promise<void>`
  - `newClientMsgId(): string`

- [x] **Step 1: 写实现**

类型形状逐条对齐后端 record（`ModelVo`/`ConversationVo`/`MessageVo`/`MessagePageVo`/`ModelCatalogVo`/`StreamRequest`），
**字段名与可空性都不许凭感觉写**：`MessageVo.usage` 是 `ChatUsageVo{promptTokens,completionTokens}`（没有 reasoningTokens，
因为 reasoning 不落库），`MessagePageVo` 的列表字段叫 `items` 不是 `messages`，`ConversationPageVo` 是 `{items, nextCursor}`。

```ts
// src/api/chat.ts
import { apiUrl, request } from './request'
import { readEventStream, type ChatStreamHandlers } from './sse'

export interface ChatModel {
  key: string
  name: string
  tag?: string
  supportsThinking: boolean
}

export interface ModelCatalog {
  defaultModel: string
  defaultEffort: string
  efforts: string[]
  models: ChatModel[]
}

export interface Conversation {
  id: number
  title: string
  modelKey: string
  effort: string
  messageCount: number
  lastPreview: string
  /** 后端返回 epoch 毫秒（LocalDateTime 经 Jackson 的输出） */
  updateTime: number
}

export interface ConversationPage {
  items: Conversation[]
  nextCursor: string | null
}

export interface ChatUsage {
  promptTokens: number | null
  completionTokens: number | null
}

export interface ChatMessage {
  id: number
  conversationId: number
  role: 'user' | 'assistant'
  content: string
  status: 'streaming' | 'done' | 'failed' | 'truncated' | 'superseded'
  modelKey: string | null
  effort: string | null
  stopReason: string | null
  usage: ChatUsage | null
  createTime: number
}

export interface MessagePage {
  hasMore: boolean
  items: ChatMessage[]
}

export interface StreamPayload {
  conversationId: number
  content?: string
  modelKey?: string
  effort?: string
  clientMsgId?: string
  regenerate?: boolean
}

export const fetchModelCatalog = (): Promise<ModelCatalog> =>
  request<ModelCatalog>('/ai/chat/models')

export const createConversation = (body: { modelKey?: string; effort?: string }): Promise<Conversation> =>
  request<Conversation>('/ai/chat/conversations', { method: 'POST', body })

export const listConversations = (cursor?: string): Promise<ConversationPage> =>
  request<ConversationPage>(
    `/ai/chat/conversations${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`
  )

export const listMessages = (id: number, beforeId?: number): Promise<MessagePage> =>
  request<MessagePage>(
    `/ai/chat/conversations/${id}/messages${beforeId ? `?beforeId=${beforeId}` : ''}`
  )

export const stopMessage = (id: number): Promise<null> =>
  request<null>(`/ai/chat/messages/${id}/stop`, { method: 'POST' })

/**
 * 幂等键。后端靠 uk_conv_client 保证重试不产生第二条用户消息，所以重试必须复用同一个值 ——
 * 这也是为什么它由调用方（useChat）持有、而不是在这里现生成。
 */
export function newClientMsgId(): string {
  return crypto.randomUUID()
}

export interface StreamHandlers extends ChatStreamHandlers {
  /** 建流前的业务拒绝（HTTP 200 + Result JSON），msg 是后端中文原文 */
  onReject?(msg: string): void
}

/**
 * 不走 request.ts：它有 15s AbortSignal.timeout，长流式必然被自己掐断。
 * Accept 给双值：R16 之后单值也能拿到 JSON 拒绝，但双值仍是正确写法。
 */
export async function streamChat(
  payload: StreamPayload,
  handlers: StreamHandlers,
  token: string | null,
  signal?: AbortSignal
): Promise<void> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream, application/json'
  }
  // 网关严格判前缀：必须正好是 "Bearer "
  if (token) headers.Authorization = `Bearer ${token}`

  const res = await fetch(apiUrl('/ai/chat/completions/stream'), {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
    signal
  })
  await readEventStream(res, handlers, handlers.onReject)
}
```

- [x] **Step 2: 类型检查**

Run: `npm run type-check`
Expected: 通过。这一步同时验证 `sse.ts` 的类型被正确复用 —— `readEventStream(res, …)` 里 `Response` 要能赋给
`ResponseLike`；若报结构化不兼容，**改 `ResponseLike` 的声明去贴合 `Response`**，不要靠 `as never` 糊过去。
Actual: 一次通过，`Response` 天然满足 `ResponseLike`（`headers.get`/`body`/`json`/`text` 四个成员都可赋），无需改声明。
**并验证了「type-check 真的覆盖到这个新文件」**：往 `chat.ts` 追加 `const MUTCHECK: number = "a"` 后
`vue-tsc --build` 报 `src/api/chat.ts(131,7): error TS2322`，撤掉后复跑无输出 —— 否则「绿灯」可能只是根本没检查。

- [x] **Step 3: 提交并推送**

```bash
git add src/api/chat.ts
git commit -m "思考线 F2：chat 接口层（#1–#6、#8 与 streamChat）" -- src/api/chat.ts
git show --stat --oneline HEAD
git push origin dev
```

提交 `247098d`（1 file changed, 129 insertions），推送 `20550e6..247098d dev -> dev`。
`git show --stat` 复验只含 `src/api/chat.ts`；别人暂存的 `ShinyText.vue` 仍在索引里（`git status` 显示 `AM`）、`Home.vue` 仍是 ` M`。

**检查点**：`npm run type-check` 绿。本任务**没有 Node 用例**：`chat.ts` import 了 `request.ts`，
后者读 `import.meta.env.VITE_API_BASE`，在 Node 里拿不到 Vite 注入 → 硬凑测试要改共享传输层（Global Constraint 5 禁止）。
它的正确性由 F1 的解析用例 + type-check + F6 浏览器验收共同负责，报告里要这么如实写。

**F2 执行记录**
- 字段名先取证后落笔：逐个 `cat` 过 `chat/web/dto/` 的 8 个 record，与计划一致（`items` 而非 `messages`、
  `ChatUsageVo` 无 `reasoningTokens`、`ConversationPageVo{items,nextCursor}`）。
- 顺带确认了 query 参数名：`list` 是 `cursor`+`limit`(默认 20)、`messages` 是 `beforeId`+`limit`(默认 30)。
  本层不暴露 `limit`（默认值就是我们要的），加了也是无人传参的分支。
- `StreamRequest.regenerate` 是原始 `boolean`，序列化后恒有值；TS 侧仍写成可选（不传时后端按 false），
  这是前端调用方便、不是对契约的放宽。
- 偏差：无功能级偏差；只补了一条 Step 2 的「type-check 覆盖性」取证。
- 本任务由主会话实现并自审，**未做独立复核轮**。

---

## Task F3: 流式状态机 `src/composables/useChat.ts`

**Files:**
- Create: `src/composables/useChat.ts`

**Interfaces:**
- Consumes: F2 全部导出；F1 的 `MetaPayload`/`UsagePayload`；`useAuthStore().token`
- Produces:
  - `interface ChatTurn`（UI 形状的一条消息，字段见实现）
  - `useChat()` 返回 `{ catalog, catalogError, conversations, nextCursor, activeId, messages, sending, errorText, currentModelSupportsThinking, loadCatalog, loadConversations, moreConversations, openConversation, startConversation, send, stop, regenerate, retry }`

- [ ] **Step 1: 写实现**

三条设计要点必须留在代码注释里，因为它们是**上一期踩过/证伪过的**：两段式停止、`clientMsgId` 复用、
`reasoning` 不落库导致的"刷新后思考线消失"。

```ts
// src/composables/useChat.ts
import { computed, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import {
  createConversation,
  fetchModelCatalog,
  listConversations,
  listMessages,
  newClientMsgId,
  stopMessage,
  streamChat,
  type ChatModel,
  type Conversation,
  type ModelCatalog,
  type StreamPayload,
  type UsagePayload
} from '../api/chat'

/** 一条消息在 UI 里的完整形状：历史行与流式中的行共用同一个类型，模板里就不用分两套分支。 */
export interface ChatTurn {
  key: string
  id?: number
  role: 'user' | 'assistant'
  content: string
  status?: ChatTurnStatus
  stopReason?: string | null
  usage?: UsagePayload | null
  steps: string[]
  thinkingOpen: boolean
  /** 秒。ThoughtLine 的 elapsed 就是秒（它内部 ×10） */
  elapsed: number | null
  settled: boolean
  failed: boolean
  retryable: boolean
  /** 历史行的 reasoning 不落库，所以 steps 是空的；只有本次会话内跑过的流才有思考线 */
  fromHistory: boolean
  clientMsgId?: string
}

type ChatTurnStatus = 'streaming' | 'done' | 'failed' | 'truncated' | 'superseded'

export function useChat() {
  const auth = useAuthStore()

  const catalog = ref<ModelCatalog | null>(null)
  const catalogError = ref('')
  const conversations = ref<Conversation[]>([])
  const nextCursor = ref<string | null>(null)
  const activeId = ref<number | null>(null)
  const messages = ref<ChatTurn[]>([])
  const sending = ref(false)
  const errorText = ref('')

  let turn: ChatTurn | null = null
  let controller: AbortController | null = null
  /** 重试复用同一个幂等键；新一轮发送才换新值 */
  let lastPayload: StreamPayload | null = null

  const currentModel = computed<ChatModel | null>(() => {
    const key = activeModelKey()
    return catalog.value?.models.find(m => m.key === key) ?? catalog.value?.models[0] ?? null
  })
  const currentModelSupportsThinking = computed(() => currentModel.value?.supportsThinking === true)

  function activeModelKey(): string | undefined {
    return conversations.value.find(c => c.id === activeId.value)?.modelKey ?? catalog.value?.defaultModel
  }

  const loadCatalog = async (): Promise<void> => {
    if (catalog.value) return
    try {
      catalog.value = await fetchModelCatalog()
    } catch (err) {
      catalogError.value = messageOf(err)
    }
  }

  const loadConversations = async (): Promise<void> => {
    try {
      const page = await listConversations()
      conversations.value = page.items
      nextCursor.value = page.nextCursor
    } catch (err) {
      errorText.value = messageOf(err)
    }
  }

  const moreConversations = async (): Promise<void> => {
    const cursor = nextCursor.value
    if (!cursor) return
    try {
      const page = await listConversations(cursor)
      const known = new Set(conversations.value.map(c => c.id))
      conversations.value = [...conversations.value, ...page.items.filter(c => !known.has(c.id))]
      nextCursor.value = page.nextCursor
    } catch (err) {
      errorText.value = messageOf(err)
    }
  }

  const openConversation = async (id: number): Promise<void> => {
    abortLocal('切换会话')
    activeId.value = id
    errorText.value = ''
    messages.value = []
    try {
      const page = await listMessages(id)
      // 后端按时间升序返回历史；思考线不渲染（fromHistory=true 且 steps 为空）
      messages.value = page.items.map(toTurn)
    } catch (err) {
      errorText.value = messageOf(err)
    }
  }

  const startConversation = async (): Promise<void> => {
    abortLocal('新建会话')
    try {
      const model = currentModel.value
      const created = await createConversation({
        modelKey: model?.key,
        effort: catalog.value?.defaultEffort
      })
      conversations.value = [created, ...conversations.value]
      activeId.value = created.id
      messages.value = []
      errorText.value = ''
    } catch (err) {
      errorText.value = messageOf(err)
    }
  }

  const send = async (text: string, modelKey?: string, effort?: string): Promise<void> => {
    if (!text || sending.value) return
    if (activeId.value == null) await startConversation()
    const conversationId = activeId.value
    if (conversationId == null) return

    const clientMsgId = newClientMsgId()
    lastPayload = {
      conversationId,
      content: text,
      modelKey: modelKey ?? activeModelKey(),
      effort: effort ?? catalog.value?.defaultEffort,
      clientMsgId,
      regenerate: false
    }
    await run(lastPayload)
  }

  /** 重新生成：不带 content，后端按 regenerate 复用上个问题并 supersede 旧答案。 */
  const regenerate = async (): Promise<void> => {
    if (sending.value || activeId.value == null || !lastPayload) return
    await run({
      conversationId: activeId.value,
      modelKey: lastPayload.modelKey,
      effort: lastPayload.effort,
      clientMsgId: newClientMsgId(),
      regenerate: true
    })
  }

  /** 重试复用同一个 clientMsgId，靠 uk_conv_client 保证不产生第二条用户消息。 */
  const retry = async (): Promise<void> => {
    if (sending.value || !lastPayload) return
    await run(lastPayload)
  }

  const stop = (): void => {
    // 两段式：先本地冻结（用户立刻看到停住了），再异步通知后端。
    // 反过来等 /stop 返回，卡的就是网关一个往返，用户 perceive 到的是"停止按钮没反应"。
    if (turn) {
      turn.settled = true
      turn.thinkingOpen = false
      turn.status = 'truncated'
    }
    sending.value = false
    controller?.abort()
    const id = turn?.id
    if (id != null) void stopMessage(id).catch(() => undefined)
  }

  const run = async (payload: StreamPayload): Promise<void> => {
    sending.value = true
    errorText.value = ''
    const question = payload.content
    if (question) messages.value.push({ key: `u-${payload.clientMsgId}`, role: 'user', content: question, steps: [], thinkingOpen: false, elapsed: null, settled: true, failed: false, retryable: false, fromHistory: false })

    turn = {
      key: `a-${payload.clientMsgId ?? 'regenerate'}`,
      role: 'assistant',
      content: '',
      status: 'streaming',
      steps: [],
      thinkingOpen: false,
      elapsed: null,
      settled: false,
      failed: false,
      retryable: false,
      fromHistory: false,
      clientMsgId: payload.clientMsgId
    }
    messages.value.push(turn)

    controller = new AbortController()
    const handlers = {
      onMeta: (meta: { messageId: number; supportsThinking: boolean }) => {
        if (turn) turn.id = meta.messageId
        if (turn && !meta.supportsThinking) turn.thinkingOpen = false
      },
      onThoughtStep: (step: string) => {
        if (!turn || !step) return
        turn.thinkingOpen = true
        turn.steps.push(step)
      },
      onThoughtEnd: (seconds: number) => {
        if (!turn) return
        turn.thinkingOpen = false
        turn.elapsed = seconds
      },
      onDelta: (piece: string) => {
        if (turn && piece) turn.content += piece
      },
      onUsage: (usage: UsagePayload) => {
        if (turn) turn.usage = usage
      },
      onDone: (reason: string) => {
        if (!turn) return
        turn.settled = true
        turn.thinkingOpen = false
        turn.status = reason === 'upstream' ? 'failed' : reason === 'stopped' || reason === 'length' || reason === 'truncated' ? 'truncated' : 'done'
        turn.stopReason = reason
      },
      onError: (err: { msg: string; retryable: boolean }) => {
        if (!turn) return
        turn.failed = true
        turn.retryable = err.retryable
        turn.settled = true
        turn.thinkingOpen = false
        errorText.value = err.msg
      },
      onReject: (msg: string) => {
        // 业务拒绝：这条 assistant 占位根本没开始生成，撤掉它，问题行留着好改
        errorText.value = msg
        const index = messages.value.indexOf(turn as ChatTurn)
        if (index >= 0) messages.value.splice(index, 1)
        if (turn && turn.key.startsWith('u-')) return
        const q = messages.value.findIndex(m => m.key === `u-${payload.clientMsgId}`)
        if (q >= 0) messages.value[q].content = question ?? messages.value[q].content
        turn = null
      }
    }

    try {
      await streamChat(payload, handlers, auth.token, controller.signal)
    } catch (err) {
      if (!isAbort(err)) errorText.value = messageOf(err)
      if (turn) turn.settled = true
    } finally {
      sending.value = false
      if (turn) {
        turn.settled = true
        turn.thinkingOpen = false
      }
      controller = null
      void loadConversations()
    }
  }

  const abortLocal = (reason: string): void => {
    if (turn && !turn.settled) {
      turn.settled = true
      turn.thinkingOpen = false
    }
    controller?.abort()
    controller = null
    void reason
  }

  return {
    catalog, catalogError, conversations, nextCursor, activeId, messages, sending, errorText,
    currentModel, currentModelSupportsThinking,
    loadCatalog, loadConversations, moreConversations, openConversation, startConversation,
    send, stop, regenerate, retry
  }
}

function toTurn(item: { id: number; conversationId: number; role: 'user' | 'assistant'; content: string; status: ChatTurnStatus; stopReason: string | null; usage: { promptTokens: number | null; completionTokens: number | null } | null }): ChatTurn {
  return {
    key: `h-${item.id}`,
    id: item.id,
    role: item.role,
    content: item.content,
    status: item.status,
    stopReason: item.stopReason,
    usage: item.usage ? { ...item.usage, reasoningTokens: null } : null,
    steps: [],
    thinkingOpen: false,
    elapsed: null,
    settled: true,
    failed: item.status === 'failed',
    retryable: false,
    fromHistory: true
  }
}

function messageOf(err: unknown): string {
  return err instanceof Error && err.message ? err.message : '请求失败，请稍后重试'
}

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError'
}
```

- [ ] **Step 2: 类型检查**

Run: `npm run type-check`
Expected: 通过。`toTurn` 的入参类型直接取自 `ChatMessage`（F2）—— 若这里报结构不兼容，
**改 `toTurn` 的参数类型为 `ChatMessage`**（F2 已导出），不要在模板侧放宽。

- [ ] **Step 3: 自查三条不变式（读代码逐条回答，写进报告）**

1. `stop()` 里**没有** `await stopMessage(...)` —— 有就违反两段式。
2. `retry()` 复用 `lastPayload`（含原 `clientMsgId`），`regenerate()` **换新** `clientMsgId` 且 `regenerate:true`。
3. `onThoughtStep` 只在 `handlers` 里改 `turn.steps`，别处不写 `steps`；`toTurn`（历史）恒 `steps: []` —— 这是
   "reasoning 不落库 → 刷新后思考线消失"的落点。

- [ ] **Step 4: 提交并推送**

```bash
git add src/composables/useChat.ts
git commit -m "思考线 F3：useChat 流式状态机（两段式停止、重试复用幂等键）" -- src/composables/useChat.ts
git show --stat --oneline HEAD
git push origin dev
```

**检查点**：`npm run type-check` 绿。本任务无 Node 用例（`useChat` import 了 F2→`request.ts`→`import.meta.env`，
Vue 响应式 + Vite 专用变量在 Node 里跑不通）。它的正确性靠 Step 3 的逐条自查 + F6 浏览器验收，报告里如实写。

---

## Task F4: 流式 Markdown 安全渲染

**Files:**
- Create: `src/components/AI/streamMarkdown.ts`
- Create: `src/components/AI/MarkdownBody.vue`
- Test: `src/components/AI/__tests__/streamMarkdown.test.ts`
- Modify: `package.json`、`package-lock.json`（加两个依赖）

**Interfaces:**
- Consumes: 无
- Produces:
  - `closeOpenFence(src: string): string`
  - `toSafeHtml(src: string): string`（marked → DOMPurify，外链补 `rel="noopener"`）
  - `MarkdownBody.vue` props：`{ source: string; done?: boolean }`

- [ ] **Step 1: 装依赖（关卡 GF1）**

Run: `npm i marked dompurify`
Expected: `package.json` 的 `dependencies` 多出这两项。规格 §5.4 已选定这两个库，
理由必须留在计划里：**模型输出是不可信内容，`v-html` 直插是 XSS 口子**，所以 sanitize 不是可选项。

- [ ] **Step 2: 写失败测试**

`src/components/AI/__tests__/streamMarkdown.test.ts`。坑的具体形状：模型吐到 ```` ```java ```` 而闭合围栏还没来时，
marked 会把后面整段吞进代码块，正文突然整块变灰。

```ts
import test from 'node:test'
import assert from 'node:assert/strict'
import { closeOpenFence } from '../streamMarkdown.ts'

test('奇数个围栏时补一个闭合围栏，偶数时原样', () => {
  assert.equal(closeOpenFence('abc'), 'abc')
  assert.equal(closeOpenFence('a\n```java\ncode'), 'a\n```java\ncode\n```')
  assert.equal(closeOpenFence('a\n```\nx\n```'), 'a\n```\nx\n```', '已闭合的不能再补')
  assert.equal(closeOpenFence('```a\n```b\n```c'), '```a\n```b\n```c\n```', '三个围栏 → 最后一个未闭合')
})

test('行内反引号与四反引号不算围栏（不该被误判成未闭合块）', () => {
  assert.equal(closeOpenFence('用 `code` 说明'), '用 `code` 说明')
  assert.equal(closeOpenFence('```` 外层\n``` 内层\n````'), '```` 外层\n``` 内层\n````')
})

test('补出来的围栏一定在行首（marked 只认行首围栏）', () => {
  const out = closeOpenFence('x\n```y')
  assert.ok(out.endsWith('\n```'), out)
})
```

- [ ] **Step 3: 跑测试确认失败**

Run: `node --test src/components/AI/__tests__/`
Expected: 模块不存在 → 红。

- [ ] **Step 4: 写实现**

`src/components/AI/streamMarkdown.ts`：

```ts
// 围栏必须**在行首**才算块级围栏；行内 `code` 与更长的 ```` 围栏不参与计数。
const FENCE = /^[ \t]{0,3}(`{3,}|~{3,})/gm

/** 奇数个围栏时补一个闭合围栏，偶数时原样。 */
export function closeOpenFence(src: string): string {
  const fences = src.match(FENCE)
  if (!fences || fences.length % 2 === 0) return src
  // 只补与最后一个开围栏同字符同长度的那一串，否则 ``` 开头却要补 ~~~ 就还是没闭合
  const last = fences[fences.length - 1].trim()
  return `${src}\n${last}`
}

/**
 * 给所有带 target 的 <a> 补 rel=noopener，防 tabnabbing。
 * 写成纯字符串处理而不是 DOMPurify hook：DOMPurify 需要 window，
 * 这样在 Node 里也能证伪（spec §5.4 要求"链接统一 rel=noopener"）。
 */
export function decorateLinks(html: string): string {
  return html.replace(/<a\b([^>]*)>/gi, (tag, attrs: string) => {
    if (!/\btarget\s*=/i.test(attrs)) return tag
    if (/\brel\s*=\s*"[^"]*noopener[^"]*"/i.test(attrs)) return tag
    const kept = attrs.replace(/\brel\s*=\s*"[^"]*"/gi, '')
    return `<a${kept} rel="noopener noreferrer">`
  })
}
```

`decorateLinks` 的两条用例（追加到同一个测试文件）：

```ts
import { closeOpenFence, decorateLinks } from '../streamMarkdown.ts'

test('decorateLinks：有 target 才补 rel，已有 noopener 的不重复补', () => {
  assert.equal(
    decorateLinks('<a href="https://x.dev" target="_blank">x</a>'),
    '<a href="https://x.dev" target="_blank" rel="noopener noreferrer">x</a>'
  )
  assert.equal(
    decorateLinks('<a href="https://x.dev" rel="noopener" target="_blank">x</a>'),
    '<a href="https://x.dev" rel="noopener" target="_blank">x</a>'
  )
  assert.equal(
    decorateLinks('<a href="https://x.dev">x</a>'),
    '<a href="https://x.dev">x</a>',
    '没有 target 就不该动它'
  )
})
```

**注意**：这条规则把"四反引号包三反引号"的用例判为**未闭合**还是**已闭合**？
```` ```` 外层 / ``` 内层 / ```` ```` 是 3 个匹配（偶数？奇数？）—— 请按 `FENCE` 的实际匹配结果推导，
**推出什么就断言什么**，不许为了让用例好看改断言。上面测试块里那条的期望值就是按"FENCE 只认行首、
`{3,}` 贪婪、故匹配到 ```` ```` ````、` ``` `、```` ```` ```` 共 3 个 → 奇数 → 补齐"来的，
所以第二条期望值应改为 `'```` 外层\n``` 内层\n````\n````'`，并保留注释说明这是**已知取舍**
（流式期间多包一次围栏只影响瞬时外观，`done` 后用原文重解析定稿）。

**执行时先跑一遍再看实际值**：如果 `FENCE` 匹配到 4 个（把 `x` 行也算进去），就把期望值改成不补齐。
两种结果都可接受，但必须写清是哪种、为什么。

- [ ] **Step 5: 写渲染组件**

`src/components/AI/MarkdownBody.vue`：

```vue
<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { onBeforeUnmount, ref, watch } from 'vue'
import { closeOpenFence, decorateLinks } from './streamMarkdown'

const props = defineProps<{ source: string; done?: boolean }>()

const html = ref('')
let frame = 0

marked.setOptions({ gfm: true, breaks: false })

/** 模型输出不可信：每次更新都要重新过 DOMPurify，不是只做首次。 */
const render = (): void => {
  const raw = props.done ? props.source : closeOpenFence(props.source)
  const unsafe = marked.parse(raw, { async: false })
  html.value = decorateLinks(
    DOMPurify.sanitize(unsafe, {
      USE_PROFILES: { html: true },
      FORBID_TAGS: ['style', 'form', 'input', 'iframe', 'script'],
      FORBID_ATTR: ['style']
    })
  )
}

/** delta 只累积字符串，渲染用 rAF 合并：每帧重解析是 O(n²)，长回答会把浏览器钉住。 */
const schedule = (): void => {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    render()
  })
}

watch(() => props.source, schedule)
watch(
  () => props.done,
  () => {
    if (frame) {
      cancelAnimationFrame(frame)
      frame = 0
    }
    render()
  }
)
render()

onBeforeUnmount(() => {
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html —— 上面已过 DOMPurify，这是本段唯一允许的 v-html -->
  <div class="md" v-html="html" />
</template>

<style scoped>
.md {
  font-size: 15px;
  line-height: 1.65;
  color: var(--text);
  overflow-wrap: anywhere;
}
.md :deep(pre) {
  overflow-x: auto;
  padding: 12px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--text) 6%, transparent);
}
.md :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.92em;
}
.md :deep(a) {
  color: inherit;
  text-decoration: underline;
}
.md :deep(table) {
  border-collapse: collapse;
}
.md :deep(th),
.md :deep(td) {
  padding: 4px 10px;
  border: 1px solid var(--border);
}
.md :deep(> :first-child) {
  margin-top: 0;
}
.md :deep(> :last-child) {
  margin-bottom: 0;
}
</style>
```

外链安全由 `decorateLinks` 兜：任何带 `target` 的 `<a>` 一律补 `rel="noopener noreferrer"`（spec §5.4 的硬要求），
它写成纯字符串函数而不是 DOMPurify hook，原因是 DOMPurify 需要 `window`、hook 在 Node 里测不了，
而这条规则恰恰需要一条能跑的用例。

- [ ] **Step 6: 跑测试 + 类型检查**

Run: `node --test src/components/AI/__tests__/` && `npm run type-check`
Expected: 全绿（围栏用例的期望值按 Step 4 的推导修正后）。

- [ ] **Step 7: 提交并推送**

```bash
git add src/components/AI/streamMarkdown.ts src/components/AI/MarkdownBody.vue src/components/AI/__tests__/streamMarkdown.test.ts package.json package-lock.json
git commit -m "思考线 F4：marked + DOMPurify 流式 Markdown 渲染（rAF 合并、围栏补齐）" -- src/components/AI/streamMarkdown.ts src/components/AI/MarkdownBody.vue src/components/AI/__tests__/streamMarkdown.test.ts package.json package-lock.json
git show --stat --oneline HEAD
git push origin dev
```

**检查点**：Node 用例全绿；`type-check` 绿；`package.json` 只多两个 dependency，没动 devDependencies。

---

## Task F5: 会话列表与消息气泡（含思考线接线）

**Files:**
- Create: `src/components/AI/ConversationList.vue`
- Create: `src/components/AI/MessageItem.vue`

**Interfaces:**
- Consumes: F3 的 `ChatTurn`；`ThoughtLine.vue`（现成组件，props 见 Step 2）；F4 的 `MarkdownBody.vue`
- Produces: 两个组件的 props/emits 契约（F6 的 `Chat.vue` 依赖它们）

- [ ] **Step 1: 写 ConversationList.vue**

```vue
<script setup lang="ts">
import type { Conversation } from '../../api/chat'

const props = defineProps<{
  items: Conversation[]
  activeId: number | null
  hasMore: boolean
}>()

const emit = defineEmits<{
  select: [id: number]
  create: []
  more: []
}>()

const fmtTime = (ms: number): string => {
  if (!Number.isFinite(ms)) return ''
  const d = new Date(ms)
  const today = new Date()
  const sameDay = d.toDateString() === today.toDateString()
  return sameDay
    ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

void props
</script>

<template>
  <aside class="conv">
    <button class="conv-new" @click="emit('create')">＋ 新对话</button>

    <ul class="conv-list">
      <li v-for="item in items" :key="item.id">
        <button
            class="conv-item"
            :data-active="item.id === activeId ? '' : undefined"
            @click="emit('select', item.id)"
        >
          <span class="conv-title">{{ item.title }}</span>
          <span class="conv-meta">
            {{ item.effort }} · {{ item.modelKey }} · {{ fmtTime(item.updateTime) }}
          </span>
        </button>
      </li>
    </ul>

    <button v-if="hasMore" class="conv-more" @click="emit('more')">加载更多</button>
    <p v-if="!items.length" class="conv-empty">还没有对话</p>
  </aside>
</template>

<style scoped>
.conv {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 232px;
  flex: 0 0 232px;
  padding: 12px 8px;
  border-right: 1px solid var(--border);
  overflow: hidden;
}
.conv-new {
  height: 36px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  font: inherit;
  cursor: pointer;
}
.conv-new:hover { background: var(--sb-hover); }
.conv-list {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.conv-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.conv-item:hover { background: var(--sb-hover); }
.conv-item[data-active] { background: var(--sb-active); }
.conv-title {
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-weight: 500;
}
.conv-meta {
  font-size: 12px;
  color: var(--text-muted);
}
.conv-more {
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  cursor: pointer;
}
.conv-empty {
  margin: 8px 4px;
  font-size: 13px;
  color: var(--text-muted);
}
@media (max-width: 900px) {
  .conv { display: none; }
}
</style>
```

小屏直接隐藏列表（不是本段范围，见"显式不做"），但 `Chat.vue` 要留一个入口按钮能切出来，
所以列表组件本身不掺和显隐逻辑 —— 由父级 `v-if` 控制，`display:none` 那条媒体查询删掉，
改成父级控制。**执行时按这条改**（计划正文已把结论写死：组件不做显隐）。

- [ ] **Step 2: 写 MessageItem.vue（思考线接线的落点）**

`ThoughtLine` 的真实契约（已读源码核对）：`steps: string[]`、`working: boolean`、`elapsed?: number`（**单位秒**，
组件内 `Math.round(elapsed * 10)` 转成内部的十分之一秒）、`doneLabel?: string`、`collapseOnSettle`（默认 true）、
`label`（默认 `Thinking…`）。中文文案与"思考中断"由这里传，不改 ThoughtLine 组件。

```vue
<script setup lang="ts">
import MarkdownBody from './MarkdownBody.vue'
import ThoughtLine from './ThoughtLine.vue'
import type { ChatTurn } from '../../composables/useChat'

const props = defineProps<{ turn: ChatTurn }>()

const emit = defineEmits<{ retry: [id: number | undefined]; regenerate: [] }>()

/**
 * 只有真的跑过流才渲染思考线：reasoning 不落库，历史行的 steps 恒空，
 * 刷新重进会话时思考线消失是预期结果，不是 bug。
 */
const showThought = computed(() => props.turn.steps.length > 0 || props.turn.thinkingOpen)

/** truncated + 空正文 = 用户在模型还在思考时就按了停止。 */
const stoppedWhileThinking = computed(
  () => props.turn.status === 'truncated' && props.turn.settled && !props.turn.content
)
const label = computed(() => (props.turn.failed ? '思考中断' : '思考中…'))
const doneLabel = computed(() =>
  props.turn.failed ? '思考中断' : `已思考 ${(props.turn.elapsed ?? 0).toFixed(1)} 秒`
)

import { computed } from 'vue'
</script>

<template>
  <div class="turn" :class="{ 'turn-user': turn.role === 'user' }">
    <div v-if="turn.role === 'assistant'" class="turn-ai">
      <ThoughtLine
          v-if="showThought"
          :steps="turn.steps"
          :working="turn.thinkingOpen"
          :elapsed="turn.elapsed ?? undefined"
          :label="label"
          :done-label="doneLabel"
          :settle-after="0"
      />
      <MarkdownBody v-if="turn.content" :source="turn.content" :done="turn.settled" />
      <p v-else-if="stoppedWhileThinking" class="turn-note">已停止（模型还在思考）</p>

      <div v-if="turn.failed" class="turn-actions">
        <button class="turn-retry" @click="emit('retry', turn.id)">重试</button>
        <button class="turn-regen" @click="emit('regenerate')">重新生成</button>
      </div>
    </div>
    <p v-else class="turn-body">{{ turn.content }}</p>
  </div>
</template>

<style scoped>
.turn { display: flex; padding: 6px 0; }
.turn-user { justify-content: flex-end; }
.turn-ai {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  max-width: 760px;
}
.turn-body {
  margin: 0;
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 18px;
  background: var(--sb-active);
  color: var(--text);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.turn-note {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
}
.turn-actions { display: flex; gap: 8px; }
.turn-retry,
.turn-regen {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.turn-retry:hover,
.turn-regen:hover { background: var(--sb-hover); }
</style>
```

**执行时必须注意的两处**（都是本计划自己的排版问题，不是设计）：
`import { computed } from 'vue'` 要放到 `<script setup>` 顶部与其它 import 并列（上面写在了下面，照抄能跑但不规范）；
`retry` 事件带着 `turn.id`，但 F3 的 `retry()` 不接参数 —— **按 F3 为准**，`emit('retry')` 不带实参，
把 `defineEmits<{ retry: [] }>` 改掉。理由：重试的语义是"复用上一次的 payload 与幂等键"，
状态在 `useChat` 里，气泡不该决定重试哪一条。

- [ ] **Step 3: 类型检查**

Run: `npm run type-check`
Expected: 通过（`.vue` 文件由 vue-tsc 检查）。若 `ThoughtLine` 的 props 类型不匹配，
**以 `ThoughtLine.vue` 的实际 `defineProps` 为准调整传参**，不改那个组件（它已在线，改它有回归面）。

- [ ] **Step 4: 提交并推送**

```bash
git add src/components/AI/ConversationList.vue src/components/AI/MessageItem.vue
git commit -m "思考线 F5：会话列表与消息气泡（ThoughtLine 接线 + 思考中断/已停止边界显示）" -- src/components/AI/ConversationList.vue src/components/AI/MessageItem.vue
git show --stat --oneline HEAD
git push origin dev
```

**检查点**：`type-check` 绿。

---

## Task F6: Chat.vue 接线 + 浏览器验收

**Files:**
- Modify: `src/views/AI/Chat.vue`（整体重写）

**Interfaces:**
- Consumes: F3 的 `useChat()`、F5 两个组件、`PromptBar`（现成，props 已知）
- Produces: 可用的 `/ai` 对话页

- [ ] **Step 1: 重写 Chat.vue**

必须显式处理的坑（spec §5.3）：`PromptBar` 的 `DEFAULT_MODELS` 是 `gpt-5.5` 那类演示值。
**不传 `:models` 与 `:efforts`，用户选到的 model 就是假的**，发出去被后端拒成"不支持的模型"。
`efforts` 虽然恰好与后端一致，但恰好一致不是可以依赖的理由。

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PromptBar from '../../components/AI/PromptBar.vue'
import ConversationList from '../../components/AI/ConversationList.vue'
import MessageItem from '../../components/AI/MessageItem.vue'
import { useChat } from '../../composables/useChat'
import { useTheme } from '../../composables/useTheme'
import type { PromptBarSendDetail } from '../../components/AI/PromptBar.vue'

const theme = useTheme()
const chat = useChat()
const showList = ref(false)
const scroller = ref<HTMLElement | null>(null)

const promptColors = computed(() =>
  theme.value === 'light'
    ? { background: '#ffffff', color: '#0d0d0d', menuBackground: '#ffffff', sparkColor: '#8ab4ff' }
    : { background: '#2a2a2a', color: '#ececec', menuBackground: '#2a2a2a', sparkColor: '#6ea8ff' }
)

/** #1 的目录喂给 PromptBar：models 需要 key/name/tag，efforts 是字符串数组。 */
const barModels = computed(() =>
  (chat.catalog.value?.models ?? []).map(m => ({ key: m.key, name: m.name, tag: m.tag }))
)
const barEfforts = computed(() => chat.catalog.value?.efforts ?? [])
const defaultModel = computed(() => chat.catalog.value?.defaultModel ?? '')
const defaultEffort = computed(() => chat.catalog.value?.defaultEffort ?? '')

const hasMessages = computed(() => chat.messages.value.length > 0)

const send = async (text: string, detail: PromptBarSendDetail): Promise<void> => {
  await chat.send(text, detail.model?.key, detail.effort)
  scrollToEnd()
}

const stop = (): void => chat.stop()

const attach = (): string[] => []
const dictate = (): string => ''

const scrollToEnd = (): void => {
  void chat.$expose // 占位删除见下
  requestAnimationFrame(() => {
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

onMounted(async () => {
  await chat.loadCatalog()
  await chat.loadConversations()
})
</script>

<template>
  <section class="chat-page">
    <ConversationList
        v-if="showList"
        :items="chat.conversations.value"
        :active-id="chat.activeId.value"
        :has-more="!!chat.nextCursor.value"
        @select="id => { chat.openConversation(id); scrollToEnd() }"
        @create="chat.startConversation"
        @more="chat.moreConversations"
    />

    <div class="chat-main">
      <header class="chat-head">
        <button class="chat-toggle" @click="showList = !showList">对话列表</button>
        <span v-if="chat.currentModel.value" class="chat-model">
          {{ chat.currentModel.value.name }}
          <em v-if="chat.currentModelSupportsThinking">· 思考线</em>
        </span>
      </header>

      <div v-if="!hasMessages" class="chat-hero">
        <h1 class="greeting">问点什么</h1>
      </div>

      <div v-else ref="scroller" class="chat-scroll">
        <MessageItem
            v-for="turn in chat.messages.value"
            :key="turn.key"
            :turn="turn"
            @retry="chat.retry"
            @regenerate="chat.regenerate"
        />
      </div>

      <p v-if="chat.errorText.value || chat.catalogError.value" class="chat-error">
        {{ chat.errorText.value || chat.catalogError.value }}
      </p>

      <div class="prompt-wrap">
        <PromptBar
            placeholder="问点什么"
            :busy="chat.sending.value"
            :models="barModels"
            :default-model="defaultModel"
            :efforts="barEfforts"
            :default-effort="defaultEffort"
            :on-attach="attach"
            :on-dictate="dictate"
            :background="promptColors.background"
            :color="promptColors.color"
            :menu-background="promptColors.menuBackground"
            :spark-color="promptColors.sparkColor"
            :spark-boost="1"
            :width="680"
            :radius="26"
            :max-rows="5"
            :morph-duration="240"
            :squash="0.12"
            :tilt="8"
            :press-scale="0.96"
            @send="send"
            @stop="stop"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.chat-page {
  flex: 1;
  min-height: 0;
  display: flex;
}
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 0 24px 20px;
}
.chat-head {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 44px;
  flex: none;
}
.chat-toggle {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: transparent;
  color: var(--text-muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.chat-model { font-size: 13px; color: var(--text-muted); }
.chat-model em { font-style: normal; color: var(--text); }
.chat-hero {
  flex: 1;
  display: grid;
  place-content: center;
  gap: 24px;
}
.greeting {
  margin: 0;
  font-size: clamp(24px, 2.6vw, 32px);
  font-weight: 500;
  letter-spacing: -0.02em;
}
.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 4px 0 16px;
}
.chat-error {
  flex: none;
  margin: 0 0 10px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-size: 13px;
  color: var(--text);
  background: color-mix(in srgb, #e5484d 10%, transparent);
}
.prompt-wrap {
  flex: none;
  width: 100%;
  max-width: 680px;
  margin: 0 auto;
}
@media (max-width: 767px) {
  .chat-main { padding: 0 14px 14px; }
}
</style>
```

**执行时的两处硬修正**（照抄会出错）：
1. `scrollToEnd()` 里的 `void chat.$expose` 是本计划的排版残留，**删掉**，只留 `requestAnimationFrame` 那段。
2. `useChat()` 返回的是普通对象里的 `ref`，在 `<script setup>` 里不解构就直接 `chat.messages.value` 用是**对的**；
   模板里 Vue 只自动解构顶层 ref，`chat.messages` 是嵌套属性 → 模板必须写 `chat.messages.value`（上面已这么写）。
   如果你觉得别扭，就在 script 顶部 `const { messages, sending } = chat` 解构后使用 —— 二者选一，**别混用**。

- [ ] **Step 2: 类型检查 + 构建**

Run: `npm run type-check` 然后 `npm run build-only`
Expected: 都通过（`build-only` = `vite build`，会真跑一遍打包，能抓到模板里的引用错误）。

- [ ] **Step 3: 浏览器验收（关卡 GF2：需要用户重启 8105）**

前置：网关 8100 与 MySQL 在跑；**8105 必须是重启后的新代码**（含 E 段 Task 13–19）；
用 `test918/123456` 登录（真验证码+真 JWT —— 不许塞令牌、不许改验证码）。

`npm run dev` 后用浏览器打开 `http://localhost:5173/ai`，逐项取证并**记录实际观察到的**：

| # | 操作 | 必须看到 |
|---|---|---|
| A | 首屏 | 模型下拉里是 `deepseek-chat` / `deepseek-reasoner`（不是 gpt-5.5），档位五项 |
| B | 选 `deepseek-reasoner`，问"17 乘 23 等于多少？请说明推理过程" | 思考线出现且**逐条增长**，每条是完整短句（如 `先算乘法。`），不是单词碎片 |
| C | 正文开始那一刻 | 思考线收起成"已思考 N.N 秒"，N.N 与 `thought_end.seconds` 同源 |
| D | 流式期间正文有代码块 | 不出现"整块突然变灰并一直灰着"（围栏补齐生效）；`done` 后定稿 |
| E | 生成中点停止 | 立刻冻结（不等接口），库里该行 `truncated/stopped`；#6 重进会话只剩正文 |
| F | 在思考阶段就点停止 | 气泡显示"已停止（模型还在思考）"，不是空气泡 |
| G | 同会话并发发第二条 | 输入框上方出现"上一条回答仍在生成"（后端原文） |
| H | 刷新页面重进会话 | **思考线消失**，正文完整 —— 这是不落库的预期，不是 bug |
| I | 选 `deepseek-chat` 问一条 | 全程零思考线 |
| J | DevTools Network | 长回答期间连接不被 dev proxy 掐断；`Content-Type: text/event-stream` |
| K | 断网模拟（DevTools offline）后点重试 | 复用同一 `clientMsgId`，用户消息不重复 |

- [ ] **Step 4: 清理与回写**

- 浏览器验收产生的会话/消息：**不删**（用户会看），但在报告里给出会话 id。
- 计划文档补"执行记录"（含与计划的偏差、变异/取证结果、GF1 实际装的版本、K 项是否验到）。
- 后端仓库的 E 段取证日志 §5"未做的事"补一句"F 段浏览器验收完成/未完成"。

- [ ] **Step 5: 提交并推送**

```bash
git add src/views/AI/Chat.vue
git commit -m "思考线 F6：/ai 对话页接线（真实 models/efforts + 思考线渲染）" -- src/views/AI/Chat.vue
git show --stat --oneline HEAD
git push origin dev
```

后端仓库的文档回写单独提交（同样 pathspec）：

```bash
cd F:/AAAJAVA/code/ezPracticeExercises
git add docs/superpowers/plans/2026-10-25-ai-chat-thinking-line-frontend.md docs/superpowers/plans/2026-10-25-ai-chat-thinking-line-e2e-log.md
git commit -m "F 段执行记录与取证回写" -- docs/superpowers/plans/2026-10-25-ai-chat-thinking-line-frontend.md docs/superpowers/plans/2026-10-25-ai-chat-thinking-line-e2e-log.md
git push origin dev
```

---

## 自审（写完计划后回看 spec §5）

**1. spec 覆盖**

| spec 条目 | 落在哪个任务 |
|---|---|
| §5.1 `api/chat.ts`：控制面复用 `request`/`apiUrl`/`BizError` | F2 |
| §5.1 帧解析器唯一、先看 content-type、半帧拼接、`data:` 去最多一个空格、`ping` 忽略、`error` 不抛 | F1（四条各有用例）+ F2 |
| §5.1 `Accept` 双值 | F2 Step 1 的 headers |
| §5.2 `clientMsgId` 复用重试 | F3（Step 3 自查第 2 条） |
| §5.2 停止两段式 | F3（Step 3 自查第 1 条） |
| §5.2 重新生成不带 content | F3 `regenerate()` |
| §5.2 `retryable` 决定给不给重试 | F1 解析 `retryable` → F3 写 `turn.retryable` → F5 渲染按钮 |
| §5.3 `ConversationList.vue` / `MessageItem.vue` 新建 | F5 |
| §5.3 助手气泡 = ThoughtLine + 正文 | F5 |
| §5.3 PromptBar 必须传真实 `models`/`efforts` | F6 Step 1（并写明不传的后果） |
| §5.4 `marked` + `DOMPurify` | F4 |
| §5.4 未闭合围栏补齐 | F4 `closeOpenFence`（有表驱动用例） |
| §5.4 rAF 合并、`done` 定稿 | F4 `MarkdownBody.vue` |
| §5.4 每次都 sanitize、链接 `rel="noopener"`、禁 iframe/script/事件属性 | F4（`decorateLinks` + 一条 Node 用例；自审时发现原先只写"将来再说"，已就地补齐） |
| §5.5 四种边界显示 | F3（错误原文）+ F5（思考中断 / 空正文 / 刷新后消失） |
| §5.5 刷新后思考线消失是预期 | F3 `toTurn` 恒 `steps: []` + F6 验收 H 项 |

**2. 占位符扫描**：无 TBD；两处"执行时必须修正"（F5 的 import 位置与 `retry` 无参、F6 的 `$expose` 残留）
都是**明确指令**而不是留白。

**3. 类型一致性**：`UsagePayload`（F1）被 F3 的 `ChatTurn.usage` 与 `toTurn` 使用；`ChatTurn`（F3）被 F5 的 props 使用；
`StreamHandlers`（F2）= `ChatStreamHandlers`（F1）+ `onReject`；`Conversation`/`ChatModel`（F2）被 F5/F6 使用。
`readEventStream` 的第三参 `onReject` 与 `StreamHandlers.onReject` 是同一语义，F2 直接转发，没有第二套签名。

**类型补充**：`streamMarkdown.ts` 里 `marked.parse(unsafe, {async:false})` 返回 `string | undefined`，
`decorateLinks` 的入参是 `string` —— F4 Step 5 的写法已经用 `DOMPurify.sanitize(...)` 的输出（string）作参数，
不需要额外断言。`marked.parse` 的返回值直接交给 `DOMPurify.sanitize`，它接受 string 与 Thenable<string>，
`{ async: false }` 保证走 string 分支。

**F4 Step 4 的围栏推导要求不变**：先跑一遍看 `FENCE` 实际匹配到几个，按结果写期望值并解释，不许为了好看改断言。
