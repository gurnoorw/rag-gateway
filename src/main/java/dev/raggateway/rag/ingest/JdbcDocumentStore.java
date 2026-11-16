package dev.raggateway.rag.ingest;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DocumentStore} on the {@code documents} table (V4) with {@link JdbcClient}, which joins the caller's
 * Spring transaction, so {@link DocumentService} gets the document and outbox writes in one transaction.
 *
 * <p>The upsert is one {@code INSERT .. ON CONFLICT (tenant_id, collection_id, doc_id) DO UPDATE}: no read
 * before the write, so two concurrent PUTs to the same address cannot both insert (the unique key decides) and
 * the surrogate id and chunk count of an existing row are kept.
 */
@Repository
public class JdbcDocumentStore implements DocumentStore {

    private static final String COLUMNS = """
            id, tenant_id, collection_id, doc_id, title, content, acl_groups, content_hash, status, chunk_count,
            updated_at""";

    private final JdbcClient jdbc;

    public JdbcDocumentStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public UUID upsert(DocumentUpsert d) {
        return jdbc.sql("""
                        INSERT INTO documents (tenant_id, collection_id, doc_id, title, content, acl_groups, content_hash)
                        VALUES (:tenant, :collection, :docId, :title, :content, CAST(:groups AS text[]), :hash)
                        ON CONFLICT (tenant_id, collection_id, doc_id) DO UPDATE
                        SET title = EXCLUDED.title, content = EXCLUDED.content, acl_groups = EXCLUDED.acl_groups,
                            content_hash = EXCLUDED.content_hash, status = 'queued', updated_at = now()
                        RETURNING id
                        """)
                .param("tenant", d.tenantId())
                .param("collection", d.collection())
                .param("docId", d.docId())
                .param("title", d.title())
                .param("content", d.content())
                .param("groups", d.aclGroups().stream().sorted().toArray(String[]::new))
                .param("hash", d.contentHash())
                .query(UUID.class)
                .single();
    }

    @Override
    public Optional<IndexedDocument> find(String tenantId, String collection, String docId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM documents
                        WHERE tenant_id = :tenant AND collection_id = :collection AND doc_id = :docId
                        """)
                .param("tenant", tenantId)
                .param("collection", collection)
                .param("docId", docId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public Optional<IndexedDocument> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    @Override
    public void markFailed(UUID id) {
        jdbc.sql("UPDATE documents SET status = 'failed', updated_at = now() WHERE id = :id").param("id", id).update();
    }

    private static IndexedDocument map(ResultSet rs) throws SQLException {
        return new IndexedDocument(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("collection_id"),
                rs.getString("doc_id"),
                rs.getString("title"),
                rs.getString("content"),
                groups(rs.getArray("acl_groups")),
                rs.getString("content_hash"),
                DocumentStatus.fromDb(rs.getString("status")),
                rs.getInt("chunk_count"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Set<String> groups(Array array) throws SQLException {
        if (array == null) return Set.of();
        try {
            return Set.copyOf(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }
}
