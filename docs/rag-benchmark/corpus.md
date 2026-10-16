# Frozen corpus for retrieval benchmark

SHA256: `9600fa59e021c5d4d7814e235ca7d21f213cc6fc154ec705611ec78be8a5c80c`

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-0 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料
本资料为检索实验独立编写，没有使用用户已上传文档。下文示例参数仅属于本资料，不代表项目实际配置。每节提供一组明确的检索证据。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-1 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 01 相等对象与哈希键
哈希容器先按哈希值定位桶，再比较键是否相等。两个对象的 equals 判定相等时，hashCode 必须一致；哈希值相同不保证对象相等。键入容器后不应修改参与相等比较的字段。示例订单键只使用不可变订单编号，不使用可变订单状态。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-2 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 02 哈希桶树化
HashMap 冲突桶可以从链表转成红黑树。常见树化条件是链表节点达到 8 个且数组容量至少 64。容量不足时优先扩容以分散冲突。树化改善大量冲突时的查找成本，不让 HashMap 自动具备线程安全性。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-3 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 03 并发容器与复合操作
ConcurrentHashMap 保证受支持单次操作的并发安全，先读取再判断再写入不会自动成为一个原子整体。单键条件更新可考虑 compute 或 putIfAbsent。若还涉及库存扣减和新增订单两份记录，应通过数据库事务维护约束。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-4 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 04 可见性与自增
volatile 用于可见性和内存顺序保证，自增包含读取、计算和写回，因此 volatile 不能使 count++ 成为原子操作。共同增加计数可使用 AtomicInteger。多个字段要一起改变时，仍可能需要锁或事务。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-5 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 05 比较交换与版本号
CAS 比较当前值和预期值，再决定是否更新。值从 A 变成 B 又变回 A，单纯比较当前值会漏掉中间变化，即 ABA 问题。可以把版本号和数值一起比较。高争用导致反复自旋重试，消耗 CPU。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-6 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 06 工作线程与排队顺序
示例线程池核心线程 4 个、最大线程 8 个、队列容量 16。少于核心数量时创建线程，达到核心数量后尝试入队，队列满才扩张到最大数量。最大线程和队列都满时执行拒绝策略，不会一开始就创建 8 个线程。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-7 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 07 调用方运行拒绝策略
CallerRunsPolicy 在线程池未关闭但无法接收任务时，让提交任务的调用线程执行任务，拖慢提交速度，形成一定背压。如果调用者是 HTTP 请求线程，该请求也会更慢。该策略不保证线程池关闭后仍执行新任务。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-8 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 08 异步方法与代理边界
Spring @Async 一般通过代理拦截生效。同一个对象直接调用自己的异步方法可能绕过代理，因此仍在当前线程执行。可以把异步处理放到另一个 Bean，由注入的代理接收调用。异步线程异常也需单独观察。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-9 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 09 声明式事务回滚
默认声明式事务通常对 RuntimeException 和 Error 回滚。受检异常是否回滚需配置。捕获异常后不重新抛出或标记回滚，事务可能仍提交。数据库事务不能自然回滚磁盘、外部模型和 ES 的操作。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-10 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 10 模型评测事务边界
示例评测先用短事务登记会话编号、requestId 和 PENDING 状态，提交后在事务外等待模型，再用第二个短事务保存结果。重复请求正在处理时返回处理中，完成后重放旧结果。等待模型期间不持有数据库行锁。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-11 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 11 联合索引顺序
示例联合索引按 tenant_id、status、created_at 排序。查询前导列可利用最左前缀，只按 created_at 查询通常不能同样利用完整前缀。应看 EXPLAIN 验证执行计划，不能用 SQL 条件书写顺序判断是否走索引。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-12 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 12 覆盖索引与回表
InnoDB 二级索引通常包含索引列和主键。所需字段全部在索引中时，覆盖索引可以减少回表。按租户和状态查订单 id 可能由索引覆盖；查大段正文通常仍需通过主键读取整行。更多索引增加写入及空间成本。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-13 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 13 一致性读与版本视图
读已提交通常每次一致性读建立新的 Read View，可重复读通常沿用同一事务首次一致性读的视图。第二次查询是否看到别人的新提交取决于隔离级别及读取方式。加锁读取属于当前读，不能当作快照读解释。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-14 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 14 三类数据库日志
undo 支持回滚和历史版本读取，redo 帮助崩溃恢复页修改，binlog 用于复制及恢复。撤销事务涉及 undo，恢复页变更涉及 redo，把变化传给副本涉及 binlog。它们各有职责，不是同一份日志的不同名字。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-15 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 15 本地事务事件表
示例上传在同一个数据库事务插入文档记录和 Outbox 事件。发布器读取未发送事件发往队列，失败则保留并重试，恢复数据库已提交但消息未送达的情况。收到发送确认但尚未更新事件状态时可能重复发送，需要消费端幂等。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-16 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 16 发送确认与消费确认
RabbitMQ publisher confirm 是 broker 对发布的确认，不代表解析完成。消费者手动 ACK 表示应用已完成消费。示例只有切片、向量化和索引发布都完成才 ACK，不能收到发布确认就把文档设为 READY。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-17 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 17 文档处理租约
示例条件 UPDATE 在文档为 PROCESSING 且无有效租约时写 owner token，影响行数为 1 才领取成功。每 20 秒续租，示例租约 2 分钟。宕机后续租停止，过期后可重新领取；旧 token 不能更新完成状态。这是逻辑资格，不是长期数据库行锁。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-18 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 18 重试与死信
示例最多尝试 4 次，失败间隔为 2 秒、4 秒、8 秒。超过次数移入死信队列，保存任务编号和错误原因。死信供分析和人工重放，不意味着处理成功。重试需幂等，否则可能产生重复切片。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-19 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 19 段落切分与上下文
示例先识别标题和段落，同章节短段落合并，超长段落按句子边界递归拆分。每块最大 600 个估算 Token，重叠 60 个估算 Token。重叠缓解断句但带来重复证据；估算预算不是官方 tokenizer 的精确计数。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-20 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 20 关键词相关性
BM25 根据词频、词项稀有程度及文档长度计算相关性，适合精确类名、错误码和方法名。示例 title 权重 3、content 权重 1。IK 中文分词影响词项匹配，但分词不理解整个问题的语义。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-21 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 21 语义近邻
向量检索将问题和切片映射到同一空间，支持不同措辞表达相近含义。示例维度 2048，入库和查询模型必须兼容。KNN 找到最近邻不代表存在足够回答问题的证据，需要额外判断相关性。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-22 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 22 倒数排名融合
标准 RRF 对同一切片累加各路 1/(60+rank)，未被某一路召回的贡献为零。排名融合减少原始分数尺度不同的影响，两路靠前的切片更有优势。另加原始分数补偿就不是纯 RRF，需验证权重是否压过排名贡献。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-23 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 23 会话上下文存储
示例问答消息保存在 Redis，过期时间 90 分钟，长期练习成绩保存 MySQL。上下文支持连续提问，成绩支持跨重启查询。key 包含用户和知识域。整体读出再写回消息列表可能丢失并发追加，可改用原子追加。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-24 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 24 固定窗口计数
示例每用户每 60 秒最多 20 次请求。INCR 与首次 EXPIRE 分开执行，中间故障可能留下无过期计数键。用 Lua 原子执行计数、首次设置过期和放行判断。固定窗口有边界突发，不等于平滑令牌桶。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-25 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 25 流式通信方向
SSE 在 HTTP 上从服务端单向发送事件，WebSocket 握手后建立双向连接。示例页面处理 session、delta、done，将 delta 逐段追加。流式传输改善首段等待体验，不保证整段生成总耗时更低。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-26 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 26 无证据与回答生成
知识助手应判断片段是否足以支持问题，没有依据时返回 NEEDS_SOURCE。非空列表不等于有证据。引用编号存在也不代表内容支持结论。检索错误和生成错误需分别定位。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-27 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 27 身份与资源权限
JWT 验签与过期检查识别身份，角色及组织标签控制知识域访问。标签进入索引过滤条件，在召回前限制范围。隐藏前端按钮不足以防越权。签名不等于加密，不应把密钥放进可读取的载荷。

## 1f6489b5-28be-41fe-97c9-4e513f1f2057-28 | rag-benchmark-java.md

文档：rag-benchmark-java.md
章节：独立 Java 后端检索评测资料 > 28 上传分片状态
示例维护 uploadId、总片数、已收序号、总大小和用户归属，单片上限 2 MiB、总文件上限 40 MiB。分片齐全后按序合并并核对总大小，还可验证哈希。任务索引只在内存时重启后无法可靠续传。
