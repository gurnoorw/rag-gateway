package dev.raggateway.core;

import java.util.Set;

/**
 * The authenticated caller: one API key of one tenant. Groups and the admin flag live on the key
 * (DECISIONS.md C16): a tenant has several keys, so the ACL feature can show two callers of the same
 * tenant seeing different chunks. Holds no secret: the presented key is never kept after hashing.
 *
 * @param keyId  database id of the key (not the key)
 * @param tenant owning tenant
 * @param groups ACL groups used by retrieval (t10, t12); empty means the key retrieves nothing
 * @param admin  may write documents (t09, t11)
 */
public record ApiKeyPrincipal(String keyId, Tenant tenant, Set<String> groups, boolean admin) {
    public ApiKeyPrincipal {
        groups = Set.copyOf(groups);
    }
}
