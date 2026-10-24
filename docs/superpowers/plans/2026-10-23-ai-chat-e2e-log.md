# AI 对话子系统 · 走网关端到端实测记录（Task 12）

日期：2026-10-24 10:28–11:05
被测：`AIModule` chat 子系统 #1–#8（工作树，Task 1–11 产物）
链路：`curl → Gateway:8100（JwtAuthGlobalFilter）→ AIModule:8105 → api.deepseek.com`
账号：`test918`（用户提供，BCrypt 登录，未插测试账号、未绕验证码、未伪造 token）
数据库：主库 `ezpracticeexercises`（`ai_conversation` / `ai_message`）

> 本文只记**实测到的东西**。每条结论后面是原始输出片段，不是推断。

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 8 个接口经网关全通 | ✅ #1–#8 全部正向 + 拒绝路径实测 |
| 事件契约 `meta→delta*→usage→done` | ✅ 真实上游，最长一条 16384 帧 |
| 三段短事务 / 流式期间零写入 | ✅ user 行 `update_time-create_time=0`，assistant 行只等于生成耗时 |
| 幂等（重试即重新生成） | ✅ 同 `clientMsgId` 三次不再产生用户消息 |
| 并发拒绝、stop、客户端断连 | ✅ 三种收尾的 `stop_reason` 互不相同 |
| 上游故障 | ✅ `error{retryable:true}` + `done{upstream}` + `status=failed` |
| 长流经网关 | ✅ 44s / 16385 帧不断流，**G4（改 Nacos 超时）不需要** |
| 心跳 | ✅ 真线上出现过 `event:ping`（22s 流 1 次、44s 流 2 次） |
| **发现并修掉的缺陷** | **R17：Spring AI 把 `finish_reason` 交付成大写 `LENGTH`，导致被 max_tokens 截断的回答记成 `done`** |
| 未复现的单次异常 | Step 4 首次 create 返回 `服务内部错误`，此后 26 次全成功（见 §8） |

## 1. 链路前置证明（不许跳过的那一步）

```
models_noauth=401          # GET  /ai/chat/models
stream_noauth=401          # POST /ai/chat/completions/stream
```
无 token 一律 401 → 网关过滤器在链上、`/ai/**` 路由存在。计划里担心的"`common-jwt.yaml` dataId 不存在所以验签可能失效"这条**不成立**：验签真的在工作（段 B 已用真 token 打通，本轮再次确认 401/200 分界）。

token 获取（真实验证码路径）：
```
GET  /auth/captcha → {"captchaKey":"3bbcba4b…","imageBase64":"data:image/png;base64,…"}
图片可读内容      = 062909
Redis db0 auth:captcha:3bbcba4b… = 062909      # 两者一致，说明提交的是真码
POST /auth/login → {"code":1,…,"data":{"userName":"test918","exp":…,"token":"…"}}  token_len=219
```

## 2. 控制面 #1–#6

```
#1 models   efforts=['Low','Medium','High','Extra','Max']
            models=[('deepseek-chat','Fast',False), ('deepseek-reasoner','Reasoning',True)]
#2 create   {"id":41,"title":"联调会话","modelKey":"deepseek-chat","effort":"High","messageCount":0,"lastPreview":"","updateTime":1792809344936}
#3 list     [(41,'联调会话','High',0,13)]   # 时间字段 13 位数字
#4 rename   {"code":1,"msg":"success","data":null}
#6 (空)     {"hasMore":false,"items":[]}
#5 delete   code=1 → 列表里消失 → #6/#7 都返回"会话不存在" → 真库 status=0（软删，未物理删）
```
中文往返是**逐字节**核对的：`#3` 返回的 title 码点 `[0x8054,0x8c03,0x4f1a,0x8bdd]` = `联调会话`。
终端里显示的乱码是 Windows 控制台 GBK 渲染，不是数据问题。

⚠️ 非 ASCII 请求体必须 `--data-binary @<utf-8 文件>`。Git Bash 内联 `-d '{"title":"中文"}'` 会按 GBK 码页发出。
本轮额外实测到：**加上 `Content-Type: application/json;charset=UTF-8` 之后内联也不坏**（网关/容器改用 UTF-8 解码），但**不给 charset 时同样能成功**（26 次），所以这条不作为前端硬要求。

