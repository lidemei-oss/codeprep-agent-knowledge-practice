package com.lm.codeprep.knowledge;

import com.lm.codeprep.auth.OrgTagGuard;
import com.lm.codeprep.auth.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import java.util.List;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final DocumentUploadService uploads;
    private final ResumableUploadService resumable;
    private final KnowledgeDocumentService documents;
    private final KnowledgeAgentService agent;
    private final OrgTagGuard guard;

    public KnowledgeController(DocumentUploadService uploads, ResumableUploadService resumable, KnowledgeDocumentService documents,
                               KnowledgeAgentService agent, OrgTagGuard guard) {
        this.uploads = uploads; this.resumable = resumable; this.documents = documents; this.agent = agent; this.guard = guard;
    }
//接口地址，单表上传文件
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
/*
    这是 SpringBoot 文件上传接口：接收 form-data 文件，附带分类、组织标签、分组参数；
    先校验当前登录用户是否具备该组织的写权限；校验通过后执行文件上传，返回文件存储记录对象。
*/
    public StoredDocument upload(@RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "java") String category,
                                 @RequestParam(defaultValue = "java") String orgTag,
                                 @RequestParam(defaultValue = "") String groupName, Authentication auth) {
        guard.checkWrite(auth, orgTag); return uploads.upload(file, category, orgTag, groupName);
    }

    @GetMapping("/documents")
    public List<DocumentView> documents(Authentication auth) {
        return documents.visibleDocuments(auth);
    }

    @GetMapping("/groups")
    public List<GroupView> groups(Authentication auth) {
        return documents.visibleGroups(auth);
    }

    @PatchMapping("/documents/{documentId}/group")
    public DocumentView moveGroup(@PathVariable String documentId, @Valid @RequestBody GroupRequest request,
                                  Authentication auth) {
        return documents.moveGroup(auth, documentId, request.groupName());
    }

    @PostMapping("/uploads")
    public UploadStarted begin(@Valid @RequestBody BeginUpload request, Authentication auth) {
        guard.checkWrite(auth, request.orgTag());
        return new UploadStarted(resumable.begin(((UserPrincipal) auth.getPrincipal()).getUsername(), request.filename(), request.totalChunks(), request.totalSize(), request.category(), request.orgTag(), request.groupName()));
    }

    @PutMapping(value = "/uploads/{uploadId}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public void chunk(@PathVariable String uploadId, @PathVariable int index, @RequestBody byte[] bytes, Authentication auth) {
        resumable.writeChunk(((UserPrincipal) auth.getPrincipal()).getUsername(), uploadId, index, bytes);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public StoredDocument complete(@PathVariable String uploadId, Authentication auth) {
        return resumable.complete(((UserPrincipal) auth.getPrincipal()).getUsername(), uploadId);
    }

    @PostMapping("/chat")
    public RagResponse chat(@Valid @RequestBody ChatRequest request, Authentication auth) {
        guard.checkRead(auth, request.orgTag());
        return agent.ask(((UserPrincipal) auth.getPrincipal()).getUsername(), request.sessionId(), request.question(),
                request.category(), request.orgTag(), !Boolean.FALSE.equals(request.allowWebSearch()));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> stream(@RequestParam String question, @RequestParam(defaultValue = "java") String category,
                                                     @RequestParam(defaultValue = "java") String orgTag, @RequestParam(required = false) String sessionId,
                                                     @RequestParam(defaultValue = "true") boolean allowWebSearch,
                                                     Authentication auth) {
        guard.checkRead(auth, orgTag);
        String username = ((UserPrincipal) auth.getPrincipal()).getUsername();
        return agent.stream(username, sessionId, question, category, orgTag, allowWebSearch)
                .map(event -> ServerSentEvent.<AgentEvent>builder(event).event(event.type()).build());
    }

    public record BeginUpload(@NotBlank String filename, int totalChunks, long totalSize,
                              @NotBlank String category, @NotBlank String orgTag, String groupName) { }
    public record UploadStarted(String uploadId) { }
    public record GroupRequest(@NotBlank String groupName) { }
}
