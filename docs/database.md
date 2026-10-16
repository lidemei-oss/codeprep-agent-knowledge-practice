# MyBatis-Plus 数据层与调用链

项目以 Spring Boot 3.3.4 + MyBatis-Plus Boot 3 Starter 3.5.17 连接 MySQL 8。`CodePrepApplication` 使用 `@MapperScan` 扫描 `persistence.mapper`；`application.yml` 配置 JDBC URL、账号、密码与下划线到驼峰映射。启动时 `schema.sql` 用 `CREATE TABLE IF NOT EXISTS` 建表。测试使用 H2 的 MySQL 兼容模式，不会连接开发机上的 MySQL。

| 表 | 业务数据 | Service | Mapper |
| --- | --- | --- | --- |
| `cp_account` | 演示账号、BCrypt 哈希、角色和组织标签 | `AccountService` | `AccountMapper` |
| `cp_document` | 文档元数据、分组、处理状态 | `KnowledgeStore` | `DocumentMapper` |
| `cp_document_outbox` | 文档任务投递状态、尝试次数、下次重试时间与领取租约 | `DocumentSubmissionService`、`DocumentJobPublisher`、`DocumentJobReconciler` | `DocumentOutboxMapper` |
| `cp_chunk` | 可用于回退检索的切片文本，不存向量 | `KnowledgeStore` | `ChunkMapper` |
| `cp_practice_session` | 刷题会话快照、题目、答题记录和 requestId 幂等结果 | `PracticeAgentService` | `PracticeSessionMapper` |
| `cp_agent_run` | Agent 运行结果与轨迹 | `AgentRunStore` | `AgentRunMapper` |

Controller 不直接操作 Mapper。`AuthController` → `AccountService` → `AccountMapper`；`KnowledgeController` → `KnowledgeDocumentService`（文档列表、分组、移组）或上传/知识问答 Service → `KnowledgeStore` → 文档/切片 Mapper；`PracticeController` → `PracticeAgentService` → `PracticeSessionMapper`；`AgentController` → `AgentOrchestrator` → 刷题/检索 Service 和 `AgentRunStore` → 对应 Mapper。

文档上传先落磁盘，`DocumentSubmissionService.accept` 在一个 MySQL 事务中写 `cp_document(PROCESSING)` 和 `cp_document_outbox(PENDING)`；任一写入失败都会回滚。接口提交事务后即可返回。后台发布器定时领取待投递行，RabbitMQ confirm 且未 return 才记为 `SENT`；失败时保留 `PROCESSING` 文档并将任务退避重试。发布确认丢失时可能重复投递，消费端对 READY 文档跳过。定时对账还会给历史遗留的无 Outbox `PROCESSING` 文档补建任务。消费者解析文件、生成向量，写 `cp_chunk` 与 ES，全部成功后更新 MySQL 与 ES 的 `READY` 状态再 ACK。处理失败会清理部分切片并重试，最后标记 `FAILED` 并送死信。MySQL、文件系统、RabbitMQ、ES 没有全局事务；上线仍需监控死信、处理停滞与跨资源不一致。

正常检索仍以 ES 的 IK/BM25 + KNN 为主。ES 不可用时，Service 可以从 MySQL 取 READY 切片文本并在 Java 中做简易关键词评分；这只是降级路径，不是 MySQL FULLTEXT 索引，也不能替代 ES 的语义召回。

刷题时按 `orgTag/category/groupName` 从 READY 文档检索，创建的会话快照写入 `cp_practice_session`。提交答案时先在短事务中 `SELECT ... FOR UPDATE`，验证 `questionId/requestId` 并持久化 `pending` 占用；事务提交、行锁和连接释放后才调用 DeepSeek 评测及生成下一题。完成时再开启短事务锁行、核对占用令牌并保存成绩、下一题与幂等结果。同一 requestId 在处理中返回 409，完成后重试返回已存结果；相同会话的不同请求在评测期间也会被挡住，避免双重计分。普通异常会释放占用；进程崩溃遗留的占用不自动过期，需人工确认后恢复。单行 JSON 快照便于演示持久化，但复杂统计和高并发场景应拆成会话、题目、答题等独立表。

旧 `data/documents.json` 仍作为一次性导入来源：创建 `KnowledgeStore` 时按文档 ID 检查 MySQL，缺失才插入；不覆盖现有行，也不删除旧文件。旧 ES 向量片段不自动复制到 `cp_chunk`，仍可在 ES 中检索和采样；要让旧切片文本完整落库，可重新上传原文档。进行中的分片上传状态仍为进程内数据，重启后原 uploadId 无法续传。

启动顺序：`docker compose up -d mysql redis rabbitmq elasticsearch` → 等待容器健康 → 配好 Qwen/DeepSeek/Tavily → `mvn spring-boot:run`。本地 MySQL 默认 URL 为 `jdbc:mysql://127.0.0.1:3307/codeprep`（容器内仍为 3306），默认账号为 `root/1234`；可用 `MYSQL_HOST_PORT` 改 Compose 端口，并同步设置 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`。演示密码和自动建表方案不得直接用于生产；生产应使用独立数据库账号和版本化迁移工具。
