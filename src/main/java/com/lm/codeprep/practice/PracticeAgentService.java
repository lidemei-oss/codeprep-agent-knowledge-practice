package com.lm.codeprep.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels;
import com.lm.codeprep.knowledge.KnowledgeSearchService;
import com.lm.codeprep.persistence.entity.PracticeSessionEntity;
import com.lm.codeprep.persistence.mapper.PracticeSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.lm.codeprep.practice.PracticeModels.*;

@Service
public class PracticeAgentService {
    private static final int MAX_GENERATED_QUESTION_CHARS = 180;
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(30);
    private final KnowledgeSearchService search;
    private final DeepSeekClient llm;
    private final ObjectMapper mapper;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final PracticeSessionMapper sessionMapper;
    private final TransactionTemplate transactionTemplate;

    /** Isolated unit tests only; Spring injects the Mapper-backed constructor. */
    public PracticeAgentService(KnowledgeSearchService search, DeepSeekClient llm, ObjectMapper mapper) {
        this(search, llm, mapper, null, null);
    }

    @Autowired
    public PracticeAgentService(KnowledgeSearchService search, DeepSeekClient llm, ObjectMapper mapper,
                                PracticeSessionMapper sessionMapper, PlatformTransactionManager transactionManager) {
        if (sessionMapper != null && transactionManager == null)
            throw new IllegalArgumentException("数据库刷题服务必须配置事务管理器");
        this.search = search; this.llm = llm; this.mapper = mapper; this.sessionMapper = sessionMapper;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public Question start(String username, StartRequest request) {
        int count = Math.max(1, Math.min(request.targetCount(), 20));
        String sessionId = UUID.randomUUID().toString();
        String groupName = request.groupName() == null ? null : KnowledgeModels.normalizeGroup(request.groupName());
        SessionState state = new SessionState(new Session(sessionId, username, request.category(), request.orgTag(),
                count, 0, 0, 0, Instant.now(), groupName), null, List.of());
        Question first = next(state, 1);
        saveNew(state);
        return first;
    }

    public Question startFocused(String username, String category, String orgTag, String focusTopic,
                                 List<Citation> evidence) {
        return startFocused(username, category, orgTag, null, focusTopic, evidence);
    }

    public Question startFocused(String username, String category, String orgTag, String groupName, String focusTopic,
                                 List<Citation> evidence) {
        if (focusTopic == null || focusTopic.isBlank() || evidence == null || evidence.isEmpty())
            throw new IllegalArgumentException("针对性复练需要薄弱知识点和题源资料");
        String sessionId = UUID.randomUUID().toString();
        SessionState state = new SessionState(new Session(sessionId, username, category, orgTag, 3, 0, 0, 0,
                Instant.now(), groupName),
                focusTopic, List.copyOf(evidence));
        Question first = next(state, 1);
        saveNew(state);
        return first;
    }

    public AnswerResult answer(String username, String sessionId, AnswerRequest request) {
        if (transactionTemplate != null && TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("答题评测不能在已有数据库事务内调用");
        Reservation reservation = inTransaction(() -> reserve(username, sessionId, request));
        if (reservation.replayed() != null) return reservation.replayed();
        try {
            SessionState working = copyState(reservation.state());
            Question answered = working.current;
            Evaluation evaluation = evaluate(answered.prompt(), request.answer(), working.expectedContext);
            working.attempts.add(new AttemptReview(answered.id(), answered.prompt(), evaluation.score(),
                    List.copyOf(evaluation.missing())));
            working.session = new Session(working.session.id(), working.session.username(), working.session.category(),
                    working.session.orgTag(), working.session.total(), working.session.completed() + 1,
                    working.session.score() + evaluation.score(),
                    Math.min(100, (working.session.mastery() * 3 + evaluation.score()) / 4),
                    working.session.createdAt(), working.session.groupName());
            Question following = working.session.completed() < working.session.total()
                    ? next(working, working.session.completed() + 1) : null;
            AnswerResult result = new AnswerResult(sessionId, request.questionId(), evaluation.score(),
                    evaluation.feedback(), evaluation.missing(), working.session.mastery(), following);
            return inTransaction(() -> complete(username, sessionId, request, reservation.token(), working, result));
        } catch (RuntimeException exception) {
            try { inTransaction(() -> { release(sessionId, reservation.token()); return null; }); }
            catch (RuntimeException releaseFailure) { exception.addSuppressed(releaseFailure); }
            throw exception;
        }
    }

    private Reservation reserve(String username, String sessionId, AnswerRequest request) {
        SessionState state = requiredForUpdate(sessionId);
        synchronized (state) {
            if (!state.session.username().equals(username)) throw new SecurityException("刷题会话归属不匹配");
            String key = submissionKey(sessionId, request.requestId());
            Submitted existing = state.submissions.get(key);
            if (existing != null) {
                verifySameSubmission(existing.questionId(), existing.answer(), request);
                return new Reservation(null, null, existing.result());
            }
            if (state.pending != null) {
                if (state.pending.requestId().equals(request.requestId()))
                    verifySameSubmission(state.pending.questionId(), state.pending.answer(), request);
                throw new PracticeEvaluationInProgressException("该刷题会话正在评测，请稍后用同一 requestId 重试");
            }
            if (state.current == null || !request.questionId().equals(state.current.id()))
                throw new IllegalStateException("题目已更新，请使用当前 questionId");
            String token = UUID.randomUUID().toString();
            state.pending = new PendingSubmission(request.requestId(), request.questionId(), request.answer(), token,
                    Instant.now());
            saveExisting(state);
            return new Reservation(token, state, null);
        }
    }

    private AnswerResult complete(String username, String sessionId, AnswerRequest request, String token,
                                  SessionState working, AnswerResult result) {
        SessionState state = requiredForUpdate(sessionId);
        synchronized (state) {
            if (!state.session.username().equals(username)) throw new SecurityException("刷题会话归属不匹配");
            if (state.pending == null || !token.equals(state.pending.token())
                    || state.current == null || !request.questionId().equals(state.current.id()))
                throw new IllegalStateException("评测占用状态已变化，请重新查询会话");
            state.session = working.session;
            state.current = working.current;
            state.expectedContext = working.expectedContext;
            state.attempts.addAll(working.attempts.subList(state.attempts.size(), working.attempts.size()));
            state.submissions.put(submissionKey(sessionId, request.requestId()),
                    new Submitted(request.questionId(), request.answer(), result));
            state.pending = null;
            saveExisting(state);
            return result;
        }
    }

    private void release(String sessionId, String token) {
        SessionState state = requiredForUpdate(sessionId);
        synchronized (state) {
            if (state.pending != null && token.equals(state.pending.token())) {
                state.pending = null;
                saveExisting(state);
            }
        }
    }

    private SessionState copyState(SessionState state) {
        SessionState copy = new SessionState(state.session, state.focusTopic, state.seedCitations);
        copy.current = state.current;
        copy.expectedContext = state.expectedContext;
        copy.attempts.addAll(state.attempts);
        return copy;
    }

    private void verifySameSubmission(String questionId, String answer, AnswerRequest request) {
        if (!questionId.equals(request.questionId()) || !answer.equals(request.answer()))
            throw new IllegalStateException("同一 requestId 不能提交不同答案");
    }

    private String submissionKey(String sessionId, String requestId) { return sessionId + ":" + requestId; }

    private <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate == null ? work.get() : transactionTemplate.execute(status -> work.get());
    }

    public Session session(String username, String id) {
        Session session = required(id).session;
        if (!session.username().equals(username)) throw new SecurityException("刷题会话归属不匹配");
        return session;
    }

    public ReviewData reviewData(String username, String id) {
        SessionState state = required(id);
        if (!state.session.username().equals(username)) throw new SecurityException("刷题会话归属不匹配");
        synchronized (state) {
            return new ReviewData(id, state.session.category(), state.session.orgTag(), List.copyOf(state.attempts),
                    state.session.groupName());
        }
    }

    private Question next(SessionState state, int sequence) {
        List<Citation> citations = state.seedCitations;
        if (citations.isEmpty()) {
            String query = "Java " + state.session.category() + " 核心原理 实现 优缺点";
            var retrieval = state.session.groupName() == null
                    ? search.retrieve(query, state.session.orgTag(), state.session.category())
                    : search.retrieve(query, state.session.orgTag(), state.session.category(), state.session.groupName());
            citations = retrieval.citations().isEmpty()
                    ? state.session.groupName() == null
                        ? search.samples(state.session.orgTag(), state.session.category(), 3)
                        : search.samples(state.session.orgTag(), state.session.category(), state.session.groupName(), 3)
                    : retrieval.citations();
        }
        if (citations.isEmpty()) throw new IllegalStateException("所选分组没有 READY 文档，请先上传并等待解析完成");
        String context = citations.stream().limit(3).map(Citation::content).reduce((a, b) -> a + "\n" + b).orElseThrow();
        String prompt = state.focusTopic == null
                ? "结合资料说明“" + sourceTopic(citations.get(0)) + "”，重点解释原理、适用场景和一个常见误区。"
                : switch (sequence % 3) {
                    case 1 -> "针对“" + state.focusTopic + "”，说明核心原理和触发条件，并举一个工程场景。";
                    case 2 -> "针对“" + state.focusTopic + "”，描述实现步骤、关键代码思路及常见误区。";
                    default -> "针对“" + state.focusTopic + "”，分析性能、边界条件和失败处理。";
                };
        String generated = prompt;
        try {
            String response = llm.chat("你是 Java 后端刷题出题 Agent。仅依据资料出一道聚焦具体知识点的开放题。"
                            + "只输出单行纯文本题干，不要标题、Markdown、编号、答案或多个子问题；不超过 180 个字符。",
                    "资料：" + context.substring(0, Math.min(1500, context.length()))
                            + "\n题目方向：" + prompt + "\n这是本组第 " + sequence + " 题。")
                    .timeout(MODEL_TIMEOUT)
                    .block();
            generated = conciseQuestion(response, prompt);
        } catch (RuntimeException ignored) { }
        state.current = new Question(UUID.randomUUID().toString(), state.session.id(), generated, "OPEN_ENDED", sequence,
                List.of("先说数据结构或调用链", "再说并发、性能和失败处理"), citations.stream().map(Citation::chunkId).toList());
        state.expectedContext = context;
        return state.current;
    }

    private String sourceTopic(Citation citation) {
        String chapter = citation.content().lines().map(String::strip)
                .filter(line -> line.startsWith("章节："))
                .map(line -> line.substring("章节：".length()).strip())
                .findFirst().orElse("");
        if (chapter.contains(">")) chapter = chapter.substring(chapter.lastIndexOf('>') + 1).strip();
        chapter = chapter.replaceFirst("^[0-9]+[.、\\s]*", "").strip();
        String topic = chapter.isBlank() ? citation.title().replaceFirst("(?i)\\.(md|txt|pdf|docx)$", "") : chapter;
        return topic.length() > 70 ? topic.substring(0, 70) + "…" : topic;
    }

    private String conciseQuestion(String response, String fallback) {
        if (response == null || response.isBlank()) return fallback;
        String question = response.strip().replaceFirst("^#{1,6}\\s*", "")
                .replaceFirst("^(题目|开放题)[:：]\\s*", "")
                .replace("**", "").replace("`", "").strip();
        if (question.contains("\n") || question.contains("#") || question.length() > MAX_GENERATED_QUESTION_CHARS
                || question.length() < 10 || question.chars().filter(ch -> ch == '？' || ch == '?').count() > 1)
            return fallback;
        return question;
    }

    private int score(String answer, String expected) {
        if (answer == null || answer.isBlank()) return 0;
        String normalized = answer.toLowerCase(); int hits = 0; int total = 0;
        for (String token : substantiveContext(expected).toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2) { total++; if (normalized.contains(token)) hits++; }
        }
        int coverage = total == 0 ? 0 : Math.min(70, hits * 70 / Math.max(1, Math.min(total, 12)));
        int depth = (answer.contains("原理") || answer.contains("实现") ? 10 : 0) + (answer.contains("优缺点") || answer.contains("性能") ? 10 : 0) + (answer.length() > 120 ? 10 : 0);
        return Math.min(100, coverage + depth);
    }

