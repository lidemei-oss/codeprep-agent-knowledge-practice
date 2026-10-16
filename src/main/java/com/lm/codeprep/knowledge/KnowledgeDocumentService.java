package com.lm.codeprep.knowledge;

import com.lm.codeprep.auth.OrgTagGuard;
import com.lm.codeprep.auth.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.lm.codeprep.knowledge.KnowledgeModels.DocumentView;
import static com.lm.codeprep.knowledge.KnowledgeModels.GroupView;
import static com.lm.codeprep.knowledge.KnowledgeModels.StoredDocument;

@Service
public class KnowledgeDocumentService {
    private final KnowledgeStore store;
    private final OrgTagGuard guard;

    public KnowledgeDocumentService(KnowledgeStore store, OrgTagGuard guard) {
        this.store = store;
        this.guard = guard;
    }

    public List<DocumentView> visibleDocuments(Authentication authentication) {
        UserPrincipal user = (UserPrincipal) authentication.getPrincipal();
        return store.documents().stream()
                .filter(document -> canRead(user, document.orgTag()))
                .map(this::view)
                .toList();
    }

    public List<GroupView> visibleGroups(Authentication authentication) {
        UserPrincipal user = (UserPrincipal) authentication.getPrincipal();
        return store.groups().stream()
                .filter(group -> canRead(user, group.orgTag()))
                .toList();
    }

    public DocumentView moveGroup(Authentication authentication, String documentId, String groupName) {
        StoredDocument existing = store.requiredDocument(documentId);
        guard.checkWrite(authentication, existing.orgTag());
        return view(store.moveGroup(documentId, groupName));
    }

    private boolean canRead(UserPrincipal user, String orgTag) {
        return user.account().roles().contains("OWNER") || user.account().organizationTags().contains(orgTag);
    }

    private DocumentView view(StoredDocument document) {
        return new DocumentView(document.id(), document.title(), document.category(), document.orgTag(),
                document.status(), document.fileName(), document.createdAt(), document.chunkCount(), document.groupName());
    }
}
