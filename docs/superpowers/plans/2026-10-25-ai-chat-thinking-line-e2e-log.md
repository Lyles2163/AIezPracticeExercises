# E 段（思考线）Task 19 取证日志

日期：2026-10-25。范围：删掉 Spring AI 网关、自建 `WebClient` 成为唯一上游实现之后的**真上游取证**与**段 D 回归重跑**。
上一期（段 D）的同名日志：`2026-10-23-ai-chat-e2e-log.md`；设计：`2026-10-24-ai-chat-thinking-line-design.md`；计划与逐任务执行记录：`2026-10-24-ai-chat-thinking-line.md`。

## 0. 结论摘要

- 真 `deepseek-reasoner` 的一次短问答：帧序 `meta → thought_step×6 → thought_end → delta×72 → usage → done`，
  **思考线是真实推理的按句切分，不是演出来的**；计划 Step 4 的 9 条断言全 PASS。
- 同一次运行里换 `deepseek-chat`：**零** `thought_step`/`thought_end`，`meta.supportsThinking=false` —— 宁缺毋滥成立。
- 段 D 的**服务级 9 项**回归全 PASS（含最关键的 `length` 收尾：新 transport 下 R17 那条路径仍然对）。
- 段 D 的**网关级 4 项**（44s 长流经网关、`event:ping` 经网关、`Accept` 四值矩阵经网关、验证码+JWT 全链）**没做**，
  原因见 §5：它们必须经 8091/8100 网关打到 8105，而 8105 是用户从 IDEA 起的服务，跑的是 07:49 的旧代码。
  要补这段，需要用户重启 8105（关卡 G1 类动作，我不自行处理）。
- 零新增 Maven 依赖、零 DDL：`ai_message`/`ai_conversation` 表结构未变，取证行已清零。

## 1. 取证环境

诊断实例（不注册进 Nacos，因此网关不会把流量分给它，也不影响用户的 8105）：

```
CP="F:/AAAJAVA/code/ezPracticeExercises/AIModule/target/classes;$(cat $TEMP/ai-cp.txt)"
"$JAVA_HOME/bin/java.exe" -XX:TieredStopAtLevel=1 -cp "$CP" org.leon.aimodule.AiModuleApplication \
  --server.port=8106 --spring.cloud.nacos.discovery.enabled=false
```

`ai-cp.txt` 由 `mvn -pl AIModule dependency:build-classpath -Dmdep.outputFile=...` 生成。
启动 6.28s，`Started AiModuleApplication`；配置仍从 Nacos 拉（`spring.cloud.nacos.config` 与 discovery 是两件事），
DeepSeek key 因此来自 Nacos 的 `AiLocal.properties`，命令行里没有出现任何密钥。

身份用 `X-User-Name: test918` 直连 8106。
**这一点必须说清**：本轮用它只证明"服务自身的行为"，不证明任何鉴权结论——
鉴权链路的证据仍来自段 D（经网关），并且段 D 的网关级重跑还欠着（§5）。

脚本：`t19_evidence.py`（真上游 9 断言）与 `t19_d_regress.py` / `t19_r3.py`（段 D 服务级），
都在本机 `$TEMP` 下，跑完即弃；它们只读 HTTP 与 SSE，不改代码。

## 2. 真 reasoner 帧取证（会计费，一次短问答）

请求：会话 51，`modelKey=deepseek-reasoner`，`effort=Medium`，问题"17 乘 23 等于多少？请说明推理过程。"

```
meta        {"conversationId":51,"messageId":51,"modelKey":"deepseek-reasoner","effort":"Medium","supportsThinking":true}
thought_step {"text":"We need answer in Chinese."}
thought_step {"text":"Need compute 17*23 and explain reasoning."}
thought_step {"text":"Need not overdo."}
thought_step {"text":"17*23 = 391."}
thought_step {"text":"Provide process perhaps 17*(20+3)=340+51=391 or 23*(10+7)."}
thought_step {"text":"Ensure answer."}
thought_end  {"seconds":0.4}
delta ×72    （正文，与库内逐字节一致）
usage        {"promptTokens":44,"completionTokens":128,"reasoningTokens":55}
done         {"finishReason":"done"}
```

| # | 断言 | 实测 | 结论 |
|---|---|---|---|
| 1 | 首帧 `meta`、末帧 `done` | `meta .. done` | PASS |
| 2 | `thought_step` 条数在 1..12 | 6 | PASS |
| 3 | `thought_end` 恰好一帧且 `seconds>=0` | `0.4` | PASS |
| 4 | 步骤是可读短句而非单词碎片 | 最短 12 字符 | PASS |
| 5 | `meta.supportsThinking=true` | true | PASS |
| 6 | `0 < reasoningTokens < completionTokens` | 55 < 128 | PASS |
| 7 | `thought_end` 之后才出现 `delta`（相不交错） | index 7 vs 8 | PASS |
| 8 | `deepseek-chat` 零 thought 帧 | `meta + delta×10 + usage + done` | PASS |
| 9 | `deepseek-chat` 的 `meta` 报 false | false | PASS |

三条值得记下来的读法：

- **`seconds` 不是"总耗时"**。它是"首条 reasoning → 首条正文"的间隔，0.4s 是合理的：reasoner 的首 token
  与正文衔接很快，而整条流（72 个 delta）还要跑好几秒。前端如果要显示"已思考 N 秒"，用的就是这个数，
  别拿它当请求延迟。
- **切句确实把碎片合并了**：上游那次问答实测 63 帧 reasoning（每条一两个词），到前端只出 6 条短句。
  Task 14 的切句器不是纸面设计。
