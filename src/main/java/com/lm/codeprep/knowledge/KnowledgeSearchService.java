package com.lm.codeprep.knowledge;

import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

@Service
public class KnowledgeSearchService {
    private final KnowledgeStore store;
    private final ElasticsearchKnowledgeGateway elasticsearch;
    private final QwenEmbeddingClient embedding;

    public KnowledgeSearchService(KnowledgeStore store, ElasticsearchKnowledgeGateway elasticsearch, QwenEmbeddingClient embedding) {
        this.store = store; this.elasticsearch = elasticsearch; this.embedding = embedding;
    }

    public RetrievalResult retrieve(String question, String orgTag, String category) {
        return retrieve(question, orgTag, category, null);
    }




    //优先 ES 混合检索（向量 + 关键词 hybrid）；ES 挂了 / ES 查不到内容，自动降级到数据库关键词检索；支持按 group 分组限定文档范围。
    public RetrievalResult retrieve(String question, String orgTag, String category, String groupName) {
        Set<String> documentIds = groupName == null ? null : store.readyDocumentIds(orgTag, category, groupName);
        if (documentIds != null && documentIds.isEmpty())
            return new RetrievalResult(List.of(), 0, 0, 0, "EMPTY_GROUP");
        List<Double> vector = null;
        try { vector = embedding.embed(question); } catch (RuntimeException ignored) { }
        boolean indexUnavailable = false;
        if (elasticsearch.enabled()) {
            try {
                RetrievalResult result = documentIds == null
                        ? elasticsearch.hybridSearch(question, orgTag, category, vector)
                        : elasticsearch.hybridSearch(question, orgTag, category, vector, documentIds);
                if (!result.citations().isEmpty()) return result;
            } catch (RuntimeException exception) { indexUnavailable = true; }
        }
        List<Citation> lexical = store.lexicalSearch(question, orgTag, category, groupName, 8);
        String strategy = indexUnavailable && lexical.isEmpty() ? "PRIVATE_INDEX_UNAVAILABLE" : store.keywordSource();
        return new RetrievalResult(lexical, 0, lexical.size(), lexical.size(), strategy, null, indexUnavailable);
    }



    public List<Citation> citationsFor(String documentId) {
        return store.documentChunks(documentId).stream().map(c -> new Citation(c.id(), c.documentId(), c.title(), c.content(),
                c.category(), c.chunkNo(), 1, "DOCUMENT")).toList();
    }

    public List<Citation> samples(String orgTag, String category, int limit) {
        return samples(orgTag, category, null, limit);
    }

    public List<Citation> samples(String orgTag, String category, String groupName, int limit) {
        List<Citation> local = store.chunks(orgTag, category, groupName).stream().limit(limit)
                .map(c -> new Citation(c.id(), c.documentId(), c.title(), c.content(), c.category(),
                        c.chunkNo(), 1, "KNOWLEDGE_SAMPLE")).toList();
        if (!local.isEmpty() || !elasticsearch.enabled()) return local;
        Set<String> documentIds = groupName == null ? null : store.readyDocumentIds(orgTag, category, groupName);
        return elasticsearch.sampleReady(orgTag, category, documentIds, limit);
    }
}