## 3. #7 流式主链路（真实 DeepSeek）

```
$ curl -N -H 'Accept: text/event-stream, application/json' -X POST $BASE/completions/stream \
    --data-binary @b_q1.json          # {"conversationId":41,"content":"用三句话介绍快速排序","effort":"High","clientMsgId":"e2e-1"}

帧统计：84 = 1 meta + 81 delta + 1 usage + 1 done
响应头：Content-Type: text/event-stream / Cache-Control: no-cache, no-transform
        X-Accel-Buffering: no / Transfer-Encoding: chunked
```
头 5 帧与末 3 帧原样：
```
event:meta
data:{"conversationId":41,"messageId":13,"modelKey":"deepseek-chat","effort":"High","supportsThinking":false}

event:delta
data:{"text":"1"}

event:delta
data:{"text":"."}
```
```
event:usage
data:{"promptTokens":10,"completionTokens":81}

event:done
data:{"finishReason":"done"}
```
**帧的线格式是 `data:{...}`，冒号后没有空格**（`SseEmitter` 原样输出）。计划 Step 8 的示例用 `sed 's/^data: //'` 剥前缀，剥不掉——按 `data: ` 解析的客户端会少一个字符。标准 `EventSource` 会主动吃掉一个可选空格，所以浏览器侧无感；自写解析器要按"无空格"处理。

## 4. 落库与写次数不变式（spec 5.5）

```
12 | user      | done   | stop=null   | len=10  | pt=null | ct=null | cmid=e2e-1  | dur_ms=0.0000  | replyTo=0
13 | assistant | done   | stop=done   | len=138 | pt=10   | ct=81   | cmid=e2e-1:a| dur_ms=1574.0  | replyTo=12
```
`dur_ms = update_time - create_time`。user 行是 **0**（插完就没再动），assistant 行 1574ms 正好是那 81 个 token 的生成耗时 → **流式期间零次写入**，收尾一次 UPDATE。这条在单测里由 `ChatStreamWriteCountTest`（3 帧 vs 3000 帧交互序列逐条相等）钉住，本轮是真库侧的对应证据。

**逐字一致**（8.3 第一条）：把 `stream1.txt` 的 81 个 delta 拼起来与库内正文比字节：
```
delta 帧= 81 字符= 138
cmp db13.txt streamed13.txt → 逐字一致 ✓   （两边都是 359 字节）
```
（第一次比出不一致是我自己的错：python 文本模式把 `\n` 写成 `\r\n`。用 `newline=''` 重写后一致。）

## 5. 幂等 / 重新生成 / 并发拒绝

```
7a 同 clientMsgId=e2e-1 再发（正文换成"重复提交的旧文本"）
   → 用户消息仍只有 id=12 一条；id=13 转 superseded；新回答 id=14 done，cmid=NULL
7b regenerate:true
   → 不新增用户消息；id=14 转 superseded；新回答 id=15
7c A 流生成中打第二条
   HTTP 200  Content-Type: application/json;charset=UTF-8
   {"code":0,"msg":"上一条回答仍在生成","data":null}
```
7a/7b 的关键点是**助手行的 `client_msg_id` 必须是 NULL**：派生键 `e2e-1:a` 在同一会话里是固定值，第二次重试会撞 `uk_conv_client`（Task 11 的 R15，本轮在真库上再次确认它不再发生）。

7c 的 A 流同时是一条长流：1838 delta / 11.6s / 3257 字，完整跑完没有被网关掐。

## 6. stop 与客户端断连（两种收尾互不混淆）

```
6a 中途 stop
   messageId=23，stop 前已收 334 帧
   stop → {"code":1,"msg":"success","data":null}
   客户端侧：335 delta + 1 meta，**没有 usage / done**
   再 stop 一次 → code=1（幂等）
   stop 不存在的 id → {"code":0,"msg":"会话不存在"}
   库内：23 | assistant | truncated | stop=stopped | len=645 | dur_ms=3200

6b 客户端断连（curl --max-time 4 强断，exit=28）
   库内：21 | assistant | truncated | stop=client_disconnect | len=660 | dur_ms=3990
```
两条都是"已产出内容保留 + 只写一次终态"。`stopped` 与 `client_disconnect` 在库里可区分，这是 spec 5.6 要的。
断连这条尤其重要：它是 Task 10/11 一直标着 ⚠️ 的"真实异步源时序未验证"——`emitter.onError/onCompletion → service.clientGone()` 在真 Tomcat + 真断连下确实收敛成 `truncated`，且没有把 `truncated` 覆盖成 `done`（R14 的 claim + DB 守卫在真线上生效）。

