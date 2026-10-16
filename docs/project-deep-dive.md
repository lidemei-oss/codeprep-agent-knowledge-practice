# CodePrep RAG Agent：全链路、数据库与 Java 后端详解

> 本文按当前代码整理，不把规划中的功能写成已经实现。主业务只有私有知识问答和分组刷题；统一 Agent 是这两项业务的受控编排入口。建议配合 `src/main/java`、`src/main/resources/schema.sql` 和 `docs/api.md` 阅读。配置值、默认账号仅适合本地演示，正式环境不能照搬。

## 1. 一句话讲清项目

用户上传学习资料后，系统把原文件保存到磁盘，MySQL 记录文档状态，RabbitMQ 异步触发 Tika 解析、结构化切块、Qwen 向量化，并把文本和向量分别放进 MySQL、Elasticsearch。提问时，系统以组织标签和类别限制范围，融合 BM25 与 KNN 召回，必要时改写检索或搜索 Tavily，再用证据和会话上下文构造 Prompt，交给 DeepSeek 生成答案。刷题则从用户选择的文档组中取题源，生成题目、评测答案，持久化刷题状态；Agent 负责路由问答、出题、评测和错题复盘。

```text
浏览器 :5500 ─JWT/HTTP、WebSocket─> Spring Boot :8083
                                      ├─ MySQL/MyBatis-Plus：账号、文档、Outbox、切片文本、刷题状态、Agent 轨迹
                                      ├─ 磁盘：原始资料和上传分片
                                      ├─ RabbitMQ：文档处理任务与死信
                                      ├─ Elasticsearch：IK/BM25、KNN 向量索引
                                      ├─ Redis：短期对话历史、限流
                                      ├─ Qwen Embedding：文本转向量
                                      ├─ DeepSeek：意图路由、问答、出题、评测
                                      └─ Tavily：私有库真正零召回后的可选公开搜索
```

### 1.1 分层与包结构

项目是**按业务域分包、每个域内部遵守 Controller → Service → Mapper/基础设施适配器**，并非把全项目所有 Controller 放到同一文件夹。`auth` 管登录与权限，`knowledge` 管上传/检索/问答，`practice` 管刷题，`agent` 管受控编排，`persistence/entity` 与 `persistence/mapper` 管关系型数据，`infra` 管 ES、Redis、模型和公网搜索，`config` 管安全、CORS 与 WebSocket。`KnowledgeController` 不直接调用 Mapper；文档列表、分组和移组委托 `KnowledgeDocumentService`。

**数据边界必须讲清**：MyBatis-Plus 不负责 ES/Redis/RabbitMQ；`cp_chunk` 只保存可回退检索的文本和元数据，2048 维向量在 ES；Redis 不保存刷题成绩；RabbitMQ 只传 `documentId`，不传整份文件。

## 2. 启动、配置与初始化顺序

