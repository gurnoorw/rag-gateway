package dev.raggateway.rag.ingest;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated {@code PUT} body plus its address (D-47). The controller builds it from the path, the
 * caller's tenant and the JSON body {@code {"title", "content", "acl_groups"}}; an invalid value throws
 * {@link IllegalArgumentException}, which the controller maps to 400 {@code invalid_request}.
 *
 * @param tenantId   the admin key's tenant, never from the body (C17)
 * @param collection path segment, {@link #ID}
 * @param docId      path segment, {@link #ID} (the corpus maps a file path to dots, e.g.
 *                   {@code concepts.workloads.controllers.job})
 * @param title      1..{@value #MAX_TITLE_CHARS} characters
 * @param content    non-blank; the byte limit is enforced on the request body before parsing (413)
 * @param aclGroups  1..{@value #MAX_GROUPS} groups, each {@link #GROUP}; any group of the tenant (an admin key
 *                   is tenant-wide, C16)
 */
public record DocumentUpsert(String tenantId, String collection, String docId, String title, String content,
                             Set<String> aclGroups) {

    /** Collection and document ids: URL-safe, no slash (Tomcat rejects an encoded slash in a path). */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    public static final Pattern GROUP = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    public static final int MAX_TITLE_CHARS = 500;
    public static final int MAX_GROUPS = 20;

    public DocumentUpsert {
        require(tenantId != null && !tenantId.isBlank(), "tenant is required");
        require(collection != null && ID.matcher(collection).matches(), "collection must match " + ID.pattern());
        require(docId != null && ID.matcher(docId).matches(), "document id must match " + ID.pattern());
        require(title != null && !title.isBlank() && title.length() <= MAX_TITLE_CHARS,
                "title must be 1.." + MAX_TITLE_CHARS + " characters");
        require(content != null && !content.isBlank(), "content must not be blank");
        require(aclGroups != null && !aclGroups.isEmpty() && aclGroups.size() <= MAX_GROUPS,
                "acl_groups must hold 1.." + MAX_GROUPS + " groups");
        aclGroups = Set.copyOf(aclGroups);
        for (String g : aclGroups) require(GROUP.matcher(g).matches(), "each ACL group must match " + GROUP.pattern());
    }

    /** The hash stored on the document and on its outbox job. */
    public String contentHash() {
        return DocumentHash.of(title, content, aclGroups);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
