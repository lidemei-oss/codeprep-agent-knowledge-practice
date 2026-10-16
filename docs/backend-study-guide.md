# CodePrep 后端学习手册：从能运行到能讲清楚

> 以当前仓库代码为准。本文是学习路径与动手练习；逐接口、逐表的完整索引见 [项目全链路详解](project-deep-dive.md)，运行参数见 [API 与启动](api.md)。不要背一句“用了 MySQL、Redis、RabbitMQ、ES”，而要能说清每个组件存什么、谁调用它、失败时会怎样。

## 0. 先建立项目的整体心智模型

这个项目只有两个业务目标：**私有知识问答**和**按资料组刷题**。Agent 不是第三套独立业务，而是接收自然语言目标、选择已定义工具、调用上述两套业务并记录轨迹的入口。

```text
浏览器
  │ HTTP + JWT / WebSocket
  ▼
Controller（接协议、校验参数、取得当前用户）
  ▼
Service（权限、状态机、编排、事务）
  ├─ MyBatis-Plus Mapper → MySQL：业务状态
  ├─ RabbitMQ → 文档异步处理
  ├─ Elasticsearch → 关键词/向量检索
  ├─ Redis → 短期会话上下文/限流
  └─ Qwen、DeepSeek、Tavily → 向量、生成、公开搜索
```

先记住三条主线：

1. **上传入库**：登录 → 上传 → 文件落盘和 MySQL 原子写入 PROCESSING 文档/Outbox 待投递任务 → RabbitMQ → 解析/切块/向量化 → MySQL 文本与 ES 索引 → READY。
2. **问答**：读权限 → 问题向量化 → ES BM25+KNN → MySQL 降级/改写/可选 Tavily → Prompt → DeepSeek → SSE/WebSocket/JSON → Redis 历史。
3. **刷题**：选组 → 组内 READY 题源 → 生成题目 → MySQL 会话快照 → 短事务占用 requestId → 事务外评测/生成下题 → 短事务保存结果 → Agent 错题复盘。

最重要的关系是：**MySQL 是业务状态的主存储；ES 是可重建的检索索引；Redis 是带过期时间的临时状态；RabbitMQ 是任务通道，不是数据库。**

## 1. 推荐学习顺序与验收标准

| 阶段 | 先读的入口 | 学完必须能做到 |
| --- | --- | --- |
| ① Spring MVC 与请求链 | `CodePrepApplication`、`KnowledgeController`、`GlobalExceptionHandler` | 从 URL 追踪到 Service，说明参数校验和异常响应发生在哪层。 |
| ② MySQL/MyBatis-Plus | `schema.sql`、六个 Entity/Mapper、`KnowledgeStore`、`DocumentSubmissionService`、`PracticeAgentService` | 画出六张表，解释 `BaseMapper`、自定义 SQL、事务与行锁。 |
| ③ 安全 | `SecurityConfig`、`JwtAuthenticationFilter`、`AccountService`、`OrgTagGuard` | 区分认证、角色授权、组织标签和数据归属校验。 |
| ④ 上传/RabbitMQ | `DocumentUploadService`、`DocumentSubmissionService`、`DocumentJobPublisher/Consumer`、`DocumentJobReconciler` | 解释为什么接口返回 PROCESSING，以及 Outbox、confirm、ACK、DLQ 各保障什么。 |
| ⑤ RAG | `StructuredDocumentChunker`、`KnowledgeSearchService`、ES/Qwen/Prompt/DeepSeek 适配器 | 手画 BM25/KNN/RRF、降级、Prompt 和引用来源。 |
| ⑥ Agent/刷题 | `AgentPlanner`、`AgentOrchestrator`、`PracticeAgentService`、`MistakeReviewSkill` | 说明工具路由、有限步轨迹、组内题源、评分与错题复练。 |
| ⑦ Redis/流式与排障 | `RedisContextStore`、`AgentWebSocketHandler`、前端 `index.html` | 对比 JSON/SSE/WebSocket，排查会话污染和流式错乱。 |

