# 主要接口

先登录：`POST /api/auth/login`，演示账号 `alice/alice123`，读者账号 `reader/reader123`。

统一 Agent 入口：`POST /api/agent/runs`，请求示例：

```json
{"task":"解释 HashMap 的哈希冲突","category":"java","orgTag":"java"}
```

响应含 `runId`、`sessionId`、`intent`、`status`、`answer`/`question`/`evaluation`、`citations`、`steps`。`GET /api/agent/runs/{runId}` 查询本人轨迹。复用同用户、同组织标签的 `sessionId` 可延续上下文。刷题任务可写 `"task":"根据知识库出一道 Java 题"`；评估时再提交 `practiceSessionId`、`questionId`、唯一 `requestId` 和 `answer`。状态包括 `COMPLETED`、`NEEDS_SOURCE`、`REVIEW_REQUIRED`、`RETRIEVAL_UNAVAILABLE`；最后一种表示私有检索故障，不能当作零命中并触发公开搜索。

知识问答增加证据审核：候选非空但不支持问题时也会改写重试；私有或公开片段只有通过审核才能用于生成。筛选故障返回 `EVIDENCE_VERIFICATION_UNAVAILABLE`，公开搜索故障返回 `WEB_SEARCH_UNAVAILABLE`，两者均停止生成。DeepSeek 关闭时，有候选的问答会提示审核不可用，不绕过门控。

普通问答的 `retrieval.evidence`、流式 `retrieval` 事件的 `evidence`、Agent 结果的 `evidence` 包含最终 `SUPPORTED/INSUFFICIENT/ERROR` 状态与 `attempts`。每次 attempt 保留查询、来源策略、两路召回数、原始 `candidates` 及 `selection`；外层 `citations` 仅包含实际选用证据，并按筛选后的顺序编号。`rerankedHits` 是原始融合候选数，不等于最终引用数。

`privateIndexUnavailable` 保留原始 ES 故障标记：数据库回退片段通过审核时仍可回答；若回退片段不足且 ES 已故障，则停止并提示检索不可用，不能当成真实未命中联网。经用户授权，审核会参考同会话最近六条历史来理解指代，但历史不能替代来源证据。

错题归因 Skill：完成至少一题后，只传原刷题会话 ID，不传 `questionId` 或 `answer`：

```json
{"task":"复盘错题并针对性练习","category":"java","orgTag":"java","practiceSessionId":"<原刷题会话 ID>","allowWebSearch":false}
```

响应的 `intent` 为 `REVIEW_MISTAKES`，`skill` 给出 `skillId`、`weakConcepts`、`sourcePracticeSessionId` 和 `reviewPracticeSessionId`；`answer` 是复盘说明，`citations` 是私有依据，`question` 是新的首道复练题。下一题按普通刷题接口提交答案。原会话须属于当前用户，且 `category`/`orgTag` 一致。没有答题记录时返回 `NEEDS_PRACTICE_HISTORY`，没有可用资料时返回 `NEEDS_SOURCE`；该 Skill 不调用 Tavily。

私有知识库未命中时默认把问题发送给 Tavily Search。若要对某次请求关闭公开搜索，传 `"allowWebSearch":false`；SSE 用同名查询参数，WebSocket 用同名消息字段。未配置 `TAVILY_API_KEY` 时，轨迹会提示搜索未配置，所有来源无证据则返回 `NEEDS_SOURCE`。网页引用包含 `retrievalSource: "WEB_SEARCH"` 和 `url`，不能误认为私有资料。