- **`reasoningTokens` 是拆分不是加数**：55 含在 128 里。它只上 `usage` 帧，不写 `ai_message`
  （库里仍只有 `prompt_tokens`/`completion_tokens` 两列，零 DDL）。

## 3. 帧的线上形状（给前端的硬事实）

- 我们的下游写的是 `data:{...}`（**冒号后无空格**），而上游 DeepSeek 给的是 `data: {...}`（有空格）。
  两者不一样，前端解析器必须都能吃下；段 D 曾因为按 `data: ` 剥前缀踩过一次假取证（见那份日志 §11 附近）。
- 一帧一行 JSON，帧间空行分隔；`thought_step` 的 `text` 里若出现换行会被 Jackson 转义成 `\n`，不会提前断帧
  （`SseChannelTest.escapesNewlines` 守这件事，E 段没动它）。

## 4. 段 D 服务级回归（新 transport 之下）

会话 53–58，全部 `deepseek-chat`（省钱），除 §4 最后一项外都是一次性短问答。

| 项 | 断言 | 实测 | 结论 |
|---|---|---|---|
| R1 逐字一致 | 流出的 delta 拼接 == 库内 `content` | 57 == 57 字节；`length` 那轮 7386 == 7386 字节 | PASS |
| R2 终态字段 | `status=done`、`stop_reason=done`、usage 有值 | id 55 齐全 | PASS |
| R3 `length` 收尾 | 被 `max_tokens` 截断时 `done{length}` 且库 `stop_reason=length` | `delta×4096`、`done{finishReason:"length"}`、库 `done/length`、`completionTokens=4096` | PASS |
| R4 幂等 | 同 `clientMsgId` 连发两次不产生第二条用户消息 | 用户消息 2 条（`d-r1` + `d-idem`） | PASS |
| R5 并发拒绝 | 第二条返回 `Result` JSON 而非半截 SSE | `ct=application/json;charset=UTF-8`、`{code:0,msg:"上一条回答仍在生成"}` | PASS |
| R6 stop | 中途停 → `truncated/stopped`，正文保留已产出部分 | id 62 content="## 月球的诞生：一场改写地球" | PASS |
| R7 断连 | 客户端提前关 socket → `truncated/client_disconnect` | id 64 | PASS |
| R8 软删 | #6 与 #7 都按"会话不存在"拒绝、不建流 | 两条都 `{code:0,msg:"会话不存在"}` | PASS |
| R9 坏 cursor | JSON `code=0`，不 500 | `{code:0,msg:"cursor 无效"}` | PASS |

**R3 是这一节最重要的一条。** 上一期的 R17 缺陷（Spring AI 把 `length` 交付成 `LENGTH`，导致被截断的回答记成
`done`）正是靠"真实上游 + 数 token"才发现的；换了 transport 之后必须重新数一次。第一次取证我按旧记录用了
Max 档（16384），结果模型自然说完（`completionTokens=11964`、`finishReason=done`），**没有触发截断条件**——
那是取证设计的问题，不是代码缺陷。改用 `max_tokens=4096` 的 Medium 档逼出截断后：`delta` 恰好 4096 帧、
`done{length}`、库 `stop_reason=length`。归一逻辑现在住在 `OpenAiStreamChatModelGateway.lower()`，
上游直接给小写也照样安全，且这条路径有单测 `finishReasonLowerCased` 作为变异可证的哨兵。

## 5. 没做的事（以及为什么不能糊过去）

网关级 4 项没跑：44s 长流经网关不断流、`event:ping` 经网关仍出现、`Accept` 四值矩阵经网关仍全 200 JSON、
验证码+JWT 全链取 token。

- 这四项的**被测对象就是"经不经过网关"**，在 8106 直连上做等于把要证明的东西假设掉。
- 8105 从 IDEA 起（pid 8064，07:49 启动），不含 Task 19 的 bean 切换；把它改成新代码只有两条路：
  用户重启，或者我起一个注册进 Nacos 的第二实例。后者会污染共享注册表、并与 8105 抢流量（结果不可解释），
  所以不做。**需要用户重启 8105**，之后这 4 项按段 D 日志 §8/§1 的同一手法重跑。
- 上一期遗留的一次未复现异常（`#2 create` 经网关偶发"服务内部错误"，26 次重试全成功）仍挂在
  段 D 日志 §11，本轮没有新线索。

## 6. 清理

- DB：本轮共产生 8 会话 / 19 消息（含上一节 53–58）。全部删除，删前后都查了计数：
  `delete from ai_message where conversation_id in (select id from ai_conversation where user_name='test918')` → 19；
  `delete from ai_conversation where user_name='test918'` → 8；复查 `conv=0 msg=0`。
  删除前确认 `ai_conversation` 里只有 `test918` 一个 `user_name`，所以"归零"不会误伤别人。
- 进程：8106 诊断实例已停（`netstat` 里只剩我这侧的 TIME_WAIT，无 LISTENING）。
- 两个表结构一行未动，无 DDL。

## 7. 单测/IT 计数（与计划对账）

- 全模块单测 **175/175**，`ChatMapperDbIT` **8/8**。
- 计划预测 172 = 144 + 36 − 8。差额 3 = Task 19 Step 1 逐条核对旧网关测试时发现的**真实覆盖缺口**
  （空 `choices` + 真 usage 的收尾帧、正文与 `finish_reason` 同帧、空白 `finish_reason` 归 null），
  按计划要求"先补新用例再删旧文件"补进 `UpstreamChunkParseTest`。**不是**放宽期望值凑出来的数。