每个阶段用同一个方法学习：**读入口 → 找调用方/被调用方 → 看数据在何处变化 → 在本地做一次验证 → 用自己的话复述。** 不要只记类名。

## 2. 第一阶段：Spring Boot 请求是怎样走到代码里的？

打开 [`CodePrepApplication`](../src/main/java/com/lm/codeprep/CodePrepApplication.java)：`@SpringBootApplication` 启动组件扫描与自动配置；`@MapperScan` 注册 MyBatis Mapper 接口代理；`@EnableAsync` 支持异步方法，`@EnableScheduling` 驱动 Outbox 轮询与对账。`pom.xml` 以 Java 17 为编译目标，包含 Spring MVC、Security、WebSocket、WebClient、AMQP、Redis、MyBatis-Plus 等依赖。项目默认是 **Spring MVC/Tomcat 服务**，不是因为引入 `spring-boot-starter-webflux` 就变成全响应式服务器。

以 `POST /api/knowledge/chat` 为例：

```text
HTTP 请求
→ Servlet Filter Chain（JWT Filter 尝试认证）
→ Spring Security 决定能否访问
→ DispatcherServlet 找到 KnowledgeController.chat
→ Jackson 反序列化 ChatRequest，@Valid 检查 @NotBlank
→ OrgTagGuard 检查组织标签读权限
→ KnowledgeAgentService.ask 执行业务
→ 返回 RagResponse，由 Jackson 转 JSON
```

[`KnowledgeController`](../src/main/java/com/lm/codeprep/knowledge/KnowledgeController.java) 接协议和当前 `Authentication`，不直接访问 Mapper。`KnowledgeDocumentService` 负责可见文档/分组和移组业务。`GlobalExceptionHandler` 把部分参数/状态异常转 400、安全异常转 403。一个重要细节：`@Valid` 检查的是输入形状，不等于业务合法、更不等于权限通过。

你要理解的 Java/Spring 基础：对象与接口、构造器注入、单例 Bean、注解/反射、MVC 参数绑定、JSON 序列化、Filter 与 Controller 的顺序。问自己：“如果我在 Controller 里直接写 SQL、调用 ES、拼 Prompt，后果是什么？”答案是协议与业务耦合、测试困难、WebSocket 和 Agent 入口无法复用。

**动手任务**：给 `KnowledgeController.chat`、`KnowledgeAgentService.ask`、`KnowledgeSearchService.retrieve` 下断点，发起一次提问，记录每一步输入/输出。再故意把 `category` 传空，观察是否在业务调用前被校验挡住。

## 3. 第二阶段：MySQL 与 MyBatis-Plus 是这个项目的骨架

### 3.1 先画表，再读代码

[`schema.sql`](../src/main/resources/schema.sql) 定义六张表：

| 表 | 主键 | 存什么 | 为什么要持久化 |
| --- | --- | --- | --- |
| `cp_account` | `username` | BCrypt 密码哈希、角色 JSON、组织标签 JSON | 登录和授权跨重启保留。 |
| `cp_document` | 文档 UUID | 标题、类别、组织标签、分组、PROCESSING/READY/FAILED、切片数 | 显示上传状态、按分组筛选。 |
| `cp_document_outbox` | 文档 UUID | 待投递/SENDING/SENT 状态、重试次数、领取租约 | 文档与投递意图原子提交，MQ 暂时不可用时仍可恢复发送。 |
| `cp_chunk` | `documentId-序号` | 切片文本、标题、类别、标签、序号 | ES 不可用时关键词降级；刷题取题源。向量不在这张表。 |
| `cp_practice_session` | 会话 UUID | 用户名、完整 `state_json` | 题目、答案、掌握度和 requestId 幂等结果跨重启保留。 |
| `cp_agent_run` | run UUID | 用户名、完整 `result_json` | 查看本人 Agent 结果与步骤轨迹。 |