## 7. 上游故障注入

**没有改 `application.yaml`**，改用命令行覆盖在诊断实例 8106 上注入（`--spring.ai.openai.api-key=sk-invalid-for-fault-injection`，`--spring.cloud.nacos.discovery.enabled=false` 不注册进 Nacos，所以网关不会被引到它上面）：

```
HTTP/1.1 200   Content-Type: text/event-stream

event:meta
data:{"conversationId":41,"messageId":25,…}

event:error
data:{"errorCode":"UPSTREAM_MODEL_ERROR","msg":"模型服务暂时不可用","retryable":true}

event:done
data:{"finishReason":"upstream"}
```
```
25 | failed | stop=upstream | contentLen=0 | error_msg=401 Unauthorized from POST https://api.deepseek.com/v1/chat/completions
```
`git diff` 里没有任何 api-key 改动（`grep -c sk-invalid application.yaml` = 0）。
"已产出内容保留"这一半在这条路径上取不到（401 在第一帧之前就失败，content 本来就是空），由单测 `upstreamFailureKeepsPartialContent` 覆盖。
另有一条**非注入的**真实故障：一次大上下文请求上游 `Connection reset` → 行 37 `failed | stop=upstream | err=Connection reset`，同样只写一次终态。

## 8. 长流、心跳与网关超时（关卡 G4 的判定）

| 流 | 时长 | delta 帧 | ping | 结果 |
|---|---|---|---|---|
| TCP 详述 | 11.6s | 1838 | 0 | done |
| 2500 字说明 | 15.6s | 2037 | **1** | done |
| 数到 3000（Max 档） | 22.2s | 8000 | **1** | done，正文 13892 字 |
| 数到 9000（Max 档，撞 16384 上限） | 44.0s | 16384 | **2** | **length**（修复后） |

**网关没有掐断任何一条**，也没有在 30s/60s 附近整齐断流 → **G4（改 Nacos `gateway.yaml` 的 `response-timeout`）不需要动**，共享配置保持原样。

心跳的行为要记准：它是 `scheduleAtFixedRate(15s)`，**与是否有数据流动无关**。上面 22s 那条流一直在出 delta，仍然收到 1 次 `event:ping`。所以前端必须把 `ping` 当成"任意时刻可能插进来"的事件忽略掉，不能假设它只在空闲时出现。

`event:ping` 的载荷是 `{"t":<毫秒>}`。

## 9. R17：`finish_reason` 大小写（本轮唯一的代码缺陷，已修）

数到 9000 那条正好停在 `max_tokens=16384`，库里却记 `stop=done`、`done{finishReason:"done"}`。
先问上游要真相（直连 `api.deepseek.com`，`max_tokens=60`）：
```
data: {"choices":[{"delta":{"content":""},"finish_reason":"length"}],"usage":{…}}
出现过的 finish_reason： 61× null，1× "length"
```
DeepSeek 明明给了 `length`。再在 `SpringAiChatModelGateway.stream()` 里插一行临时日志看 Spring AI 交付的形状（**验完已删，`grep -rn TEMP-DEBUG AIModule/src` = 0**）：
```
TEMP-DEBUG-G1 … fr=LENGTH textLen=0 meta=DefaultChatGenerationMetadata[finishReason='LENGTH', …]
```
**Spring AI 1.0.0-M7 把 finish_reason 大写化**。编排层的 `switch (upstream) { case "length" -> STOP_LENGTH; default -> STOP_DONE; }` 于是落到 default。
`Task 10` 的单测 `lengthFinishReason` 喂的是小写 `"length"`（照计划写的），所以它一直绿——**mock 与真实形状不一致，测试替实现撒了谎**，这正是 Task 12 存在的理由。

