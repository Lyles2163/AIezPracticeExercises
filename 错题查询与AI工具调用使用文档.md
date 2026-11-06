# 错题查询接口 + AI 工具调用 使用文档

> 写给初学者：这份文档用大白话讲清楚"我加了什么、怎么用、背后怎么跑的"。
> 你不需要一次看懂所有代码，先跑通接口、再让 AI 调起来就行。

---

## 一、这次改了什么（一句话版）

1. **PracticeModule** 新增一个接口 `GET /incorrectlyQuestions/list`，能查"当前登录用户还没掌握的错题"，把题干、选项、你的作答、正确答案、解析都返回给 AI。
2. **AIModule** 给聊天加了"工具调用"能力：AI 觉得用户想看错题时，会自动调用上面那个接口，拿到结果后再组织语言回答用户。

---

## 二、接口怎么用

### 2.1 接口信息

| 项目 | 值 |
|------|-----|
| 请求方式 | `GET` |
| 路径 | `/incorrectlyQuestions/list` |
| 身份认证 | 请求头 `X-User-Name: 你的用户名`（由网关注入，前端正常带 token 即可） |

### 2.2 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `subjectId` | Long | 否 | 按科目过滤，不传就查所有科目 |
| `limit` | Integer | 否 | 返回多少条，默认 10，最大 50 |

### 2.3 调用示例

**带 token 走网关（前端正常用法）：**

```http
GET http://localhost:8100/incorrectlyQuestions/list?subjectId=1&limit=5
Authorization: Bearer <你的JWT>
```

**直接打 PracticeModule（本地调试，跳过网关）：**

```http
GET http://localhost:8102/incorrectlyQuestions/list?limit=10
X-User-Name: test920
```

> 注意：直接打 8102 时必须手动带 `X-User-Name`，因为网关注入的这一步被跳过了。

### 2.4 返回示例

```json
{
  "code": 1,
  "msg": "success",
  "data": [
    {
      "questionId": 6,
      "subjectId": 1,
      "subjectName": "Java 基础",
      "type": 1,
      "difficulty": 2,
      "title": "下列哪个不是 Java 的基本数据类型？",
      "options": [
        { "optionKey": "A", "content": "int" },
        { "optionKey": "B", "content": "String" },
        { "optionKey": "C", "content": "boolean" },
        { "optionKey": "D", "content": "double" }
      ],
      "tags": ["数据类型"],
      "wrongCount": 3,
      "lastWrongAt": 1730000000000,
      "status": 1,
      "lastUserAnswerJson": "\"C\"",
      "answerJson": "\"B\"",
      "analysis": "String 是引用类型，不是基本数据类型。"
    }
  ]
}
```

字段说明：
- `type`：题型，1单选 2多选 3判断 4填空 5简答
- `difficulty`：难度，1简单 2中等 3困难
- `status`：掌握状态，1未掌握 2已掌握（这个接口只返回未掌握的，也就是 status=1）
- `lastUserAnswerJson`：你上次选的答案
- `answerJson`：正确答案
- `analysis`：解析

---

## 三、AI 怎么自动调这个接口

### 3.1 工作流程（看图说话）

```
用户："帮我看看我有哪些错题"
   │
   ▼
AI 模型收到消息，同时看到"你可以用 query_wrong_questions 这个工具"
   │
   ▼
模型决定：这个问题需要调用工具 → 返回 tool_calls
   │
   ▼
AIModule 拦截到 tool_calls → 执行 WrongQuestionsTool.execute()
   │                            → Feign 调用 PracticeModule 的 /incorrectlyQuestions/list
   │                            → 拿到错题 JSON
   ▼
把工具结果塞回给模型，模型再组织成自然语言回答用户
```

### 3.2 你需要做什么

**什么都不用做**，只要：
1. 启动 PracticeModule（8102）
2. 启动 AIModule（8105）
3. 启动 Nacos（两个服务都注册上去，AIModule 才能通过服务名找到 PracticeModule）

然后用户在聊天里问"我有哪些错题"之类的问题，AI 就会自动查。

### 3.3 工具叫什么

工具名：`query_wrong_questions`

它会出现在发给模型的请求里（`tools` 字段），模型看到后就能调用。

---

## 四、关键代码位置速查

如果你想改东西，看这些文件就够了：

### PracticeModule 侧

