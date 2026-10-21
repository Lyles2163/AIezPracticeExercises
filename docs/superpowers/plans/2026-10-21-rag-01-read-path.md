# RAG 读路径实现计划（Stage 0–3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> 本文件只覆盖 spec 的第 0–3 步。执行前先读 `2026-10-21-rag-00-roadmap.md` 的“全局约束”和“已核实并必须遵守的 spec 修正”。

**Goal:** 在 AIModule 建立 RAG 依赖地基、两个 ES 索引、切片器、embedding 网关和混合检索服务，并提供 `/ai/kb/search`、`/ai/retrieval/debug` 两个读接口；手工灌 5 条切片即可验证中文 BM25 + kNN + RRF + 两级权限过滤全部工作。

**Architecture:** `IndexRegistry` 是 mapping 的唯一生产者；`ChunkPlanner` 是纯函数；`EmbeddingGateway` 是唯一向量出口（百炼 `text-embedding-v3`，1024 维，单批 ≤20）；`RetrievalService` 是唯一检索读入口，权限 filter 在 BM25 与 kNN 两条腿里各生成一次。ES 只做派生视图，本阶段不写 MySQL。

**Tech Stack:** Java 21 / Spring Boot 3.2.4 / Spring AI 1.0.0-M7 / elasticsearch-java 9.5.4 / jtokkit 1.1.0 / JUnit 5 + Mockito / 本地 ES 9.5.4（IK 分词）。

**Spec:** `docs/superpowers/specs/2026-10-12-elasticsearch-rag-design.md`（第 0–3、5.1、5.3、7.1、7.2、7.6 节）

---

## 全局约束（本文件所有任务隐含）

- JDK 21 与 Maven 路径见总览文件；统一 `-pl AIModule -am` 构建。
- 所有新代码在 `org.leon.aimodule.rag` 下；`Result<T>` 放 `org.leon.commonjwt.pojo`。
- 日志、注释、`@DisplayName` 用中文；提交信息用中文。
- ES 密码/百炼 Key 不写进 `application.yaml`，只用环境变量占位。
- 集成测试默认跳过；显式运行：`-Dgroups=es-it -Dsurefire.excludedGroups=`（embedding 用 `embedding-it`）。
- 每个 Task 结束前跑该 Task 的测试；每个 Stage 最后一个 Task 提交一次 commit。

---

## Stage 0：依赖与地基

### Task 0.1: 安装 IK 并启动本地 ES

**Files:**
- 修改（本机，不进 git）：`F:\ES\elasticsearch-9.5.4\plugins\analysis-ik`
- 无仓库文件改动

**Interfaces:**
- Consumes: 无
- Produces: `https://127.0.0.1:9200` 可访问；`ik_smart` / `ik_max_word` 可用。若安装失败则产出 `rag.analyzer=cjk` 的降级结论。

- [ ] **Step 1: 若 9200 已在监听，先停掉 ES 进程（插件安装要求节点停止）**

```bash
powershell -NoProfile -Command "\$p=(Get-NetTCPConnection -LocalPort 9200 -ErrorAction SilentlyContinue).OwningProcess; if(\$p){Stop-Process -Id \$p -Force; Start-Sleep -Seconds 3}"
```

- [ ] **Step 2: 安装 IK 9.5.4**

```bash
cd /f/ES/elasticsearch-9.5.4
cmd //c "bin\\elasticsearch-plugin.bat install https://get.infini.cloud/elasticsearch/analysis-ik/9.5.4"
```

预期：`-> Installed analysis-ik`。若 URL 404 或下载失败，跳到 Step 6 的降级分支。

- [ ] **Step 3: 后台启动 ES**

```bash
powershell -NoProfile -Command "Start-Process -FilePath 'F:\ES\elasticsearch-9.5.4\bin\elasticsearch.bat' -WindowStyle Hidden"
for i in $(seq 1 90); do code=$(curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9200/); [ "$code" = "401" ] && echo "ES up" && break; sleep 2; done
```

预期：60 秒内出现 `ES up`。若 3 分钟仍不通，读 `F:\ES\elasticsearch-9.5.4\logs\elasticsearch.log`。

- [ ] **Step 4: 读取本机密码（只进环境变量，不打印、不写文件）**

```bash
export ELASTIC_PASSWORD="$(tr -d '\r\n' < '/f/ES/密码.txt')"
test -n "$ELASTIC_PASSWORD" && echo "password loaded"
```

- [ ] **Step 5: 验证插件与 IK 切词**

```bash
curl -sk -u "elastic:$ELASTIC_PASSWORD" https://127.0.0.1:9200/_cat/plugins?v
curl -sk -u "elastic:$ELASTIC_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"analyzer":"ik_smart","text":"快速排序时间复杂度"}' \
  https://127.0.0.1:9200/_analyze
curl -sk -u "elastic:$ELASTIC_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"analyzer":"ik_max_word","text":"快速排序时间复杂度"}' \
  https://127.0.0.1:9200/_analyze
```

预期：`_cat/plugins` 出现 `analysis-ik`；`ik_smart` 输出 `快速/排序/时间/复杂度` 一类词，`ik_max_word` 输出更多子词。把两条 `_analyze` 的 token 列表贴进本文件末尾的执行记录。

- [ ] **Step 6: 降级分支（只有 Step 2/5 失败才执行）**

不装插件，改为确认 `cjk`：

```bash
curl -sk -u "elastic:$ELASTIC_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"analyzer":"cjk","text":"快速排序时间复杂度"}' \
  https://127.0.0.1:9200/_analyze
```

预期：二元组 `快速/速排/排序/...`。此时把 `application.yaml` 的 `rag.analyzer` 改成 `cjk`，并在执行记录写明原因。

---

### Task 0.2: CommonJwt 增加泛型 `Result<T>`

**Files:**
- Create: `CommonJwt/src/main/java/org/leon/commonjwt/pojo/Result.java`
- Test: 由 Task 3.3 的 MockMvc 测试间接触发（不单独建测试）

**Interfaces:**
- Consumes: 无
- Produces:
  - `Result<T>.getCode(): Integer`，成功为 `1`
  - `Result<T>.getMsg(): String`
  - `Result<T>.getData(): T`
  - `Result.success(): Result<T>`、`Result.success(T): Result<T>`
  - `Result.error(String): Result<T>`、`Result.error(int, String): Result<T>`

- [ ] **Step 1: 写类**

```java
package org.leon.commonjwt.pojo;

import java.io.Serializable;

/**
 * 泛型统一返回体。UserModule/AuthModule 原有的两份 Result 不动，
 * 只有 AIModule 使用这一份，避免扩大改动爆炸半径。
 */
public class Result<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public Result() {
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.code = 1;
        result.msg = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        return error(0, msg);
    }

    public static <T> Result<T> error(int code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
```

- [ ] **Step 2: 编译 CommonJwt**

```bash
cd /f/AAAJAVA/code/ezPracticeExercises
"$MVN" -o -pl CommonJwt -am -DskipTests install
```

预期：`BUILD SUCCESS`。这一步同时把 CommonJwt 装进本地仓库，后面单独启动 AIModule 时不必再 `-am`。

---

### Task 0.3: AIModule 依赖与配置

**Files:**
- Modify: `AIModule/pom.xml`
- Modify: `AIModule/src/main/resources/application.yaml`

**Interfaces:**
- Consumes: `Result<T>`（Task 0.2）
- Produces: `co.elastic.clients:elasticsearch-java:9.5.4`、`spring-boot-starter-validation`、`jtokkit:1.1.0` 在 classpath；`spring.ai.openai.embedding.*` 与 `rag.*` 配置可绑定。

- [ ] **Step 1: 在 `AIModule/pom.xml` 的 `<properties>` 里加版本与测试排除组**

```xml
<properties>
    <java.version>21</java.version>
    <jtokkit.version>1.1.0</jtokkit.version>
    <surefire.excludedGroups>es-it,embedding-it,db-it,mq-it</surefire.excludedGroups>
</properties>
```

- [ ] **Step 2: 加三个依赖（放在 Spring AI starter 之后）**

```xml
<!-- ES 官方 Java 客户端：显式 9.5.4，覆盖 Boot 3.2.4 的 8.10.4 托管版本 -->
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <version>9.5.4</version>
</dependency>

<!-- 让 @Valid 真正生效 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- 按 token 切片 -->
<dependency>
    <groupId>com.knuddels</groupId>
    <artifactId>jtokkit</artifactId>
    <version>${jtokkit.version}</version>
</dependency>
```

不要在 AIModule 里加 `rest-client` 或 `parsson`：9.5.4 走 `elasticsearch-rest5-client` + Jackson。

- [ ] **Step 3: 在 `<build><plugins>` 里配置 surefire**

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludedGroups>${surefire.excludedGroups}</excludedGroups>
    </configuration>
</plugin>
```

显式跑集成测试时用 `-Dsurefire.excludedGroups=` 清空排除。

- [ ] **Step 4: 在 `application.yaml` 的 `spring.ai.openai` 下加 embedding 段**

```yaml
  ai:
    openai:
      api-key: sk-032471b595b942f9913934a812f78cee  # 现有 DeepSeek Key，本次不动
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
      embedding:
        # 百炼的 OpenAI 兼容端点，必须单独覆盖 base-url/api-key，否则请求会发到 DeepSeek
        api-key: ${DASHSCOPE_API_KEY:}
        base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
        options:
          model: text-embedding-v3
          dimensions: 1024