修法放在适配器边界（`toChunk`）：`fr.trim().toLowerCase(Locale.ROOT)`，空白归 null。编排层的契约仍然是小写常量，不跟着厂商大小写走。
TDD：先按真实帧形状加断言 → 红 `expected: <length> but was: <LENGTH>` → 修 → 绿；真线上复验：
```
帧: 8192 delta + meta + usage + ping + done
event:done
data:{"finishReason":"length"}
35 | assistant | done | stop=length | len=14212 | pt=28 | ct=8192 | dur_ms=23062
```
状态仍是 `done`（回答是完整交付的，只是被长度截了），`stop_reason=length` 负责区分——与 spec 一致。

顺带确认的两点：中间帧的 `finishReason` 是**空串**而不是 null（`toChunk` 归 null 的规则是对的）；正常收尾 DeepSeek 的 `stop` 落到 default 变成我们的 `done`，所以 `done` 事件里永远不会出现 `stop` 这个词。

## 10. `deepseek-reasoner` 实测（8.3 最后一条）

```
meta: {"modelKey":"deepseek-reasoner","supportsThinking":true,…}
正文： '391'（17×23，1 帧 delta）
usage: {"promptTokens":42,"completionTokens":34}
```
两个结论：
1. **推理内容根本没有到达我们**。`completionTokens=34` 而正文只有 3 个字符 → 思考部分被上游计入 token 但没进 `getOutput().getText()`（Spring AI 不映射 `reasoning_content`）。也就是说 `supportsThinking=true` 目前是一个**前端无法兑现的旗子**：按它渲染思考线会永远是空的。本期不实现思考链，所以只是记档；将来接 `thought_step` 时必须换 `metadata` 里的 reasoning 字段，不能指望 `delta`。
2. **temperature 是否被忽略：无法判定**。同题三档（Low 1.3 / Medium 1.0 / Max 0.2）输出各不相同（43/50/75 completion tokens）。但"输出不同"不能证明 temperature 生效——采样本身就有随机性。能确定的只有：请求里带 `temperature` 上游不报错（无 400），所以 effort 在 reasoner 上**至少**仍然通过 `maxTokens` 起作用。spec 第七节按这个口径改写，不写"已证实忽略"。

## 11. 一次没复现的异常（诚实记录）

Step 4 的第一次 `#2 create`（经网关、UTF-8 文件体）返回：
```
{"code":0,"msg":"服务内部错误","data":null}
```
之后同样的请求体、同样的链路 **26 次全部 `code=1`**（含 20 次连续压测）。
- 形状说明它来自 AIModule 的 advice（`msg` 字段；网关自己的错误是 `{"timestamp":…,"status":…}`），即 catch-all `other(Exception)` 命中过一次。
- 8105 是用户从 IDEA 启的，没有文件日志（AIModule 无 logback 配置），堆栈只在它的控制台里，我看不到。
- 同一时刻直连 8105 与直连 8106 用同一份体都成功。
结论：**未定位，不假装已排除**。如果它再出现，第一件事是看 8105 控制台里那条 `[chat] 未预期异常` 的堆栈。

## 12. 已知未覆盖（下一轮或前端联调时补）

- **跨用户越权**：#4/#5/#6/#7/#8 的"别人的会话返回同一文案"只有单测覆盖（`requireOwned`）。真链路上需要**第二个账号**，而关卡 G3 只批了复用 `test918`、没批插新账号。段 B 已证明网关**覆盖**客户端自带的 `X-User-Name`，所以注入路径是关的。
- **多实例**：stop/注册表是进程内的，跨实例不生效（spec 第九节明确接受这条边界）。
- **推流中途 advice 触发**：`write()` 里的 `response.isCommitted()` 守卫在真线上没被触发过。
- `deepseek-reasoner` 的 reasoning 通道（见 §10）。

## 13. 数据清理

取证期间在主库产生的全部会话与消息（`test918` / `sseprobe`，会话 id 5–47）已用 JDBC 探针事务删除：
```
wiped ai_message=<n> ai_conversation=<n>
remaining conv=0 msg=0
```
诊断实例（8106）已全部停止；用户的 8100/8103/8105/8848 未动。
IT 用例写在独立 schema `ezpracticeexercises_chat_it`，与主库无关。
