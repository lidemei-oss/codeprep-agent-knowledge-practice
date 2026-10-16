# 项目设计与端到端流程

## 1. 定位与模块

CodePrep 是面向 Java 后端学习的**受控 RAG Agent**。对用户只提供两项业务：私有知识问答和按文档分组刷题；统一 Agent 入口负责识别意图、选择工具、记录执行轨迹，还能调用错题复盘 Skill。它不执行任意工具，也不是无限自主循环的通用 Agent。

代码按业务域组织，而不是把所有类堆在全局 `controller/service/mapper` 文件夹：

| 目录 | 职责 | 关键入口 |
| --- | --- | --- |
| `auth`、`config` | 登录、JWT、角色/组织标签授权、WebSocket 握手 | `AuthController`、`SecurityConfig`、`OrgTagGuard` |
| `knowledge` | 文档上传、分片合并、RabbitMQ 消费、解析切块、检索与问答 | `KnowledgeController`、`KnowledgeDocumentService`、`DocumentIngestionService`、`KnowledgeAgentService` |
| `practice` | 题目生成、作答评测、会话状态与幂等重放 | `PracticeController`、`PracticeAgentService` |
| `agent` | 意图规划、有限步编排、错题复盘、运行轨迹 | `AgentController`、`AgentOrchestrator`、`MistakeReviewSkill` |
| `persistence/entity`、`persistence/mapper` | MySQL 的实体和 MyBatis-Plus Mapper | `DocumentMapper`、`ChunkMapper`、`PracticeSessionMapper` 等 |
| `infra` | ES、Qwen、DeepSeek、Tavily、Redis 的外部适配 | `ElasticsearchKnowledgeGateway` 等 |
| `frontend` | 登录、上传/分组、知识问答、Agent 工作台和刷题页面 | `index.html` |

HTTP 的共通入口是：前端携带 JWT → Spring Security Filter 还原用户 → Controller 校验参数 → Service 执行业务与组织标签权限 → Mapper/基础设施组件 → 响应。Controller 不直接调用 Mapper；MyBatis-Plus 只负责关系型数据，ES/Redis/RabbitMQ 不经过 Mapper。

## 2. 数据归属

| 存储 | 保存内容 | 为什么放在这里 |
| --- | --- | --- |
| MySQL | 账号、文档元数据/分组、待投递任务（Outbox）、切片文本、刷题会话/答案/幂等结果、Agent 轨迹 | 需要可靠保存并可在重启后读取的业务状态 |
| Elasticsearch | IK/BM25 字段、2048 维向量、组织标签/类别/文档 ID 过滤字段 | 全文与语义召回 |
| Redis | 问答会话短期上下文（6 小时）与限流计数 | 有 TTL 的高频临时状态；故障时有进程内降级 |
| RabbitMQ | 文档异步处理任务（消息体仅 `documentId`）与死信 | 解耦上传响应和耗时入库 |
| 本地磁盘 | 上传原文件、分片临时文件 | Tika 解析与失败重试需重新读取原文 |

MySQL 由 `schema.sql` 初始化。旧 `data/documents.json` 只作为幂等导入来源，不再承担写入；旧 ES 切片仍可检索，但不会自动复制到 MySQL 的 `cp_chunk`。详见 [数据层说明](database.md)。

## 3. 启动与认证

1. `docker compose up -d mysql redis rabbitmq elasticsearch`，项目 MySQL 默认映射到宿主机 3307，避免占用已有 3306。另行准备 Qwen Embedding 服务及 DeepSeek/Tavily 密钥。
2. Spring Boot 建立 MySQL 连接、执行 `schema.sql`；`AccountService` 仅在演示账号缺失时写入 BCrypt 哈希；`KnowledgeStore` 导入旧文档元数据。
3. `DocumentIngestionService` 检查并创建 ES 索引；RabbitMQ 配置声明主交换机、持久化队列、死信交换机和死信队列。
4. 用户调用 `POST /api/auth/login` 得到 JWT。之后的 HTTP 请求走 JWT Filter；知识域操作还要经过 `OrgTagGuard`。WebSocket 握手使用 token，再于消息处理时检查组织标签。

## 4. 上传资料：同步接收、异步入库

```text
普通上传 / 分片合并
  → Controller 校验写权限
  → DocumentUploadService 保存原文件
  → DocumentSubmissionService 在同一 MySQL 事务写 cp_document(PROCESSING) + cp_document_outbox(PENDING)
  → 上传接口返回 PROCESSING；前端轮询状态
  → DocumentJobPublisher 定时领取 Outbox 任务，发布 documentId 并等待 broker confirm/return
  → 确认成功将 Outbox 标为 SENT；失败则按退避时间重试
  → RabbitMQ 主队列
  → DocumentJobConsumer 读取文件并调用 DocumentIngestionService
  → Tika/Markdown 解析 → 标题/段落切块 → Qwen Embedding
  → cp_chunk 保存文本 + ES 保存文本和向量
  → ES 与 MySQL 状态改为 READY → 手动 ACK
```