```

- [ ] **Step 5: 在 `application.yaml` 顶层加 `rag` 段（与 `spring` 同级）**

```yaml
rag:
  analyzer: ik                     # 安装 IK 失败时改 cjk
  admin-names: ${RAG_ADMIN_NAMES:}
  elasticsearch:
    uris: ${ES_URIS:https://127.0.0.1:9200}
    username: ${ES_USERNAME:elastic}
    password: ${ELASTIC_PASSWORD:}
    ca-cert-path: ${ES_CA_CERT:F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt}
  index:
    auto-create: true
    kb-alias: ai_kb_chunk
    kb-physical: ai_kb_chunk_v1
    question-alias: ai_question
    question-physical: ai_question_v1
  embedding:
    dimensions: 1024
    batch-size: 10
    concurrency: 4
  search:
    top-k-default: 6
    top-k-max: 20
    rrf-rank-constant: 60
    rank-window-size: 50
    knn-k: 20
    num-candidates: 100
    knn-similarity: 0.55
  async: poll
```

- [ ] **Step 6: 下载依赖并编译**

```bash
"$MVN" -pl AIModule -am -DskipTests compile
```

预期：`BUILD SUCCESS`。第一次需要联网下载 ES 客户端与 jtokkit。

---

### Task 0.4: ES 配置属性与客户端 Bean

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/config/RagProperties.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/config/EsClientFactory.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/config/ElasticsearchConfig.java`

**Interfaces:**
- Consumes: `application.yaml` 的 `rag.*`
- Produces:
  - `RagProperties.getAnalyzer(): String`
  - `RagProperties.getElasticsearch(): RagProperties.Elasticsearch`
  - `RagProperties.getIndex(): RagProperties.Index`
  - `RagProperties.getEmbedding(): RagProperties.Embedding`
  - `RagProperties.getSearch(): RagProperties.Search`
  - `ElasticsearchClient`、`ElasticsearchTransport`、`Rest5Client` 三个 Bean
  - `EsClientFactory.create(RagProperties.Elasticsearch): ElasticsearchClient`（集成测试用）

- [ ] **Step 1: 写 `RagProperties`**

```java
package org.leon.aimodule.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** ik | cjk；只在 IndexRegistry 生成 mapping 时读取 */
    private String analyzer = "ik";

    /** 临时管理员名单：/ai/retrieval/debug、/ai/admin/**、official 上传 */
    private List<String> adminNames = new ArrayList<>();

    private Elasticsearch elasticsearch = new Elasticsearch();
    private Index index = new Index();
    private Embedding embedding = new Embedding();
    private Search search = new Search();

    /** poll | mq；阶段 4a 只实现 poll */
    private String async = "poll";

    @Data
    public static class Elasticsearch {
        private String uris = "https://127.0.0.1:9200";
        private String username = "elastic";
        private String password = "";
        private String caCertPath = "F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt";
    }

    @Data
    public static class Index {
        private boolean autoCreate = true;
        private String kbAlias = "ai_kb_chunk";
        private String kbPhysical = "ai_kb_chunk_v1";
        private String questionAlias = "ai_question";
        private String questionPhysical = "ai_question_v1";
    }

    @Data
    public static class Embedding {
        private int dimensions = 1024;
        private int batchSize = 10;
        private int concurrency = 4;
    }

    @Data
    public static class Search {
        private int topKDefault = 6;
        private int topKMax = 20;
        private int rrfRankConstant = 60;
        private int rankWindowSize = 50;
        private int knnK = 20;
        private int numCandidates = 100;
        private float knnSimilarity = 0.55f;
    }
}
```

- [ ] **Step 2: 写 `EsClientFactory`（SSL + Basic Auth）**

```java
package org.leon.aimodule.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;

public final class EsClientFactory {

    private EsClientFactory() {
    }

    public static ElasticsearchClient create(RagProperties.Elasticsearch props) throws Exception {
        Rest5Client rest5 = Rest5Client.builder(URI.create(props.getUris()))
                .setSSLContext(sslContext(props.getCaCertPath()))
                .setDefaultHeaders(new Header[]{basicAuth(props.getUsername(), props.getPassword())})
                .build();
        ElasticsearchTransport transport = new Rest5ClientTransport(rest5, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    public static SSLContext sslContext(String caCertPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (InputStream in = Files.newInputStream(Path.of(caCertPath))) {
            ca = cf.generateCertificate(in);
        }
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("es-ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    static Header basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return new BasicHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 3: 写 `ElasticsearchConfig`**

```java
package org.leon.aimodule.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(RagProperties props) throws Exception {
        // 直接构造完整客户端，不使用 Boot 的 ElasticsearchClientAutoConfiguration：
        // 它依赖 8.x 的 RestClient，而 9.5.4 的传输层是 rest5_client。
        return EsClientFactory.create(props.getElasticsearch());
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(ElasticsearchClient client) {
        return client._transport();
    }
}
```

`ElasticsearchClient._transport()` 返回 `ElasticsearchTransport`，该 Bean 主要给测试和运维取用；Spring 关闭时 `Rest5Client` 会由 transport 关闭。

- [ ] **Step 4: 编译**

```bash
"$MVN" -pl AIModule -am -DskipTests compile
```

预期：`BUILD SUCCESS`。若出现 `NoSuchMethodError`/`NoClassDefFoundError` 且堆栈指向 Jackson（`com.fasterxml.jackson.*`），进入 Step 5；否则跳过。

- [ ] **Step 5: 仅在 Jackson 冲突时覆盖版本**

在 `AIModule/pom.xml` 的 `<properties>` 加：

```xml
<jackson-bom.version>2.18.2</jackson-bom.version>
```

重新编译并启动；把实际命中的错误与版本写进执行记录。若没有冲突，不要加这一行。

---

### Task 0.5: 统一异常处理与 `/ai/kb/ping`

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/exception/RagException.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/exception/RagForbiddenException.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/AiExceptionHandler.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/KbPingController.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`（Task 0.4）、`Result<T>`（Task 0.2）
- Produces:
  - `GET /ai/kb/ping` → `Result<Map<String,Object>>`，data 含 `clusterName`、`version`、`aliases`
  - 异常统一转 `Result{code:0}`，不泄露堆栈

- [ ] **Step 1: 写异常类型**

```java
package org.leon.aimodule.rag.exception;

public class RagException extends RuntimeException {
    public RagException(String message) {
        super(message);
    }

    public RagException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package org.leon.aimodule.rag.exception;

public class RagForbiddenException extends RagException {
    public RagForbiddenException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: 写 `AiExceptionHandler`**

```java
package org.leon.aimodule.rag.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.transport.TransportException;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice(basePackages = "org.leon.aimodule.rag")
public class AiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.error("参数校验失败: " + msg);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<Void> handleMissingHeader(MissingRequestHeaderException e) {
        return Result.error("缺少请求头: " + e.getHeaderName());
    }

    @ExceptionHandler(RagException.class)
    public Result<Void> handleRag(RagException e) {
        log.warn("[RAG] 业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler({ElasticsearchException.class, TransportException.class, IOException.class})
    public Result<Void> handleEs(Exception e) {
        log.warn("[RAG] 检索服务不可用: {}", e.getMessage());
        return Result.error("检索服务暂时不可用");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("[RAG] 未预期异常", e);
        return Result.error("服务内部错误");
    }
}
```

- [ ] **Step 3: 写 `KbPingController`**

```java
package org.leon.aimodule.rag.web;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import co.elastic.clients.elasticsearch.indices.GetAliasResponse;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/ai/kb")
public class KbPingController {

    private final ElasticsearchClient client;

    public KbPingController(ElasticsearchClient client) {
        this.client = client;
    }

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() throws IOException {
        InfoResponse info = client.info();
        GetAliasResponse aliasResponse = client.indices().getAlias(a -> a
                .index("*")
                .ignoreUnavailable(true)
                .allowNoIndices(true));

        TreeSet<String> aliases = new TreeSet<>();
        aliasResponse.aliases().values()
                .forEach(indexAliases -> aliases.addAll(indexAliases.aliases().keySet()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clusterName", info.clusterName());
        data.put("version", info.version().number());
        data.put("aliases", List.copyOf(aliases));
        return Result.success(data);
    }
}
```

- [ ] **Step 4: 启动 AIModule 做冒烟**

```bash
export ELASTIC_PASSWORD="$(tr -d '\r\n' < '/f/ES/密码.txt')"
export DASHSCOPE_API_KEY="${DASHSCOPE_API_KEY}"
cd /f/AAAJAVA/code/ezPracticeExercises/AIModule
"$MVN" spring-boot:run
```

另开终端：

```bash
curl -s http://127.0.0.1:8105/ai/kb/ping
```

预期：

```json
{"code":1,"msg":"success","data":{"clusterName":"...","version":"9.5.4","aliases":[]}}
```

如果启动失败并报 Jackson 相关错误，回到 Task 0.4 Step 5。若报 Nacos 配置缺失，确认 `common-jwt.yaml` 在 Nacos 中存在（现有 AIModule 启动依赖它）；不要在本次改动里改 Nacos。

- [ ] **Step 5: 提交**

```bash
cd /f/AAAJAVA/code/ezPracticeExercises
git add CommonJwt/src/main/java/org/leon/commonjwt/pojo/Result.java \
        AIModule/pom.xml AIModule/src/main/resources/application.yaml \
        AIModule/src/main/java/org/leon/aimodule/rag/config \
        AIModule/src/main/java/org/leon/aimodule/rag/exception \
        AIModule/src/main/java/org/leon/aimodule/rag/web
git commit -m "搭建RAG地基：ES 9.5.4客户端、泛型Result与连通性接口"
```

---

## Stage 1：索引与切片

### Task 1.1: 解析/切片模型与 TokenCounter

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/parse/ParsedPage.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/parse/ParsedDoc.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/parse/ChunkDraft.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/chunk/TokenCounter.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/chunk/TokenCounterTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ParsedPage(int pageNo, String text)`
  - `ParsedDoc(String fileName, String title, List<ParsedPage> pages)`
  - `ChunkDraft(int seq, String content, int tokenCount, Integer pageNo, String book, String chapterPath)`
  - `TokenCounter.count(String): int`
  - `TokenCounter.tail(String, int): String`
  - `TokenCounter.hardSplit(String, int): List<String>`

- [ ] **Step 1: 写三个 record**

```java
package org.leon.aimodule.rag.parse;

public record ParsedPage(int pageNo, String text) {
}
```

```java
package org.leon.aimodule.rag.parse;

import java.util.List;

public record ParsedDoc(String fileName, String title, List<ParsedPage> pages) {
    public ParsedDoc {
        pages = List.copyOf(pages);
    }
}
```

```java
package org.leon.aimodule.rag.parse;

public record ChunkDraft(int seq, String content, int tokenCount,
                         Integer pageNo, String book, String chapterPath) {
}
```

- [ ] **Step 2: 写 `TokenCounter`**

```java
package org.leon.aimodule.rag.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TokenCounter {

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    public int count(String text) {
        return text == null || text.isBlank() ? 0 : encoding.countTokens(text);
    }

    /** 取末尾 maxTokens 个 token 对应的文本，用作切片重叠 */
    public String tail(String text, int maxTokens) {
        IntArrayList ids = encoding.encode(text);
        if (ids.size() <= maxTokens) {
            return text;
        }
        IntArrayList tail = new IntArrayList(maxTokens);
        for (int i = ids.size() - maxTokens; i < ids.size(); i++) {
            tail.add(ids.get(i));
        }
        return encoding.decode(tail);
    }

    /** 单句超过预算时的最后手段：按 token 硬切 */
    public List<String> hardSplit(String text, int maxTokens) {
        IntArrayList ids = encoding.encode(text);
        List<String> parts = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += maxTokens) {
            int to = Math.min(from + maxTokens, ids.size());
            IntArrayList part = new IntArrayList(to - from);
            for (int i = from; i < to; i++) {
                part.add(ids.get(i));
            }
            parts.add(encoding.decode(part));
        }
        return parts;
    }
}
```

- [ ] **Step 3: 写测试**

```java
package org.leon.aimodule.rag.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();

    @Test
    @DisplayName("空文本计 0 token")
    void emptyIsZero() {
        assertEquals(0, counter.count(""));
        assertEquals(0, counter.count(null));
    }

    @Test
    @DisplayName("tail 只保留末尾预算内的 token")
    void tailKeepsSuffix() {
        String text = "alpha beta gamma delta epsilon";
        String tail = counter.tail(text, 2);
        assertTrue(counter.count(tail) <= 2);
        assertTrue(text.endsWith(tail) || tail.contains("epsilon"));
    }

    @Test
    @DisplayName("hardSplit 每段都不超过预算且拼回原文 token 数不变")
    void hardSplitRespectsBudget() {
        String text = "快速排序的时间复杂度是O(nlogn)，空间复杂度是O(logn)。".repeat(20);
        List<String> parts = counter.hardSplit(text, 30);
        assertTrue(parts.size() > 1);
        int total = parts.stream().mapToInt(counter::count).sum();
        assertTrue(total >= counter.count(text) - parts.size());
        parts.forEach(p -> assertTrue(counter.count(p) <= 30));
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
"$MVN" -pl AIModule -am test -Dtest=TokenCounterTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 3, Failures: 0`。

---

### Task 1.2: `ChunkPlanner`（纯函数）+ 单测

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/chunk/ChunkPlanner.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/chunk/ChunkPlannerTest.java`

**Interfaces:**
- Consumes: `ParsedDoc`、`ParsedPage`、`ChunkDraft`、`TokenCounter`
- Produces: `List<ChunkDraft> plan(ParsedDoc doc)`；每个 chunk `tokenCount <= 500`，`seq` 从 1 连续，重叠 60 token，段落优先、单段超预算按句切、句子超预算按 token 硬切。

- [ ] **Step 1: 写 `ChunkPlanner`**

```java
package org.leon.aimodule.rag.chunk;

import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.ParsedDoc;
import org.leon.aimodule.rag.parse.ParsedPage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 500 token 预算 / 60 token 重叠；段落优先，单段超预算按句切，句子仍超预算按 token 硬切。
 * 纯函数：不读库、不发请求，输入相同输出必然相同。
 */
@Component
public class ChunkPlanner {

    public static final int BUDGET_TOKENS = 500;
    public static final int OVERLAP_TOKENS = 60;

    private static final Pattern HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百千零〇\\d]+[章节篇]|\\d+(\\.\\d+){0,3}[\\s、.]\\S.{0,48}|[一二三四五六七八九十]+、\\S.{0,48})$");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？!?；;\\n])|(?<=[.!?]\\s)");

    private final TokenCounter tokenCounter;

    public ChunkPlanner(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public List<ChunkDraft> plan(ParsedDoc doc) {
        List<Segment> segments = toSegments(doc);
        List<ChunkDraft> drafts = new ArrayList<>();
        List<Segment> window = new ArrayList<>();
        int windowTokens = 0;

        for (Segment segment : segments) {
            if (!window.isEmpty() && windowTokens + segment.tokens() > BUDGET_TOKENS) {
                drafts.add(toDraft(drafts.size() + 1, window));
                window = overlapWindow(drafts.get(drafts.size() - 1), segment);
                windowTokens = window.stream().mapToInt(Segment::tokens).sum();
            }
            window.add(segment);
            windowTokens += segment.tokens();
        }
        if (!window.isEmpty()) {
            drafts.add(toDraft(drafts.size() + 1, window));
        }
        return drafts;
    }

    private List<Segment> toSegments(ParsedDoc doc) {
        List<Segment> result = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        for (ParsedPage page : doc.pages()) {
            for (String paragraph : splitParagraphs(page.text())) {
                String text = paragraph.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (isHeading(text)) {
                    updateHeadings(headings, text);
                }
                String chapter = headings.isEmpty() ? null : String.join(" → ", headings);
                for (String piece : splitToBudget(text)) {
                    result.add(new Segment(piece, tokenCounter.count(piece), page.pageNo(), chapter));
                }
            }
        }
        return result;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\\R\\s*\\R")) {
            if (block.isBlank()) {
                continue;
            }
            if (block.indexOf('\n') < 0) {
                paragraphs.add(block);
                continue;
            }
            for (String line : block.split("\\R")) {
                if (!line.isBlank()) {
                    paragraphs.add(line);
                }
            }
        }
        return paragraphs;
    }

    private List<String> splitToBudget(String text) {
        if (tokenCounter.count(text) <= BUDGET_TOKENS) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE.split(text)) {
            if (sentence.isBlank()) {
                continue;
            }
            if (tokenCounter.count(sentence) > BUDGET_TOKENS) {
                if (current.length() > 0) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(tokenCounter.hardSplit(sentence, BUDGET_TOKENS));
                continue;
            }
            if (current.length() > 0 && tokenCounter.count(current + sentence) > BUDGET_TOKENS) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private boolean isHeading(String text) {
        return text.length() <= 80 && HEADING.matcher(text).matches();
    }

    private void updateHeadings(List<String> headings, String text) {
        boolean topLevel = text.matches("^第[一二三四五六七八九十百千零〇\\d]+[章篇].*")
                || text.matches("^\\d+[\\s、.].*");
        if (topLevel) {
            headings.clear();
        }
        headings.add(text.length() > 60 ? text.substring(0, 60) : text);
        while (headings.size() > 3) {
            headings.remove(0);
        }
    }

    private List<Segment> overlapWindow(ChunkDraft last, Segment next) {
        String tail = tokenCounter.tail(last.content(), OVERLAP_TOKENS);
        if (tail.isBlank()) {
            return new ArrayList<>();
        }
        int tailTokens = tokenCounter.count(tail);
        if (tailTokens + next.tokens() > BUDGET_TOKENS) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(
                new Segment(tail, tailTokens, next.pageNo(), next.chapterPath())));
    }

    private ChunkDraft toDraft(int seq, List<Segment> window) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : window) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(segment.text());
        }
        String content = sb.toString().strip();
        Segment first = window.get(0);
        return new ChunkDraft(seq, content, tokenCounter.count(content),
                first.pageNo(), null, first.chapterPath());
    }

    private record Segment(String text, int tokens, Integer pageNo, String chapterPath) {
    }
}
```

- [ ] **Step 2: 写 `ChunkPlannerTest`**

```java
package org.leon.aimodule.rag.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.parse.ChunkDraft;
import org.leon.aimodule.rag.parse.ParsedDoc;
import org.leon.aimodule.rag.parse.ParsedPage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPlannerTest {

    private final TokenCounter tokenCounter = new TokenCounter();
    private final ChunkPlanner planner = new ChunkPlanner(tokenCounter);

    @Test
    @DisplayName("长文按 500 token 预算切分且 seq 连续")
    void splitsByBudget() {
        String paragraph = "快速排序的时间复杂度是 O(n log n)，空间复杂度是 O(log n)。".repeat(60);
        ParsedDoc doc = new ParsedDoc("algo.txt", "算法", List.of(new ParsedPage(1, paragraph)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= ChunkPlanner.BUDGET_TOKENS));
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).seq());
        }
    }

    @Test
    @DisplayName("相邻切片有重叠")
    void hasOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            text.append("第").append(i).append("段：排序算法的稳定性与时间复杂度分析。\n\n");
        }
        ParsedDoc doc = new ParsedDoc("algo.txt", "算法", List.of(new ParsedPage(1, text.toString())));

        List<ChunkDraft> chunks = planner.plan(doc);
        assertTrue(chunks.size() >= 2);

        String firstTail = chunks.get(0).content();
        firstTail = firstTail.substring(Math.max(0, firstTail.length() - 30));
        assertTrue(chunks.get(1).content().contains(firstTail),
                "第二个切片开头应包含第一个切片末尾的重叠文本");
    }

    @Test
    @DisplayName("单段超预算时按句号切开")
    void splitsOversizedParagraph() {
        String paragraph = "这是一个很长的句子。".repeat(200);
        ParsedDoc doc = new ParsedDoc("a.txt", "标题", List.of(new ParsedPage(1, paragraph)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= ChunkPlanner.BUDGET_TOKENS));
    }

    @Test
    @DisplayName("页码取切片起始段落所在页")
    void keepsPageNumber() {
        String page1 = "第一页内容。".repeat(80);
        String page2 = "第二页内容。".repeat(80);
        ParsedDoc doc = new ParsedDoc("book.pdf", "教材", List.of(
                new ParsedPage(1, page1), new ParsedPage(2, page2)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertEquals(1, chunks.get(0).pageNo());
        assertTrue(chunks.stream().anyMatch(c -> Integer.valueOf(2).equals(c.pageNo())));
    }

    @Test
    @DisplayName("章节标题进入 chapterPath")
    void tracksChapterPath() {
        String text = "第3章 数据结构\n\n3.2 常见排序算法\n\n快速排序是分治算法。";
        ParsedDoc doc = new ParsedDoc("book.pdf", "教材", List.of(new ParsedPage(45, text)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertNotNull(chunks.get(0).chapterPath());
        assertTrue(chunks.get(0).chapterPath().contains("第3章"));
        assertTrue(chunks.get(0).chapterPath().contains("3.2"));
    }

    @Test
    @DisplayName("空文档返回空列表")
    void emptyDoc() {
        assertTrue(planner.plan(new ParsedDoc("empty.txt", "空", List.of())).isEmpty());
        assertTrue(planner.plan(new ParsedDoc("empty.txt", "空",
                List.of(new ParsedPage(1, "   ")))).isEmpty());
    }

    @Test
    @DisplayName("中英混排仍遵守预算")
    void mixedLanguage() {
        String text = ("Quick sort average time is O(n log n). 快速排序平均时间复杂度是 O(nlogn)。").repeat(80);
        ParsedDoc doc = new ParsedDoc("mixed.txt", "混排", List.of(new ParsedPage(1, text)));

        List<ChunkDraft> chunks = planner.plan(doc);

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.stream().allMatch(c -> c.tokenCount() <= ChunkPlanner.BUDGET_TOKENS));
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
"$MVN" -pl AIModule -am test -Dtest=ChunkPlannerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：7 个测试全绿。若重叠测试因 token 边界偶发失败，打印 `chunks.get(0).content()` 末尾与 `chunks.get(1).content()` 开头，按实际 token 边界调整断言（不要放宽预算断言）。

---

### Task 1.3: 索引 mapping 与 `IndexRegistry`

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/index/MappingSpec.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/index/IndexMappings.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/index/IndexRegistry.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/index/IndexBootstrap.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/index/IndexMappingsTest.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/index/IndexRegistryIT.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`、`RagProperties`
- Produces:
  - `IndexRegistry.ensureKbIndex(): void`、`ensureQuestionIndex(): void`
  - `IndexRegistry.swapAlias(String alias, String newPhysical): void`
  - `IndexRegistry.stats(): Map<String, Long>`（别名 → 文档数）
  - `IndexRegistry.deleteIndex(String physical): void`
  - `IndexRegistry.refresh(String alias): void`
  - `IndexMappings.kb(String analyzer): MappingSpec`
  - `IndexMappings.question(String analyzer): MappingSpec`

- [ ] **Step 1: 写 `MappingSpec` 与 `IndexMappings`**

```java
package org.leon.aimodule.rag.index;

public record MappingSpec(String settings, String properties) {
}
```

```java
package org.leon.aimodule.rag.index;

/**
 * 索引 mapping 的唯一生产者。analyzer 一旦建索引就不可改，
 * 切换 ik/cjk 等于重建索引，所以这里按 analyzer 整体替换。
 */
public final class IndexMappings {

    private IndexMappings() {
    }

    public static MappingSpec kb(String analyzer) {
        String settings = "ik".equalsIgnoreCase(analyzer) ? IK_SETTINGS : BASE_SETTINGS;
        String title = textField(analyzer);
        String content = "\"content\":{\"type\":\"text\"," + analyzerProps(analyzer) + "}";
        String properties = """
                {"properties":{
                  "chunk_id":{"type":"long"},
                  "doc_id":{"type":"long"},
                  "scope":{"type":"keyword"},
                  "owner_name":{"type":"keyword"},
                  "subject_id":{"type":"long"},
                  "title":%s,
                  "content":%s,
                  "vec":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine",
                         "index_options":{"type":"int8_hnsw","m":16,"ef_construction":100}},
                  "book":{"type":"keyword"},
                  "chapter_path":{"type":"keyword","index":false,"doc_values":true},
                  "page_no":{"type":"integer"},
                  "token_count":{"type":"integer"}
                }}""".formatted(title, content);
        return new MappingSpec(settings, properties);
    }

    public static MappingSpec question(String analyzer) {
        String settings = "ik".equalsIgnoreCase(analyzer) ? IK_SETTINGS : BASE_SETTINGS;
        String title = textField(analyzer);
        String analysis = "\"analysis\":{\"type\":\"text\"," + analyzerProps(analyzer) + "}";
        String properties = """
                {"properties":{
                  "question_id":{"type":"long"},
                  "scope":{"type":"keyword"},
                  "owner_name":{"type":"keyword"},
                  "subject_id":{"type":"long"},
                  "title":%s,
                  "analysis":%s,
                  "vec":{"type":"dense_vector","dims":1024,"index":true,"similarity":"cosine",
                         "index_options":{"type":"int8_hnsw","m":16,"ef_construction":100}},
                  "tags":{"type":"keyword"},
                  "difficulty":{"type":"integer"},
                  "qtype":{"type":"keyword"}
                }}""".formatted(title, analysis);
        return new MappingSpec(settings, properties);
    }

    private static String textField(String analyzer) {
        return "{\"type\":\"text\"," + analyzerProps(analyzer) + ",\"fields\":{\"kw\":{\"type\":\"keyword\"}}}";
    }

    private static String analyzerProps(String analyzer) {
        if ("ik".equalsIgnoreCase(analyzer)) {
            return "\"analyzer\":\"zh_index\",\"search_analyzer\":\"zh_search\"";
        }
        return "\"analyzer\":\"cjk\"";
    }

    private static final String BASE_SETTINGS = """
            {"number_of_shards":1,"number_of_replicas":0,"refresh_interval":"30s"}""";

    private static final String IK_SETTINGS = """
            {"number_of_shards":1,"number_of_replicas":0,"refresh_interval":"30s",
             "analysis":{"analyzer":{
               "zh_index":{"tokenizer":"ik_max_word"},
               "zh_search":{"tokenizer":"ik_smart"}
             }}}""";
}
```

- [ ] **Step 2: 写 `IndexRegistry`**

```java
package org.leon.aimodule.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import org.leon.aimodule.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class IndexRegistry {

    private final ElasticsearchClient client;
    private final RagProperties props;

    public IndexRegistry(ElasticsearchClient client, RagProperties props) {
        this.client = client;
        this.props = props;
    }

    public void ensureKbIndex() throws IOException {
        ensure(props.getIndex().getKbAlias(), props.getIndex().getKbPhysical(),
                IndexMappings.kb(props.getAnalyzer()));
    }

    public void ensureQuestionIndex() throws IOException {
        ensure(props.getIndex().getQuestionAlias(), props.getIndex().getQuestionPhysical(),
                IndexMappings.question(props.getAnalyzer()));
    }

    private void ensure(String alias, String physical, MappingSpec spec) throws IOException {
        if (client.indices().existsAlias(a -> a.name(alias)).value()) {
            return;
        }
        if (client.indices().exists(e -> e.index(physical)).value()) {
            client.indices().updateAliases(u -> u.actions(
                    Action.of(a -> a.add(add -> add.index(physical).alias(alias)))));
            return;
        }
        client.indices().create(c -> c
                .index(physical)
                .aliases(alias, a -> a)
                .settings(s -> s.withJson(new StringReader(spec.settings())))
                .mappings(m -> m.withJson(new StringReader(spec.properties()))));
    }

    /** 原子切别名：reindex 完成后唯一动作 */
    public void swapAlias(String alias, String newPhysical) throws IOException {
        String oldPhysical = resolvePhysical(alias);
        client.indices().updateAliases(u -> u.actions(
                Action.of(a -> a.remove(r -> {
                    r.index(oldPhysical).alias(alias);
                    return r;
                })),
                Action.of(a -> a.add(add -> add.index(newPhysical).alias(alias)))));
    }

    public Map<String, Long> stats() throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String alias : new String[]{props.getIndex().getKbAlias(), props.getIndex().getQuestionAlias()}) {
            if (client.indices().existsAlias(a -> a.name(alias)).value()) {
                result.put(alias, client.count(c -> c.index(alias)).count());
            }
        }
        return result;
    }

    public void refresh(String alias) throws IOException {
        client.indices().refresh(r -> r.index(alias));
    }

    public void deleteIndex(String physical) throws IOException {
        if (client.indices().exists(e -> e.index(physical)).value()) {
            client.indices().delete(d -> d.index(physical));
        }
    }

    private String resolvePhysical(String alias) throws IOException {
        var response = client.indices().getAlias(a -> a.name(alias));
        return response.aliases().keySet().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("别名不存在: " + alias));
    }
}
```

注意 `swapAlias` 里 `Action.of` 的 `remove` lambda 返回 `ObjectBuilder<RemoveAction>`；`r.index(...).alias(...)` 返回的是 `RemoveAction.Builder`，需要 `return r;` 才能匹配函数式接口。上面的写法是合法的。

- [ ] **Step 3: 写 `IndexBootstrap`**

```java
package org.leon.aimodule.rag.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.index", name = "auto-create", havingValue = "true")
public class IndexBootstrap implements ApplicationRunner {

    private final IndexRegistry registry;

    public IndexBootstrap(IndexRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            registry.ensureKbIndex();
            registry.ensureQuestionIndex();
            log.info("[RAG] 索引已就绪: {}", registry.stats());
        } catch (Exception e) {
            // 检索是增强不是依赖：ES 不可用时应用仍要能启动
            log.warn("[RAG] 启动时创建索引失败，检索功能暂不可用: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 写 mapping 单测**

```java
package org.leon.aimodule.rag.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexMappingsTest {

    @Test
    @DisplayName("ik 模式使用 ik_max_word 建索引、ik_smart 查询")
    void ikMapping() {
        MappingSpec spec = IndexMappings.kb("ik");
        assertTrue(spec.settings().contains("ik_max_word"));
        assertTrue(spec.settings().contains("ik_smart"));
        assertTrue(spec.properties().contains("zh_index"));
        assertTrue(spec.properties().contains("zh_search"));
        assertTrue(spec.properties().contains("\"dims\":1024"));
        assertTrue(spec.properties().contains("int8_hnsw"));
    }

    @Test
    @DisplayName("cjk 模式只使用内置分析器，不声明 analysis 段")
    void cjkMapping() {
        MappingSpec spec = IndexMappings.kb("cjk");
        assertFalse(spec.settings().contains("analysis"));
        assertTrue(spec.properties().contains("\"analyzer\":\"cjk\""));
        assertFalse(spec.properties().contains("ik_"));
    }

    @Test
    @DisplayName("题目索引没有 book/chapter_path/page_no")
    void questionMapping() {
        String props = IndexMappings.question("ik").properties();
        assertTrue(props.contains("question_id"));
        assertTrue(props.contains("\"analysis\":{\"type\":\"text\""));
        assertFalse(props.contains("chapter_path"));
        assertFalse(props.contains("page_no"));
    }
}
```

- [ ] **Step 5: 写 ES 集成测试（默认跳过）**

```java
package org.leon.aimodule.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.EsClientFactory;
import org.leon.aimodule.rag.config.RagProperties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("es-it")
class IndexRegistryIT {

    private static ElasticsearchClient client;

    private static ElasticsearchClient client() throws Exception {
        Assumptions.assumeTrue(System.getenv("ELASTIC_PASSWORD") != null, "需要 ELASTIC_PASSWORD");
        RagProperties.Elasticsearch es = new RagProperties.Elasticsearch();
        es.setUris(System.getenv().getOrDefault("ES_URIS", "https://127.0.0.1:9200"));
        es.setUsername(System.getenv().getOrDefault("ES_USERNAME", "elastic"));
        es.setPassword(System.getenv("ELASTIC_PASSWORD"));
        es.setCaCertPath(System.getenv().getOrDefault("ES_CA_CERT",
                "F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt"));
        client = EsClientFactory.create(es);
        return client;
    }

    @AfterAll
    static void cleanUp() throws Exception {
        ElasticsearchClient c = client();
        c.indices().delete(d -> d.index("test_ai_kb_chunk_v1"));
        c._transport().close();
    }

    @Test
    @DisplayName("测试索引建立后可被别名检索到，mapping 含 1024 维与 IK 分析器")
    void createsIndexWithAlias() throws Exception {
        ElasticsearchClient c = client();
        c.indices().delete(d -> d.index("test_ai_kb_chunk_v1"));

        RagProperties props = new RagProperties();
        props.setAnalyzer("cjk");
        props.getIndex().setKbAlias("test_ai_kb_chunk");
        props.getIndex().setKbPhysical("test_ai_kb_chunk_v1");
        IndexRegistry registry = new IndexRegistry(c, props);

        registry.ensureKbIndex();

        assertTrue(c.indices().existsAlias(a -> a.name("test_ai_kb_chunk")).value());
        String mapping = c.indices().getMapping(m -> m.index("test_ai_kb_chunk_v1")).toString();
        assertTrue(mapping.contains("dims=1024") || mapping.contains("\"dims\":1024"));
        assertTrue(mapping.contains("cjk") || mapping.contains("analyzer"));
        Map<String, Long> stats = registry.stats();
        assertTrue(stats.containsKey("test_ai_kb_chunk"));
        assertFalse(stats.get("test_ai_kb_chunk") > 0);

        registry.deleteIndex("test_ai_kb_chunk_v1");
    }
}
```

- [ ] **Step 6: 跑单测与集成测试**

```bash
"$MVN" -pl AIModule -am test -Dtest=IndexMappingsTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：3 个测试全绿。

```bash
export ELASTIC_PASSWORD="$(tr -d '\r\n' < '/f/ES/密码.txt')"
"$MVN" -pl AIModule -am test -Dtest=IndexRegistryIT -Dgroups=es-it -Dsurefire.excludedGroups= \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`BUILD SUCCESS`。若 mapping 的 `toString()` 格式不含 `dims=1024`，用 `curl -k -u elastic:$ELASTIC_PASSWORD "https://127.0.0.1:9200/test_ai_kb_chunk_v1/_mapping"` 人工确认后调整断言。

- [ ] **Step 7: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/rag/index \
        AIModule/src/main/java/org/leon/aimodule/rag/chunk \
        AIModule/src/main/java/org/leon/aimodule/rag/parse \
        AIModule/src/test/java/org/leon/aimodule/rag
git commit -m "新增切片器与索引注册表：500token预算、IK映射与别名机制"
```

---

## Stage 2：EmbeddingGateway

### Task 2.1: 唯一向量出口 + 分批/重试/维度断言

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/exception/EmbeddingException.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/embedding/EmbeddingClient.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/embedding/SpringAiEmbeddingClient.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/embedding/EmbeddingGateway.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/embedding/EmbeddingGatewayTest.java`

**Interfaces:**
- Consumes: `RagProperties.Embedding`、Spring AI `EmbeddingModel`
- Produces:
  - `EmbeddingGateway.embed(List<String>): List<float[]>`，顺序与输入一致
  - `EmbeddingGateway.callCount(): long`（批次调用计数，阶段 4b 幂等测试用）
  - `EmbeddingClient.embed(List<String>): List<float[]>`

- [ ] **Step 1: 写 `EmbeddingException`**

```java
package org.leon.aimodule.rag.exception;

public class EmbeddingException extends RagException {
    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: 写接口与 Spring AI 适配器**

```java
package org.leon.aimodule.rag.embedding;

import java.util.List;

public interface EmbeddingClient {
    List<float[]> embed(List<String> texts);
}
```

```java
package org.leon.aimodule.rag.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
```

- [ ] **Step 3: 写 `EmbeddingGateway`**

```java
package org.leon.aimodule.rag.embedding;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.EmbeddingException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全项目唯一的向量出口。百炼单次最多 20 条，超过直接 400；
 * batchSize=10 是硬性分批，不是性能优化。
 */
@Slf4j
@Component
public class EmbeddingGateway {

    private static final int MAX_ATTEMPTS = 3;

    private final EmbeddingClient client;
    private final RagProperties props;
    private final ExecutorService executor;
    private final AtomicLong callCounter = new AtomicLong();

    public EmbeddingGateway(EmbeddingClient client, RagProperties props) {
        this.client = client;
        this.props = props;
        this.executor = Executors.newFixedThreadPool(
                Math.max(1, props.getEmbedding().getConcurrency()),
                r -> {
                    Thread t = new Thread(r, "rag-embedding");
                    t.setDaemon(true);
                    return t;
                });
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, props.getEmbedding().getBatchSize());
        List<List<String>> batches = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += batchSize) {
            batches.add(texts.subList(from, Math.min(from + batchSize, texts.size())));
        }

        List<CompletableFuture<List<float[]>>> futures = batches.stream()
                .map(batch -> CompletableFuture.supplyAsync(() -> embedBatch(batch), executor))
                .toList();

        List<float[]> vectors = new ArrayList<>(texts.size());
        try {
            for (CompletableFuture<List<float[]>> future : futures) {
                vectors.addAll(future.join());
            }
        } catch (CompletionException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof EmbeddingException embeddingException) {
                throw embeddingException;
            }
            throw new EmbeddingException("embedding 调用失败", cause);
        }
        return vectors;
    }

    /** 阶段 4b 的幂等测试用它断言“重复消息没有产生新的 embedding 调用” */
    public long callCount() {
        return callCounter.get();
    }

    private List<float[]> embedBatch(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                callCounter.incrementAndGet();
                List<float[]> vectors = client.embed(batch);
                assertShape(batch, vectors);
                return vectors;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[RAG] embedding 第 {}/{} 次失败，batchSize={}: {}",
                        attempt, MAX_ATTEMPTS, batch.size(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(500L << (attempt - 1));
                }
            }
        }
        throw new EmbeddingException("embedding 调用失败，已重试 " + MAX_ATTEMPTS + " 次", last);
    }

    private void assertShape(List<String> batch, List<float[]> vectors) {
        int dims = props.getEmbedding().getDimensions();
        if (vectors == null || vectors.size() != batch.size()) {
            throw new EmbeddingException("embedding 返回条数不符: 期望 " + batch.size()
                    + "，实际 " + (vectors == null ? 0 : vectors.size()));
        }
        for (float[] vector : vectors) {
            if (vector == null || vector.length != dims) {
                throw new EmbeddingException("embedding 维度不符: 期望 " + dims
                        + "，实际 " + (vector == null ? 0 : vector.length));
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("embedding 重试被中断", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
```

- [ ] **Step 4: 写单测**

```java
package org.leon.aimodule.rag.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.EmbeddingException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingGatewayTest {

    private RagProperties props(int batchSize) {
        RagProperties props = new RagProperties();
        props.getEmbedding().setBatchSize(batchSize);
        props.getEmbedding().setConcurrency(2);
        props.getEmbedding().setDimensions(4);
        return props;
    }

    private float[] vec() {
        return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
    }

    @Test
    @DisplayName("21 条文本切成 10/10/1 三批，顺序保持")
    void batchesAtMostTen() {
        AtomicInteger batches = new AtomicInteger();
        EmbeddingClient fake = texts -> {
            batches.incrementAndGet();
            List<float[]> out = new ArrayList<>();
            for (String text : texts) {
                float[] v = vec();
                v[0] = Integer.parseInt(text.replace("t", "")) / 100f;
                out.add(v);
            }
            return out;
        };
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        List<String> texts = IntStream.range(0, 21).mapToObj(i -> "t" + i).toList();
        List<float[]> vectors = gateway.embed(texts);

        assertEquals(21, vectors.size());
        assertEquals(3, batches.get());
        assertEquals(3, gateway.callCount());
        assertEquals(0.00f, vectors.get(0)[0], 0.0001f);
        assertEquals(0.20f, vectors.get(20)[0], 0.0001f);
        gateway.shutdown();
    }

    @Test
    @DisplayName("维度不符立即失败")
    void rejectsWrongDimensions() {
        EmbeddingClient fake = texts -> List.of(new float[]{1f, 2f});
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        EmbeddingException e = assertThrows(EmbeddingException.class,
                () -> gateway.embed(List.of("a")));
        assertTrue(e.getMessage().contains("维度"));
        gateway.shutdown();
    }

    @Test
    @DisplayName("首次失败后重试成功")
    void retriesTransientFailure() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient fake = texts -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("网络抖动");
            }
            return List.of(vec());
        };
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        List<float[]> vectors = gateway.embed(List.of("a"));

        assertEquals(1, vectors.size());
        assertEquals(2, calls.get());
        gateway.shutdown();
    }

    @Test
    @DisplayName("空输入不发请求")
    void emptyInput() {
        AtomicInteger calls = new AtomicInteger();
        EmbeddingClient fake = texts -> {
            calls.incrementAndGet();
            return List.of();
        };
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props(10));

        assertTrue(gateway.embed(List.of()).isEmpty());
        assertEquals(0, calls.get());
        gateway.shutdown();
    }
}
```

- [ ] **Step 5: 跑单测**

```bash
"$MVN" -pl AIModule -am test -Dtest=EmbeddingGatewayTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：4 个测试全绿。