`cp_document` 的 `(org_tag, category, status, group_name)` 是组合索引；`cp_chunk` 的 `(document_id, chunk_no)` 支持按文档读切片。知道索引的**最左前缀**、回表、`EXPLAIN`、索引选择性是什么，但不要凭建了索引就声称有性能提升数据。`cp_chunk.content` 是 LONGTEXT，没有 MySQL FULLTEXT 索引；回退检索是 Java 在 READY 切片上做简易包含评分。

### 3.2 框架具体怎样配置

[`application.yml`](../src/main/resources/application.yml) 中 `spring.datasource` 指向 MySQL，默认宿主机端口 3307；`spring.sql.init.mode=always` 启动时执行 `CREATE TABLE IF NOT EXISTS`；`mybatis-plus.configuration.map-underscore-to-camel-case=true` 让 `document_id` 映射 Java 的 `documentId`。[`CodePrepApplication`](../src/main/java/com/lm/codeprep/CodePrepApplication.java) 用 `@MapperScan` 注册 `persistence.mapper` 下的接口。

每个 Entity 使用 `@TableName("cp_...")` 与 `@TableId(..., type=IdType.INPUT)`；ID 由代码提供，不靠 MySQL 自增。Mapper 接口继承 `BaseMapper<Entity>`，因此能调用 `selectById`、`insert`、`updateById`、`selectList`。当通用 CRUD 不够时仍手写 SQL：[`ChunkMapper.selectReady`](../src/main/java/com/lm/codeprep/persistence/mapper/ChunkMapper.java) JOIN 文档表，只返回 READY 切片；[`PracticeSessionMapper.selectForUpdate`](../src/main/java/com/lm/codeprep/persistence/mapper/PracticeSessionMapper.java) 执行行锁查询。

把“调用 Mapper”讲成具体例子：

```text
GET /api/knowledge/documents
→ KnowledgeController.documents
→ KnowledgeDocumentService.visibleDocuments
→ KnowledgeStore.documents
→ DocumentMapper.selectList(null)
→ cp_document → 按当前用户标签过滤后返回 DocumentView
```

```text
POST 文档上传后
→ DocumentSubmissionService.accept（一个 MySQL 事务）
→ DocumentMapper.insert(cp_document, status=PROCESSING)
→ DocumentOutboxMapper.insert(cp_document_outbox, status=PENDING)
→ 定时发布器确认 RabbitMQ 收到后把 Outbox 标为 SENT
→ 处理成功：KnowledgeStore.saveChunk → ChunkMapper.insert(cp_chunk)
→ KnowledgeStore.markReady → DocumentMapper.updateById(cp_document)
```

### 3.3 重点：事务、行锁和幂等

[`DocumentSubmissionService.accept`](../src/main/java/com/lm/codeprep/knowledge/DocumentSubmissionService.java) 用 `@Transactional` 同时保存 PROCESSING 文档和 Outbox 待投递任务；任一步 SQL 失败，两个写入都回滚。[`PracticeAgentService.answer`](../src/main/java/com/lm/codeprep/practice/PracticeAgentService.java) 则使用 `TransactionTemplate` 两次开启短事务：第一段 `SELECT ... FOR UPDATE` 锁住会话行，校验 `username/questionId/requestId` 并写入 `pending` 占用；提交释放行锁和连接后，在事务外评测答案及生成下一题；第二段重新锁行、核对 token、保存成绩及 requestId 幂等结果。相同请求评测中返回 409，完成后重放首次结果；同 requestId 换答案则拒绝。这个占用和结果都在 MySQL，不依赖 Redis。

你必须会解释：