普通上传用 `POST /api/knowledge/documents/upload`。分片上传先 `POST /api/knowledge/uploads` 获取 uploadId，再按序调用 `PUT /api/knowledge/uploads/{id}/chunks/{index}`，最后 `POST /api/knowledge/uploads/{id}/complete` 合并；合并后进入同一异步链路。上传接口返回表示原文件已保存，文档和待投递任务已提交到 MySQL，**不表示** RabbitMQ 已收到消息，更不表示文档已可检索；前端应轮询状态直到 READY 或 FAILED。

网络断开、confirm 超时或消息不可路由时，Outbox 保持待处理并指数退避重试；发布进程在确认后、标记 `SENT` 前崩溃也可能重复投递，所以不能宣称“恰好一次”。历史遗留的、没有 Outbox 行的长期 `PROCESSING` 文档会由定时对账补建任务；原文件已丢失时标为 `FAILED`。消费者遇到异常会清理可能残留的切片并在一次投递内最多尝试三次；最终失败将文档标为 FAILED，并 `NACK(requeue=false)` 进入死信队列。已有 READY 的重复消息直接 ACK。Outbox 解决“文档已提交但消息未发出”的断点；MySQL、文件、ES 和 RabbitMQ 仍没有全局事务，死信、消费者长时间停滞和跨资源不一致仍需监控与补偿。

## 5. 知识问答：检索、证据、生成

入口有 `POST /api/knowledge/chat`（完整 JSON）、`GET /api/knowledge/chat/stream`（SSE）、`/ws/agent` 的 `KNOWLEDGE_ASK`（WebSocket）。它们调用 `KnowledgeAgentService`；统一 Agent 的问答分支走 `AgentOrchestrator`，检索原则相同。

```text
问题 + orgTag/category
  → Qwen 对问题向量化
  → ES IK/BM25 关键词召回 + KNN 向量召回
  → READY/组织标签/类别过滤 → RRF 融合排序
  → 无证据时改写问题并重试一次
  → 仍无证据且允许公开搜索时调用 Tavily
  → 证据片段 + 最近会话历史 + 问题组成增强 Prompt
  → DeepSeek 生成答案 → 引用与检索轨迹返回
```

正常路径优先私有知识库。ES 报错时可读取 MySQL 的 READY 切片文本并由 Java 做简易关键词降级；若两者都不可用，返回 `PRIVATE_INDEX_UNAVAILABLE`，**不会**把检索故障当作零命中后自动发送问题给 Tavily。真正零召回时，公开搜索默认开启，可用 `allowWebSearch=false` 单次关闭。网页摘要/URL 与问题会发给所配置的模型服务；敏感资料应关闭公开搜索。WebSocket/SSE 逐片返回 `delta`，并带检索信息及引用。

## 6. 分组刷题与错题复盘

1. `GET /api/knowledge/groups` 列出当前用户可见分组。上传时可指定 `groupName`，已有文档可通过 `PATCH /api/knowledge/documents/{id}/group` 移组，不必重算向量。
2. `POST /api/practice/sessions` 可指定 `category/orgTag/groupName/targetCount`。Service 只从所选组的 READY 文档检索题源；组内无可用资料则拒绝出题。DeepSeek 尝试生成单题，输出不合规则回退到固定题干。
3. 会话、当前题、依据片段、答题记录与 requestId 幂等结果保存到 MySQL。`POST /api/practice/sessions/{id}/answers` 分三段：短事务 `SELECT ... FOR UPDATE` 校验并保存 `pending` 占用 → 事务外调用 DeepSeek 评测并生成下一题（模型失败可规则评分）→ 短事务再次锁行、核对占用令牌并保存结果。相同 requestId 在处理中返回 HTTP 409，不会重复评测；完成后重试返回已存结果。异常会尝试释放占用，进程在评测中崩溃留下的占用不自动过期，需要人工确认后恢复。
4. 统一 Agent 的错题复盘 Skill 读取本人历史答题，归纳缺失概念，**只检索私有资料**，给出引用复盘并创建针对性复练会话，不把错题自动发送给 Tavily。

## 7. 统一 Agent 与直接接口的区别

`POST /api/agent/runs` 先由 `AgentPlanner` 识别为知识问答、开始刷题、评测答案或错题复盘，再由 `AgentOrchestrator` 调用对应 Service。每步记录动作与观察，最多 8 步；问答答案还检查引用编号是否对应已召回片段。运行结果与轨迹保存到 MySQL，可用 `GET /api/agent/runs/{runId}` 按本人身份读取。直接问答接口适合流式聊天；统一 Agent 入口适合需要工具路由、刷题和轨迹的任务。

## 8. 边界与验证

自动化测试使用 H2 的 MySQL 兼容模式，覆盖 Controller、Mapper 持久化、旧 JSON 幂等导入、刷题重放等。真实 MySQL 已验证建表、登录和跨应用重启读取；完整的 ES/RabbitMQ/Qwen/DeepSeek/Tavily 联调要在这些服务全都可用时单独验证。

当前还不是生产系统：演示账号由程序初始化；分片上传中的临时 uploadId 状态仍在内存；死信没有自动重放；`schema.sql` 不是版本化迁移；跨 MySQL/ES/RabbitMQ 的一致性需要补偿；WebSocket 多实例广播与审计仍待建设。
