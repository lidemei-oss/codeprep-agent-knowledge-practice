# CodePrep RAG Agent｜知识问答与刷题

先看 [端到端设计流程](docs/design-flow.md)；想按后端知识逐步学会项目，可读 [后端学习手册](docs/backend-study-guide.md)；需要逐接口、逐表查阅时，见 [项目全链路详解](docs/project-deep-dive.md)。接口与启动命令见 [API 说明](docs/api.md)，表结构与 Mapper 分层见 [数据层说明](docs/database.md)。

两个业务模块：

- 知识问答：私有资料、RAG 检索、增强 Prompt、引用回答、SSE/WebSocket 流式输出。
- 刷题训练：知识库出题、答案评测、弱点反馈、掌握度和幂等重试。
- 文档分组：上传时指定分组，已有文档可移动分组；刷题时只从所选组的 READY 文档出题。



## Agent 运行方式

统一入口 `POST /api/agent/runs` 接收学习任务。规划器在知识检索、开始刷题、评估答案三个基础动作外，还能在请求带有刷题 `practiceSessionId` 且不带答案时执行 `REVIEW_MISTAKES` Skill。它读取本人该会话的答题记录，归纳重复缺失的知识点，从私有库检索复习依据，给出引用复盘并创建 3 题针对性复练会话。执行器记录规划、工具调用、观察结果的轨迹。知识问答在召回后通过 DeepSeek 判断证据充分性，按相关性选取最少必要片段；候选非空但证据不足也会改写问题重试一次，再按设置搜索公开网页。私有及公开片段都须通过证据检查；无依据返回 `NEEDS_SOURCE`，筛选故障返回 `EVIDENCE_VERIFICATION_UNAVAILABLE`，不继续生成或自动联网。生成后校验引用编号，缺失或越界标记 `REVIEW_REQUIRED`。运行记录可用 `GET /api/agent/runs/{runId}` 查询。

这是**有边界的 RAG Agent**，不是任意工具、自主无限循环的通用 Agent。错题复盘是一个有前置条件、证据检索、归因、生成和校验的业务 Skill；单次运行仍有明确步骤上限。统一 Agent 接口返回完整 JSON，直接问答接口才提供逐片段流式响应。

私有知识库零召回并改写重试后，Agent 工作台及普通/流式问答默认调用 Tavily Search。问题会发送给 Tavily，网页摘要与 URL 作为外部证据进入 RAG Prompt；同时问题与检索片段会发送给配置的模型服务。可在页面取消勾选，或在 API 传 `allowWebSearch=false`，对单次请求关闭公开搜索。敏感问题请关闭联网。

## 技术主链路

资料上传后由 Apache Tika 解析（Markdown 文件保留原始标题和代码围栏），按标题层级、段落和代码块组织片段；超长内容按段落、句子等边界递归切分，同章节短段落合并，相邻片段保留部分上下文。默认最大约 700、最小约 160、重叠约 80 个**估算 Token**；这是本地保守估算，并非 Qwen 官方 tokenizer 的精确计数。RabbitMQ 默认开启；上传时在一个 MySQL 事务中提交文档与 Outbox 待投递任务，接口随即返回 `PROCESSING`。后台发布器只发送 `documentId` 到持久化队列，断线时按退避时间重试；消费者执行解析、切片、向量化和索引，成功后 ACK，失败最多尝试三次并进入死信队列。Qwen Embedding 通过 OpenAI-compatible `/v1/embeddings` 接口生成 2048 维向量。

消费者在清理旧切片前，先通过 MySQL 条件更新领取文档处理权：只有 `PROCESSING` 且无人领取（或旧租约已过期）的文档能被领取；更新行数为 0 的重复消息不会进入清理/重建。完成和失败状态也按领取令牌条件更新。处理期间定期续租；若消费者宕机导致租约过期，对账任务将已发送的 Outbox 重新置为待投递。已有 MySQL 数据库启动时会补齐领取字段。该机制解决正常租约内的并发消费竞争，不等于 MySQL 与 ES 的跨系统恰好一次事务；极端租约失效时仍需版本化索引做完全隔离。

Markdown 的标题与代码围栏可直接识别；Word/PDF 经 Tika 转成纯文本后，标题层级依赖编号、章节和独立问句等规则推断，原始排版样式暂不保留。新策略只作用于新上传的文档；已有 ES 片段需重新上传文档才能按新规则建索引。

查询阶段分别执行 Elasticsearch IK/BM25 关键词召回和 KNN 向量召回，按过滤条件限制组织标签与知识域，再使用 RRF 与原始分数补偿融合。前 8 条用于候选召回，不能直接视为答案依据。HTTP、SSE、WebSocket 与 Agent 知识问答共用 `GroundedKnowledgeService`，调用 `EvidenceSelectionService` 按原问题审核候选，只把筛选通过的片段写入增强 Prompt。普通问题尽量使用 1～3 条，多知识点问题最多 5 条；模型返回的 ID 必须属于本次候选且不重复。筛选结果和每次原始候选保存在 `retrieval.evidence` 或 Agent 的 `evidence` 中，原始召回数量不会被精选数量覆盖。