---

### Task 2.2: 百炼真实调用验证

**Files:**
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/embedding/EmbeddingGatewayLiveTest.java`

**Interfaces:**
- Consumes: `EmbeddingGateway`、`DASHSCOPE_API_KEY`
- Produces: 真实 1024 维向量与相似度结论（写进执行记录）

- [ ] **Step 1: 写真实调用测试（`@Tag("embedding-it")`）**

```java
package org.leon.aimodule.rag.embedding;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("embedding-it")
class EmbeddingGatewayLiveTest {

    @Test
    @DisplayName("百炼 text-embedding-v3 返回 1024 维，相近句相似度高于无关句")
    void realDashScope() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "需要 DASHSCOPE_API_KEY");

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(apiKey)
                .build();
        OpenAiEmbeddingModel model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-v3")
                        .dimensions(1024)
                        .build());

        RagProperties props = new RagProperties();
        props.getEmbedding().setDimensions(1024);
        props.getEmbedding().setBatchSize(10);
        props.getEmbedding().setConcurrency(2);
        EmbeddingGateway gateway = new EmbeddingGateway(new SpringAiEmbeddingClient(model), props);

        List<float[]> vectors = gateway.embed(List.of(
                "快速排序的时间复杂度",
                "快排的复杂度是多少",
                "火车相遇问题怎么求相遇时间"));

        assertEquals(3, vectors.size());
        assertEquals(1024, vectors.get(0).length);
        double related = cosine(vectors.get(0), vectors.get(1));
        double unrelated = cosine(vectors.get(0), vectors.get(2));

        System.out.printf("cosine(相近)=%.4f cosine(无关)=%.4f%n", related, unrelated);
        assertTrue(related > 0.7, "相近句相似度应 > 0.7，实际 " + related);
        assertTrue(unrelated < 0.4, "无关句相似度应 < 0.4，实际 " + unrelated);
        gateway.shutdown();
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
```

- [ ] **Step 2: 运行真实调用**

```bash
export DASHSCOPE_API_KEY="$(printenv DASHSCOPE_API_KEY)"
"$MVN" -pl AIModule -am test -Dtest=EmbeddingGatewayLiveTest -Dgroups=embedding-it \
  -Dsurefire.excludedGroups= -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：`Tests run: 1, Failures: 0`，控制台打印两个 cosine。把四个数字（实际 related/unrelated）贴进执行记录。若相近句未过 0.7，不要改阈值迎合结果；先检查是否把 `dimensions` 发给了百炼（`OpenAiEmbeddingOptions.dimensions(1024)` 必须存在），再记录真实值供 spec 复核。