- 事务的原子性只覆盖同数据库 SQL；DeepSeek、ES、RabbitMQ、磁盘不会跟着自动回滚。
- `FOR UPDATE` 是数据库协调并发的关键；Java `synchronized` 只能管同 JVM/同对象，无法保障多实例。
- 模型调用的 `Mono` 有 30 秒超时，等待期间不持有会话行锁或事务连接，但仍占用调用线程。异常会尝试清除 `pending`；进程崩溃后的残留占用不自动过期，避免误重试造成并发评测，需要人工确认后恢复。
- `@Transactional` 依赖 Spring 代理；本类内部调用同类带事务方法时要警惕代理失效。刷题此处选择 `TransactionTemplate` 显式划出两个事务边界。
- JSON 快照简化样例实现，缺点是统计单题、局部更新、模式演进不方便；扩展时应拆分会话/题目/作答/幂等表。

**动手任务**：登录后启动一次刷题，查看 `cp_practice_session.state_json`；提交答案后再次查看，找到 requestId、得分与下一题。重发相同请求验证结果重放；改用同一 requestId 配不同答案验证冲突。能在纸上写出这段事务的时间线才算掌握。

## 4. 第三阶段：Spring Security、JWT、角色和组织标签

登录调用链是 [`AuthController`](../src/main/java/com/lm/codeprep/auth/AuthController.java) → `AccountService.authenticate` → `AccountMapper.selectById` → `PasswordEncoder.matches` → `JwtService.issue`。密码库里保存 BCrypt 哈希，不保存明文。JWT 的 subject 是用户名，默认 12 小时过期。客户端后续在 `Authorization: Bearer <token>` 发送。

[`JwtAuthenticationFilter`](../src/main/java/com/lm/codeprep/auth/JwtAuthenticationFilter.java) 位于用户名密码过滤器之前：验签取用户名，再从 MySQL 加载账号与当前角色/组织标签，放入 `SecurityContext`。因此代码不是单纯信任 JWT 内旧的角色声明。[`SecurityConfig`](../src/main/java/com/lm/codeprep/config/SecurityConfig.java) 规定登录公开、其余业务需认证，HTTP Session 使用 STATELESS。[`OrgTagGuard`](../src/main/java/com/lm/codeprep/auth/OrgTagGuard.java) 再判断某标签下能读还是能写；刷题会话和 Agent 运行记录还检查是否属于本人。

必须区分四个概念：**认证**＝谁在请求；**角色**＝OWNER/READER 等；**组织标签**＝可访问哪个知识域；**数据归属**＝这条刷题会话/运行记录属于谁。比如 `reader` 拥有 `java` 标签，不等于能修改 `java` 资料，也不等于能读另一个用户的刷题会话。

WebSocket 不走普通业务 Controller，所以[`JwtHandshakeInterceptor`](../src/main/java/com/lm/codeprep/config/JwtHandshakeInterceptor.java) 在握手时校验查询参数中的 JWT；[`AgentWebSocketHandler`](../src/main/java/com/lm/codeprep/knowledge/AgentWebSocketHandler.java) 在处理消息时仍调用组织标签 Guard。这个实现适合本地演示；正式环境不宜长期把 JWT 暴露在 URL 查询参数中。

**动手任务**：用 `alice` 与 `reader` 分别登录，尝试上传文档、读文档、启动刷题、访问另一个用户的 runId。解释每次是 401、403、400 还是成功，在哪层被拦截。

## 5. 第四阶段：上传、RabbitMQ 与多资源一致性

普通上传：`KnowledgeController.upload` → `OrgTagGuard.checkWrite` → [`DocumentUploadService.upload`](../src/main/java/com/lm/codeprep/knowledge/DocumentUploadService.java) 保存本地原文件 → [`DocumentSubmissionService.accept`](../src/main/java/com/lm/codeprep/knowledge/DocumentSubmissionService.java) 在同一事务写 `cp_document(PROCESSING)` 与 `cp_document_outbox(PENDING)` → 接口返回 PROCESSING。后台 [`DocumentJobPublisher.dispatchDue`](../src/main/java/com/lm/codeprep/knowledge/DocumentJobPublisher.java) 领取到期任务，发布仅含 documentId 的消息，等待 broker confirm 并检查 mandatory return；成功记 SENT，失败保留任务并指数退避重试。**接口返回成功不是已经入队或解析完成**，所以前端轮询文档状态。

