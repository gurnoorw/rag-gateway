package dev.raggateway.rag.query;

import dev.raggateway.rag.PgVector;
import dev.raggateway.rag.RagProperties;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The two retrieval legs on the {@code chunks} table (t10, DECISIONS.md D-49), running the {@link RetrievalSql}
 * statements unchanged. Tenant, collection and ACL groups are predicates inside each statement (C16, C17); rows
 * come back exactly as the database ordered them, and nothing is filtered here.
 * <ul>
 *   <li>{@link #keyword}: one auto-commit statement, so it holds a pool connection only while it runs.</li>
 *   <li>{@link #vector}: a read-only transaction that first applies
 *       {@link RetrievalSql#vectorSessionSettings} ({@code SET LOCAL}, so they end with the transaction and never
 *       leak to the next borrower of the connection), then the distance-ordered statement. The caller embeds the
 *       question before calling, so no connection is held while the embedder works.</li>
 * </ul>
 * A database failure propagates as Spring's {@code DataAccessException}; the retriever treats it as an error in
 * every mode. Thread-safe.
 */
@Repository
public class ChunkSearchRepository {

    /** One candidate row in leg order. {@code chunkId} is the uuid's canonical text, so it orders like Postgres. */
    public record Row(String chunkId, String docId, String title, int ordinal, String text) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getString("chunk_id"), rs.getString("doc_id"),
            rs.getString("title"), rs.getInt("ordinal"), rs.getString("text"));

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final List<String> vectorSettings;

    public ChunkSearchRepository(JdbcClient jdbc, PlatformTransactionManager transactions, RagProperties rag) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.vectorSettings = RetrievalSql.vectorSessionSettings(rag.retrieval());
    }

    /** Keyword leg: Postgres full-text ranking (C18), OR between terms (C19), best first. */
    public List<Row> keyword(String tenant, String collection, Set<String> groups, String question, int limit) {
        return jdbc.sql(RetrievalSql.KEYWORD)
                .param("tenant", tenant).param("collection", collection).param("groups", array(groups))
                .param("question", question).param("limit", limit)
                .query(ROW).list();
    }

    /** Vector leg: cosine distance to {@code vector}, nearest first, through the HNSW index with the D-49 settings. */
    public List<Row> vector(String tenant, String collection, Set<String> groups, float[] vector, int limit) {
        String literal = PgVector.literal(vector);
        String[] acl = array(groups);
        return readOnly.execute(status -> {
            vectorSettings.forEach(s -> jdbc.sql(s).update());
            return jdbc.sql(RetrievalSql.VECTOR)
                    .param("tenant", tenant).param("collection", collection).param("groups", acl)
                    .param("vector", literal).param("limit", limit)
                    .query(ROW).list();
        });
    }

    private static String[] array(Set<String> groups) {
        return groups.stream().sorted().toArray(String[]::new);
    }
}
