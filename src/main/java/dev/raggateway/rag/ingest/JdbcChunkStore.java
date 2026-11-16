package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The {@code chunks} table on plain JDBC (t09, D-47).
 * <ul>
 *   <li>{@link #replaceForDocument} is one transaction: delete the document's chunks, insert the new ones in JDBC
 *       batches of at most {@value #INSERT_BATCH_ROWS} rows, set the document's status to {@code indexed} and its
 *       chunk count. Readers see the old set or the new one; running it twice gives the same rows. A failure
 *       anywhere rolls all of it back.</li>
 *   <li>Vectors are bound as pgvector's text form ({@code [x,y,...]}) with {@code CAST(? AS vector)}: no extra
 *       driver dependency, and the column type still checks the length (768).</li>
 *   <li>Tenant, collection, doc id, title and ACL groups are copied from the document row the worker read (C16,
 *       C17), so retrieval filters on the chunk row alone.</li>
 *   <li>The transaction is a {@link TransactionTemplate} around the method body, not an annotation: the class
 *       works the same outside Spring (tests on a bare DataSource), and the connection is taken only here, after
 *       the embedder has returned.</li>
 * </ul>
 */
public final class JdbcChunkStore implements ChunkStore {

    static final int INSERT_BATCH_ROWS = 100;

    private static final String DELETE = "DELETE FROM chunks WHERE document_id = ?";
    private static final String INSERT = """
            INSERT INTO chunks (document_id, tenant_id, collection_id, doc_id, title, ordinal, text, embedding, acl_groups)
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)
            """;
    private static final String MARK_INDEXED =
            "UPDATE documents SET status = 'indexed', chunk_count = ? WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcChunkStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public void replaceForDocument(IndexedDocument document, List<ChunkText> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException(chunks.size() + " chunks but " + vectors.size() + " vectors");
        }
        List<Row> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) rows.add(new Row(chunks.get(i), vectors.get(i)));
        String[] groups = new TreeSet<>(document.aclGroups()).toArray(String[]::new);

        tx.executeWithoutResult(status -> {
            jdbc.update(DELETE, document.id());
            jdbc.batchUpdate(INSERT, rows, INSERT_BATCH_ROWS, (ps, row) -> {
                Array acl = ps.getConnection().createArrayOf("text", groups);
                ps.setObject(1, document.id());
                ps.setString(2, document.tenantId());
                ps.setString(3, document.collection());
                ps.setString(4, document.docId());
                ps.setString(5, document.title());
                ps.setInt(6, row.chunk().ordinal());
                ps.setString(7, row.chunk().text());
                ps.setString(8, vectorLiteral(row.vector()));
                ps.setArray(9, acl);
            });
            int updated = jdbc.update(MARK_INDEXED, rows.size(), document.id());
            if (updated != 1) throw new IllegalStateException("document " + document.id() + " no longer exists");
        });
    }

    @Override
    public int countForDocument(UUID documentId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class,
                documentId);
        return count == null ? 0 : count;
    }

    /** pgvector text form. {@link Float#toString} round-trips exactly and pgvector parses its exponent form. */
    static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) throw new IllegalArgumentException("vector value " + i + " is not finite");
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private record Row(ChunkText chunk, float[] vector) {
    }
}