分片上传：`begin` 生成 uploadId → 每片写 `uploadId.part.index` → `complete` 按序合并并校验总长度 → 进入同一文档/Outbox 事务链路。当前 `UploadState` 在进程内 `ConcurrentHashMap`，重启后原 uploadId 无法继续；不能把它描述为跨重启可靠续传。

RabbitMQ 由 [`DocumentJobRabbitConfiguration`](../src/main/java/com/lm/codeprep/knowledge/DocumentJobRabbitConfiguration.java) 声明持久化 direct exchange、主队列、DLX/DLQ。发布失败与消费失败是两种不同重试：发布阶段依靠 Outbox 退避重试，SENDING 租约过期可重领；[`DocumentJobReconciler`](../src/main/java/com/lm/codeprep/knowledge/DocumentJobReconciler.java) 给历史无 Outbox 的超时 PROCESSING 文档补任务。消费者[`DocumentJobConsumer`](../src/main/java/com/lm/codeprep/knowledge/DocumentJobConsumer.java) 使用手动 ACK：已 READY 直接 ACK；否则调用处理服务；处理失败同一次投递内最多重试三次，最终标 FAILED，NACK 不重新入队，消息进入死信。默认 prefetch=2，并发消费者 1～2。要能解释 publisher confirm、return、consumer ACK、死信分别发生在不同阶段，不能混为“保证只处理一次”。

真正的处理见[`DocumentIngestionService`](../src/main/java/com/lm/codeprep/knowledge/DocumentIngestionService.java)：Markdown 保留原文，其他文件经 Tika 提取；[`StructuredDocumentChunker`](../src/main/java/com/lm/codeprep/knowledge/StructuredDocumentChunker.java) 按标题、段落、代码块、估算 Token 切分；Qwen 对片段生成 2048 维向量；`cp_chunk` 保存文本，ES 保存文本+向量，先为 PROCESSING；全部成功后 ES 与 MySQL 标 READY，再 ACK。失败会尝试清理已写片段，但 MySQL/磁盘/ES/RabbitMQ 没有分布式事务，仍需死信巡检、超时 PROCESSING 扫描和对账。

**动手任务**：上传一份小 Markdown，连续观察 `cp_document.status`、`chunk_count` 与 `cp_chunk` 行数；再在 RabbitMQ 管理页看主队列/死信队列。口述为什么消息体只放 documentId、为什么原文件必须能被消费者读取、为什么多实例时本地磁盘需要改造。

## 6. 第五阶段：RAG 检索到底怎样工作？

RAG 是“先找证据，再让模型依据证据生成”，不是把所有文档直接塞进模型。**入库侧**决定片段质量；**查询侧**决定能否找到相关片段；**Prompt/生成侧**决定是否遵守证据。

查询侧调用链：[`KnowledgeAgentService.retrieve`](../src/main/java/com/lm/codeprep/knowledge/KnowledgeAgentService.java) → [`KnowledgeSearchService.retrieve`](../src/main/java/com/lm/codeprep/knowledge/KnowledgeSearchService.java) → Qwen 对问题向量化 → [`ElasticsearchKnowledgeGateway.hybridSearch`](../src/main/java/com/lm/codeprep/infra/ElasticsearchKnowledgeGateway.java) 分别做 IK/BM25 与 KNN，并按 READY、orgTag、category 过滤 → 以 chunkId 合并、RRF+有限原始分数排序 → 返回 Citation。刷题选组时先从 MySQL 查该组 READY 文档 ID，作为 ES 查询过滤条件；普通问答不跟随刷题组。