1. 在项目根目录配置 `.env`（不要提交密钥），运行 `docker compose up -d mysql redis rabbitmq elasticsearch`；Compose 的 MySQL 容器内端口是 3306，默认映射宿主机 3307。Qwen Embedding、DeepSeek 和 Tavily 另行配置。
2. 运行 `mvn spring-boot:run`。Spring Boot 3.3.4、Java 17 编译目标、MyBatis-Plus Boot 3 Starter 3.5.17 由 `pom.xml` 声明。服务默认仅监听 `127.0.0.1:8083`。
3. `application.yml` 的 `spring.config.import: optional:file:.env[.properties]` 读入根目录 `.env`。MySQL 连接来自 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`；`spring.sql.init.mode=always` 执行 `schema.sql` 中的 `CREATE TABLE IF NOT EXISTS`。
4. `@SpringBootApplication` 扫描组件，`@MapperScan("com.lm.codeprep.persistence.mapper")` 注册 Mapper 代理，`@EnableAsync` 支持关闭 RabbitMQ 时的本地异步处理，`@EnableScheduling` 驱动 Outbox 投递与对账。`AccountService` 仅在演示账号不存在时写入 BCrypt 哈希；`KnowledgeStore` 可把旧 `data/documents.json` 中缺失的文档元数据按 ID 导入 MySQL。
5. `DocumentIngestionService.prepareIndex()` 创建 ES 索引；RabbitMQ 配置声明持久化主交换机、主队列、死信交换机和死信队列。浏览器前端单独通过 `python -m http.server 5500 --bind 127.0.0.1` 启动。

启动时 MySQL 是必需依赖；配置开关默认启用 ES、RabbitMQ、Redis、Qwen、DeepSeek、Tavily。但“开关为 true”不代表服务已健康，联调必须分别检查数据库、队列、索引、模型和搜索密钥。`schema.sql` 只能建缺失表，不会迁移已有表的列或索引；生产需要 Flyway/Liquibase 一类版本化迁移。

## 3. 六张 MySQL 表及关系

| 表 | 主键和主要字段 | 读写方 | 说明 |
| --- | --- | --- | --- |
| `cp_account` | `username`；`password_hash`、`roles_json`、`org_tags_json` | `AccountService` → `AccountMapper` | BCrypt 哈希，角色与标签以 JSON 文本保存；未拆成角色/授权关系表。 |
| `cp_document` | UUID `id`；标题、`category`、`org_tag`、`status`、文件名、时间、切片数、`group_name` | `KnowledgeStore` → `DocumentMapper` | `PROCESSING/READY/FAILED`；`(org_tag,category,status,group_name)` 组合索引。 |
| `cp_document_outbox` | `document_id`；投递状态、尝试次数、重试时间、租约、错误信息 | `DocumentSubmissionService`/`DocumentJobPublisher` → `DocumentOutboxMapper` | 与新文档同事务创建；发布失败时保留任务并重试。 |
| `cp_chunk` | `documentId-chunkNo`；`document_id`、标题、正文、类别、标签、序号 | `KnowledgeStore` → `ChunkMapper` | 文本回退检索和刷题采样；`(document_id,chunk_no)` 索引；无向量列、无声明外键。 |
| `cp_practice_session` | UUID `id`；`username`、`state_json` | `PracticeAgentService` → `PracticeSessionMapper` | 一行 JSON 快照含会话、当前题、依据、作答与 requestId 幂等结果。 |
| `cp_agent_run` | UUID `id`；`username`、`result_json` | `AgentRunStore` → `AgentRunMapper` | Agent 的结果和步骤轨迹；按用户名校验读取权限。 |

数据库保存业务真相，ES 是检索索引。切片入库时 MySQL 和 ES 分别写入，**没有跨资源 ACID 事务**。`cp_document` 与 `cp_chunk` 之间也没有数据库级外键约束，由应用维护关联与清理。旧 JSON 文件只作幂等导入源，不再是写入目标；旧 ES 切片不会自动反向导入 `cp_chunk`。

## 4. MyBatis-Plus 是如何配置和运行的？

```text
pom.xml：mybatis-plus-spring-boot3-starter + mysql-connector-j
application.yml：spring.datasource JDBC 配置；mybatis-plus.configuration.map-underscore-to-camel-case=true
CodePrepApplication：@MapperScan 扫描 Mapper 接口
entity：@TableName("cp_...") + @TableId(value="...", type=IdType.INPUT)
mapper：继承 BaseMapper<Entity>；个别方法额外写 @Select SQL
service：注入 Mapper，封装查询、状态变更和权限/业务规则
```

`BaseMapper` 提供 `selectById/insert/updateById/delete/selectList` 等通用操作。项目用 UUID 或账号名自行提供主键，因此 `IdType.INPUT`，不是数据库自增。`map-underscore-to-camel-case` 让 `org_tag` 对应 `orgTag`、`state_json` 对应 `stateJson`。`KnowledgeStore.readyDocumentIds()` 用 `QueryWrapper` 组合 READY、组织标签、类别、组名；`ChunkMapper.selectReady()` 用自定义 JOIN SQL 关联文档状态、按新文档和切片序号取题源。不是所有逻辑都靠自动 CRUD：特定 JOIN 与加锁查询仍要写 SQL。

文档接收的关键事务在 `DocumentSubmissionService.accept()`：同一 MySQL 事务内插入 `cp_document(PROCESSING)` 和 `cp_document_outbox(PENDING)`，第二次插入失败会一起回滚。这解决文档记录已提交但消息发送失败后无恢复依据的问题；文件仍在数据库事务之外。刷题答案使用 `TransactionTemplate` 分成两个短事务：第一次 `SELECT ... FOR UPDATE` 校验并持久化 `pending(requestId,questionId,answer,token)` 占用；提交后在事务外评测、检索并生成下题；第二次锁行核对 token，保存成绩、下一题和 requestId 结果并清除占用。这样同一会话评测期间的并发请求不重复调用模型；已完成请求可重放。锁定依赖实际事务和数据库行锁；代码里的 `synchronized(state)` 主要照顾单元测试的内存实现，不能替代跨实例数据库锁。模型等待期间没有持有会话行锁或事务连接。

示例调用链：`POST /api/practice/sessions/{id}/answers` → `PracticeController.answer` → 第一段短事务写 `pending` → 提交并释放行锁 → DeepSeek 评测、生成下题 → 第二段短事务核对 token 并写成绩/幂等结果 → 返回 `AnswerResult`。同 requestId 正在处理时返回 HTTP 409；异常会尝试释放占用。进程崩溃后的残留占用目前不自动过期，以避免尚在运行的模型请求与重试请求并发评测。

## 5. 登录、JWT 与组织标签授权

`POST /api/auth/login` → `AuthController` → `AccountService.authenticate` → `AccountMapper.selectById` → BCrypt 比对 → `JwtService.issue`。JWT 含用户名、角色和组织标签声明，有效期默认 12 小时。**真正的每次 HTTP 授权不直接相信 JWT 中的角色声明**：`JwtAuthenticationFilter` 验签并取用户名后，重新从 MySQL 加载账号，构造 `UserPrincipal` 和 Spring Security 权限。

`SecurityConfig` 使用无状态会话，并把 JWT Filter 放在 `UsernamePasswordAuthenticationFilter` 前；除登录、WebSocket 握手路径和少量公开路径外均要求认证。`UserPrincipal` 把角色变为 `ROLE_*`、标签变为 `ORG_TAG:*`，再附加知识读/写权限。`OrgTagGuard` 在业务入口二次检查组织标签：OWNER 放行；其他用户必须有目标标签及相应读/写权限。`reader` 可以读取其标签下的资料并刷题，不能写资料；演示 `alice` 是 OWNER。读别人的刷题会话、Agent 轨迹还要校验 `username`，不能只凭知道 ID。

WebSocket 握手通过 `/ws/agent?token=<JWT>` 做 JWT 校验，把认证对象放入 session attributes，处理每条消息时再检查组织标签。浏览器 `sessionStorage` 暂存 JWT，刷新当前标签页可恢复；前端和服务端分别使用 5500、8083 端口，CORS 只允许本地前端来源。URL 查询参数中的 JWT 可能被浏览器历史或代理日志记录，正式环境应考虑更安全的票据传递方式。

## 6. API 总览与调用链

所有业务 HTTP 接口都在认证后进入 Controller；下表列出核心流程，详细参数见 `docs/api.md`。`GET /actuator/health`、`/actuator/metrics` 是运维端点，不属于业务接口。

| 接口 | Controller → 主要 Service | 主要读写或外部调用 |
| --- | --- | --- |
| `POST /api/auth/login` | `AuthController` → `AccountService`/`JwtService` | `cp_account` 查询、BCrypt、签发 JWT |
| `POST /api/knowledge/documents/upload` | `KnowledgeController` → `DocumentUploadService` → `DocumentSubmissionService` | 磁盘、`cp_document`、`cp_document_outbox`；返回 PROCESSING，后台另行投递 |
| `POST /api/knowledge/uploads` | `KnowledgeController` → `ResumableUploadService.begin` | 内存中创建 uploadId 状态 |
| `PUT /api/knowledge/uploads/{id}/chunks/{index}` | `KnowledgeController` → `ResumableUploadService.writeChunk` | 本地 `.part` 文件与内存接收记录 |
| `POST /api/knowledge/uploads/{id}/complete` | `KnowledgeController` → `ResumableUploadService.complete` → `DocumentUploadService` | 合并文件、原子写文档与 Outbox 任务 |
| `GET /api/knowledge/documents` | `KnowledgeController` → `KnowledgeDocumentService` → `KnowledgeStore` | `cp_document` 查询并按用户标签过滤 |
| `GET /api/knowledge/groups` | 同上 | 由文档元数据聚合分组和 READY 数 |
| `PATCH /api/knowledge/documents/{id}/group` | `KnowledgeController` → `KnowledgeDocumentService.moveGroup` → `KnowledgeStore` | 校验原文档写权限，更新 `cp_document.group_name` |
| `POST /api/knowledge/chat` | `KnowledgeController` → `KnowledgeAgentService` → 检索/Prompt/模型 | ES/Qwen/MySQL/Tavily/DeepSeek/Redis；返回 JSON 与引用 |
| `GET /api/knowledge/chat/stream` | 同上 | SSE `session/retrieval/delta/done` 事件 |
| `WebSocket /ws/agent` | `AgentWebSocketHandler` → 问答 Service 或 `AgentOrchestrator` | `KNOWLEDGE_ASK` 流式；`AGENT_RUN` 一次性结果 |
| `POST /api/practice/sessions` | `PracticeController` → `PracticeAgentService.start` | 检索组内题源、DeepSeek 出题、插入 `cp_practice_session` |
| `POST /api/practice/sessions/{id}/answers` | `PracticeController` → `PracticeAgentService.answer` | `SELECT FOR UPDATE`、模型/规则评测、更新快照 |
| `GET /api/practice/sessions/{id}` | `PracticeController` → `PracticeAgentService.session` | 按本人身份读取快照中的会话 |
| `POST /api/agent/runs` | `AgentController` → `AgentOrchestrator` | 规划、调用问答/刷题/复盘工具、保存 `cp_agent_run` |
| `GET /api/agent/runs/{runId}` | `AgentController` → `AgentRunStore` | 按本人身份读取 Agent 轨迹 |

HTTP Controller 做参数绑定与 `@Valid` 校验、获取 `Authentication`、转发业务操作；Service 决定规则；Mapper 管 SQL。`GlobalExceptionHandler` 将部分参数/状态错误转成 400，将 `SecurityException` 转成 403。请求参数并不是全部有强校验，例如刷题数会在 Service 内截到 1～20。

## 7. 文档上传到 READY：逐步看完整链路

### 7.1 同步接收

普通上传时，Controller 先用 `OrgTagGuard.checkWrite` 校验目标标签。`DocumentUploadService` 生成 UUID，保存文件到 `data/uploads`。`DocumentSubmissionService.accept()` 在一个 MySQL 事务中插入 `cp_document(PROCESSING)` 和 `cp_document_outbox(PENDING)`，切片数为 0、分组默认为“默认分组”。事务提交后接口返回；这只代表资料已接收、任务待投递，**不代表 RabbitMQ 已收到消息，更不代表可以检索**。后台 `DocumentJobPublisher` 轮询到期任务，先以领取令牌和租约标为 `SENDING`，发送仅含文档 ID 的消息并等待 confirm/return；确认成功标为 `SENT`，未确认、不可路由或网络异常则恢复 `PENDING` 并指数退避。前端每 3 秒查询文档状态。

分片上传分为 begin、写片、complete。每片最大约 5 MiB，服务器以 `uploadId.part.index` 写盘；complete 按序合并并校验总字节数，再通过相同事务创建文档与 Outbox 任务。`UploadState` 在 `ConcurrentHashMap`：进程重启后已上传的 `.part` 文件虽可能仍在磁盘，原 uploadId 状态却无法恢复，因此目前是**单进程内断点续传**，并非跨重启可靠续传；也没有逐片哈希校验。

### 7.2 RabbitMQ 消费和索引构建

`DocumentJobRabbitConfiguration` 声明 durable direct exchange/queue 与死信路由。监听器是手动 ACK、prefetch=2、并发 1～2。`DocumentJobConsumer` 收到 ID 后，如果文档已经 `READY` 则直接 ACK；否则通过 `DocumentUploadService.pathOf(id)` 找到原文件，调用 `DocumentIngestionService.processOrThrow()`。消费失败会在**同一次投递内立即重试**最多 3 次；最终标 `FAILED` 并 `basicNack(requeue=false)`，消息进入死信队列。当前不是指数退避，也没有自动死信重放。

处理步骤：

```text
原文件 → Markdown 保留原文/Tika 解析其他格式 → 结构化切块
      → 清除该文档旧 cp_chunk 与 ES 片段
      → 对每个片段调用 Qwen /v1/embeddings
      → cp_chunk 插入文本；ES 插入正文、标签、类别、向量，暂标 PROCESSING
      → ES 批量改 READY → MySQL 文档改 READY/记录 chunkCount → RabbitMQ ACK
