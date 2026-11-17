package dev.raggateway.rag.query;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Counts leaked chunks (t12 round prep, D-51): given what a caller was shown, which of those chunk ids the caller
 * was not allowed to see. Independent of the retrieval SQL (it re-derives visibility from the chunk rows), so a bug
 * in a retrieval predicate cannot hide itself. Used by the t12 leakage suite and by the t13 evaluation's
 * "unauthorised asker" check (the eval source set sees main code, not tests). Never on a request path.
 *
 * <p>A chunk id is leaked when it is not a chunk of {@code tenant} and {@code collection} whose groups overlap the
 * caller's groups, or when it does not exist at all (an id the database never served is also a defect).
 */
public final class AclAudit {

    static final String VISIBLE = """
            SELECT c.chunk_id::text
              FROM chunks c
             WHERE c.chunk_id::text = ANY(CAST(:ids AS text[]))
               AND c.tenant_id = :tenant
               AND c.collection_id = :collection
               AND c.acl_groups && CAST(:groups AS text[])
            """;

    private final JdbcClient jdbc;

    public AclAudit(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** The shown chunk ids the caller may not see, in the order given; empty means no leak. */
    public List<String> leakedChunkIds(String tenant, String collection, Set<String> callerGroups,
                                       Collection<String> shownChunkIds) {
        if (shownChunkIds.isEmpty()) return List.of();
        String[] ids = shownChunkIds.stream().distinct().toArray(String[]::new);
        Set<String> visible = Set.copyOf(jdbc.sql(VISIBLE)
                .param("ids", ids).param("tenant", tenant).param("collection", collection)
                .param("groups", callerGroups.stream().sorted().toArray(String[]::new))
                .query(String.class).list());
        return shownChunkIds.stream().distinct().filter(id -> !visible.contains(id)).toList();
    }
}