为什么同时要两路检索？“`SELECT FOR UPDATE`”这类精确术语可能被 BM25 找得更稳，而“如何避免并发重复评分”之类不同措辞可能受益于向量召回。RRF 不要求两路分数同量纲，但当前实现还叠加了部分 ES 原始分数；它不是 Cross-Encoder 精排。默认候选数 40、最终最多 8 条，是待评测的参数，不是准确率证明。

ES 为空或失败时[`KnowledgeStore.lexicalSearch`](../src/main/java/com/lm/codeprep/knowledge/KnowledgeStore.java)在 MySQL READY 切片文本上做简单词项包含评分；它不是数据库 FULLTEXT，更不能代替 KNN。ES 报错且 SQL 回退也空时策略 `PRIVATE_INDEX_UNAVAILABLE`，不能据此触发 Tavily。真正无依据才尝试改写问题重检，再按本次 `allowWebSearch` 调 Tavily。公开搜索默认允许，敏感问题应关闭。Tavily 找到的是公开证据，不能冒充私有库来源。

[`RagPromptBuilder`](../src/main/java/com/lm/codeprep/knowledge/RagPromptBuilder.java) 把问题、最近六条对话、编号片段组成 Prompt，受 12000 字符上下文预算约束；[`DeepSeekClient`](../src/main/java/com/lm/codeprep/infra/DeepSeekClient.java) 生成答案。直接问答无引用事实核验；统一 Agent 主要验证 `[1]` 等编号是否在召回列表内，并不证明那段证据支持那句话。所以准确率要分层测试：切块完整率、Recall@K、排序、引用正确率、无依据拒答率、最终答案事实一致性。

**动手任务**：问一个文档里有明确答案的问题，记录 `RetrievalResult.strategy`、`vectorHits/keywordHits`、前 3 个 Citation 和实际 Prompt。再问一个库里没有的问题，分别打开/关闭 Tavily，解释 `NEEDS_SOURCE`、`WEB_SEARCH`、`PRIVATE_INDEX_UNAVAILABLE` 的语义。不要把“模型答得像真的”当成准确率测试。

## 7. 第六阶段：刷题与 Agent 如何复用前面的能力？

[`PracticeController`](../src/main/java/com/lm/codeprep/practice/PracticeController.java) 有创建会话、提交答案、读取会话三个入口。`start()` 只取所选组的 READY 文档题源，用 DeepSeek 尝试生成单行开放题，不合规则回退固定题干；第一题和状态保存到 MySQL。`answer()` 先占用、在事务外评测、最后提交结果；模型要求返回 JSON 评分、评语和缺失概念，失败时改用关键词覆盖与回答深度的规则评分。**规则评分仅是可用性降级，不能宣称语义评测准确。** 掌握度、答题记录、下一题和幂等结果在第二段短事务中保存。

[`AgentPlanner`](../src/main/java/com/lm/codeprep/agent/AgentPlanner.java) 根据已有答案/题目 ID、刷题会话 ID、模型提议或关键词选择四种动作：`SEARCH_KNOWLEDGE`、`START_PRACTICE`、`GRADE_ANSWER`、`REVIEW_MISTAKES`。[`AgentOrchestrator`](../src/main/java/com/lm/codeprep/agent/AgentOrchestrator.java) 按显式白名单调用检索或刷题 Service，记录 `PLAN → ACT → OBSERVE` 式步骤，最多 8 步，结果写 `cp_agent_run`。它是**受控业务 Agent**，不是可以自主访问任意工具的通用 Agent，也未使用 Spring AI 框架。

[`MistakeReviewSkill`](../src/main/java/com/lm/codeprep/agent/MistakeReviewSkill.java) 读当前用户历史答题，选出缺失知识点，从私有库检索复习证据，输出复盘并创建 3 题针对性会话；它不调用 Tavily，沿用原刷题组。你应能分辨“Agent 编排层”与“业务工具层”：Agent 决定用哪个工具、记录结果；`PracticeAgentService` 才真正修改刷题状态。