证据审核默认限时 30 秒，使用 JSON 模式、最多 1024 输出 Token；官方 DeepSeek 接口为审核关闭思考，普通回答参数保持原有配置。超时、模型关闭、无效 JSON、未知 ID 或上下文预算不足均按故障处理。必要证据放不进 Prompt 时停止生成，不静默截断。配置项为 `CODEPREP_EVIDENCE_TIMEOUT_MS`、`CODEPREP_EVIDENCE_MAX_SELECTED`、`CODEPREP_EVIDENCE_MAX_CANDIDATE_CHARS`。审核增加模型调用和等待时间，且仍属于模型判断，不能等同事实正确性保证。刷题的题源召回仍使用原有检索服务，本次新增门控针对知识问答。

安全方面使用 Spring Security + JWT。JWT 带有角色和组织标签；HTTP 接口由 JWT Filter 认证，WebSocket 握手必须带 `?token=<JWT>`，并在消息处理前复用组织标签 Guard。Redis 用于会话上下文和问答限流；刷题 requestId 幂等结果随会话快照保存到 MySQL。答题先在短事务中保存评测占用，再在事务外调用模型，最后用短事务提交成绩；并发请求不会重复评测同一会话，评测中的重试返回 HTTP 409。

分组在“资料类别 + 组织标签”内生效，原有文档归入“默认分组”。刷题的 BM25、KNN 和回退检索都通过所选组的文档 ID 限定范围；错题复练沿用原刷题组。普通知识问答仍按组织标签和类别检索，不受刷题分组选择影响。MyBatis-Plus 将账号、文档元数据/分组、切片文本、刷题会话/答题记录和 Agent 轨迹保存到 MySQL；ES 仍保存向量索引，Redis 保存短期对话上下文。旧 `data/documents.json` 启动时按 ID 幂等导入，原文件保留且不再写入。旧 ES 片段不会反向写入 MySQL；若本地切片为空，刷题样本可从 ES 读取。

## 快速启动

```text
docker compose up -d mysql redis rabbitmq elasticsearch
mvn spring-boot:run
cd frontend
python -m http.server 5500 --bind 127.0.0.1
```

打开 `http://127.0.0.1:5500`。演示账号：`alice / alice123`，只读账号：`reader / reader123`。
前端把 JWT 暂存于当前浏览器标签页的 `sessionStorage`，刷新页面会恢复登录；关闭标签页或点击“退出登录”会清除该登录状态。文档处于 `PROCESSING` 时，列表每 3 秒自动更新，直到变为 `READY` 或 `FAILED`。不要在共享电脑上保留已登录的标签页。

默认连接 MySQL，并开启 ES、RabbitMQ、Redis、Qwen Embedding、DeepSeek 和 Tavily 公开搜索。先启动 MySQL/ES/RabbitMQ/Redis，准备 Qwen Embedding 服务及 DeepSeek、Tavily 密钥；MySQL 不可用会使应用启动失败。首次启动执行 `schema.sql` 建表并导入旧文档元数据（如果存在）。本项目 MySQL 默认映射宿主机 `3307`（避开已有 3306 服务），本地演示账号为 `root/1234`；RabbitMQ 为 `codeprep/codeprep`，仅适合本机。RabbitMQ 管理界面位于 `http://127.0.0.1:15672`。默认 Qwen 模型为 Qwen3-Embedding-4B，客户端将输出裁剪、归一化为 2048 维。先上传资料并等待文档为 READY，再运行 Agent。完整链路配置见 [API 与启动说明](docs/api.md)，分层与表设计见 [MyBatis-Plus 数据层](docs/database.md)，整体设计见 [架构说明](docs/architecture.md)。

## 关于参考仓库

参考了 [xkcoding/spring-boot-demo](https://github.com/xkcoding/spring-boot-demo) 中的 RBAC Security、Elasticsearch、RabbitMQ、WebSocket、Redis、上传、异步和 Actuator demo，但没有把所有 demo 直接堆进业务代码；对应取舍见 [参考映射](docs/spring-boot-demo-reference.md)。

## 当前边界

这是可运行的学习型 Agent 骨架：Controller → Service → MyBatis-Plus Mapper 分层负责关系型数据；ES、Redis、RabbitMQ 由独立网关/组件负责。账号、文档、Outbox 待投递任务、刷题记录和 Agent 轨迹现可跨重启读取；上传分片进行中的临时状态仍在进程内，重启后不能续传同一 uploadId。已有 H2/MyBatis-Plus 持久化、Outbox 原子提交与重试、文档领取竞争、规划、零召回、组织标签权限和 HTTP 流程的自动化测试；真实 MySQL 与 RabbitMQ/ES/模型全链路仍需按本机服务状态验证。生产部署仍需补充正式账号管理、密钥管理、审计日志、通用数据库版本迁移、消费者停滞监控、跨 MySQL/ES/RabbitMQ 的补偿和多实例 WebSocket 广播。


本项目将持续更新和完善。