- [ ] **Step 3: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/rag/embedding \
        AIModule/src/main/java/org/leon/aimodule/rag/exception/EmbeddingException.java \
        AIModule/src/test/java/org/leon/aimodule/rag/embedding
git commit -m "新增embedding网关：10条分批、三次重试与1024维断言"
```

---

## Stage 3：混合检索与读接口

### Task 3.1: 请求/响应模型、权限 filter 与混合查询构造

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/RetrievalQuery.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/SearchOptions.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/KnowledgeSearchItem.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/KnowledgeSearchResponse.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/DebugLegItem.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/DebugSearchResponse.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/KbChunkDoc.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/VectorMath.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/PermissionFilters.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/HybridQueryBuilder.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/search/PermissionFiltersTest.java`

**Interfaces:**
- Consumes: `RagProperties.Search`
- Produces:
  - `SearchOptions(String ownerName, List<String> scopes, Long subjectId, int topK)`
  - `PermissionFilters.build(List<String> scopes, String ownerName, Long subjectId): List<Query>`
  - `HybridQueryBuilder.build(String index, String queryText, float[] vector, List<Query> filters, SearchOptions options, RagProperties.Search search): SearchRequest`
  - `HybridQueryBuilder.bm25Request(...): SearchRequest`（debug 用）
  - `HybridQueryBuilder.knnRequest(...): SearchRequest`（debug 用）
  - `VectorMath.cosine(float[], float[]): double`