**动手任务**：使用 Agent 请求“根据知识库出题”，查看 `steps` 和 `cp_agent_run.result_json`；提交一次答案，再只带 `practiceSessionId` 请求错题复盘。逐步指出每一步调用了哪个 Service、读写了哪张表。

## 8. 第七阶段：Redis 与流式响应的实际作用

[`RedisContextStore`](../src/main/java/com/lm/codeprep/infra/RedisContextStore.java) 保存问答历史，key 形如 `codeprep:chat:<sessionId>`、TTL 6 小时；限流 key 为 `codeprep:rate:<orgTag>`，通过 `INCR` 与首次 `EXPIRE` 形成时间窗口。当前问答限流维度是组织标签而非单用户。Redis 故障时会话转本机内存、限流放行；多实例之间不会共享降级数据。刷题幂等结果在 MySQL，不要把它讲成 Redis 幂等方案。配置里的 `rag.cache-seconds` 尚未形成实际的检索结果缓存。

普通 `/api/knowledge/chat` 一次返回 JSON。SSE `/api/knowledge/chat/stream` 和 WebSocket `/ws/agent` 可逐片发送 `session/retrieval/delta/done` 事件；前端主要用 WebSocket。Spring MVC 服务里使用 WebClient/Flux 调模型 SSE，不等于整套后端都是 WebFlux。`chat().block()` 会等待外部模型；在高并发和数据库事务内调用时要考虑线程占用及超时。

会话 ID 由 `SessionScope` 限定到用户名和组织标签。当前 WebSocket 事件没有每个问题独立的 requestId，同一连接并行提问存在片段交错风险；Redis 的“读列表→追加→整列表写回”也不是原子操作，存在同一会话并发写覆盖风险。讨论这些要说成**代码风险/改进方向**，不要说成已经实测发生的故障。

**动手任务**：保持同一 session 连续问两个相关问题，观察 HISTORY 对答案的影响；新建 session 再问同样问题，判断是否是上下文污染。用完整 JSON 接口和流式接口分别问一次，比较引用和最终答案。

## 9. 把项目串起来：一张数据读写表

| 行为 | MySQL | ES | Redis | RabbitMQ | 磁盘/模型 |
| --- | --- | --- | --- | --- | --- |
| 登录 | 读 `cp_account` | — | — | — | BCrypt/JWT |
| 上传 | 同事务写 `cp_document(PROCESSING)` 和 `cp_document_outbox(PENDING)` | — | — | 后台确认投递 documentId | 写原文件 |
| 异步入库 | 写 `cp_chunk`、更新 `cp_document` | 写向量/READY | — | 消费、ACK/NACK | Tika、Qwen |
| 私有问答 | 可能读 `cp_chunk` 降级 | BM25+KNN | 读写历史、限流 | — | Qwen、DeepSeek；必要时 Tavily |
| 分组刷题 | 读文档/切片、写会话快照 | 可检索/采样 | — | — | DeepSeek 出题/评测 |
| Agent 运行 | 写/读 `cp_agent_run` | 视选中动作 | 问答动作使用历史 | — | 路由/生成/复盘 |

这张表是回答“各服务为什么存在”的核心。看到一条异常，应先定位它属于哪个阶段与哪个真相源。例如文档在 MySQL 是 READY 而 ES 查不到：优先查索引一致性；用户显示登录但不能移组：先查权限，再查 CORS 的 `PATCH` 预检。

## 10. 面试时应能现场回答的 18 个问题