    private List<String> missing(String answer, String expected) {
        List<String> result = new ArrayList<>(); String normalized = answer == null ? "" : answer.toLowerCase();
        for (String token : substantiveContext(expected).toLowerCase().split("[^\\p{L}\\p{N}]+"))
            if (token.length() >= 3 && !normalized.contains(token) && result.size() < 5) result.add(token);
        return result;
    }

    private String substantiveContext(String expected) {
        return expected.lines().filter(line -> !line.stripLeading().startsWith("文档：")
                && !line.stripLeading().startsWith("章节："))
                .collect(Collectors.joining("\n"));
    }

    private Evaluation evaluate(String question, String answer, String context) {
        if (llm.enabled()) {
            try {
                String json = llm.chat("你是严格的 Java 技术评测器。只输出 JSON：{\"score\":0到100整数,\"feedback\":\"具体评语\",\"missing\":[\"缺失知识点\"]}。"
                        + "仅以给定资料判断，不要编造。", "题目：" + question + "\n资料：" + context + "\n用户答案：" + answer)
                        .timeout(MODEL_TIMEOUT).block();
                var parsed = mapper.readTree(json);
                int score = Math.max(0, Math.min(100, parsed.path("score").asInt()));
                String feedback = parsed.path("feedback").asText();
                List<String> missing = new ArrayList<>();
                parsed.path("missing").forEach(node -> missing.add(node.asText()));
                if (!feedback.isBlank()) return new Evaluation(score, feedback + "（模型评测）", missing);
            } catch (Exception ignored) { }
        }
        int score = score(answer, context);
        return new Evaluation(score, "按概念覆盖和回答深度进行规则评分；请对照资料复核语义正确性。", missing(answer, context));
    }

