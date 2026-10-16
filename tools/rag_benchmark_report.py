"""Read-only corpus snapshot and reporting for the opt-in live RAG benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import re
import sys
import time
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORT_DIR = ROOT / "docs" / "rag-benchmark"


def configuration():
    values = {}
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    values.update(os.environ)
    return values


def request(url, body=None, headers=None):
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def upload():
    cfg = configuration()
    base_url = "http://127.0.0.1:8083"
    source = REPORT_DIR / "source.md"
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    state_file = REPORT_DIR / "upload.json"
    prior = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else None
    login = request(base_url + "/api/auth/login", {
        "username": cfg.get("RAG_BENCHMARK_USER", "alice"),
        "password": cfg.get("RAG_BENCHMARK_PASSWORD", "alice123"),
    })
    auth = {"Authorization": "Bearer " + login["token"]}
    if prior and prior.get("sourceSha256") == source_hash:
        state = prior
    else:
        boundary = "rag-benchmark-" + uuid.uuid4().hex
        payload = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                   "filename=\"rag-benchmark-java.md\"\r\nContent-Type: text/markdown; charset=utf-8\r\n\r\n").encode()
        payload += source.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        scope = {"category": "rag-benchmark-20261014", "orgTag": "rag-benchmark",
                 "groupName": "独立召回评测"}
        req = urllib.request.Request(base_url + "/api/knowledge/documents/upload?" + urllib.parse.urlencode(scope),
                                     data=payload, headers={**auth, "Content-Type": "multipart/form-data; boundary=" + boundary})
        started = time.perf_counter()
        with urllib.request.urlopen(req, timeout=60) as response:
            document = json.load(response)
        state = {"documentId": document["id"], "sourceSha256": source_hash, "scope": scope,
                 "httpReturnStatus": document["status"], "uploadHttpMs": (time.perf_counter() - started) * 1000,
                 "submittedAt": time.time(), "document": document}
        state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"UPLOAD_ACCEPTED id={document['id']} status={document['status']}", flush=True)
    for attempt in range(120):
        documents = request(base_url + "/api/knowledge/documents", headers=auth)
        document = next((d for d in documents if d["id"] == state["documentId"]), None)
        if document is None:
            raise RuntimeError("The uploaded benchmark document is not visible")
        if attempt % 5 == 0 or document["status"] != "PROCESSING":
            print(f"UPLOAD_POLL status={document['status']} chunks={document['chunkCount']}", flush=True)
        state["document"] = document
        if document["status"] == "READY":
            state["readyObservedAfterSeconds"] = time.time() - state["submittedAt"]
            state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(state, ensure_ascii=False, indent=2), flush=True)
            return
        if document["status"] == "FAILED":
            state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
            raise RuntimeError("Self-authored document ingestion failed; inspect worker logs")
        time.sleep(3)
    raise RuntimeError("The benchmark document did not become READY within six minutes")


def snapshot():
    cfg = configuration()
    es_url = cfg.get("ELASTICSEARCH_URL", "http://127.0.0.1:9200").rstrip("/")
    index = cfg.get("ELASTICSEARCH_INDEX", "codeprep_knowledge_chunk")
    upload_state = json.loads((REPORT_DIR / "upload.json").read_text(encoding="utf-8"))
    scope = upload_state["scope"]
    document_id = upload_state["documentId"]
    mapping = request(f"{es_url}/{index}/_mapping")
    settings = request(f"{es_url}/{index}/_settings")
    result = request(f"{es_url}/{index}/_search", {
        "size": 10000, "track_total_hits": True,
        "query": {"bool": {"filter": [
            {"term": {"status": "READY"}}, {"term": {"orgTag": scope["orgTag"]}},
            {"term": {"category": scope["category"]}}, {"term": {"documentId": document_id}},
        ]}},
        "_source": {"excludes": ["embedding"]},
    })
    hits = sorted(result["hits"]["hits"], key=lambda h: (
        h["_source"].get("documentId", ""), h["_source"].get("chunkNo", 0)))
    if result["hits"]["total"]["value"] != len(hits):
        raise RuntimeError("Corpus exceeds snapshot limit; pagination is required")
    chunks = [{"esId": h["_id"], **h["_source"]} for h in hits]
    digest = hashlib.sha256(json.dumps(chunks, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    (REPORT_DIR / "corpus.json").write_text(json.dumps({
        "index": index, "orgTag": scope["orgTag"], "category": scope["category"], "sha256": digest,
        "documentId": document_id,
        "mapping": mapping, "settings": settings, "chunks": chunks,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# Frozen corpus for retrieval benchmark", "", f"SHA256: `{digest}`", ""]
    for c in chunks:
        lines.extend([f"## {c.get('chunkId', c['esId'])} | {c.get('title', '')}", "", c.get("content", ""), ""])
    (REPORT_DIR / "corpus.md").write_text("\n".join(lines), encoding="utf-8")
    template = json.loads((REPORT_DIR / "cases-template.json").read_text(encoding="utf-8"))
    template.update({"corpusSha256": digest, "documentId": document_id,
                     "orgTag": scope["orgTag"], "category": scope["category"]})
    for item in template["cases"]:
        if item["type"] == "absent":
            item["relevantChunkNos"] = []
        else:
            anchors = item.get("evidenceAnchors", [item.get("evidence", "")])
            item["relevantChunkNos"] = [c["chunkNo"] for c in chunks if any(a in c["content"] for a in anchors)]
            if not item["relevantChunkNos"]:
                raise RuntimeError(f"Evidence missing after ingestion for {item['id']}")
            for anchor in anchors:
                if not any(anchor in c["content"] for c in chunks):
                    raise RuntimeError(f"One required evidence anchor is missing for {item['id']}")
    (REPORT_DIR / "cases.json").write_text(json.dumps(template, ensure_ascii=False, indent=2), encoding="utf-8")
    documents = {}
    for c in chunks:
        item = documents.setdefault(c["documentId"], {"title": c.get("title"), "chunks": 0})
        item["chunks"] += 1
    print(json.dumps({"chunks": len(chunks), "documents": documents, "sha256": digest,
                      "mapping": mapping, "settings": settings}, ensure_ascii=False, indent=2))


def preview(start, count, max_chars):
    data = json.loads((REPORT_DIR / "corpus.json").read_text(encoding="utf-8"))
    for chunk in data["chunks"][start:start + count]:
        print(f"\n--- {chunk.get('chunkId', chunk['esId'])} | {chunk.get('title')} ---")
        content = chunk.get("content", "")
        print(content if max_chars == 0 else content[:max_chars])


def fused_rrf(row, k=60):
    scores = {}
    entries = {}
    for name in ("bm25Candidates", "knnCandidates"):
        for rank, item in enumerate(row[name], 1):
            chunk_id = item["chunkId"]
            entries[chunk_id] = item
            scores[chunk_id] = scores.get(chunk_id, 0.0) + 1.0 / (k + rank)
    return [{**entries[chunk_id], "score": score} for chunk_id, score in
            sorted(scores.items(), key=lambda pair: (-pair[1], pair[0]))[:8]]


def measures(ranking, relevant):
    ids = [item["chunkId"] for item in ranking]
    found = relevant.intersection(ids[:8])
    first = next((rank for rank, chunk_id in enumerate(ids[:8], 1) if chunk_id in relevant), None)
    return {
        "hit1": int(bool(relevant.intersection(ids[:1]))),
        "hit3": int(bool(relevant.intersection(ids[:3]))),
        "hit8": int(bool(found)),
        "recall8": len(found) / len(relevant),
        "precision8": len(found) / 8,
        "rr8": 0.0 if first is None else 1.0 / first,
        "firstRelevantRank": first,
        "retrievedRelevantIds": sorted(found),
    }


def percentile(values, p):
    values = sorted(values)
    position = (len(values) - 1) * p
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def summarize():
    raw = json.loads((REPORT_DIR / "live-results.json").read_text(encoding="utf-8"))
    cases = json.loads((REPORT_DIR / "cases.json").read_text(encoding="utf-8"))
    corpus = json.loads((REPORT_DIR / "corpus.json").read_text(encoding="utf-8"))
    if not raw.get("completedAt") or not raw.get("snapshotUnchangedAtEnd"):
        raise RuntimeError("The live benchmark did not complete or the frozen corpus changed")
    if len(raw["cases"]) != len(cases["cases"]):
        raise RuntimeError("Incomplete case coverage")
    doc_id = cases["documentId"]
    positives = []
    negatives = []
    for row in raw["cases"]:
        label = row["label"]
        rankings = {
            "BM25": row["bm25Candidates"][:8],
            "KNN": row["knnCandidates"][:8],
            "CurrentHybrid": row["currentHybrid"][:8],
            "PureRRFDiagnostic": fused_rrf(row),
        }
        relevant = {f"{doc_id}-{n}" for n in label["relevantChunkNos"]}
        annotated = {"id": label["id"], "type": label["type"], "topic": label["topic"],
                     "question": label["question"], "relevantIds": sorted(relevant),
                     "rankings": rankings}
        if relevant:
            annotated["metrics"] = {name: measures(ranking, relevant) for name, ranking in rankings.items()}
            positives.append(annotated)
        else:
            annotated["returnedCounts"] = {name: len(ranking) for name, ranking in rankings.items()}
            negatives.append(annotated)

    aggregate = {}
    for subset_name, subset in (
            ("all", positives), ("exact", [r for r in positives if r["type"] == "exact"]),
            ("paraphrase", [r for r in positives if r["type"] == "paraphrase"]),
            ("multi", [r for r in positives if r["type"] == "multi"])):
        aggregate[subset_name] = {}
        for method in ("BM25", "KNN", "CurrentHybrid", "PureRRFDiagnostic"):
            aggregate[subset_name][method] = {
                field: statistics.mean(r["metrics"][method][field] for r in subset)
                for field in ("hit1", "hit3", "hit8", "recall8", "precision8", "rr8")
            }
    latency = {field: {"p50Ms": percentile([row[field] for row in raw["cases"]], .5),
                       "p95Ms": percentile([row[field] for row in raw["cases"]], .95)}
               for field in ("embeddingMs", "bm25Ms", "knnMs", "hybridEsMs", "hybridWithEmbeddingMs")}
    config = configuration()
    url = config.get("ELASTICSEARCH_URL", "http://127.0.0.1:9200").rstrip("/")
    status_counts = request(f"{url}/{raw['index']}/_search", {
        "size": 0, "query": {"term": {"documentId": doc_id}},
        "aggs": {"status": {"terms": {"field": "status", "size": 10}}}})
    counts = {bucket["key"]: bucket["doc_count"] for bucket in status_counts["aggregations"]["status"]["buckets"]}
    upload_state = json.loads((REPORT_DIR / "upload.json").read_text(encoding="utf-8"))
    counts_by_type = {kind: sum(row["type"] == kind for row in positives)
                      for kind in ("exact", "paraphrase", "multi")}
    summary = {"completedAt": raw["completedAt"], "answerableQueries": len(positives),
               "absentQueries": len(negatives), "topics": len({row["topic"] for row in positives}),
               "queryTypes": counts_by_type, "upload": upload_state,
               "corpusChunks": raw["corpusChunks"], "corpusSha256": raw["corpusSha256"],
               "annotationMethod": cases["annotationMethod"], "indexStatusCounts": counts,
               "metrics": aggregate, "latency": latency,
               "warmupEmbeddingMs": raw["warmupEmbeddingMs"],
               "reembeddedAnchorCosine": raw["reembeddedAnchorCosine"],
               "absentOrgScopePassed": raw["absentOrgScopePassed"],
               "absentTopicNonemptyRate": {method: statistics.mean(r["returnedCounts"][method] > 0 for r in negatives)
                                           for method in ("BM25", "KNN", "CurrentHybrid", "PureRRFDiagnostic")},
               "positiveDetails": positives, "negativeDetails": negatives}
    (REPORT_DIR / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    names = {"BM25": "BM25 单路", "KNN": "KNN 单路", "CurrentHybrid": "当前混合（RRF＋分数补偿）",
             "PureRRFDiagnostic": "纯 RRF 对照（离线计算）"}
    pct = lambda value: f"{value * 100:.2f}%"
    lines = ["# CodePrep RAG 实服务召回评测", "", f"完成时间：{raw['completedAt']}", "",
             "## 评测范围与方法", "",
             f"- 使用真实 ES 索引 `{raw['index']}`、本地 Qwen 模型 `{raw['model']}`（2048 维）。",
             "- 调用生产 Java `ElasticsearchKnowledgeGateway` 和 `QwenEmbeddingClient`，没有 mock。",
             f"- 自建 1 份 Markdown 文档，通过真实 HTTP 上传及异步处理生成 {raw['corpusChunks']} 个 READY 切片；该文档状态计数：`{counts}`。",
             "- 自建原文见 source.md，上传文件名称 rag-benchmark-java.md；没有使用用户既有资料。没有测试 PDF 解析器。",
             f"- HTTP 接收耗时 {upload_state['uploadHttpMs']:.2f} ms，返回 {upload_state['httpReturnStatus']}；约 {upload_state['readyObservedAfterSeconds']:.2f} 秒后轮询观察到 READY。后者是本次单文档观测值，含轮询粒度。",
             f"- 预先基于自建文档的 28 个基础主题标注 {len(positives)} 个可回答问题（{counts_by_type['exact']} 个术语型、{counts_by_type['paraphrase']} 个语义改述、{counts_by_type['multi']} 个跨章节），另加 {len(negatives)} 个库外问题。",
             "- 原文和标签均由助手自建，证据锚点在运行前固定；尚未经独立评审，是小规模合成评测集。",
             "- 标注在检索运行前固定；问题与标签见 cases.json，原文快照见 corpus.json / corpus.md。",
             f"- 两路使用相同 READY / orgTag={cases['orgTag']} / category={cases['category']} 过滤；断言所有返回 ID 都属于自建文档。BM25 最多取 40 条，KNN k=8、num_candidates=40，统一评估 Top 8。",
             "- 当前混合结果直接调用实际 Java hybridSearch；纯 RRF 仅用同批候选离线作消融对照，不修改生产排序。",
             "- 关闭问题改写、公开搜索与生成；本次只评估检索，不计算回答准确率或拒答准确率。",
             f"- 重算同一已入库切片的向量，与 ES 存量向量余弦相似度为 {raw['reembeddedAnchorCosine']:.8f}。",
             "- 测试前后核对自建切片 ID、文本和数量未改变；不存在的组织标签查询返回空，范围过滤检查通过。",
             f"- 语料 SHA256：`{raw['corpusSha256']}`。", "", "## 实测指标", "",
             "| 方法 | Hit@1 | Hit@3 | Hit@8 | 标注集 Recall@8 | MRR@8 | Precision@8 |",
             "|---|---:|---:|---:|---:|---:|---:|"]
    for method, metrics in aggregate["all"].items():
        lines.append(f"| {names[method]} | {pct(metrics['hit1'])} | {pct(metrics['hit3'])} | {pct(metrics['hit8'])} | {pct(metrics['recall8'])} | {metrics['rr8']:.4f} | {pct(metrics['precision8'])} |")
    current = aggregate["all"]["CurrentHybrid"]
    knn_metrics = aggregate["all"]["KNN"]
    lines.extend(["", f"本批自建问题中，当前混合 Recall@8={pct(current['recall8'])}，KNN 单路 Recall@8={pct(knn_metrics['recall8'])}；这批结果未证明混合在 Recall@8 上有额外提升。",
                  f"当前混合 Precision@8={pct(current['precision8'])}，按本次完整证据标签计算。多数问题只需要一个切片，固定返回八条会带入多余上下文；这不是回答准确率。"])
    lines.extend(["", "Hit@K 表示至少一条标注证据进入前 K；Recall@8 是每个问题找回的标注相关切片比例再取平均；MRR@8 是首条相关证据倒数排名的平均，未命中记 0。Precision@8 分母固定为 8。库外问题不进入这些分母。",
                  "", "### 术语问题与语义改述", "",
                  "| 类型 | 方法 | Hit@1 | Hit@8 | MRR@8 |", "|---|---|---:|---:|---:|"])
    for subset in ("exact", "paraphrase", "multi"):
        for method in ("BM25", "KNN", "CurrentHybrid", "PureRRFDiagnostic"):
            m = aggregate[subset][method]
            subset_name = {"exact": "术语型", "paraphrase": "语义改述", "multi": "跨章节"}[subset]
            lines.append(f"| {subset_name} | {names[method]} | {pct(m['hit1'])} | {pct(m['hit8'])} | {m['rr8']:.4f} |")
    lines.extend(["", "## 每题排名（缺失为 —）", "",
                  "| ID | 问题 | BM25 首证据排名 | KNN 首证据排名 | 当前混合首证据排名 | 纯 RRF 首证据排名 |",
                  "|---|---|---:|---:|---:|---:|"])
    for row in positives:
        ranks = [str(row["metrics"][method]["firstRelevantRank"] or "—")
                 for method in ("BM25", "KNN", "CurrentHybrid", "PureRRFDiagnostic")]
        lines.append(f"| {row['id']} | {row['question']} | " + " | ".join(ranks) + " |")
    lines.extend(["", "## 库外问题结果", "",
                  "所有返回切片都缺少问题所需的完整证据。这里衡量的是没有证据时仍返回候选的比例，不是模型实际幻觉率；本次未调用生成模型。",
                  "", "| ID | 库外问题 | BM25 条数 | KNN 条数 | 当前混合条数 |", "|---|---|---:|---:|---:|"])
    for row in negatives:
        n = row["returnedCounts"]
        lines.append(f"| {row['id']} | {row['question']} | {n['BM25']} | {n['KNN']} | {n['CurrentHybrid']} |")
    lines.extend(["", "## 延迟（毫秒）", "", "| 阶段 | P50 | P95 |", "|---|---:|---:|"])
    for field, name in (("embeddingMs", "Qwen 问题向量化"), ("bm25Ms", "单次 BM25"),
                        ("knnMs", "单次 KNN"), ("hybridEsMs", "生产混合检索，两次 ES 串行＋融合"),
                        ("hybridWithEmbeddingMs", "Qwen＋生产混合检索")):
        lines.append(f"| {name} | {latency[field]['p50Ms']:.2f} | {latency[field]['p95Ms']:.2f} |")
    lines.extend(["", f"Qwen 首次语料向量预热耗时 {raw['warmupEmbeddingMs']:.2f} ms，不进入上述分位数。",
                  f"这些数据是单线程、本地小语料、预热后的 {len(raw['cases'])} 次样本；每题先采集单路基线，再调用混合检索，可能受缓存影响。包含读取结果和传输文本的耗时，未包含鉴权、MySQL 回退、对话历史或 DeepSeek 生成，不能作为端到端延迟或并发性能。",
                  "", "## 已确认的问题及改进方向", "",
                  "1. 库外问题 8/8 都得到 8 条混合结果。KNN 会找最近邻，但最近不代表足以回答问题；当前以非空判断有证据，无法拒绝这些弱相关结果。应先评估相关性/证据充分性，再决定改写或 NEEDS_SOURCE，而非直接对 RRF 分数随意设置阈值。",
                  "2. 当前补偿项可达 BM25 0.2、向量约 0.05，单路 RRF 首名仅约 0.0164，可能压过排名融合。是否保留应依据本表及更大验证集判断；不能预设混合一定优于单路。",
                  f"3. 语料只有 {raw['corpusChunks']} 个 READY 切片，多数查询只有一条标注证据。样本成对改述，来源和标签由同一助手构造；不能推断真实用户问题、大规模知识库或回答准确率。",
                  "4. 自建资料中的示例参数不是项目配置，例如文档写 2 分钟租约、4 次重试，仅用于检查检索是否拿到正确证据。",
                  "5. 测试文档保留在独立组织标签 rag-benchmark、类别 rag-benchmark-20261014、分组“独立召回评测”，不混入 java 知识域。",
                  "", "## 复现", "", "```bat",
                  "mvn -q -Dcodeprep.build.directory=target-rag-benchmark -Dcodeprep.rag.benchmark=true -Dtest=RagRetrievalLiveBenchmarkTest test",
                  "python tools\\rag_benchmark_report.py summarize", "```", "",
                  "实服务测试默认跳过，只有明确设置 codeprep.rag.benchmark=true 才执行。若更新了知识库，须重新生成快照并在查询前重新标注，不可沿用旧切片标签。详情和全部片段排名见 live-results.json / summary.json。"])
    (REPORT_DIR / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    console = {key: value for key, value in summary.items() if key not in ("positiveDetails", "negativeDetails")}
    console["rankingRegressions"] = [{"id": row["id"], "question": row["question"],
                                      "ranks": {method: row["metrics"][method]["firstRelevantRank"]
                                                for method in row["metrics"]}}
                                     for row in positives if row["metrics"]["CurrentHybrid"]["rr8"] < row["metrics"]["KNN"]["rr8"]]
    print(json.dumps(console, ensure_ascii=False, indent=2))


def summarize_evidence():
    raw = json.loads((REPORT_DIR / "evidence-live-results.json").read_text(encoding="utf-8"))
    if not raw.get("completedAt") or not raw.get("corpusUnchanged"):
        raise RuntimeError("Live evidence evaluation has not finished")
    document_id = raw["documentId"]
    legacy_path = REPORT_DIR / "evidence-legacy-results.json"
    legacy = json.loads(legacy_path.read_text(encoding="utf-8")) if legacy_path.exists() else {"cases": []}
    legacy_errors = [row["label"]["id"] for row in legacy["cases"] if row["selection"]["status"] == "ERROR"]
    details = []
    for row in raw["cases"]:
        label = row["label"]
        relevant = {f"{document_id}-{number}" for number in label["relevantChunkNos"]}
        selected = row["selection"]["citations"]
        item = {"id": label["id"], "question": label["question"], "split": row["split"],
                "type": label["type"], "status": row["selection"]["status"],
                "candidateCount": len(row["candidates"]), "selectedCount": len(selected),
                "selectedIds": [c["chunkId"] for c in selected], "verificationMs": row["verificationMs"]}
        if relevant:
            before = measures(row["candidates"], relevant)
            after = measures(selected, relevant)
            item.update({"answerable": True, "before": before, "after": after,
                         "selectedPrecision": len(relevant.intersection(item["selectedIds"])) / len(selected) if selected else 0.0,
                         "allRequiredKept": int(relevant.issubset(item["selectedIds"])),
                         "extraSelected": len(set(item["selectedIds"]) - relevant)})
        else:
            item.update({"answerable": False, "rejected": int(item["status"] == "INSUFFICIENT" and not selected)})
        details.append(item)
    results = {}
    for split in ("all", "regression", "holdout"):
        subset = [d for d in details if split == "all" or d["split"] == split]
        positive = [d for d in subset if d["answerable"]]
        negative = [d for d in subset if not d["answerable"]]
        results[split] = {
            "answerable": len(positive), "absent": len(negative),
            "rawRecall8": statistics.mean(d["before"]["recall8"] for d in positive),
            "selectedRecall": statistics.mean(d["after"]["recall8"] for d in positive),
            "rawPrecision8": statistics.mean(d["before"]["precision8"] for d in positive),
            "selectedPrecision": statistics.mean(d["selectedPrecision"] for d in positive),
            "allRequiredKeptRate": statistics.mean(d["allRequiredKept"] for d in positive),
            "rawHit1": statistics.mean(d["before"]["hit1"] for d in positive),
            "selectedHit1": statistics.mean(d["after"]["hit1"] for d in positive),
            "rawMRR8": statistics.mean(d["before"]["rr8"] for d in positive),
            "selectedMRR": statistics.mean(d["after"]["rr8"] for d in positive),
            "meanSelectedCount": statistics.mean(d["selectedCount"] for d in positive),
            "meanExtraSelectedCount": statistics.mean(d["extraSelected"] for d in positive),
            "absentRejected": sum(d["rejected"] for d in negative),
            "absentRejectionRate": statistics.mean(d["rejected"] for d in negative),
            "verificationP50Ms": percentile([d["verificationMs"] for d in subset if d["verificationMs"] is not None], .5),
            "verificationP95Ms": percentile([d["verificationMs"] for d in subset if d["verificationMs"] is not None], .95),
            "measuredLatencySamples": sum(d["verificationMs"] is not None for d in subset),
            "successfulSelectedRecall": statistics.mean(d["after"]["recall8"] for d in positive if d["status"] != "ERROR"),
            "errors": sum(d["status"] == "ERROR" for d in subset),
        }
    summary = {"completedAt": raw["completedAt"], "model": raw["model"], "corpusSha256": raw["corpusSha256"],
               "corpusUnchanged": raw["corpusUnchanged"], "requestMode": raw.get("requestMode"),
               "legacyPartialRunErrorIds": legacy_errors, "metrics": results, "cases": details}
    (REPORT_DIR / "evidence-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    pct = lambda number: f"{number * 100:.2f}%"
    lines = ["# 证据筛选改造：真实模型验证", "", f"模型：{raw['model']}；完成时间：{raw['completedAt']}。", "",
             "## 改动", "",
             "知识问答新增共享证据门控。BM25＋KNN 的 Top 8 仍作为候选，DeepSeek 根据原问题判断正文能否直接支持回答，选择最少必要片段并重新排序。普通问题尽量 1～3 条，跨章节最多 5 条；严格校验返回 ID、唯一性和 Prompt 预算。",
             "非空但无有效证据会进入原问题改写和可选公开搜索。筛选超时、模型关闭、无效 JSON 等故障返回 EVIDENCE_VERIFICATION_UNAVAILABLE，不视为未命中，不继续生成或自动联网。",
             "", "## 测试范围", "",
             "- 只使用此前自建并通过实际上传接口入库的 rag-benchmark-java.md，29 个 READY 切片，不使用用户文档。",
             "- 68 个既有回归问题：60 个有证据、8 个库外；新增 16 个留出问题：12 个有证据、4 个库外。标签在调用筛选前固定。",
             "- 回归候选使用已冻结的生产混合检索结果；新问题实际调用 ES 与 Qwen 重新检索。",
             "- 证据筛选调用真实配置的 DeepSeek，未 mock；关闭回答生成和公开搜索，只测单次候选筛选，不把结果当完整重试/联网链路指标。",
             "- 当前请求使用 JSON 模式、最多 1024 输出 Token，官方 DeepSeek 接口关闭思考；所有 ERROR 样本仍保留，不当作成功拒绝。",
             f"- 参数调整前的初版测量出现 {len(legacy_errors)} 次已记录故障，保存在 evidence-legacy-results.json，不与本表混算；其中 Q48 状态从原始 Surefire 日志恢复，精确时长没有持久化。这些记录未删除，当前参数的整批测量重新开始。",
             "- 原文和问题都是助手自建，留出集也是同一作者、同一小语料，不是独立真实用户评测。",
             "- 测试前后核对自建语料 ID、正文及 READY 状态未变化。",
             "", "## 实测", "",
             "| 分组 | 有证据问题 | 原候选 Recall@8 | 精选证据 Recall | 全部必要证据保留率 | 库外拒绝 | 精选平均条数 |",
             "|---|---:|---:|---:|---:|---:|---:|"]
    names = {"all": "全部", "regression": "回归集", "holdout": "新增留出集"}
    for split, value in results.items():
        lines.append(f"| {names[split]} | {value['answerable']} | {pct(value['rawRecall8'])} | {pct(value['selectedRecall'])} | {pct(value['allRequiredKeptRate'])} | {value['absentRejected']}/{value['absent']} | {value['meanSelectedCount']:.2f} |")
    lines.extend(["", f"模型故障样本数：{results['all']['errors']}；排除服务故障后，证据筛选本身的覆盖率为 {pct(results['all']['successfulSelectedRecall'])}。这与包含故障的整体证据可用率分开报告。"])
    lines.extend(["", "| 分组 | 候选 Precision@8 | 精选 Precision | 原 Hit@1 | 精选 Hit@1 | 原 MRR@8 | 精选 MRR |",
                  "|---|---:|---:|---:|---:|---:|---:|"])
    for split, value in results.items():
        lines.append(f"| {names[split]} | {pct(value['rawPrecision8'])} | {pct(value['selectedPrecision'])} | {pct(value['rawHit1'])} | {pct(value['selectedHit1'])} | {value['rawMRR8']:.4f} | {value['selectedMRR']:.4f} |")
    lines.extend(["", "候选 Precision@8 分母固定为 8，精选 Precision 分母为实际选入片段数，二者不是同一固定 K 指标；数值变化同时包含候选删减，不代表召回算法本身提升了同等幅度。相关标签按预先指定的完整证据计算，未标注片段也可能提供部分辅助信息。",
                  "", "## 每题结果", "",
                  "| ID | 问题 | 状态 | 候选/精选数 | 必要证据保留率 |", "|---|---|---|---:|---:|"])
    for row in details:
        recall = pct(row["after"]["recall8"]) if row["answerable"] else "库外"
        lines.append(f"| {row['id']} | {row['question']} | {row['status']} | {row['candidateCount']}/{row['selectedCount']} | {recall} |")
    q22 = next(d for d in details if d["id"] == "Q22")
    lines.extend(["", "## 联合索引案例", "", q22["question"], "",
                  f"原混合结果的正确切片在第 {q22['before']['firstRelevantRank']} 位；精选后在第 {q22['after']['firstRelevantRank']} 位，使用 {q22['selectedCount']} 条。原候选排序没有修改，生成上下文采用新的证据顺序。",
                  "", "## 代价与限制", "",
                  f"本批有精确记录的 {results['all']['measuredLatencySamples']} 次证据审核 P50 {results['all']['verificationP50Ms']:.2f} ms、P95 {results['all']['verificationP95Ms']:.2f} ms。这是额外模型调用耗时；若第一次不足再改写/联网，会进一步增加等待。本次不测首字、完整回答耗时或并发。",
                  "候选 Recall@8 保留原算法用于回归，精选证据覆盖率单独记录。模型审核仍可能误判；应持续扩大独立问题集，并评估证据遗漏、误拒绝、调用成本和延迟。生产代码尚未接入专门 cross-encoder，也未声称彻底消除幻觉。",
                  "", "## 复现", "", "```bat",
                  "mvn -q -Dcodeprep.build.directory=target-evidence-check -Dcodeprep.rag.evidence.benchmark=true -Dtest=RagEvidenceLiveBenchmarkTest test",
                  "python tools\\rag_benchmark_report.py summarize-evidence", "```", "",
                  "完整选择结果见 evidence-live-results.json，指标和每题标签对比见 evidence-summary.json，新问题见 evidence-holdout.json。普通接口、流式接口和 Agent 的门控/故障行为另由自动化测试验证。",
                  "", "结构化请求参数参考：[DeepSeek Chat Completions 官方说明](https://api-docs.deepseek.com/api/create-chat-completion/)。模型服务仍可能抖动，不能因一次顺利的批量运行就承诺无超时。"])
    (REPORT_DIR / "evidence-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"metrics": results, "q22": q22,
                      "misses": [d for d in details if d["answerable"] and d["allRequiredKept"] == 0],
                      "falseAccepts": [d for d in details if not d["answerable"] and d["status"] == "SUPPORTED"]},
                     ensure_ascii=False, indent=2))


def recover_timeout():
    """Recover a measured failed row that the first fail-fast harness did not flush."""
    xml_file = ROOT / "target-evidence-check" / "surefire-reports" / "TEST-com.lm.codeprep.knowledge.RagEvidenceLiveBenchmarkTest.xml"
    suite = ET.parse(xml_file).getroot()
    logs = "\n".join(element.text or "" for element in suite.iter("system-out"))
    failed_ids = re.findall(r"EVIDENCE_LIVE (\w+) ERROR selected=0 reason=MODEL_TIMEOUT", logs)
    if not failed_ids:
        raise RuntimeError("No live MODEL_TIMEOUT event found in the test log")
    raw_file = REPORT_DIR / "evidence-live-results.json"
    partial = json.loads(raw_file.read_text(encoding="utf-8"))
    original = json.loads((REPORT_DIR / "live-results.json").read_text(encoding="utf-8"))
    cases = {row["label"]["id"]: row for row in original["cases"]}
    existing = {row["label"]["id"] for row in partial["cases"]}
    for failed_id in failed_ids:
        if failed_id in existing:
            continue
        row = cases[failed_id]
        partial["cases"].append({"label": row["label"], "split": "regression",
                                 "candidates": row["currentHybrid"],
                                 "selection": {"status": "ERROR", "citations": [], "reason": "MODEL_TIMEOUT"},
                                 "verificationMs": None, "configuredTimeoutMs": 30000,
                                 "measurementProvenance": "Recovered status from original Surefire system-out; exact duration not flushed"})
    partial["recoveredTimeoutIds"] = failed_ids
    raw_file.write_text(json.dumps(partial, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"recoveredTimeoutIds": failed_ids, "persistedCases": len(partial["cases"])}, ensure_ascii=False))


def model_probe():
    cfg = configuration()
    base = cfg.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    address = urllib.parse.urlsplit(base)
    information = {"host": address.hostname, "scheme": address.scheme,
                   "model": cfg.get("DEEPSEEK_MODEL", "deepseek-chat"),
                   "keyConfigured": bool(cfg.get("DEEPSEEK_API_KEY"))}
    started = time.perf_counter()
    try:
        headers = {"Authorization": "Bearer " + cfg.get("DEEPSEEK_API_KEY", "")}
        req = urllib.request.Request(base + "/models", headers=headers)
        with urllib.request.urlopen(req, timeout=10) as response:
            models = json.load(response)
        information["models"] = [model.get("id") for model in models.get("data", [])]
        information["elapsedSeconds"] = time.perf_counter() - started
    except Exception as exception:
        information["failureType"] = type(exception).__name__
        if hasattr(exception, "code"):
            information["httpStatus"] = exception.code
    print(json.dumps(information, ensure_ascii=False, indent=2))


def completion_probe():
    cfg = configuration()
    base = cfg.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    started = time.perf_counter()
    body = {"model": "deepseek-flash", "thinking": {"type": "disabled"}, "stream": False,
            "response_format": {"type": "json_object"}, "max_tokens": 64,
            "messages": [{"role": "system", "content": "Only output JSON: {\"ok\":true}"},
                         {"role": "user", "content": "Return the requested JSON."}]}
    information = {"requestedModel": "deepseek-flash"}
    try:
        req = urllib.request.Request(base + "/chat/completions", data=json.dumps(body).encode(),
                headers={"Content-Type": "application/json", "Authorization": "Bearer " + cfg.get("DEEPSEEK_API_KEY", "")})
        with urllib.request.urlopen(req, timeout=20) as response:
            data = json.load(response)
        information.update({"responseModel": data.get("model"),
                            "hasContent": bool(data.get("choices", [{}])[0].get("message", {}).get("content")),
                            "elapsedSeconds": time.perf_counter() - started})
    except Exception as exception:
        information["failureType"] = type(exception).__name__
        if hasattr(exception, "code"): information["httpStatus"] = exception.code
    print(json.dumps(information, ensure_ascii=False, indent=2))


def implementation_report():
    tests = failures = errors = skipped = 0
    test_classes = 0
    for file in (ROOT / "target-evidence-check" / "surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(file).getroot()
        tests += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0"))
        errors += int(suite.get("errors", "0"))
        skipped += int(suite.get("skipped", "0"))
        test_classes += 1
    smoke = json.loads((REPORT_DIR / "evidence-smoke-results.json").read_text(encoding="utf-8"))
    legacy = json.loads((REPORT_DIR / "evidence-legacy-results.json").read_text(encoding="utf-8"))
    results = [{"id": row["label"]["id"], "status": row["selection"]["status"],
                "reason": row["selection"]["reason"], "selectedCount": len(row["selection"]["citations"])}
               for row in smoke["cases"]]
    summary = {"automatedTests": {"classes": test_classes, "tests": tests, "skipped": skipped,
                                  "failures": failures, "errors": errors},
               "requestMode": smoke.get("requestMode"), "liveQualityVerified": False,
               "smokeResults": results, "legacyRecordedCases": len(legacy["cases"]),
               "legacyErrors": sum(row["selection"]["status"] == "ERROR" for row in legacy["cases"])}
    (REPORT_DIR / "evidence-verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# 证据筛选改造及验证记录", "", "## 已实现", "",
             "- 普通知识问答（HTTP、SSE、WebSocket）与 Agent 知识问答共用 GroundedKnowledgeService。",
             "- BM25＋KNN 前 8 条保留为候选；EvidenceSelectionService 用 DeepSeek 判断原问题能否被正文支持，选择必要证据并重新排序。",
             "- 普通问题尽量 1～3 条，多知识点最多 5 条。只有返回的合法候选 ID 才能进入 Prompt；拒绝重复、外来 ID、无效/矛盾 JSON。",
             "- SUPPORTED 才生成；INSUFFICIENT 即使候选非空也改写重试，再按设置公开搜索；ERROR 停止回答及公开搜索。",
             "- 筛选超时、模型关闭、格式错误、未知 ID、上下文预算不足均明确返回 EVIDENCE_VERIFICATION_UNAVAILABLE，保留失败原因。",
             "- 原始候选及各次查询统计保存在 evidence.attempts；最终 citations 仅包括真正选用片段。",
             "- 必要证据超出 Prompt 预算时停止生成，不静默丢掉已经选中的片段。",
             "- 经用户授权，将最近六条会话历史发送给审核模型，仅用于指代消解，不作为事实证据。",
             "- ES 故障后若 MySQL 降级候选也缺乏支持，按检索故障处理，不冒充零召回去公开搜索。",
             "- 审核使用 JSON 模式、最多 1024 输出 Token；官方 DeepSeek 请求关闭思考，普通生成配置保持原样。",
             "- 原始 RRF＋分数补偿候选算法没有修改。本次增加的排序发生在证据选择阶段。",
             "", "## 自动化验证", "",
             f"本地回归：{tests - skipped} 个执行用例，{skipped} 个显式实服务测试默认跳过；失败 {failures}，错误 {errors}。",
             "覆盖非空弱证据、改写后仍按原问题审核、公开片段筛选、模型超时及关闭、外来/重复 ID、Prompt 预算、普通/流式/Agent 不生成故障回答、八步边界，以及原有上传、Outbox、MyBatis-Plus 持久化和刷题并发流程。",
             "", "## 实服务验证与限制", "",
             "只使用自建并通过上传接口入库的 rag-benchmark-java.md，29 个 READY 切片，未使用用户资料。",
             "初版批量审核曾实际完成正常筛选，包括将 Q22 的联合索引证据从原混合第 2 位选到第 1 位；随后出现连续 30 秒超时。已测记录全部保存在 evidence-legacy-results.json，未删除失败样本。该批未完成，不发布整批质量指标。",
             "改为受限 JSON 请求后重新抽查，结果如下：", "",
             "| 样本 | 审核状态 | 原因 | 最终证据数 |", "|---|---|---|---:|"]
    for row in results:
        lines.append(f"| {row['id']} | {row['status']} | {row['reason']} | {row['selectedCount']} |")
    lines.extend(["", "这证明当前故障路径拒绝把候选当作有效证据，但不能证明新请求参数下的筛选质量已全面通过。DeepSeek /models 可访问；当前模型和服务列出的 deepseek-flash 的极小补全请求均曾超时，且审核返回 HTTP 503。没有修改密钥或擅自切换模型。",
                  "本次未完成 68 个回归＋16 个新问题的整批质量复测，因此没有编造新的 Precision、Recall 或拒绝准确率。模型服务恢复后可继续验证。审核仍可能误判，也会增加一次或多次模型调用的延迟。",
                  "", "## 使用与复现", "",
                  "重启后端使代码生效。默认审核期限 30 秒；可用 CODEPREP_EVIDENCE_TIMEOUT_MS、CODEPREP_EVIDENCE_MAX_SELECTED、CODEPREP_EVIDENCE_MAX_CANDIDATE_CHARS 配置。模型关闭时有候选的问答会报告审核不可用，不绕过审核。",
                  "", "```bat", "mvn -q -Dcodeprep.build.directory=target-evidence-check test",
                  "mvn -q -Dcodeprep.build.directory=target-evidence-check -Dcodeprep.rag.evidence.benchmark=true -Dcodeprep.rag.evidence.smoke=true -Dtest=RagEvidenceLiveBenchmarkTest test",
                  "mvn -q -Dcodeprep.build.directory=target-evidence-check -Dcodeprep.rag.evidence.benchmark=true -Dtest=RagEvidenceLiveBenchmarkTest test",
                  "python tools\\rag_benchmark_report.py summarize-evidence", "```", "",
                  "实服务评测会保留 ERROR 记录，连续三次服务故障后停止，避免继续无意义请求；只有完整测量完成后，汇总工具才计算质量指标。当前配置仍是 deepseek-chat，可根据服务实际可用模型自行更新 DEEPSEEK_MODEL。",
                  "", "结构化请求参数：[DeepSeek 官方 Chat Completions 文档](https://api-docs.deepseek.com/api/create-chat-completion/)。"])
    (REPORT_DIR / "evidence-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["upload", "snapshot", "preview", "summarize", "summarize-evidence", "recover-timeout", "model-probe", "completion-probe", "implementation-report"])
    parser.add_argument("--start", type=int, default=0)
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--max-chars", type=int, default=0)
    args = parser.parse_args()
    if args.mode == "upload":
        upload()
    elif args.mode == "snapshot":
        snapshot()
    elif args.mode == "preview":
        preview(args.start, args.count, args.max_chars)
    elif args.mode == "summarize":
        summarize()
    elif args.mode == "summarize-evidence":
        summarize_evidence()
    elif args.mode == "recover-timeout":
        recover_timeout()
    elif args.mode == "model-probe":
        model_probe()
    elif args.mode == "completion-probe":
        completion_probe()
    else:
        implementation_report()
