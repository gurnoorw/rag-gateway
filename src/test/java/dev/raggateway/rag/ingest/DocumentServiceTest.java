package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ingest.UpsertResult.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PUT decision table of D-51 on in-memory stores: every combination of status and what changed. The same over
 * HTTP on the real database, with the row lock, is {@link IncrementalIndexingIT}; atomicity is
 * {@link DocumentServiceTxIT}.
 */
class DocumentServiceTest {

    final IngestFakes fakes = new IngestFakes();
    final DocumentService service = new DocumentService(fakes.documents, fakes.outbox);

    UpsertResult put(String title, String content, String... groups) {
        return service.upsert(new DocumentUpsert("t1", "docs", "d", title, content, Set.of(groups)));
    }

    void setStatus(UUID id, DocumentStatus status) {
        IndexedDocument r = fakes.documents.get(id);
        fakes.documents.rows.put(id, new IndexedDocument(r.id(), r.tenantId(), r.collection(), r.docId(), r.title(),
                r.content(), r.aclGroups(), r.contentHash(), status, r.chunkCount(), r.updatedAt()));
    }

    int jobs(UUID id) {
        return fakes.outbox.jobsFor(id).size();
    }

    @Test
    void aNewDocumentIsQueuedWithOneJob() {
        UpsertResult r = put("T", "Body.", "eng");
        assertThat(r.outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(r.queued()).isTrue();
        assertThat(jobs(r.documentId())).isEqualTo(1);
    }

    @Test
    void theSameVersionIsUnchangedWhileQueuedOrIndexed() {
        UUID id = put("T", "Body.", "eng").documentId();
        assertThat(put("T", "Body.", "eng").outcome()).isEqualTo(Outcome.UNCHANGED);
        setStatus(id, DocumentStatus.INDEXED);
        assertThat(put("T", "Body.", "eng").outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(jobs(id)).isEqualTo(1);
    }

    @Test
    void theSameVersionOfAFailedDocumentIsQueuedAgain() {
        UUID id = put("T", "Body.", "eng").documentId();
        setStatus(id, DocumentStatus.FAILED);

        assertThat(put("T", "Body.", "eng").outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(jobs(id)).isEqualTo(2);
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.QUEUED);
    }

    @Test
    void onlyTheGroupsChangedOnAnIndexedDocumentIsAclUpdatedWithoutAJob() {
        UUID id = put("T", "Body.", "eng").documentId();
        new IndexingWorker(fakes.outbox, fakes.documents, fakes.chunks,
                new HeadingParagraphSentenceChunker(new dev.raggateway.core.CharRatioTokenEstimator(), 400, 50, 400),
                new FakeEmbedder(), 4, Duration.ofMinutes(5), 20).pollOnce();

        assertThat(put("T", "Body.", "sre").outcome()).isEqualTo(Outcome.ACL_UPDATED);

        IndexedDocument doc = fakes.documents.get(id);
        assertThat(doc.aclGroups()).containsExactly("sre");
        assertThat(doc.contentHash()).isEqualTo(DocumentHash.of("T", "Body.", Set.of("sre")));
        assertThat(doc.status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(fakes.chunks.rows(id)).isNotEmpty()
                .allSatisfy(c -> assertThat(c.aclGroups()).containsExactly("sre"));
        assertThat(jobs(id)).isEqualTo(1);
        // And again with the new groups: unchanged.
        assertThat(put("T", "Body.", "sre").outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void aGroupChangeOnADocumentStillQueuedOrFailedQueuesAFullJob() {
        UUID id = put("T", "Body.", "eng").documentId();
        assertThat(put("T", "Body.", "sre").outcome()).isEqualTo(Outcome.QUEUED);
        setStatus(id, DocumentStatus.FAILED);
        assertThat(put("T", "Body.", "ops").outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(jobs(id)).isEqualTo(3);
    }

    @Test
    void aTitleOrContentChangeOnAnIndexedDocumentIsQueuedEvenWithOtherGroups() {
        UUID id = put("T", "Body.", "eng").documentId();
        setStatus(id, DocumentStatus.INDEXED);
        assertThat(put("T2", "Body.", "sre").outcome()).isEqualTo(Outcome.QUEUED);
        setStatus(id, DocumentStatus.INDEXED);
        assertThat(put("T2", "Body 2.", "sre").outcome()).isEqualTo(Outcome.QUEUED);
        assertThat(jobs(id)).isEqualTo(3);
    }

    @Test
    void outcomeWireValues() {
        assertThat(Outcome.QUEUED.wireValue()).isEqualTo("queued");
        assertThat(Outcome.UNCHANGED.wireValue()).isEqualTo("unchanged");
        assertThat(Outcome.ACL_UPDATED.wireValue()).isEqualTo("acl_updated");
    }
}
