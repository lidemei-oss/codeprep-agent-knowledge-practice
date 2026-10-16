# 证据筛选改造及验证记录

## 已实现

- 普通知识问答（HTTP、SSE、WebSocket）与 Agent 知识问答共用 GroundedKnowledgeService。
- BM25＋KNN 前 8 条保留为候选；EvidenceSelectionService 用 DeepSeek 判断原问题能否被正文支持，选择必要证据并重新排序。
- 普通问题尽量 1～3 条，多知识点最多 5 条。只有返回的合法候选 ID 才能进入 Prompt；拒绝重复、外来 ID、无效/矛盾 JSON。
- SUPPORTED 才生成；INSUFFICIENT 即使候选非空也改写重试，再按设置公开搜索；ERROR 停止回答及公开搜索。
- 筛选超时、模型关闭、格式错误、未知 ID、上下文预算不足均明确返回 EVIDENCE_VERIFICATION_UNAVAILABLE，保留失败原因。
- 原始候选及各次查询统计保存在 evidence.attempts；最终 citations 仅包括真正选用片段。
- 必要证据超出 Prompt 预算时停止生成，不静默丢掉已经选中的片段。
- 经用户授权，将最近六条会话历史发送给审核模型，仅用于指代消解，不作为事实证据。
- ES 故障后若 MySQL 降级候选也缺乏支持，按检索故障处理，不冒充零召回去公开搜索。
- 审核使用 JSON 模式、最多 1024 输出 Token；官方 DeepSeek 请求关闭思考，普通生成配置保持原样。
- 原始 RRF＋分数补偿候选算法没有修改。本次增加的排序发生在证据选择阶段。

## 自动化验证

本地回归：75 个执行用例，2 个显式实服务测试默认跳过；失败 0，错误 0。
覆盖非空弱证据、改写后仍按原问题审核、公开片段筛选、模型超时及关闭、外来/重复 ID、Prompt 预算、普通/流式/Agent 不生成故障回答、八步边界，以及原有上传、Outbox、MyBatis-Plus 持久化和刷题并发流程。

## 实服务验证与限制

只使用自建并通过上传接口入库的 rag-benchmark-java.md，29 个 READY 切片，未使用用户资料。
初版批量审核曾实际完成正常筛选，包括将 Q22 的联合索引证据从原混合第 2 位选到第 1 位；随后出现连续 30 秒超时。已测记录全部保存在 evidence-legacy-results.json，未删除失败样本。该批未完成，不发布整批质量指标。
改为受限 JSON 请求后重新抽查，结果如下：

| 样本 | 审核状态 | 原因 | 最终证据数 |
|---|---|---|---:|
| Q22 | ERROR | MODEL_TIMEOUT | 0 |
| M03 | ERROR | MODEL_TIMEOUT | 0 |
| N04 | ERROR | MODEL_HTTP_503 | 0 |

这证明当前故障路径拒绝把候选当作有效证据，但不能证明新请求参数下的筛选质量已全面通过。DeepSeek /models 可访问；当前模型和服务列出的 deepseek-flash 的极小补全请求均曾超时，且审核返回 HTTP 503。没有修改密钥或擅自切换模型。
本次未完成 68 个回归＋16 个新问题的整批质量复测，因此没有编造新的 Precision、Recall 或拒绝准确率。模型服务恢复后可继续验证。审核仍可能误判，也会增加一次或多次模型调用的延迟。

## 使用与复现

重启后端使代码生效。默认审核期限 30 秒；可用 CODEPREP_EVIDENCE_TIMEOUT_MS、CODEPREP_EVIDENCE_MAX_SELECTED、CODEPREP_EVIDENCE_MAX_CANDIDATE_CHARS 配置。模型关闭时有候选的问答会报告审核不可用，不绕过审核。

```bat
mvn -q -Dcodeprep.build.directory=target-evidence-check test
mvn -q -Dcodeprep.build.directory=target-evidence-check -Dcodeprep.rag.evidence.benchmark=true -Dcodeprep.rag.evidence.smoke=true -Dtest=RagEvidenceLiveBenchmarkTest test
mvn -q -Dcodeprep.build.directory=target-evidence-check -Dcodeprep.rag.evidence.benchmark=true -Dtest=RagEvidenceLiveBenchmarkTest test
python tools\rag_benchmark_report.py summarize-evidence
```

实服务评测会保留 ERROR 记录，连续三次服务故障后停止，避免继续无意义请求；只有完整测量完成后，汇总工具才计算质量指标。当前配置仍是 deepseek-chat，可根据服务实际可用模型自行更新 DEEPSEEK_MODEL。

结构化请求参数：[DeepSeek 官方 Chat Completions 文档](https://api-docs.deepseek.com/api/create-chat-completion/)。