    private SessionState required(String id) {
        if (sessionMapper == null) return java.util.Optional.ofNullable(sessions.get(id))
                .orElseThrow(() -> new IllegalArgumentException("刷题会话不存在"));
        return fromEntity(sessionMapper.selectById(id));
    }

    private SessionState requiredForUpdate(String id) {
        return sessionMapper == null ? required(id) : fromEntity(sessionMapper.selectForUpdate(id));
    }

    private SessionState fromEntity(PracticeSessionEntity entity) {
        if (entity == null) throw new IllegalArgumentException("刷题会话不存在");
        try {
            PracticeSnapshot snapshot = mapper.readValue(entity.stateJson, PracticeSnapshot.class);
            SessionState state = new SessionState(snapshot.session(), snapshot.focusTopic(), snapshot.seedCitations());
            state.current = snapshot.current(); state.expectedContext = snapshot.expectedContext();
            state.attempts.addAll(snapshot.attempts()); state.submissions.putAll(snapshot.submissions());
            state.pending = snapshot.pending();
            return state;
        } catch (JsonProcessingException exception) { throw new IllegalStateException("刷题会话解析失败", exception); }
    }

    private PracticeSessionEntity toEntity(SessionState state) {
        PracticeSessionEntity entity = new PracticeSessionEntity();
        entity.id = state.session.id(); entity.username = state.session.username();
        try {
            entity.stateJson = mapper.writeValueAsString(new PracticeSnapshot(state.session, state.current,
                    state.expectedContext, state.focusTopic, state.seedCitations, state.attempts, state.submissions,
                    state.pending));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("刷题会话序列化失败", exception); }
        return entity;
    }