- `POST /api/knowledge/documents/upload`：上传资料；支持 Tika 能解析的 TXT、Markdown、PDF、DOCX。返回 `PROCESSING` 表示原文件和 MySQL 中的文档/Outbox 待投递任务已提交，消息可能尚未到 RabbitMQ；通过文档列表轮询到 `READY` 后才能用于问答和刷题。
- 上传时可传 `groupName` 查询参数（留空归入“默认分组”）；`GET /api/knowledge/groups` 列出当前用户可见分组及 READY 文档数。`PATCH /api/knowledge/documents/{documentId}/group` 携带 `{"groupName":"缓存"}` 可将已有文档移组，需该组织标签的写权限；无须重新向量化。
- `POST /api/knowledge/uploads`、`PUT /api/knowledge/uploads/{id}/chunks/{index}`、`POST /api/knowledge/uploads/{id}/complete`：分片上传和断点续传；合并后也会原子写入文档和 Outbox 待投递任务。
- `POST /api/knowledge/chat`：返回完整 RAG 回答、引用、检索统计和 Prompt 预览。
- `GET /api/knowledge/chat/stream`：SSE 流式接口。
- `WebSocket /ws/agent?token=<JWT>`：发送 `{"type":"KNOWLEDGE_ASK","question":"...","category":"java","orgTag":"java"}` 可逐片段返回；`AGENT_RUN` 返回一次性 Agent 结果，不逐 token 推送。
- `POST /api/practice/sessions`：创建刷题会话。
- 创建刷题会话时传 `{"category":"java","orgTag":"java","groupName":"缓存","targetCount":3,"difficulty":"medium"}`，仅从该组 READY 文档检索和出题；省略 `groupName` 保留原有全知识域行为。Agent 出题任务也可传 `groupName`。
- `POST /api/practice/sessions/{id}/answers`：提交答案，必须带当前 `questionId` 和唯一 `requestId`。评测期间同会话请求返回 HTTP 409“正在评测”，请稍后用相同 requestId 重试；完成后相同请求会重放已保存结果，不会再次调用模型。

## 本地运行

```text
docker compose up -d mysql redis rabbitmq elasticsearch
mvn spring-boot:run
cd frontend
python -m http.server 5500 --bind 127.0.0.1
```

MySQL 是默认必需的关系型存储；ES、RabbitMQ、Redis、Qwen、DeepSeek、Tavily 的应用开关默认均为 `true`。请先启动 MySQL/ES/RabbitMQ/Redis 并等待就绪，单独准备提供 OpenAI-compatible `/v1/embeddings` 的 Qwen 服务，再设置模型与搜索密钥：

```text
CODEPREP_ELASTICSEARCH_ENABLED=true
CODEPREP_EMBEDDING_ENABLED=true
CODEPREP_LLM_ENABLED=true
CODEPREP_RABBITMQ_ENABLED=true
MYSQL_URL=jdbc:mysql://127.0.0.1:3307/codeprep?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
MYSQL_USER=root
MYSQL_PASSWORD=1234
RABBITMQ_USER=codeprep
RABBITMQ_PASSWORD=codeprep
QWEN_EMBEDDING_BASE_URL=...
QWEN_EMBEDDING_API_KEY=...
QWEN_EMBEDDING_MODEL=Qwen3-Embedding-4B
DEEPSEEK_API_KEY=...
CODEPREP_REDIS_ENABLED=true
CODEPREP_JWT_SECRET=<随机且至少 32 字节的密钥>
TAVILY_API_KEY=<你的 Tavily 密钥>
```

若要在本地禁用某项外部服务，可显式设为 `false`；公开搜索单次关闭还可传 `allowWebSearch=false`：

```text
CODEPREP_ELASTICSEARCH_ENABLED=false
CODEPREP_RABBITMQ_ENABLED=false
CODEPREP_REDIS_ENABLED=false
CODEPREP_EMBEDDING_ENABLED=false
CODEPREP_LLM_ENABLED=false
CODEPREP_WEB_SEARCH_ENABLED=false
```

Qwen3-Embedding-4B 的原始向量会由客户端裁剪、归一化为 2048 维；自定义模型若输出不足 2048 维，上传处理会失败。启用 ES 前等待服务健康，并确认索引带 IK 插件和正确 mapping；现存早期索引可能需要迁移。RabbitMQ 默认声明主交换机、持久化队列和死信队列；管理界面在 `http://127.0.0.1:15672`。默认账号密码只适合本地演示，演示账号和默认 JWT 密钥也不能用于生产。
MyBatis-Plus 启动时按 `schema.sql` 建表（包括 `cp_document_outbox`）；首次运行会把旧 `data/documents.json` 中尚不存在的文档记录导入 MySQL，原文件保留。旧 ES 片段仍可检索，但不会自动反向导入 MySQL 切片表；如需完整切片文本落库，可重新上传旧文档。Outbox 默认每 2 秒扫描待投递任务，发送失败退避重试；对账默认每分钟运行一次，补建超过 30 分钟且缺少 Outbox 行的 PROCESSING 文档任务。可用 `CODEPREP_OUTBOX_*` 环境变量调整相关间隔。Outbox 不负责死信自动重放或已发送但长期未消费任务的恢复。

项目 MySQL 容器默认把宿主机 `3307` 映射到容器 `3306`，以避开本机已有的 3306 服务。若设置 `MYSQL_HOST_PORT` 改变映射，请同时设置匹配的 `MYSQL_URL`。