| 文件 | 作用 |
|------|------|
| `controller/IncorrectlyQuestionsController.java` | 接口入口，组装返回数据 |
| `pojo/vo/WrongQuestionVO.java` | 返回给 AI 的错题详情结构 |
| `mapper/QbUserWrongMapper.java` | 里的 `selectWrongDetails` 方法，查错题的 SQL |
| `mapper/WrongDetailRow.java` | SQL 查询结果的投影行 |
| `mapper/QbSubjectMapper.java` | 里的 `selectNameById`，根据科目 id 查科目名 |

### AIModule 侧

| 文件 | 作用 |
|------|------|
| `tool/ChatTool.java` | 工具接口，新增工具就实现它 |
| `tool/ToolRegistry.java` | 自动收集所有工具，按名字索引 |
| `tool/WrongQuestionsTool.java` | 错题查询工具的具体实现 |
| `tool/PracticeFeign/PracticeFeign.java` | Feign 客户端，调用 PracticeModule |
| `tool/dto/WrongQuestionDTO.java` | 接收 PracticeModule 返回的数据 |
| `chat/stream/ChatModelGateway.java` | 模型网关接口，加了 tools 参数 |
| `chat/stream/OpenAiStreamChatModelGateway.java` | 给 DeepSeek 发 tools、解析 tool_calls |
| `chat/stream/ChatStreamService.java` | 核心循环：检测工具调用→执行→再请求模型 |
| `chat/service/CtxMessage.java` | 上下文消息，加了 toolCalls/toolCallId 字段 |

---

## 五、怎么新增一个 AI 工具

### 5.1 哪些是通用的（写一次，以后不用动）

下面这套"脚手架"是通用的，已经写好了，**以后加任何工具都不用再改它们**：

| 文件 | 作用 |
|------|------|
| `tool/ChatTool.java` | 工具接口，所有工具都实现它 |
| `tool/ToolRegistry.java` | 自动收集所有带 `@Component` 的工具，按名字索引 |
| `chat/stream/ChatStreamService.java` | 核心循环：检测模型要调工具 → 执行工具 → 把结果塞回给模型 |
| `chat/stream/ChatModelGateway.java` / `OpenAiStreamChatModelGateway.java` | 给模型发 tools 列表、解析模型返回的 tool_calls |
| `chat/service/CtxMessage.java` | 上下文消息，支持工具调用消息 |

AI 调用工具的整个流程已经跑通了，加新工具**不需要碰这些文件**。

### 5.2 每个新工具要写什么（2 步）

每加一个工具，只要做两件事：

#### 第 1 步：在 PracticeFeign 里加一个方法（对应 PracticeModule 的接口）

打开 `tool/PracticeFeign/PracticeFeign.java`，照着 `list` 方法的样子加一行：

```java
@FeignClient(name = "PracticeModuleServer", path = "/incorrectlyQuestions")
public interface PracticeFeign {

    // 已有的错题查询
    @GetMapping("/list")
    Result<List<WrongQuestionDTO>> list(@RequestHeader("X-User-Name") String userName,
                                        @RequestParam(name = "subjectId", required = false) Long subjectId,
                                        @RequestParam(name = "limit", required = false) Integer limit);

    // ↓↓↓ 新增的接口，比如查考试成绩
    @GetMapping("/practice/exam/scores")
    Result<List<ScoreDTO>> scores(@RequestHeader("X-User-Name") String userName);
}
```

> 注意：`@FeignClient` 的 `path` 是这个 Feign 客户端统一的前缀。如果你的新接口路径前缀和现有不一样（比如错题是 `/incorrectlyQuestions`，成绩是 `/practice/exam`），有两种做法：
> - 简单做法：把 `path` 去掉，每个方法的 `@GetMapping` 写完整路径；
> - 或者新建另一个 `@FeignClient` 接口，给不同的 `path`。

#### 第 2 步：写一个工具类（实现 ChatTool，加 @Component）

新建一个文件，比如 `tool/ExamScoresTool.java`：

