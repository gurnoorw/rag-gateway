package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Embedder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory stand-ins for the ingestion stores, coded against the {@link OutboxStore}, {@link DocumentStore} and
 * {@link ChunkStore} contracts (the JDBC outbox and document stores are written in parallel, t09 Dev A). Every
 * call is appended to one shared {@link #events} log, so tests can assert the order of steps across stores.
 */
final class IngestFakes {

    final List<String> events = new CopyOnWriteArrayList<>();
    final Outbox outbox = new Outbox();
    final Documents documents = new Documents();
    final Chunks chunks = new Chunks();

    /** Saves a document and queues its job, as the write side does in one transaction. */
    UUID put(String tenant, String collection, String docId, String title, String content, String... groups) {
        var upsert = new DocumentUpsert(tenant, collection, docId, title, content, java.util.Set.of(groups));
        UUID id = documents.upsert(upsert);
        outbox.insert(id, upsert.contentHash());
        return id;
    }

    enum JobStatus { PENDING, IN_PROGRESS, DONE, DEAD }

    static final class Job {
        final UUID id = UUID.randomUUID();
        final UUID documentId;
        final String contentHash;
        volatile JobStatus status = JobStatus.PENDING;
        volatile int attempts;
        volatile String lastError;

        Job(UUID documentId, String contentHash) {
            this.documentId = documentId;
            this.contentHash = contentHash;
        }
    }

    final class Outbox implements OutboxStore {
        final Map<UUID, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<Integer> claimMax = new CopyOnWriteArrayList<>();
        final List<Duration> claimLease = new CopyOnWriteArrayList<>();

        @Override
        public UUID insert(UUID documentId, String contentHash) {
            Job job = new Job(documentId, contentHash);
            jobs.put(job.id, job);
            return job.id;
        }

        @Override
        public synchronized List<OutboxEvent> claimBatch(int max, Duration lease) {
            claimMax.add(max);
            claimLease.add(lease);
            List<OutboxEvent> claimed = new ArrayList<>();
            synchronized (jobs) {
                for (Job job : jobs.values()) {
                    if (claimed.size() == max) break;
                    if (job.status != JobStatus.PENDING) continue;
                    job.status = JobStatus.IN_PROGRESS;
                    job.attempts++;
                    claimed.add(new OutboxEvent(job.id, job.documentId, job.contentHash, job.attempts));
                }
            }
            events.add("claim " + claimed.size());
            return claimed;
        }

        @Override
        public void markDone(UUID id) {
            events.add("done " + id);
            Job job = jobs.get(id);
            if (job != null) job.status = JobStatus.DONE;
        }

        @Override
        public void release(UUID id, String error) {
            events.add("release " + id);
            Job job = jobs.get(id);
            job.status = JobStatus.PENDING;
            job.lastError = error;
        }

        @Override
        public void markDead(UUID id, String error) {
            Job job = jobs.get(id);
            job.status = JobStatus.DEAD;
            job.lastError = error;
        }

        Job job(UUID id) {
            return jobs.get(id);
        }

        Job jobFor(UUID documentId) {
            synchronized (jobs) {
                return jobs.values().stream().filter(j -> j.documentId.equals(documentId)).findFirst().orElseThrow();
            }
        }
    }

    final class Documents implements DocumentStore {
        final Map<UUID, IndexedDocument> rows = new ConcurrentHashMap<>();

        @Override
        public UUID upsert(DocumentUpsert d) {
            UUID id = find(d.tenantId(), d.collection(), d.docId()).map(IndexedDocument::id).orElseGet(UUID::randomUUID);
            int count = rows.containsKey(id) ? rows.get(id).chunkCount() : 0;
            rows.put(id, new IndexedDocument(id, d.tenantId(), d.collection(), d.docId(), d.title(), d.content(),
                    d.aclGroups(), d.contentHash(), DocumentStatus.QUEUED, count, Instant.now()));
            return id;
        }

        @Override
        public Optional<IndexedDocument> find(String tenantId, String collection, String docId) {
            return rows.values().stream().filter(r -> r.tenantId().equals(tenantId)
                    && r.collection().equals(collection) && r.docId().equals(docId)).findFirst();
        }

        @Override
        public Optional<IndexedDocument> findById(UUID id) {
            events.add("find " + id);
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void markFailed(UUID id) {
            rows.computeIfPresent(id, (k, r) -> with(r, DocumentStatus.FAILED, r.chunkCount()));
        }

        IndexedDocument get(UUID id) {
            return rows.get(id);
        }
    }

    /** A stored chunk row: what {@link JdbcChunkStore} writes, copied from the document. */
    record ChunkRow(UUID documentId, String tenantId, String collection, String docId, String title, int ordinal,
                    String text, float[] embedding, java.util.Set<String> aclGroups) {
    }

    final class Chunks implements ChunkStore {
        final Map<UUID, List<ChunkRow>> byDocument = new ConcurrentHashMap<>();
        volatile Consumer<IndexedDocument> beforeReplace = d -> { };

        @Override
        public void replaceForDocument(IndexedDocument d, List<ChunkText> chunks, List<float[]> vectors) {
            events.add("replace " + d.id());
            beforeReplace.accept(d);
            if (chunks.size() != vectors.size()) throw new IllegalArgumentException("sizes differ");
            List<ChunkRow> rows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkText c = chunks.get(i);
                rows.add(new ChunkRow(d.id(), d.tenantId(), d.collection(), d.docId(), d.title(), c.ordinal(), c.text(),
                        vectors.get(i), d.aclGroups()));
            }
            byDocument.put(d.id(), List.copyOf(rows));
            documents.rows.computeIfPresent(d.id(), (k, r) -> with(r, DocumentStatus.INDEXED, rows.size()));
        }

        @Override
        public int countForDocument(UUID documentId) {
            return byDocument.getOrDefault(documentId, List.of()).size();
        }

        List<ChunkRow> rows(UUID documentId) {
            return byDocument.getOrDefault(documentId, List.of());
        }
    }

    private static IndexedDocument with(IndexedDocument r, DocumentStatus status, int count) {
        return new IndexedDocument(r.id(), r.tenantId(), r.collection(), r.docId(), r.title(), r.content(),
                r.aclGroups(), r.contentHash(), status, count, r.updatedAt());
    }

    /** Wraps an embedder; records every call's inputs, and can be made to fail. */
    final class CountingEmbedder implements Embedder {
        final Embedder delegate;
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        volatile java.util.function.Predicate<List<String>> failWhen = inputs -> false;
        volatile Runnable duringCall = () -> { };

        CountingEmbedder(Embedder delegate) {
            this.delegate = delegate;
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            events.add("embed " + texts.size());
            calls.add(List.copyOf(texts));
            duringCall.run();
            if (failWhen.test(texts)) throw new OllamaEmbedder.EmbeddingException("Ollama embed returned HTTP 500");
            return delegate.embedDocuments(texts);
        }

        @Override
        public float[] embedQuery(String text) {
            return delegate.embedQuery(text);
        }

        List<Integer> batchSizes() {
            return calls.stream().map(List::size).toList();
        }
    }
}
