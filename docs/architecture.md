# CodePrep RAG Agent 架构

本文聚焦技术取舍；按请求顺序讲解的完整流程见 [项目设计与端到端流程](design-flow.md)，关系型表和 Mapper 见 [数据层说明](database.md)。

新版只保留两个业务模块：

1. `knowledge`：资料上传、Tika 解析、切片、Qwen Embedding、ES 混合召回、增强 Prompt、DeepSeek 普通/流式回答。
2. `practice`：从知识库召回题源、生成题目、提交答案、概念覆盖与深度评分、掌握度更新、requestId 幂等重放。

`agent` 是横跨这两个业务模块的任务编排层。`AgentPlanner` 从检索问答、开始刷题、评估答案三个基础动作中选取一种；请求只带 `practiceSessionId` 时执行 `REVIEW_MISTAKES`。模型提议失败时退回规则路由。`AgentOrchestrator` 记录规划/执行/观察轨迹（最多 8 步），问答零召回时改写问题再检索一次，然后自动搜索公开网页；所有来源都无依据才以 `NEEDS_SOURCE` 停止。有资料时拼接 RAG Prompt，生成后校验引用编号。引用检查只验证编号是否对应召回列表，不能证明内容真实。错题复盘是跨刷题和私有知识检索的受控 Skill；不支持任意外部工具调用。

`MistakeReviewSkill` 的流程是：核对练习会话归属与知识域 → 汇总每题缺失概念（重复出现者优先）→ 从当前私有知识域检索证据 → 生成带引用的复盘或返回资料导读 → 基于已召回片段创建 3 题聚焦复练。没有答题记录返回 `NEEDS_PRACTICE_HISTORY`；资料不足返回 `NEEDS_SOURCE`；私有索引故障返回 `RETRIEVAL_UNAVAILABLE`，不会将错题内容自动发送至公开搜索。`skill` 字段包含原会话、薄弱点和新复练会话 ID。练习会话与答题记录现通过 MyBatis-Plus 保存到 MySQL，可跨重启读取。

文档分组隶属于资料类别和组织标签。上传和分片上传都可指定 `groupName`；现有文档可移组且不需要重新生成向量。文档元数据与分组保存在 MySQL，旧 `data/documents.json` 仅在启动时幂等导入，原文件不删除。刷题会话保存所选组，检索时先取该组 READY 文档 ID，再把 ID 集合作为 ES BM25/KNN 的 `terms` 过滤条件；MySQL 切片文本及 ES 样本回退也使用相同组过滤。错题复练沿用原会话的组，不会混入其他组的题源。普通问答仍在可访问的组织标签和类别内检索全部组。

公开搜索是用户确认后默认开启的回退路径：`私有检索 → 改写重试 → Tavily Search → 网页摘要与 URL → RAG Prompt → 答案`。单次请求可用 `allowWebSearch=false` 关闭；搜索无结果或服务不可用时仍返回 `NEEDS_SOURCE`。公开网页不自动写入私有索引；HTTP、SSE 和 WebSocket 问答均遵循此流程。

如果 Elasticsearch 报错且 MySQL 中也没有可用切片文本，会返回 `RETRIEVAL_UNAVAILABLE`（直接问答则提示检索不可用），不会把故障误判为“私有库没答案”并自动公开搜索。

基础设施不再作为业务模块暴露：

- MySQL + MyBatis-Plus：`Controller → Service → Mapper` 分层；账号、文档元数据/分组、切片文本、刷题会话快照（含 requestId 幂等结果和评测占用）和 Agent 轨迹落库。`schema.sql` 在启动时建表，旧 JSON 元数据只导入缺失 ID。答题使用两个短事务与 `SELECT ... FOR UPDATE` 占用/提交会话，DeepSeek 调用在两个事务之间完成。
- Redis：会话上下文与问答限流；Redis 不可用时退回进程内上下文。
- RabbitMQ + MySQL Outbox：上传完成时在同一事务写文档记录和待投递任务，后台发布器只发送 `documentId`，等待 broker 确认并检查路由；失败按指数退避重试，长期缺失任务由对账补建。消费者重新读取文件并执行 Tika → chunk → embedding → ES，成功后手动 ACK。处理失败最多尝试三次，随后 NACK 且不重入队，由死信交换机转入死信队列。关闭 RabbitMQ 时自动使用 `@Async`。
- Elasticsearch：索引使用 IK analyzer；KNN 使用 `dense_vector(2048)`，关键词侧使用 `multi_match` BM25，最终用 RRF 加 BM25 分数重排。
- WebSocket：`/ws/agent?token=<JWT>`，握手拦截器校验 JWT，消息处理前再次经过组织标签校验；流事件包括 `retrieval`、`delta`、`done`。
- Spring Security：JWT 中写入 roles 和 orgTags，权限分成 `ROLE_OWNER/EDITOR/READER`、`PERM_KNOWLEDGE_READ/WRITE`、`ORG_TAG:<tag>`。

## 一次知识问答

`question -> Qwen-Embedding(2048) -> ES KNN + BM25 -> RRF/BM25 rerank -> context window -> prompt -> DeepSeek stream -> citations`

`RagPromptBuilder` 会把问题、最近 6 条会话上下文和召回片段拼为可审计的增强 Prompt，并限制上下文字符数，避免大文档挤爆模型窗口。

## 文档处理状态

`PROCESSING -> READY` 或 `PROCESSING -> FAILED`。ES 中片段先标为 `PROCESSING`，整篇索引完成后才发布为 `READY`；失败时尝试清理。Outbox 让文档记录和“需要投递”的意图原子提交；RabbitMQ 发布确认只证明 broker 接收，不能证明文档处理完成。文件系统、ES 和消息系统没有全局事务。确认超时可能造成重复投递，READY 消息直接跳过；并发重入处理和死信任务仍需进一步保护、排查或重放。

关系型状态已落入 MySQL；ES 向量索引、磁盘原文件与 RabbitMQ 消息不在同一数据库事务中，仍需巡检与补偿。分片上传中的临时 uploadId 状态仍是进程内 Map，重启后不能接着使用同一 uploadId。测试中关闭外部服务后的 fallback 路径不等于真实 MySQL+ES+IK、RabbitMQ、Qwen、DeepSeek、Tavily 全链路联调。生产化仍需数据库版本迁移、审计、密钥托管、死信重放和多实例一致性。
