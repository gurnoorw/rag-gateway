package dev.raggateway.eval;

import dev.raggateway.core.TokenEstimator;
import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.ingest.DocumentHash;
import dev.raggateway.rag.ingest.DocumentStatus;
import dev.raggateway.rag.ingest.HeadingParagraphSentenceChunker;
import dev.raggateway.rag.ingest.IndexedDocument;
import dev.raggateway.rag.ingest.JdbcChunkStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the eval's own collections (t13, DECISIONS.md D-53): it reads the cleaned content of a loaded collection
 * ({@code k8s-docs}) from the {@code documents} table, so the eval sees exactly what the gateway indexed and never
 * re-cleans files, and re-chunks it in-process into one collection per chunk size in its own tenant
 * ({@code eval}): {@code k8s-c200}, {@code k8s-c400} and {@code k8s-c800}, overlap 25/50/100, maximum = target.
 * <ul>
 *   <li><b>Same code as production.</b> Chunks come from {@link HeadingParagraphSentenceChunker} and are written by
 *       {@link JdbcChunkStore}, so the tsvector, groups and SQL are the ones retrieval reads. Each chunk is embedded
 *       as {@code <title>\n\n<chunk text>}, as the indexing worker does (D-47).</li>
 *   <li><b>Incremental.</b> Each eval document stores an index version: a hash over the source document's
 *       {@link DocumentHash}, the chunk settings and the embedder. A document whose version is unchanged is skipped,
 *       so a second run reuses the index (and its chunk ids, so tie order and metrics repeat, AC-t13-9). Documents
 *       gone from the source are deleted.</li>
 *   <li><b>Embedder.</b> A keyword-only run needs vectors only because the column is {@code NOT NULL}: it indexes
 *       with the fake embedder (its vectors are never compared) and accepts an index already built with the real
 *       model, so it never overwrites real vectors. A real-model run re-embeds anything not built with that
 *       model.</li>
 *   <li><b>Groups.</b> Each eval document keeps its source document's ACL groups, so the ACL eval sees the same
 *       visibility as the demo collection.</li>
 * </ul>
 * Never on a request path; single-threaded.
 */
public final class EvalIndexer {

    /** The three compared chunk sizes (D-53); the overlap is an eighth of the target, as 50 is of 400. */
    public enum ChunkSize {
        C200(200, 25), C400(400, 50), C800(800, 100);

        public final int targetTokens;
        public final int overlapTokens;

        ChunkSize(int targetTokens, int overlapTokens) {
            this.targetTokens = targetTokens;
            this.overlapTokens = overlapTokens;
        }

        /** Maximum = target (D-53). */
        public int maxTokens() {
            return targetTokens;
        }

        /** {@code k8s-c200}, {@code k8s-c400}, {@code k8s-c800}. */
        public String collection() {
            return "k8s-c" + targetTokens;
        }