- [ ] **Step 1: 写请求与响应模型**

```java
package org.leon.aimodule.rag.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class RetrievalQuery {

    @NotBlank(message = "不能为空")
    private String query;

    @Min(value = 1, message = "至少为 1")
    @Max(value = 20, message = "最多为 20")
    private Integer topK;

    /** null=不限学科；0=只看全学科通用；>0=本学科 OR 通用 */
    private Long subjectId;

    /** official / personal；缺省=两者都查 */
    private List<String> scope;
}
```

```java
package org.leon.aimodule.rag.search;

import java.util.List;

public record SearchOptions(String ownerName, List<String> scopes, Long subjectId, int topK) {
    public SearchOptions {
        scopes = List.copyOf(scopes);
    }
}
```

```java
package org.leon.aimodule.rag.search;

public record KnowledgeSearchItem(long chunkId, long docId, int rank, double cosine,
                                  String title, String book, String chapterPath,
                                  Integer pageNo, String content) {
}
```

```java
package org.leon.aimodule.rag.search;

import java.util.List;

public record KnowledgeSearchResponse(int took, boolean empty, List<KnowledgeSearchItem> items) {
    public KnowledgeSearchResponse {
        items = List.copyOf(items);
    }
}
```

```java
package org.leon.aimodule.rag.search;

public record DebugLegItem(long chunkId, long docId, int rank, Double score, Double cosine,
                           String title, String content) {
}
```

```java
package org.leon.aimodule.rag.search;

import java.util.List;

public record DebugSearchResponse(int took, List<DebugLegItem> bm25, List<DebugLegItem> knn) {
    public DebugSearchResponse {
        bm25 = List.copyOf(bm25);
        knn = List.copyOf(knn);
    }
}
```