```java
package org.leon.aimodule.tool;

import org.leon.aimodule.tool.PracticeFeign.PracticeFeign;
import org.leon.commonjwt.pojo.Result;
import org.springframework.stereotype.Component;

import java.util.List;

@Component   // ← 加了这个，ToolRegistry 就会自动发现它
public class ExamScoresTool implements ChatTool {

    private final PracticeFeign feign;

    // 构造器注入 PracticeFeign
    public ExamScoresTool(PracticeFeign feign) {
        this.feign = feign;
    }

    // 工具名：全小写英文，模型调用时用它匹配
    @Override
    public String name() {
        return "query_exam_scores";
    }

    // 给模型看的说明：越具体越能减少误调用
    @Override
    public String description() {
        return "查询当前用户的考试成绩列表，当用户问自己的成绩、分数、考试情况时调用。";
    }

    // 参数的 JSON Schema：没有参数就写空 object
    @Override
    public String parametersSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    // 真正干活的地方：调 PracticeFeign，把结果转成字符串返回给模型
    @Override
    public String execute(String argumentsJson, String userName) {
        Result<List<ScoreDTO>> result = feign.scores(userName);
        if (result.getCode() != 1) {
            return "{\"ok\":false,\"message\":\"查询失败\"}";
        }
        return result.getData().toString();  // 直接把数据转成字符串给模型
    }
}
```

### 5.3 就这两步，完事了

- `ToolRegistry` 会自动扫描到这个新工具
- `ChatStreamService` 会自动把它放进发给模型的 `tools` 列表
- 模型觉得需要时就会调用它

**不需要改任何其他代码。**

### 5.4 完整示例：加一个"按科目查错题"的工具（带参数）

如果工具需要参数（比如传 subjectId），`parametersSchema` 和 `execute` 里要解析参数：

```java
@Override
public String parametersSchema() {
    return """
            {
              "type": "object",
              "properties": {
                "subjectId": {
                  "type": "integer",
                  "description": "科目 id，不传则查所有科目"
                }
              }
            }
            """;
}

@Override
public String execute(String argumentsJson, String userName) {
    // 用 Jackson 解析模型传来的参数 JSON
    Long subjectId = null;
    try {
        com.fasterxml.jackson.databind.JsonNode node =
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(argumentsJson);
        if (node.hasNonNull("subjectId")) {
            subjectId = node.get("subjectId").asLong();
        }
    } catch (Exception ignored) {}

    Result<List<WrongQuestionDTO>> result = feign.list(userName, subjectId, null);
    return result.getData().toString();
}
```

### 5.5 快速检查清单

加完一个工具后，确认：
- [ ] Feign 方法的 URL、参数、返回类型和 PracticeModule 接口对得上
- [ ] 工具类加了 `@Component`
- [ ] `name()` 全小写英文、不重复
- [ ] `description()` 说清"什么时候该调"
- [ ] `parametersSchema()` 是合法 JSON，参数名和 `execute` 里解析的一致
- [ ] `execute()` 返回字符串（建议 JSON 格式，模型更好理解）

---

## 六、常见问题

**Q1: 直接访问接口返回 401？**
A：走网关（8100）要带 JWT；直接打 PracticeModule（8102）要带 `X-User-Name` 头。

**Q2: AI 不调用工具，直接瞎回答？**
A：检查 `ToolRegistry` 里有没有注册到 `WrongQuestionsTool`（启动时看日志，或者断点看 `toolRegistry.all()`）。另外确保 DeepSeek API key 配好了，工具调用需要模型支持。

**Q3: AI 调工具报错"找不到 PracticeModuleServer"？**
A：Nacos 没起来，或者 PracticeModule 没注册成功。打开 Nacos 控制台（http://localhost:8848/nacos）看服务列表里有没有 `PracticeModuleServer`。

**Q4: 一次返回太多错题撑爆上下文？**
A：默认 `limit=10`，最大 50。如果还嫌多，让模型传 `limit` 参数，或者改 `IncorrectlyQuestionsController` 里的 `MAX_LIMIT`。

**Q5: 前端错题本（/practice/wrong/page）和这个接口什么区别？**
A：`/practice/wrong/page` 是给前端错题本卡片用的，只返回题干摘要，**不返回正确答案**（防止作弊）。`/incorrectlyQuestions/list` 是给 AI 答疑用的，返回完整题目和答案真值，让 AI 能讲解。两条路分开，互不影响。

---

## 七、安全提醒

`/incorrectlyQuestions/list` 返回了题目的正确答案和解析，所以**只能给 AI 用，不要暴露给前端**。前端要看错题继续走 `/practice/wrong/page`。

当前这个接口只认 `X-User-Name`，调用方传谁的用户名就查谁的错题。如果以后要做成严格的多用户隔离，要确保这个头只能由网关注入，不能让客户端伪造。