    private void saveNew(SessionState state) {
        if (sessionMapper == null) sessions.put(state.session.id(), state);
        else sessionMapper.insert(toEntity(state));
    }

    private void saveExisting(SessionState state) {
        if (sessionMapper != null) sessionMapper.updateById(toEntity(state));
    }

    public record Submitted(String questionId, String answer, AnswerResult result) { }
    public record PendingSubmission(String requestId, String questionId, String answer, String token,
                                    Instant startedAt) { }
    public record PracticeSnapshot(Session session, Question current, String expectedContext, String focusTopic,
                                   List<Citation> seedCitations, List<AttemptReview> attempts,
                                   Map<String, Submitted> submissions, PendingSubmission pending) { }
    private record Evaluation(int score, String feedback, List<String> missing) { }
    private record Reservation(String token, SessionState state, AnswerResult replayed) { }
    private static final class SessionState {
        private Session session; private Question current; private String expectedContext = "";
        private PendingSubmission pending;
        private final String focusTopic;
        private final List<Citation> seedCitations;
        private final List<AttemptReview> attempts = new ArrayList<>();
        private final Map<String, Submitted> submissions = new ConcurrentHashMap<>();
        private SessionState(Session session, String focusTopic, List<Citation> seedCitations) {
            this.session = session; this.focusTopic = focusTopic; this.seedCitations = seedCitations;
        }
    }
}