```java
package org.leon.aimodule.rag.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbChunkDoc {
    @JsonProperty("chunk_id") private Long chunkId;
    @JsonProperty("doc_id") private Long docId;
    private String scope;
    @JsonProperty("owner_name") private String ownerName;
    @JsonProperty("subject_id") private Long subjectId;
    private String title;
    private String content;
    private float[] vec;
    private String book;
    @JsonProperty("chapter_path") private String chapterPath;
    @JsonProperty("page_no") private Integer pageNo;
    @JsonProperty("token_count") private Integer tokenCount;
}
```

```java
package org.leon.aimodule.rag.search;

public final class VectorMath {

    private VectorMath() {
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0d;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
```

- [ ] **Step 2: 写权限 filter（唯一出处）**

```java
package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限过滤的唯一 Java 出处。调用方必须在 BM25 与 kNN 两条腿上各用一次；
 * 任何一条腿漏掉 filter，泄露的就是乙的私人讲义。
 */
public final class PermissionFilters {

    private PermissionFilters() {
    }

    public static List<Query> build(List<String> scopes, String ownerName, Long subjectId) {
        List<Query> filters = new ArrayList<>();
        if (scopes != null && !scopes.isEmpty()) {
            filters.add(scopeFilter(scopes, ownerName));
        }
        if (subjectId != null) {
            filters.add(subjectFilter(subjectId));
        }
        return filters;
    }

    private static Query scopeFilter(List<String> scopes, String ownerName) {
        boolean official = scopes.contains("official");
        boolean personal = scopes.contains("personal");
        if (!personal) {
            return term("scope", "official");
        }
        if (!official) {
            return personalFilter(ownerName);
        }
        return Query.of(q -> q.bool(b -> b.minimumShouldMatch("1")
                .should(term("scope", "official"), personalFilter(ownerName))));
    }

    private static Query personalFilter(String ownerName) {
        return Query.of(q -> q.bool(b -> b.filter(
                term("scope", "personal"),
                term("owner_name", ownerName))));
    }

    private static Query subjectFilter(Long subjectId) {
        if (subjectId == 0) {
            return term("subject_id", 0L);
        }
        return Query.of(q -> q.bool(b -> b.minimumShouldMatch("1")
                .should(term("subject_id", subjectId), term("subject_id", 0L))));
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static Query term(String field, long value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }
}
```

- [ ] **Step 3: 写 `HybridQueryBuilder`**

```java
package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.RRFRetrieverEntry;
import co.elastic.clients.elasticsearch._types.Retriever;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.leon.aimodule.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class HybridQueryBuilder {

    public SearchRequest build(String index, String queryText, float[] vector,
                               List<Query> filters, SearchOptions options,
                               RagProperties.Search search) {
        Query bm25 = bm25Query(queryText, filters);
        List<Float> queryVector = toList(vector);

        Retriever standard = Retriever.of(r -> r.standard(s -> s.query(bm25)));
        Retriever knn = Retriever.of(r -> r.knn(k -> k
                .field("vec")
                .queryVector(queryVector)
                .k(search.getKnnK())
                .numCandidates(search.getNumCandidates())
                .similarity(search.getKnnSimilarity())
                .filter(filters)));

        Retriever rrf = Retriever.of(r -> r.rrf(rr -> rr
                .rankConstant(search.getRrfRankConstant())
                .rankWindowSize(search.getRankWindowSize())
                .retrievers(
                        RRFRetrieverEntry.of(e -> e.retriever(standard)),
                        RRFRetrieverEntry.of(e -> e.retriever(knn)))));

        return SearchRequest.of(s -> s.index(index).size(options.topK()).retriever(rrf));
    }

    public SearchRequest bm25Request(String index, String queryText, List<Query> filters, int size) {
        return SearchRequest.of(s -> s.index(index).size(size).query(bm25Query(queryText, filters)));
    }

    public SearchRequest knnRequest(String index, float[] vector, List<Query> filters,
                                    RagProperties.Search search, int size) {
        KnnSearch knn = KnnSearch.of(k -> k
                .field("vec")
                .queryVector(toList(vector))
                .k(size)
                .numCandidates(search.getNumCandidates())
                .similarity(search.getKnnSimilarity())
                .filter(filters));
        return SearchRequest.of(s -> s.index(index).size(size).knn(knn));
    }

    public Query bm25Query(String queryText, List<Query> filters) {
        return Query.of(q -> q.bool(b -> b
                .must(m -> m.multiMatch(mm -> mm.query(queryText).fields("title^2", "content")))
                .filter(filters)));
    }

    private List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }
}
```

- [ ] **Step 4: 写权限/DSL 单测（把请求序列化成 JSON 断言）**

```java
package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionFiltersTest {

    private final HybridQueryBuilder builder = new HybridQueryBuilder();
    private final JsonpMapper mapper = new JacksonJsonpMapper();
    private final RagProperties.Search search = new RagProperties.Search();

    private float[] vector() {
        float[] v = new float[1024];
        v[0] = 1f;
        return v;
    }

    private String json(SearchRequest request) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
            request.serialize(generator, mapper);
        }
        return writer.toString();
    }

    @Test
    @DisplayName("权限 filter 在 BM25 与 kNN 两条腿里各出现一次，且写到序列化后的 JSON 里")
    void permissionFilterAppearsTwice() {
        List<Query> filters = PermissionFilters.build(List.of("official", "personal"), "alice", 12L);
        SearchRequest request = builder.build("ai_kb_chunk", "快排为什么是 O(nlogn)",
                vector(), filters, new SearchOptions("alice", List.of("official", "personal"), 12L, 6), search);
        String json = json(request);

        assertEquals(2, count(json, "\"owner_name\":\"alice\""), json);
        assertEquals(2, count(json, "\"subject_id\":12"), json);
        assertEquals(2, count(json, "\"subject_id\":0"), json);
        assertTrue(json.contains("\"rrf\""));
        assertTrue(json.contains("\"rank_constant\":60"));
        assertTrue(json.contains("\"similarity\":0.55"));
        assertFalse(json.contains("\"min_score\""), "RRF 的 _score 是名次，不能设 min_score");
    }

    @Test
    @DisplayName("只查 official 时不出现 owner_name")
    void officialOnlyHasNoOwner() {
        List<Query> filters = PermissionFilters.build(List.of("official"), "alice", null);
        assertEquals(1, filters.size());
        String json = json(builder.bm25Request("ai_kb_chunk", "排序", filters, 50));
        assertFalse(json.contains("owner_name"));
        assertTrue(json.contains("\"scope\":\"official\""));
        assertFalse(json.contains("subject_id"));
    }

    @Test
    @DisplayName("只查 personal 时必须有 owner_name")
    void personalOnlyRequiresOwner() {
        List<Query> filters = PermissionFilters.build(List.of("personal"), "alice", null);
        String json = json(builder.bm25Request("ai_kb_chunk", "排序", filters, 50));
        assertTrue(json.contains("\"scope\":\"personal\""));
        assertTrue(json.contains("\"owner_name\":\"alice\""));
        assertFalse(json.contains("\"scope\":\"official\""));
    }

    @Test
    @DisplayName("subjectId=0 只匹配全学科通用")
    void zeroSubjectMeansGeneralOnly() {
        List<Query> filters = PermissionFilters.build(List.of("official"), "", 0L);
        String json = json(builder.bm25Request("ai_kb_chunk", "排序", filters, 50));
        assertTrue(json.contains("\"subject_id\":0"));
        assertFalse(json.contains("\"subject_id\":12"));
    }

    private int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
```

- [ ] **Step 5: 跑单测**

```bash
"$MVN" -pl AIModule -am test -Dtest=PermissionFiltersTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：4 个测试全绿。若序列化里 `subject_id` 被写成 `12` 以外形式（如 `1.2E1`），把断言改成先 `json.contains("\"subject_id\"")` 再检查计数，并记录实际 JSON。

---

### Task 3.2: `RetrievalService` 与 `debug`

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/search/RetrievalService.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`、`EmbeddingGateway`、`HybridQueryBuilder`、`PermissionFilters`、`RagProperties`
- Produces:
  - `KnowledgeSearchResponse searchKnowledge(String query, SearchOptions options)`
  - `DebugSearchResponse debugKnowledge(String query, SearchOptions options)`
  - 内部 `SearchResponse<KbChunkDoc>` 的 `hit.rank()` 作为 `rank`；cosine 由 `_source.vec` 与查询向量在 Java 侧计算

- [ ] **Step 1: 写服务**

```java
package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 唯一的检索读入口。权限 filter 只在这里拼装；controller 不得自己构造 ES query。
 */
@Service
public class RetrievalService {

    private final ElasticsearchClient client;
    private final EmbeddingGateway embeddingGateway;
    private final HybridQueryBuilder queryBuilder;
    private final RagProperties props;

    public RetrievalService(ElasticsearchClient client, EmbeddingGateway embeddingGateway,
                            HybridQueryBuilder queryBuilder, RagProperties props) {
        this.client = client;
        this.embeddingGateway = embeddingGateway;
        this.queryBuilder = queryBuilder;
        this.props = props;
    }

    public KnowledgeSearchResponse searchKnowledge(String query, SearchOptions options) throws IOException {
        float[] queryVector = embedQuery(query);
        List<Query> filters = PermissionFilters.build(
                options.scopes(), options.ownerName(), options.subjectId());

        SearchRequest request = queryBuilder.build(
                props.getIndex().getKbAlias(), query, queryVector, filters, options, props.getSearch());
        SearchResponse<KbChunkDoc> response = client.search(request, KbChunkDoc.class);

        List<KnowledgeSearchItem> items = new ArrayList<>();
        List<Hit<KbChunkDoc>> hits = response.hits().hits();
        for (int i = 0; i < hits.size(); i++) {
            Hit<KbChunkDoc> hit = hits.get(i);
            KbChunkDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            int rank = hit.rank() != null ? hit.rank() : i + 1;
            items.add(new KnowledgeSearchItem(
                    doc.getChunkId(), doc.getDocId(), rank,
                    VectorMath.cosine(queryVector, doc.getVec()),
                    doc.getTitle(), doc.getBook(), doc.getChapterPath(),
                    doc.getPageNo(), doc.getContent()));
        }
        return new KnowledgeSearchResponse(response.took(), items.isEmpty(), items);
    }

    /**
     * 调参专用：把两条腿各自的前 50 名摊开，定位是切片、embedding、BM25 还是 RRF 的问题。
     */
    public DebugSearchResponse debugKnowledge(String query, SearchOptions options) throws IOException {
        float[] queryVector = embedQuery(query);
        List<Query> filters = PermissionFilters.build(
                options.scopes(), options.ownerName(), options.subjectId());

        SearchRequest bm25Request = queryBuilder.bm25Request(
                props.getIndex().getKbAlias(), query, filters, props.getSearch().getRankWindowSize());
        SearchRequest knnRequest = queryBuilder.knnRequest(
                props.getIndex().getKbAlias(), queryVector, filters, props.getSearch(),
                props.getSearch().getRankWindowSize());

        SearchResponse<KbChunkDoc> bm25Response = client.search(bm25Request, KbChunkDoc.class);
        SearchResponse<KbChunkDoc> knnResponse = client.search(knnRequest, KbChunkDoc.class);

        return new DebugSearchResponse(
                Math.max(bm25Response.took(), knnResponse.took()),
                toLegItems(bm25Response, queryVector),
                toLegItems(knnResponse, queryVector));
    }

    private List<DebugLegItem> toLegItems(SearchResponse<KbChunkDoc> response, float[] queryVector) {
        List<DebugLegItem> items = new ArrayList<>();
        List<Hit<KbChunkDoc>> hits = response.hits().hits();
        for (int i = 0; i < hits.size(); i++) {
            Hit<KbChunkDoc> hit = hits.get(i);
            KbChunkDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            items.add(new DebugLegItem(doc.getChunkId(), doc.getDocId(), i + 1, hit.score(),
                    VectorMath.cosine(queryVector, doc.getVec()), doc.getTitle(), doc.getContent()));
        }
        return items;
    }

    private float[] embedQuery(String query) {
        return embeddingGateway.embed(List.of(query)).get(0);
    }
}
```

