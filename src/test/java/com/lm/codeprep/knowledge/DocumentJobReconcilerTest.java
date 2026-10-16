package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.mapper.DocumentOutboxMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentJobReconcilerTest {
    @Test
    void oldProcessingDocumentWithoutOutboxIsQueuedForRecovery() {
        DocumentOutboxMapper outbox = mock(DocumentOutboxMapper.class);
        DocumentUploadService uploads = mock(DocumentUploadService.class);
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-1", "Java并发", "java", "java", "threads.pdf");
        when(outbox.staleWithoutOutbox(anyLong(), eq(20))).thenReturn(List.of("doc-1"));
        when(uploads.pathOf("doc-1")).thenReturn(Path.of("threads.pdf"));
        when(outbox.insertMissing(eq("doc-1"), anyLong())).thenReturn(1);

        new DocumentJobReconciler(outbox, uploads, store, 60000).backfillMissing();

        verify(outbox).insertMissing(eq("doc-1"), anyLong());
        assertEquals("PROCESSING", store.requiredDocument("doc-1").status());
    }

    @Test
    void missingOriginalFileFailsInsteadOfRetryingForever() {
        DocumentOutboxMapper outbox = mock(DocumentOutboxMapper.class);
        DocumentUploadService uploads = mock(DocumentUploadService.class);
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-2", "Java并发", "java", "java", "threads.pdf");
        when(outbox.staleWithoutOutbox(anyLong(), eq(20))).thenReturn(List.of("doc-2"));
        when(uploads.pathOf("doc-2")).thenThrow(new NoSuchElementException());

        new DocumentJobReconciler(outbox, uploads, store, 60000).backfillMissing();

        verify(outbox, never()).insertMissing(anyString(), anyLong());
        assertEquals("FAILED", store.requiredDocument("doc-2").status());
    }
}