```

切块器识别 Markdown 标题、中文/编号章节、列表、代码围栏；按章节和段落聚合，超长块递归切分，保留部分重叠。配置默认上限约 700、下限约 160、重叠约 80 个**估算 Token**，不是精确模型 tokenizer。Tika 解析 DOCX/PDF 后往往只剩纯文本，标题层级依靠规则推断。Qwen3-Embedding-4B 的输出可裁剪并归一化为 2048 维，若维度不符则处理失败。ES mapping 中 `embedding` 为 2048 维 cosine `dense_vector`，`title/content` 使用 IK 分词。

异常会尝试清理该文档的 MySQL 切片和 ES 数据；但磁盘、MySQL、RabbitMQ、ES 之间没有全局事务，清理也可能失败。当前 `DocumentJobReconciler` 会给历史遗留的、超过阈值且没有 Outbox 行的 PROCESSING 文档补建任务；原文件丢失则标 FAILED。已经标为 SENT 却长期没有被消费的任务、死信与跨资源状态差异仍需运维告警和进一步补偿。修改正文并替换旧文档、删除文档、自动重建已有索引目前不是完整接口；再次上传会生成新 ID，可能与旧资料并存。

## 8. 私有 RAG 问答：从问题到引用答案

1. `KnowledgeController.chat/stream` 检查读权限；`KnowledgeAgentService` 做限流并用 `SessionScope` 把会话 ID 约束到 `username:orgTag:UUID`，防止跨用户/知识域复用。
2. `KnowledgeSearchService.retrieve()` 调 Qwen 对问题向量化。即使 Embedding 调用失败，仍尝试关键词路径；当前这里捕获异常但没有记录降级原因，排查时应关注。ES 的 BM25 `multi_match` 搜索标题、正文和预留关键词字段；KNN 按向量召回；两路都过滤 `READY`、组织标签、资料类别。刷题指定组时先从 MySQL 取该组 READY 文档 ID，再作为 ES `terms documentId` 过滤。
3. `ElasticsearchKnowledgeGateway` 对两路结果按 chunkId 合并，使用 RRF 名次贡献并叠加少量原始分数，最多保留 8 条。这里是**融合排序**，不是已经接入交叉编码器精排。ES 无结果或报错时，Service 从 MySQL 的 READY 切片做简易词项包含评分；它不是 MySQL FULLTEXT，也没有语义能力。ES 报错且回退无结果时返回 `PRIVATE_INDEX_UNAVAILABLE`，不会把服务故障误认作真正零命中。
4. 真正无依据时 `AgentPlanner.rewrite()` 改写一次再检索。仍无结果且该请求允许公开搜索时调用 Tavily；公开搜索默认允许，但客户端可传 `allowWebSearch=false`。问题会发给 Tavily；进入 Prompt 的问题和证据还会发给配置的 DeepSeek，敏感资料需关闭公网搜索并确认模型服务的数据边界。
5. `RagPromptBuilder` 组合 QUESTION、最近最多 6 条会话记录和编号 CONTEXT，证据最多受 12000 字符预算约束。系统 Prompt 要求依证据、资料不足说明不知道、标引用、不得执行文档中的指令。`DeepSeekClient` 以较低 temperature 调 `/chat/completions`；完整 JSON、SSE 和 WebSocket 是三种返回方式。生成结束后问答历史写 Redis，TTL 为 6 小时；Redis 不可用时有本机内存降级。

**准确率边界**：当前没有标准问答评测集、明确的相关性门槛或事实级引用核验。统一 Agent 会检查引用编号是否在召回范围内，直接问答接口没有同等校验；编号合法并不代表论断真实。`RagPromptBuilder` 可能因预算截断上下文，返回的 citations 与实际进入 Prompt 的片段数也可能不同。不能在面试中说“已经保证答案绝对准确”。

## 9. 分组刷题、评测和错题复盘

前端查询 `GET /api/knowledge/groups`，只把有 READY 文档的组作为可选题源。`POST /api/practice/sessions` 带 `category/orgTag/groupName/targetCount`；Controller 校验该标签读权限，Service 把题数限制在 1～20，生成 UUID 会话。若指定分组，检索和采样都限定为该组 READY 文档；无题源则拒绝。若不指定，取整个类别/知识域。`difficulty` 当前作为请求字段存在，但 Service 尚未把它用于难度控制。

`PracticeAgentService.next()` 从前几条引用中拼题源，先形成固定开放题方向，再请求 DeepSeek 生成不超过 180 字的单行题干；格式不合格或模型出错则保留固定题干。返回 `Question(id,sessionId,prompt,hints,sourceChunkIds)`；同时把当前题、依据文本和会话快照插入 `cp_practice_session.state_json`。以后查询会话从 MySQL 反序列化快照，不依赖应用进程内存。

答题接口必须携带当前 `questionId`、唯一 `requestId` 和答案。第一次短事务锁行、检查归属与幂等记录并保存 `pending`；相同请求在评测中收到 409，完成后重试则返回已存结果；复用同一 requestId 提交不同内容报错。事务外优先请 DeepSeek 按资料输出 JSON 评分、评语、缺失点；模型不可用或格式不合格时，回退到“资料关键词覆盖+回答深度”的规则评分，**规则评分不能等同语义正确性评估**。下一题也在事务外生成。第二次短事务核对占用令牌，更新掌握度，并将尝试记录与幂等结果写回 JSON 快照。

错题复盘不是新的业务模块，而是 `MistakeReviewSkill`：根据本人历史答案中的缺失概念/低分题归纳薄弱点，仅在私有库检索证据，不调用 Tavily；给出带引用的复盘说明，并创建 3 题针对性复练会话，沿用原组。原刷题会话与当前 category/orgTag 必须一致。

## 10. 统一 Agent 的一次执行

`POST /api/agent/runs` → `AgentController` 校验组织标签 → `AgentOrchestrator.run()` 解析 session 与 runId → `AgentPlanner.decide()` 选择显式允许的 `SEARCH_KNOWLEDGE/START_PRACTICE/GRADE_ANSWER/REVIEW_MISTAKES` → 调用对应 Service/Skill → 记录动作与观察，步骤数最多 8 → 将 `RunResult` 序列化插入 `cp_agent_run`。模型只能在工具白名单内提议，无法执行任意命令或无限循环。

知识问答分支负责检索、改写、可选 Tavily、Prompt、生成和引用编号检查；无证据返回 `NEEDS_SOURCE`，索引不可用返回 `RETRIEVAL_UNAVAILABLE`，编号缺失/越界返回 `REVIEW_REQUIRED`。Agent 出题和评测复用刷题 Service；错题复盘复用 Skill。`GET /api/agent/runs/{runId}` 先验证运行记录属于当前用户。统一 Agent 接口返回完整 JSON；直接知识问答接口才是逐片流式。当前 Agent 不是 Spring AI 框架的实现，而是用 Spring Boot 自己组织的受控 plan/act/observe 流程。

## 11. 为什么同一个项目用了多种存储？

- **MySQL**：需要持久化、主键查询、条件查询和行锁事务的业务状态；MyBatis-Plus 降低基础 CRUD 样板代码。
- **Elasticsearch**：全文倒排、IK 分词、BM25 与向量 KNN；索引可重建，不作为账号/答题状态真相源。
- **Redis**：6 小时 TTL 的聊天上下文及限流计数；故障时问答仍有进程内降级，但多实例不会共享此降级状态。
- **RabbitMQ + MySQL Outbox**：上传成功响应与耗时解析/向量化解耦；MySQL 原子保存文档与待投递意图，后台发送失败可重试。durable 队列、发布确认、手动 ACK、死信处理失败任务。MQ 至少可能重复投递，所以消费逻辑先查 READY，并在重做时清理旧切片。
- **本地磁盘**：原文档重试时仍可读取；单机演示方便，多实例需换共享对象存储或让任务携带可访问的对象键。

没有“一个事务同时提交 MySQL、磁盘、ES 与 RabbitMQ”。当前策略是 MySQL Outbox、发布退避重试、遗留缺失任务对账、状态机和失败清理；正式环境仍需死信告警、消费者停滞监控、索引对账与文档版本切换。

## 12. 结合代码必须掌握的 Java 后端知识

以下问答按真实调用链组织，适合先理解再背诵；括号中给出本项目落点。

### 12.1 Spring Boot、MVC 与依赖注入

**Q：一个 HTTP 请求怎样到 Service？** 浏览器发 HTTP 请求，Servlet 容器交给 Spring Security Filter Chain，再由 DispatcherServlet 根据 `@RequestMapping` 找 Controller；Spring MVC 做参数绑定、`@Valid` 校验，把认证对象传入方法；Controller 调已注入的 Service，返回对象经 Jackson 序列化为 JSON。异常由 `@RestControllerAdvice` 统一转换。项目示例是 `KnowledgeController.chat` → `KnowledgeAgentService.ask`。

**Q：Controller、Service、Mapper 为什么分层？** Controller 只处理协议边界，Service 维护可复用的业务规则/权限/事务，Mapper 专心读写关系型库。这样 HTTP 与 WebSocket 或 Agent 入口可复用同一 Service，业务测试也能替换 ES/模型适配器。当前有按域分包，但职责仍符合分层原则。

**Q：构造器注入和 `@Component/@Service/@RestController` 是什么？** 这些注解使类成为 Spring Bean；构造器列出依赖，容器启动时装配。构造器注入让依赖不可遗漏、便于测试。`@Service` 本身不自动开启事务，只有 `@Transactional` 等额外机制才影响事务。默认 Bean 通常是单例，因此成员可变状态必须考虑并发。

**Q：`@PostConstruct`、`@EnableAsync` 在这里有什么用？** `DocumentIngestionService.prepareIndex()` 用 `@PostConstruct` 在 Bean 初始化后检查/创建 ES 索引；`@EnableAsync` 为 RabbitMQ 关闭时的 `processAsync()` 提供代理执行能力。异步调用跨 Bean 发生；同类内部调用不会自动经过代理，这是 Spring AOP 的常见追问。

**Q：`@Valid` 和 Service 校验有何区别？** `@NotBlank` 在请求进入 Controller 时拦截空字段；文件大小、分片序号、组名长度、会话归属等业务约束仍需 Service 校验。不要把输入校验等同授权校验。

### 12.2 MyBatis-Plus、JDBC、事务与 SQL

**Q：MyBatis-Plus 和 JPA 有什么差别，本项目怎么用？** 这里选 MyBatis-Plus 的 `BaseMapper` 和 `QueryWrapper` 简化 CRUD，同时保留手写 SQL 的控制权；没有用 JPA EntityManager。Mapper 接口由 `@MapperScan` 注册为代理，方法最终经 JDBC/连接池访问 MySQL。复杂查询如 READY 切片 JOIN、行锁 `FOR UPDATE` 仍在 Mapper 中明写。

**Q：为什么 `@TableName/@TableId/IdType.INPUT`？** 指定表、主键列和由应用提供主键。文档/会话/运行记录用 UUID，账号用用户名。实体字段驼峰名与 SQL 下划线列名由配置映射；例如 `groupName` ↔ `group_name`。

**Q：`@Transactional` 到底保证什么？** 它让 `DocumentSubmissionService.accept()` 的文档/Outbox SQL 在同一个事务提交或回滚。刷题答案改用 `TransactionTemplate` 显式划出“占用”和“提交”两个独立短事务；模型调用夹在中间，不在事务内。回滚仅覆盖当前数据库事务中的 SQL，不会自动撤销模型调用、RabbitMQ 消息、ES 写入或磁盘文件。`@Transactional` 通过代理生效，同类自调用要警惕代理失效；`TransactionTemplate` 不依赖自调用代理。

**Q：`SELECT ... FOR UPDATE` 为什么能防重复提交？** 在支持行锁的数据库中，它短暂锁住目标会话行；第一个请求保存持久化的 `pending` 后提交，第二个请求取得锁时看见该占用，因此不会再次调用模型。首次请求完成后再用短事务锁行核对 token、保存结果；重复 requestId 随后直接重放结果。仅使用 Java 的 `synchronized` 无法跨 JVM 实例协调。

**Q：幂等与去重有什么不同？** 幂等强调同一业务请求重复执行的外部结果一致；本项目用客户端 `requestId` + 题目 ID + 答案内容校验，保存首次结果以供重放。MQ 消费则对 READY 文档跳过，重做时清理旧切片，属于处理重复投递的基础措施；它不能单凭 RabbitMQ 实现“绝对只执行一次”。

**Q：哪些索引真正有用？** `cp_document` 的 `(org_tag,category,status,group_name)` 支持知识域/状态/分组过滤；`cp_chunk(document_id,chunk_no)` 支持某文档切片查询；刷题与 Agent 表的 username 索引支持按用户访问。MySQL 组合索引遵循最左前缀，但实际是否命中要看 SQL 条件、数据量和 `EXPLAIN`；`cp_chunk` 的正文是 LONGTEXT，没有 FULLTEXT 索引，Java 回退搜索可能扫描 READY 切片，不能说它和 ES 一样高效。

**Q：为何把刷题会话存成 JSON 快照，代价是什么？** 好处是示例项目可以一次读写完整题目、答案和幂等状态，并通过锁住一行实现简单一致性；代价是难以按单次答题做 SQL 统计、更新整行成本高、格式演进和高并发更复杂。正式业务通常拆成会话、题目、答题记录、提交幂等表，并做结构化索引。

**Q：为什么不能用 `CREATE TABLE IF NOT EXISTS` 当迁移？** 它只保证表存在，不会把旧表更新为新结构，也没有迁移版本记录；更改列类型或索引时必须有可审计、可回滚的版本化脚本。

### 12.3 Spring Security、JWT 与权限模型

**Q：认证和授权的区别？** 认证回答“你是谁”：登录校验 BCrypt，JWT Filter 验签并从 MySQL 载入账号。授权回答“你能做什么”：Security Filter Chain 要求认证，`OrgTagGuard` 再检查组织标签及读写权限，会话/轨迹还检查数据归属。账号角色和组织标签目前以 JSON 字段保存，属于简化的标签化 RBAC，并非有角色、权限、用户关系表的完整后台。

**Q：JWT 为什么是无状态的？** 服务端不保存每个登录会话，而是验签 token、读取有效期和 subject；本项目又按用户名查库加载最新角色/标签，这使权限变更可在后续请求生效，但也增加一次数据库读取。token 默认 12 小时，默认演示密钥必须替换。JWT 不是加密容器，敏感信息不应直接放在声明里。

**Q：为何 JWT Filter 放在 `UsernamePasswordAuthenticationFilter` 前？** 业务接口需要在 Spring Security 做授权决策之前建立 `SecurityContext`。`OncePerRequestFilter` 保证一次请求的过滤行为可控；当前还允许异步 dispatch 经过过滤器。WebSocket 握手不同于普通 HTTP API，因此另外用了 `JwtHandshakeInterceptor`。

**Q：Spring Security 的 401 和 403？** 未认证或令牌无效应拒绝访问；已认证但缺少目标标签/权限时由 `OrgTagGuard` 抛安全异常，统一处理为 403。设计和排查时不要把“登录失败”与“无权访问某组文档”混成一个错误。

### 12.4 RabbitMQ 与异步一致性

**Q：为什么上传不用同步解析？** Tika 解析、逐片调用 Qwen、写 ES 耗时且可能失败；让上传请求保存文件并原子记录文档与 Outbox 任务，即可快速返回 PROCESSING，后台再投递，消费者独立处理。RabbitMQ 相比把长任务放在 HTTP 线程中更适合做削峰、故障隔离和死信观察。

**Q：publisher confirm、return、consumer ACK 分别解决什么？** confirm 表示 broker 接收发布，return 表示 mandatory 消息无法路由到队列，手动 ACK 表示消费者处理成功后确认投递。三个阶段不同；publisher confirm 不能证明文档已经完成切块，consumer ACK 也不能替代 MySQL/ES 的一致性检查。Outbox 原子提交的是文档和“待发送意图”，不是 RabbitMQ 消息本身；确认结果不明时会重发，可能重复投递。

**Q：失败时重试和死信怎么工作？** 发布失败由 Outbox 定时重试并指数退避，文档保持 PROCESSING；发布进程崩溃后，`SENDING` 租约过期可再领。消息已经被消费却处理失败时，消费者在同次投递内立即重试最多三次，之后标 FAILED 并 NACK 不重新入队；队列的死信配置把消息路由到死信队列。消费阶段尚无延迟退避或自动死信重放，应单独告警和补偿，不能把发布重试等同于消费重试。

**Q：消息为什么只放 documentId？** 避免队列承载大型文件，消费者凭 ID 从持久化元数据和磁盘读取文件，也方便重复处理。但它要求原文件路径在消费者节点可访问，单机本地磁盘方案不能直接扩成独立多节点消费者。

### 12.5 Redis、缓存与并发

**Q：Redis 在这里缓存了什么？** `RedisContextStore` 保存带 6 小时 TTL 的问答对话历史、问答限流计数，还提供一个幂等键辅助方法；刷题 requestId 的最终幂等结果不依赖 Redis，而存在 MySQL 会话快照。当前 `codeprep.rag.cache-seconds` 配置项并没有在检索服务中实现结果缓存，不宜宣称“检索命中 Redis 缓存”。

**Q：TTL 与限流怎么实现？** 对话以 JSON 列表按 key 保存并设置 6 小时过期；限流通过 Redis `INCR`，第一次增长时设置窗口过期，默认问答同一组织标签一分钟 60 次。这个维度是知识域，不是每个用户；Redis 故障时 `allow()` 放行，属于可用性优先而非严格限流。

**Q：当前并发隐患？** 对话追加采用读列表、加元素、整列表写回，不是原子操作；同一会话并发提问可能丢历史。Redis 降级到本机 `ConcurrentHashMap` 后，跨实例无法共享；其中列表值本身还要注意并发修改安全。生产可改 Redis List/Stream 或 Lua 脚本，并给每次问答加 requestId、定义同会话串行策略。

### 12.6 HTTP 流式、WebSocket、Reactor 与前端

**Q：SSE 与 WebSocket 有何区别？** SSE 是基于 HTTP 的服务器单向事件流，适合答案逐片返回；WebSocket 在握手后是双向长连接，适合前端发 `KNOWLEDGE_ASK` 并接收 `session/retrieval/delta/done`。当前前端主要走 WebSocket，SSE 作为独立 API。统一 Agent 的 `AGENT_RUN` 经 WebSocket 仍是一次性结果，不是逐 token Agent 轨迹。

**Q：为什么项目同时有 spring-web 和 webflux？** 主 HTTP 服务是 Spring MVC/Tomcat；WebFlux 的 `WebClient` 与 `Flux/Mono` 被用来调用模型 API 和处理流式响应，不等于整套后端已变为 WebFlux/Netty。`chat().block()` 会等待模型并占用调用线程；刷题评测与下题生成已移到数据库事务之外，避免等待期间持有行锁和事务连接，但线程占用与超时仍需监控。

**Q：回答片段为何可能串流？** 当前 WebSocket 每收到知识问答消息就订阅一个 `Flux`，事件没有独立 requestId；同一连接如果并行提交多个任务，可能交错。前端目前每次 `ask()` 新建 socket，降低日常碰撞概率，但服务端协议层仍应做关联 ID、取消和同连接并发控制。模型 SSE 的 delta 还需按顺序拼接，不能把引用事件当成答案正文。

**Q：`record` 在项目中怎么用？** `ChatRequest`、`Citation`、`Question`、`RunResult` 等使用 Java record 表达传输和不可变快照；record 的引用字段如 `List` 本身仍可能可变，所以关键地方用 `List.copyOf()` 固化。`ConcurrentHashMap` 保证 map 操作的线程安全，不自动保证 map 中可变对象的复合操作安全。

### 12.7 检索、模型与文件处理

**Q：BM25、向量检索、RRF 分别解决什么？** BM25 对术语、类名和精准关键词敏感；向量召回帮助识别不同说法的语义相近问题；RRF 以名次融合两路候选。这里还混入有限的原始分数加权，没有独立重排模型、相关性阈值或真实准确率统计。`topK=8`、`candidateK=40` 是当前配置，可用评测集调优。

**Q：为什么切块决定问答质量？** 切太大导致无关内容污染 Prompt，切太碎会丢失定义、条件和上下文。项目保留标题路径、段落、代码围栏与部分重叠，再受估算 Token 预算约束；对 PDF/Word 的复杂表格或版式仍可能解析不准确，需要回看原文并做解析质量验收。

**Q：模型服务失败时会怎样？** Embedding 查询失败时可能退成关键词检索；文档入库时 Embedding 失败会使处理任务失败并重试/死信。问答模型不可用时可返回证据导读；刷题出题和评测则分别回退固定题干与规则评分。降级保证可用性，但不意味着输出质量等同于模型正常工作。

**Q：如何区分零召回与检索故障？** ES 报错且 MySQL 回退无资料时策略为 `PRIVATE_INDEX_UNAVAILABLE`，问答不会因此把问题自动送 Tavily；ES 正常检索和回退均无依据时才按允许标志走公开搜索。查询 Embedding 失败目前不向前端显式暴露，需要完善日志/指标。

## 13. 知识库变更、排障与可观测性

**文档新增**已有“PROCESSING → READY/FAILED”流程。**移组**只更新 MySQL 分组；刷题时通过该组文档 ID 限制检索，无需重新向量化。**修改正文/删除**尚没有完整版本替换与删除 API：理想方案是新版本单独构建、校验切片数和向量维度、原子切换可见版本，再清旧索引和原文件。更换切块策略或 Embedding 模型时应全量/分批重建受影响索引，不能只改配置。ES 现有索引存在时 `ensureIndex()` 不自动验证维度和 mapping 与当前代码是否相同。

回答错乱按“原文 → 切片 → ES/SQL 检索 → 过滤 → 实际 Prompt → DeepSeek 原始结果 → SSE/WebSocket 拼接”逐段比对。先用新 session、固定 orgTag/category、关闭 Tavily 复现；看引用的 `documentId/chunkId/retrievalSource` 和文档状态；再看是否命中错误组、旧版本或截断上下文。注意 `KnowledgeSearchService` 会在查询向量化失败时静默退化、Redis 写历史非原子、WebSocket 无请求关联 ID，这些都属于现有代码中需要监控或改进的具体风险，而非已经证实的故障原因。

建议建立固定问答集，分开评测检索 `Recall@K/MRR`、答案依据一致性、引用正确率、无证据拒答率、端到端耗时。运维层面至少观察 `cp_document` 各状态数量、PROCESSING 超时、RabbitMQ 积压/死信、ES 查询错误、Qwen/DeepSeek/Tavily 失败率与调用耗时；日志关联 `requestId/documentId/sessionId/runId`，但不可记录密钥或未经脱敏的私有原文。

## 14. 测试、启动和验收清单

项目测试使用 `src/test/resources/application-test.yml` 中 H2 的 MySQL 兼容模式，覆盖 HTTP、Mapper 持久化、权限、分组、切块、RabbitMQ 生产/消费、模型客户端、检索、规划与刷题等。H2 通过只能证明本地业务和 SQL 兼容测试通过，不能代表 MySQL、ES、RabbitMQ、Redis、Qwen、DeepSeek、Tavily 全部真实联调成功。`mvn test` 可复验单元/集成测试；Docker 环境需要另行做跨服务验收。

建议按以下顺序人工验收：

1. `docker compose ps` 查看 MySQL/Redis/RabbitMQ/ES 状态；核对 MySQL 3307、RabbitMQ 5672/15672、ES 9200，且 `.env` 不外泄。
2. 启动后调用 `/actuator/health`；检查六张表（含 `cp_document_outbox`）存在、ES mapping 的 IK 插件与 2048 维、RabbitMQ 主队列和死信队列。
3. 登录 `alice` 上传一份含明确标题和关键词的文档，观察 `PROCESSING → READY`、`cp_document.chunk_count`、`cp_chunk` 行数和 ES READY 切片数是否对应。
4. 用同知识域提问，检查引用来自该文档；再问知识库以外的问题并分别测试 Tavily 开关。用 `reader` 验证不能写文档、不能访问其他组织标签。
5. 选定分组创建刷题会话，提交答案，使用相同 `requestId` 重试确认结果一致；重启应用后再次读取会话与 Agent 轨迹。
6. 注入一次模型/ES/队列异常，检查失败状态、降级策略和死信；这一步必须在可控测试环境中操作，不能对生产数据随意故障注入。

## 15. 当前局限：面试中必须说实话

- 还没有完整的文档版本替换、删除和旧索引重建流程；重复上传可能形成多份知识。
- 还没有检索相关性阈值、独立精排模型、离线准确率报表或事实级引用核验；引用编号正确不等于结论正确。
- `cp_practice_session`/`cp_agent_run` 用大 JSON 快照便于演示，复杂查询和大规模并发应拆表；刷题 `difficulty` 尚未驱动实际难度。
- 分片任务状态在内存；原文件在本地磁盘；Redis 失效后的上下文降级也是单机内存；均未实现多实例可靠共享。
- RabbitMQ 与数据库、ES 无全局原子事务；Outbox 已覆盖“文档已提交但消息发送失败”的恢复，但没有自动死信重放、已发送消息长期未消费的恢复和完整索引对账。测试环境可验证局部流程，完整外部链路要另行联调。
- 当前 CORS 允许的方法列表缺少 `PATCH`，从 5500 跨域调用“移组”可能被浏览器预检挡住；需增加 `PATCH` 并测试，不能把前端移组说成已在浏览器跨域稳定验收。
- JWT 从 WebSocket URL 查询参数传递；演示账号/密码和默认 JWT 密钥不适合生产。授权依赖组织标签与角色，不是正式的组织、用户、角色、权限管理后台。

面试介绍建议用“业务目标 → 数据与调用链 → 为什么选这些组件 → 如何保证一致性与权限 → 出故障如何定位 → 当前不足与下一步”六步回答。详细源文件可从 `KnowledgeController`、`DocumentSubmissionService`、`DocumentJobPublisher`、`KnowledgeSearchService`、`PracticeAgentService`、`AgentOrchestrator` 和 Mapper 依次阅读。