1. **为什么不能把原文直接发给大模型？** 文档可能超上下文且无权限过滤、引用与更新控制；先切块检索再生成。
2. **为什么同时存 MySQL 切片和 ES 片段？** MySQL 是持久文本和降级题源，ES 负责倒排与向量索引；两者需对账。
3. **MyBatis-Plus 如何启动？** Starter、DataSource、`@MapperScan`、`@TableName/@TableId`、`BaseMapper` 与下划线映射。
4. **为什么还写自定义 SQL？** READY JOIN 与 `FOR UPDATE` 有特定查询/锁语义，通用 CRUD 不够。
5. **事务保证了哪些操作？** `accept()` 原子提交文档与 Outbox；`answer()` 的占用和完成分别在两个短事务中提交，中间模型调用不在事务内。都不能保证模型、MQ、磁盘、ES 同步回滚。
6. **行锁和 `synchronized` 有何区别？** 前者跨实例协调数据库同一行，后者只在 JVM 对象范围生效。
7. **requestId 幂等为什么持久化？** 网络重试和重启后仍能返回首次结果，避免重复计分。
8. **JWT 权限变更什么时候生效？** 每次请求验签后按用户名重新从 MySQL 加载账号；后续请求可取到新角色/标签。
9. **组织标签与数据归属差别？** 标签限定知识域，会话和轨迹仍按用户名隔离。
10. **发布确认与消费者 ACK 有何区别？** 前者确认 broker 收到/路由，后者确认消费处理完成。
11. **RabbitMQ 能保证消息绝不重复吗？** 不能；处理端必须设计幂等、重试、补偿。
12. **为什么先标 PROCESSING 再 READY？** 防止未完成索引被对外检索；状态也让前端和补偿任务可观察。
13. **BM25 与 KNN 互补在哪里？** 术语精准匹配与语义近似召回互补；RRF 融合候选，不保证事实正确。
14. **私有检索故障为什么不能直接搜索 Tavily？** 故障不是零召回，直接外发问题会掩盖事故并可能泄露意图。
15. **Redis 掉了会怎样？** 会话落本机内存、限流放行；可用性保留但多实例一致性与限流严格性下降。
16. **为什么用户看到“回答错乱”？** 先分辨旧文档、错误片段、Prompt/历史污染、模型错误、流事件交错；沿链路定位。
17. **Agent 与普通 RAG 接口差别？** Agent 有任务路由、工具白名单、有限步轨迹及复盘；直接 RAG 主要完成一次检索生成。
18. **项目现在最需要补什么？** 已发消息长期未消费的监控与补偿、文档版本替换/删除、跨资源索引对账、相关性评测与事实级引用核验、生产身份/密钥管理。

## 11. 最小可执行练习清单

在项目根目录运行 `docker compose up -d mysql redis rabbitmq elasticsearch`，准备 Qwen/DeepSeek/Tavily 配置后运行 `mvn spring-boot:run`，前端从 `frontend` 目录启动本地 HTTP 服务。**不要把 `.env`、JWT、模型密钥贴到聊天或提交仓库。** 若外部服务暂不可用，先运行 `mvn test`，测试配置使用 H2 的 MySQL 兼容模式，但这不能代表真实服务联调。

按顺序完成并留下自己的笔记：

1. 画出六张表与每张表对应的 Mapper、Service；指出 ES 和 Redis 不在 Mapper 范围内。
2. 断点走一遍登录和一次文档上传，记录每步入参、状态变化与外部调用。
3. 上传同组两份文档，等待 READY，核对 MySQL 切片数量和前端组内 READY 数。
4. 用两个不同组织标签的用户做读取/写入权限验证。
5. 比较一个精准关键词问题和一个同义表达问题的 BM25/KNN 召回；记录 citation 与 Prompt。
6. 在所选分组刷题，重发同一 requestId；解释行锁与快照如何避免重复计分。
7. 调 Agent 问答、出题、评测、复盘各一次，读 `steps` 和 MySQL 轨迹；确认复盘只检索私有库。
8. 关掉某个外部依赖，在测试环境验证降级/FAILED/死信，并写出恢复步骤；不要在正式数据上做故障注入。

完成标准：不看文档，用 3 分钟从上传讲到问答，再用 3 分钟从选组讲到答题事务，最后能回答“为什么 MySQL、ES、Redis、RabbitMQ 不能互相替代”。这比背技术栈更接近真正掌握项目。
