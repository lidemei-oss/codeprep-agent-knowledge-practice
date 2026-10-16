# 参考 xkcoding/spring-boot-demo 的可复用知识

参考仓库把 Spring Boot 的集成点拆成了可独立运行的 demo。新版只吸收和本项目两条业务链直接相关的部分：

| 参考 Demo | 在本项目的落点 |
|---|---|
| `demo-rbac-security` | SecurityFilterChain、JWT、RBAC 角色和动态组织标签权限 |
| `demo-elasticsearch` | 索引创建、mapping、全文查询；本项目进一步加 dense_vector/KNN |
| `demo-mq-rabbitmq` | 文档任务队列、发布确认、手动 ACK 和死信处理 |
| `demo-websocket` | 前后端长连接；本项目把服务器状态推送改为 Agent 流式事件 |
| `demo-cache-redis` / `demo-ratelimit-redis` | 会话上下文、检索缓存、AI 限流和幂等键 |
| `demo-upload` / `demo-async` | 上传落盘、异步处理；本项目增加 Tika、分片合并和 RabbitMQ worker |
| `demo-actuator` | 健康检查和指标入口 |

原仓库是 Spring Boot 集成学习集合，不应直接把所有 demo 复制到业务项目中；本项目只保留能解释 Agent 主链路的技术，并把每个技术放进真实业务流程。

参考地址：[xkcoding/spring-boot-demo](https://github.com/xkcoding/spring-boot-demo)。