        public static ChunkSize ofTarget(int target) {
            return Arrays.stream(values()).filter(s -> s.targetTokens == target).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("chunk size must be 200, 400 or 800, got " + target));
        }
    }

    /** A document of the source collection, as the gateway stored it after cleaning. */
    public record SourceDocument(String docId, String title, String content, Set<String> groups) {
        public SourceDocument {
            groups = Set.copyOf(groups);
        }
    }

    /** What one {@link #index} call did. */
    public record Report(ChunkSize size, int documents, int indexed, int unchanged, int deleted, int chunks) {
    }

    /** Embedder identities stored in the index version. */
    public static final String FAKE = "fake";
    public static final String NOMIC = "nomic-embed-text";

    private static final int EMBED_BATCH = 20;

    private final JdbcClient jdbc;
    private final JdbcChunkStore chunks;
    private final Embedder embedder;
    private final String embedderId;
    private final Set<String> acceptedEmbedders;
    private final TokenEstimator estimator;

    /**
     * @param embedder          writes the vectors of re-indexed documents
     * @param embedderId        {@link #FAKE} or {@link #NOMIC}: which embedder {@code embedder} is
     * @param acceptedEmbedders index versions built with these embedders are reused as they are (a keyword-only run
     *                          passes both; a real-model run only its own)
     */
    public EvalIndexer(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactions, Embedder embedder,
                       String embedderId, Set<String> acceptedEmbedders, TokenEstimator estimator) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        this.chunks = new JdbcChunkStore(jdbcTemplate, transactions);
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.embedderId = Objects.requireNonNull(embedderId, "embedderId");
        this.acceptedEmbedders = Set.copyOf(acceptedEmbedders);
        if (!this.acceptedEmbedders.contains(embedderId)) {
            throw new IllegalArgumentException("the embedder that writes must also be accepted");
        }
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    /** Every document of the source collection, ordered by doc id. Fails if there is none. */
    public List<SourceDocument> readSource(String tenant, String collection) {
        List<SourceDocument> docs = jdbc.sql("""
                        SELECT doc_id, title, content, acl_groups FROM documents
                         WHERE tenant_id = :tenant AND collection_id = :collection
                         ORDER BY doc_id""")
                .param("tenant", tenant).param("collection", collection)
                .query((rs, n) -> new SourceDocument(rs.getString("doc_id"), rs.getString("title"),
                        rs.getString("content"), groups(rs.getArray("acl_groups"))))
                .list();
        if (docs.isEmpty()) {
            throw new IllegalStateException("no documents in collection " + collection + " of tenant " + tenant
                    + ": load the corpus first (scripts/corpus/load_corpus.py)");
        }
        return docs;
    }

    /** Creates the eval tenant if it is missing (documents reference tenants). It has no keys. */
    public void ensureTenant(String tenant) {
        jdbc.sql("""
                        INSERT INTO tenants (id, name, tokens_per_minute, monthly_budget_usd)
                        VALUES (:id, 'RAG eval (t13), no keys', 1, 0) ON CONFLICT (id) DO NOTHING""")
                .param("id", tenant).update();
    }

    /** Brings {@code size.collection()} of {@code tenant} in line with {@code source}. */
    public Report index(List<SourceDocument> source, String tenant, ChunkSize size) {
        ensureTenant(tenant);
        String collection = size.collection();
        var chunker = new HeadingParagraphSentenceChunker(estimator, size.targetTokens, size.overlapTokens,
                size.maxTokens());
        Map<String, String> existing = jdbc.sql("""
                        SELECT doc_id, content_hash FROM documents
                         WHERE tenant_id = :tenant AND collection_id = :collection AND status = 'indexed'""")
                .param("tenant", tenant).param("collection", collection)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        int indexed = 0, unchanged = 0;
        for (SourceDocument doc : source) {
            String current = existing.get(doc.docId());
            boolean reusable = current != null && acceptedEmbedders.stream()
                    .anyMatch(e -> version(doc, size, e).equals(current));
            if (reusable) {
                unchanged++;
                continue;
            }
            String version = version(doc, size, embedderId);
            UUID id = upsert(tenant, collection, doc, version);
            List<ChunkText> pieces = chunker.split(doc.title(), doc.content());
            List<float[]> vectors = embed(doc.title(), pieces);
            var row = new IndexedDocument(id, tenant, collection, doc.docId(), doc.title(), doc.content(),
                    doc.groups(), version, DocumentStatus.QUEUED, 0, null);
            if (!chunks.replaceForDocument(row, pieces, vectors)) {
                throw new IllegalStateException("eval document " + doc.docId() + " changed while indexing");
            }
            indexed++;
        }
        int deleted = jdbc.sql("""
                        DELETE FROM documents WHERE tenant_id = :tenant AND collection_id = :collection
                           AND NOT (doc_id = ANY(CAST(:ids AS text[])))""")
                .param("tenant", tenant).param("collection", collection)
                .param("ids", source.stream().map(SourceDocument::docId).toArray(String[]::new))
                .update();
        int chunkCount = jdbc.sql("SELECT count(*) FROM chunks WHERE tenant_id = :tenant AND collection_id = :collection")
                .param("tenant", tenant).param("collection", collection).query(Integer.class).single();
        return new Report(size, source.size(), indexed, unchanged, deleted, chunkCount);
    }

    /** The groups present in the source, sorted: a caller with all of them may see every document. */
    public static Set<String> allGroups(List<SourceDocument> source) {
        Set<String> all = new TreeSet<>();
        source.forEach(d -> all.addAll(d.groups()));
        return new LinkedHashSet<>(all);
    }

    /** The index version stored as the eval document's {@code content_hash}. */
    static String version(SourceDocument doc, ChunkSize size, String embedder) {
        String text = "eval-index-v1|" + embedder + "|" + size.targetTokens + "/" + size.overlapTokens + "/"
                + size.maxTokens() + "|" + DocumentHash.of(doc.title(), doc.content(), doc.groups());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    private UUID upsert(String tenant, String collection, SourceDocument doc, String version) {
        return jdbc.sql("""
                        INSERT INTO documents (tenant_id, collection_id, doc_id, title, content, acl_groups, content_hash)
                        VALUES (:tenant, :collection, :docId, :title, :content, CAST(:groups AS text[]), :hash)
                        ON CONFLICT (tenant_id, collection_id, doc_id) DO UPDATE
                        SET title = EXCLUDED.title, content = EXCLUDED.content, acl_groups = EXCLUDED.acl_groups,
                            content_hash = EXCLUDED.content_hash, status = 'queued', updated_at = now()
                        RETURNING id""")
                .param("tenant", tenant).param("collection", collection).param("docId", doc.docId())
                .param("title", doc.title()).param("content", doc.content())
                .param("groups", doc.groups().stream().sorted().toArray(String[]::new))
                .param("hash", version)
                .query(UUID.class).single();
    }

    private List<float[]> embed(String title, List<ChunkText> pieces) {
        List<float[]> vectors = new ArrayList<>(pieces.size());
        for (int from = 0; from < pieces.size(); from += EMBED_BATCH) {
            List<String> inputs = pieces.subList(from, Math.min(pieces.size(), from + EMBED_BATCH)).stream()
                    .map(c -> title + "\n\n" + c.text())   // as IndexingWorker.embeddingText (D-47)
                    .toList();
            List<float[]> out = embedder.embedDocuments(inputs);
            if (out.size() != inputs.size()) {
                throw new IllegalStateException("embedder returned " + out.size() + " vectors for " + inputs.size());
            }
            vectors.addAll(out);
        }
        return vectors;
    }

    private static Set<String> groups(Array array) throws SQLException {
        return Set.of((String[]) array.getArray());
    }
}