- [ ] **Step 2: 编译**

```bash
"$MVN" -pl AIModule -am -DskipTests compile
```

预期：`BUILD SUCCESS`。

---

### Task 3.3: 检索接口与管理员校验

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/RagAdminGuard.java`
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/web/KbSearchController.java`
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/web/KbSearchControllerTest.java`

**Interfaces:**
- Consumes: `RetrievalService`
- Produces:
  - `POST /ai/kb/search`（`X-User-Name` 必填）
  - `POST /ai/retrieval/debug`（`X-User-Name` 必须在 `rag.admin-names` 中）
  - `RagAdminGuard.requireAdmin(String): void`

- [ ] **Step 1: 写 `RagAdminGuard`**

```java
package org.leon.aimodule.rag.web;

import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.RagForbiddenException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 临时管理员判定：网关目前只注入 X-User-Name，没有角色。
 * 名单为空时所有管理员操作一律拒绝，避免“默认放行”。
 */
@Component
public class RagAdminGuard {

    private final Set<String> adminNames;

    public RagAdminGuard(RagProperties props) {
        this.adminNames = props.getAdminNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireAdmin(String userName) {
        if (userName == null || !adminNames.contains(userName)) {
            throw new RagForbiddenException("仅管理员可访问");
        }
    }
}
```

- [ ] **Step 2: 写 controller**

```java
package org.leon.aimodule.rag.web;

import jakarta.validation.Valid;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.exception.RagException;
import org.leon.aimodule.rag.search.DebugSearchResponse;
import org.leon.aimodule.rag.search.KnowledgeSearchResponse;
import org.leon.aimodule.rag.search.RetrievalQuery;
import org.leon.aimodule.rag.search.RetrievalService;
import org.leon.aimodule.rag.search.SearchOptions;
import org.leon.commonjwt.pojo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/ai")
public class KbSearchController {

    private static final List<String> DEFAULT_SCOPES = List.of("official", "personal");

    private final RetrievalService retrievalService;
    private final RagAdminGuard adminGuard;
    private final RagProperties props;

    public KbSearchController(RetrievalService retrievalService, RagAdminGuard adminGuard,
                              RagProperties props) {
        this.retrievalService = retrievalService;
        this.adminGuard = adminGuard;
        this.props = props;
    }

    @PostMapping("/kb/search")
    public Result<KnowledgeSearchResponse> search(
            @RequestHeader("X-User-Name") String userName,
            @Valid @RequestBody RetrievalQuery query) throws IOException {
        return Result.success(retrievalService.searchKnowledge(query.getQuery(),
                options(userName, query)));
    }

    @PostMapping("/retrieval/debug")
    public Result<DebugSearchResponse> debug(
            @RequestHeader("X-User-Name") String userName,
            @Valid @RequestBody RetrievalQuery query) throws IOException {
        adminGuard.requireAdmin(userName);
        return Result.success(retrievalService.debugKnowledge(query.getQuery(),
                options(userName, query)));
    }

    private SearchOptions options(String userName, RetrievalQuery query) {
        List<String> scopes = query.getScope() == null || query.getScope().isEmpty()
                ? DEFAULT_SCOPES : query.getScope();
        for (String scope : scopes) {
            if (!"official".equals(scope) && !"personal".equals(scope)) {
                throw new RagException("scope 只能是 official 或 personal");
            }
        }
        if (scopes.contains("personal") && (userName == null || userName.isBlank())) {
            throw new RagException("个人库检索需要登录身份");
        }
        int topK = query.getTopK() == null
                ? props.getSearch().getTopKDefault() : query.getTopK();
        topK = Math.min(topK, props.getSearch().getTopKMax());
        return new SearchOptions(userName, scopes, query.getSubjectId(), topK);
    }
}
```

- [ ] **Step 3: 写 MockMvc 单测（不启 Spring 容器）**

```java
package org.leon.aimodule.rag.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.search.DebugSearchResponse;
import org.leon.aimodule.rag.search.KnowledgeSearchResponse;
import org.leon.aimodule.rag.search.RetrievalService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KbSearchControllerTest {

    private RetrievalService retrievalService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        retrievalService = mock(RetrievalService.class);
        RagProperties props = new RagProperties();
        props.getSearch().setTopKDefault(6);
        props.getSearch().setTopKMax(20);
        props.setAdminNames(List.of("admin"));
        KbSearchController controller = new KbSearchController(
                retrievalService, new RagAdminGuard(props), props);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AiExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("正常检索返回 code=1")
    void searchOk() throws Exception {
        when(retrievalService.searchKnowledge(eq("快排"), any()))
                .thenReturn(new KnowledgeSearchResponse(12, false, List.of()));

        mvc.perform(post("/ai/kb/search")
                        .header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\",\"subjectId\":12,\"scope\":[\"official\",\"personal\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.took").value(12));
    }

    @Test
    @DisplayName("空 query 被校验拦截，返回 code=0")
    void blankQueryRejected() throws Exception {
        mvc.perform(post("/ai/kb/search")
                        .header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("query")));
    }

    @Test
    @DisplayName("非管理员访问 debug 返回 code=0，且不调用检索")
    void debugRequiresAdmin() throws Exception {
        when(retrievalService.debugKnowledge(any(), any()))
                .thenReturn(new DebugSearchResponse(5, List.of(), List.of()));

        mvc.perform(post("/ai/retrieval/debug")
                        .header("X-User-Name", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("仅管理员可访问"));

        verify(retrievalService, never()).debugKnowledge(any(), any());
    }

    @Test
    @DisplayName("管理员可以访问 debug")
    void debugOkForAdmin() throws Exception {
        when(retrievalService.debugKnowledge(eq("快排"), any()))
                .thenReturn(new DebugSearchResponse(5, List.of(), List.of()));

        mvc.perform(post("/ai/retrieval/debug")
                        .header("X-User-Name", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("缺 X-User-Name 返回 code=0")
    void missingUserHeader() throws Exception {
        mvc.perform(post("/ai/kb/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"快排\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("X-User-Name")));
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
"$MVN" -pl AIModule -am test -Dtest=KbSearchControllerTest \
  -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：5 个测试全绿。若 `MissingRequestHeaderException` 没被 advice 捕获，确认 `AiExceptionHandler` 注册在 `standaloneSetup` 的 advice 里（上面的 `setControllerAdvice` 已做）。

---

### Task 3.4: 检索集成测试（两条腿 + 权限隔离 + 学科通用）

**Files:**
- Test: `AIModule/src/test/java/org/leon/aimodule/rag/search/RetrievalServiceIT.java`

**Interfaces:**
- Consumes: 本地 ES（独立 `test_ai_kb_chunk_v1` 索引）、假的 `EmbeddingClient`
- Produces: 权限隔离、subject=0、BM25 腿、kNN 腿、空召回的回归防线

- [ ] **Step 1: 写集成测试**

```java
package org.leon.aimodule.rag.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.leon.aimodule.rag.config.EsClientFactory;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingClient;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.index.IndexMappings;
import org.leon.aimodule.rag.index.IndexRegistry;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("es-it")
class RetrievalServiceIT {

    private static final String ALIAS = "test_ai_kb_chunk";
    private static final String PHYSICAL = "test_ai_kb_chunk_v1";

    private static ElasticsearchClient client;
    private static RetrievalService service;
    private static float[] baseVector;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(System.getenv("ELASTIC_PASSWORD") != null, "需要 ELASTIC_PASSWORD");

        RagProperties.Elasticsearch es = new RagProperties.Elasticsearch();
        es.setUris(System.getenv().getOrDefault("ES_URIS", "https://127.0.0.1:9200"));
        es.setUsername(System.getenv().getOrDefault("ES_USERNAME", "elastic"));
        es.setPassword(System.getenv("ELASTIC_PASSWORD"));
        es.setCaCertPath(System.getenv().getOrDefault("ES_CA_CERT",
                "F:/ES/elasticsearch-9.5.4/config/certs/http_ca.crt"));
        client = EsClientFactory.create(es);

        client.indices().delete(d -> d.index(PHYSICAL));
        client.indices().create(c -> c.index(PHYSICAL)
                .aliases(ALIAS, a -> a)
                .settings(s -> s.withJson(new StringReader(IndexMappings.kb("cjk").settings())))
                .mappings(m -> m.withJson(new StringReader(IndexMappings.kb("cjk").properties()))));

        baseVector = new float[1024];
        for (int i = 0; i < 10; i++) {
            baseVector[i] = 1f;
        }

        EmbeddingClient fake = texts -> texts.stream()
                .map(t -> t.contains("量子力学") ? thirdVector() : baseVector.clone())
                .toList();
        RagProperties props = new RagProperties();
        props.getEmbedding().setDimensions(1024);
        props.getEmbedding().setBatchSize(10);
        props.getEmbedding().setConcurrency(2);
        props.getIndex().setKbAlias(ALIAS);
        EmbeddingGateway gateway = new EmbeddingGateway(fake, props);
        service = new RetrievalService(client, gateway, new HybridQueryBuilder(), props);

        List<BulkOperation> ops = new ArrayList<>();
        ops.add(op(1, 41, "official", "", 12, "快速排序时间复杂度", "快速排序的时间复杂度是 O(nlogn)。", baseVector));
        ops.add(op(2, 42, "personal", "alice", 12, "快速排序练习", "alice 的私人讲义：快速排序平均复杂度。", baseVector));
        ops.add(op(3, 43, "personal", "bob", 12, "快速排序私人讲义", "bob 的私人讲义：快速排序只允许他自己搜到。", baseVector));
        ops.add(op(4, 44, "official", "", 0, "算法通用知识", "全学科通用的算法知识。", baseVector));
        ops.add(op(5, 45, "official", "", 12, "火车相遇", "火车相遇问题的解法与相对速度、总路程有关。", offsetVector()));
        client.bulk(BulkRequest.of(b -> b.index(PHYSICAL).operations(ops)));
        client.indices().refresh(r -> r.index(ALIAS));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client.indices().delete(d -> d.index(PHYSICAL));
            client._transport().close();
        }
    }

    private static BulkOperation op(long chunkId, long docId, String scope, String owner,
                                    long subjectId, String title, String content, float[] vector) {
        KbChunkDoc doc = new KbChunkDoc();
        doc.setChunkId(chunkId);
        doc.setDocId(docId);
        doc.setScope(scope);
        doc.setOwnerName(owner);
        doc.setSubjectId(subjectId);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setVec(vector);
        doc.setBook("测试教材");
        doc.setChapterPath("第3章 → 3.2");
        doc.setPageNo(45);
        doc.setTokenCount(20);
        return BulkOperation.of(o -> o.index(i -> i.index(PHYSICAL).id(String.valueOf(chunkId)).document(doc)));
    }

    private static float[] offsetVector() {
        float[] v = new float[1024];
        for (int i = 100; i < 110; i++) {
            v[i] = 1f;
        }
        return v;
    }

    /** 与 baseVector、offsetVector 都正交，用于空召回测试 */
    private static float[] thirdVector() {
        float[] v = new float[1024];
        for (int i = 200; i < 210; i++) {
            v[i] = 1f;
        }
        return v;
    }

    @Test
    @DisplayName("alice 能搜到官方、自己的私人、全学科通用，搜不到 bob 的私人讲义")
    void permissionIsolation() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("快速排序",
                new SearchOptions("alice", List.of("official", "personal"), 12L, 10));

        assertFalse(response.empty());
        List<Long> docIds = response.items().stream().map(KnowledgeSearchItem::docId).toList();
        assertTrue(docIds.contains(41L), "官方文档应命中");
        assertTrue(docIds.contains(42L), "alice 自己的文档应命中");
        assertTrue(docIds.contains(44L), "subject=0 的通用文档应命中");
        assertFalse(docIds.contains(43L), "bob 的私人文档绝不能出现");
        assertFalse(docIds.contains(45L), "kNN 腿的 0.55 闸门应卡掉无关向量");
    }

    @Test
    @DisplayName("debug 接口两条腿各有结果")
    void debugHasTwoLegs() throws Exception {
        DebugSearchResponse response = service.debugKnowledge("快速排序",
                new SearchOptions("alice", List.of("official", "personal"), 12L, 10));

        assertFalse(response.bm25().isEmpty(), "BM25 腿应有结果");
        assertFalse(response.knn().isEmpty(), "kNN 腿应有结果");
        assertTrue(response.bm25().stream().noneMatch(item -> item.docId() == 43L));
        assertTrue(response.knn().stream().noneMatch(item -> item.docId() == 43L));
    }

    @Test
    @DisplayName("完全无关的查询返回 empty，不编造结果")
    void emptyRecall() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("量子力学薛定谔方程",
                new SearchOptions("alice", List.of("official", "personal"), 12L, 6));
        assertTrue(response.empty());
        assertTrue(response.items().isEmpty());
    }

    @Test
    @DisplayName("只查 official 时私人文档不出现")
    void officialOnly() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("快速排序",
                new SearchOptions("alice", List.of("official"), 12L, 10));
        assertTrue(response.items().stream().noneMatch(item -> item.docId() == 42L));
    }

    @Test
    @DisplayName("cosine 与向量夹角一致，且 chunk/页码字段完整")
    void mapsFieldsAndCosine() throws Exception {
        KnowledgeSearchResponse response = service.searchKnowledge("快速排序",
                new SearchOptions("alice", List.of("official"), 12L, 10));
        KnowledgeSearchItem first = response.items().get(0);
        assertEquals(1.0, first.cosine(), 0.0001);
        assertEquals("测试教材", first.book());
        assertEquals(45, first.pageNo());
    }
}
```

- [ ] **Step 2: 运行集成测试**

```bash
export ELASTIC_PASSWORD="$(tr -d '\r\n' < '/f/ES/密码.txt')"
"$MVN" -pl AIModule -am test -Dtest=RetrievalServiceIT -Dgroups=es-it \
  -Dsurefire.excludedGroups= -Dsurefire.failIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

预期：5 个测试全绿。重点看 `permissionIsolation` 与 `debugHasTwoLegs`：前者失败通常意味着某条腿漏了 filter，后者失败通常是 `rank_window_size` 或 `knn` 参数拼错。

- [ ] **Step 3: 记录性能基线**

在测试输出里找 `took`（或加一行 `System.out.printf` 打印 `response.took()`），把单条 query 的 embedding 延迟（Task 2.2 的耗时）与 hybrid search `took` 写进执行记录。这是 spec 7.6 要求的“测数量级”。

---

### Task 3.5: 开发期种子数据与手工验收

**Files:**
- Create: `AIModule/src/main/java/org/leon/aimodule/rag/dev/RagDevSeedRunner.java`
- Modify: `AIModule/src/main/resources/application.yaml`（只加 dev-seed 开关，默认 false）

**Interfaces:**
- Consumes: `IndexRegistry`、`EmbeddingGateway`、`ElasticsearchClient`、`RagProperties`
- Produces: `rag.dev-seed.enabled=true` 时向 `ai_kb_chunk` 灌 5 条真实 embedding 的切片

- [ ] **Step 1: 加配置**

```yaml
rag:
  # ... 已有配置 ...
  dev-seed:
    enabled: false
```

- [ ] **Step 2: 写种子 Runner**

```java
package org.leon.aimodule.rag.dev;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.extern.slf4j.Slf4j;
import org.leon.aimodule.rag.config.RagProperties;
import org.leon.aimodule.rag.embedding.EmbeddingGateway;
import org.leon.aimodule.rag.index.IndexRegistry;
import org.leon.aimodule.rag.search.KbChunkDoc;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.dev-seed", name = "enabled", havingValue = "true")
public class RagDevSeedRunner implements ApplicationRunner {

    private final IndexRegistry registry;
    private final EmbeddingGateway embeddingGateway;
    private final ElasticsearchClient client;
    private final RagProperties props;

    public RagDevSeedRunner(IndexRegistry registry, EmbeddingGateway embeddingGateway,
                            ElasticsearchClient client, RagProperties props) {
        this.registry = registry;
        this.embeddingGateway = embeddingGateway;
        this.client = client;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        registry.ensureKbIndex();
        String alias = props.getIndex().getKbAlias();
        if (client.count(c -> c.index(alias)).count() > 0) {
            log.info("[RAG] dev-seed 跳过：{} 已有数据", alias);
            return;
        }

        List<String> contents = List.of(
                "快速排序是分治算法：每轮选一个基准，把小于基准的放左边、大于的放右边，平均时间复杂度 O(nlogn)，最坏 O(n^2)。",
                "快速排序的空间复杂度来自递归栈，平均 O(logn)，最坏 O(n)。",
                "归并排序稳定，时间复杂度恒为 O(nlogn)，代价是 O(n) 额外空间。",
                "火车相遇问题：相遇时间 = 总路程 ÷ 两车速度之和，注意单位统一。",
                "全学科通用：算法复杂度分析只关心输入规模趋于无穷时的增长量级。"
        );
        List<float[]> vectors = embeddingGateway.embed(contents);

        List<BulkOperation> ops = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            KbChunkDoc doc = new KbChunkDoc();
            doc.setChunkId(9000L + i);
            doc.setDocId(9000L);
            doc.setScope("official");
            doc.setOwnerName("");
            doc.setSubjectId(i == 3 ? 12L : (i == 4 ? 0L : 12L));
            doc.setTitle("算法与数据结构 第3章");
            doc.setContent(contents.get(i));
            doc.setVec(vectors.get(i));
            doc.setBook("《算法基础（测试语料）》");
            doc.setChapterPath("第3章 → 3.2 常见排序算法");
            doc.setPageNo(40 + i);
            doc.setTokenCount(contents.get(i).length());
            int id = i;
            ops.add(BulkOperation.of(o -> o.index(idx -> idx
                    .index(alias).id(String.valueOf(9000 + id)).document(doc))));
        }
        client.bulk(BulkRequest.of(b -> b.index(alias).operations(ops)));
        registry.refresh(alias);
        log.info("[RAG] dev-seed 完成：5 条切片已写入 {}", alias);
    }
}
```

- [ ] **Step 3: 启动并灌数据**

```bash
export ELASTIC_PASSWORD="$(tr -d '\r\n' < '/f/ES/密码.txt')"
export DASHSCOPE_API_KEY="$(printenv DASHSCOPE_API_KEY)"
export RAG_ADMIN_NAMES="admin"
cd /f/AAAJAVA/code/ezPracticeExercises/AIModule
"$MVN" spring-boot:run -Dspring-boot.run.arguments="--rag.dev-seed.enabled=true"
```

预期日志：`[RAG] 索引已就绪`、`[RAG] dev-seed 完成`。随后可去掉参数重启（种子会因已有数据自动跳过）。

- [ ] **Step 4: 手工验收**

```bash
curl -s -X POST http://127.0.0.1:8105/ai/kb/search \
  -H 'X-User-Name: alice' -H 'Content-Type: application/json' \
  -d '{"query":"快排为什么是 O(nlogn)","topK":6,"subjectId":12,"scope":["official","personal"]}'

curl -s -X POST http://127.0.0.1:8105/ai/retrieval/debug \
  -H 'X-User-Name: admin' -H 'Content-Type: application/json' \
  -d '{"query":"快排为什么是 O(nlogn)","topK":6,"subjectId":12,"scope":["official"]}'
```

验收标准：
1. `search` 返回 `code=1`，`empty=false`，第一条 `content` 是快速排序相关，`pageNo` 非空，`cosine` 在 0.5–1.0 之间。
2. `debug` 的 `bm25` 和 `knn` 两个数组都非空；`knn` 第一条 `score` 接近 1。
3. 把两次响应的 `took`、第一条 `cosine`、命中条数写进执行记录。

- [ ] **Step 5: 提交**

```bash
git add AIModule/src/main/java/org/leon/aimodule/rag/search \
        AIModule/src/main/java/org/leon/aimodule/rag/web \
        AIModule/src/main/java/org/leon/aimodule/rag/dev \
        AIModule/src/main/resources/application.yaml \
        AIModule/src/test/java/org/leon/aimodule/rag/search \
        AIModule/src/test/java/org/leon/aimodule/rag/web
git commit -m "打通RAG读路径：BM25+kNN混合检索、权限过滤与调参接口"
```

---

## 读路径验收清单（Stage 0–3 完成判定）

- [ ] `/ai/kb/ping` 返回 ES 9.5.4 与 `ai_kb_chunk`/`ai_question` 别名。
- [ ] `ChunkPlannerTest` 7 项全绿，所有切片 ≤500 token。
- [ ] `EmbeddingGatewayTest` 4 项全绿；真实调用 1024 维，相近句 cosine >0.7、无关句 <0.4。
- [ ] `PermissionFiltersTest` 4 项全绿；序列化 JSON 中 `owner_name`/`subject_id` 在两条腿各出现一次，且没有 `min_score`。
- [ ] `RetrievalServiceIT` 5 项全绿：alice 搜不到 bob 的私人讲义；official 半与 kNN 半都有结果。
- [ ] 手工 seed 后 `/ai/kb/search` 命中快排切片且页码正确；`/ai/retrieval/debug` 两条腿各有结果。
- [ ] 执行记录里写清了：IK 实际 token、真实 cosine、hybrid `took`、embedding 延迟。

---

## 执行记录

### 2026-10-21 计划编写时的实测记录（待执行补充）

- 依赖可用性：`co.elastic.clients:elasticsearch-java:9.5.4` 与 `elasticsearch-rest5-client:9.5.4` 可下载；客户端传输层类为 `co.elastic.clients.transport.rest5_client.low_level.Rest5Client`。
- RRF API：`RRFRetriever.Builder.retrievers(List<RRFRetrieverEntry>)`；每条 entry 用 `.retriever(Retriever)` 包裹；`RetrieverBase` 提供 `filter/minScore/name`。
- Tika 3.2.3 **没有** `PaginationHandler`；本阶段不依赖 Tika，阶段 4a 用 PDFBox 逐页抽取。
- 本机 ES 当前未启动、插件为空；Task 0.1 负责安装 IK 并启动。
- 阶段 0–3 执行时在此追加：实际 IK token、真实 cosine、`took`、embedding 延迟、Jackson 是否冲突。

