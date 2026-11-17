package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The document API (C4, D-47, D-51), every endpoint admin-only:
 * <ul>
 *   <li>{@code PUT /v1/rag/collections/{collection}/documents/{docId}} with {@code {"title", "content",
 *       "acl_groups"}} saves the document and queues its indexing job in one transaction and answers 202
 *       {@code {"collection", "doc_id", "status"}}. The status is the PUT's outcome (t11): {@code queued} (saved and
 *       queued, not yet indexed), {@code unchanged} (same version, nothing written) or {@code acl_updated} (only the
 *       groups changed on an indexed document: document and chunks updated at once, nothing re-embedded). Non-admin,
 *       oversized and over-cap PUTs never get here: {@link DocumentWriteFilter} refuses them before the body is
 *       read.</li>
 *   <li>{@code GET} on the same URL returns the document's metadata and indexing state, never its content.</li>
 *   <li>{@code DELETE} on the same URL removes the document, its chunks and its jobs in one statement and answers
 *       204 whether or not it existed: idempotent, and another tenant's document is simply not there, so the answer
 *       reveals nothing (t11). A non-admin key gets 403; an invalid id 400, like GET.</li>
 * </ul>
 * The tenant always comes from the API key (C17), so another tenant's document at the same address is 404, never
 * 403: a caller learns nothing about ids it does not own. An invalid collection, document id, title, content or
 * group is 400 {@code invalid_request} with a message naming the field.
 */
@RestController
public class DocumentController {

    static final String PATH = "/v1/rag/collections/{collection}/documents/{docId}";

    /** PUT body. Unknown fields are ignored (Spring Boot's Jackson default). */
    public record DocumentBody(@JsonProperty("title") String title,
                               @JsonProperty("content") String content,
                               @JsonProperty("acl_groups") List<String> aclGroups) {
    }

    /** 202 body. */
    public record QueuedResponse(@JsonProperty("collection") String collection,
                                 @JsonProperty("doc_id") String docId,
                                 @JsonProperty("status") String status) {
    }

    /** GET body: everything but the content. Groups sorted, so the body is stable. */
    public record DocumentResponse(@JsonProperty("collection") String collection,
                                   @JsonProperty("doc_id") String docId,
                                   @JsonProperty("title") String title,
                                   @JsonProperty("acl_groups") List<String> aclGroups,
                                   @JsonProperty("status") String status,
                                   @JsonProperty("chunk_count") int chunkCount,
                                   @JsonProperty("content_hash") String contentHash,
                                   @JsonProperty("updated_at") Instant updatedAt) {

        static DocumentResponse of(IndexedDocument d) {
            return new DocumentResponse(d.collection(), d.docId(), d.title(), d.aclGroups().stream().sorted().toList(),
                    d.status().dbValue(), d.chunkCount(), d.contentHash(), d.updatedAt());
        }
    }

    private final DocumentService service;
    private final IngestMetrics metrics;

    public DocumentController(DocumentService service, IngestMetrics metrics) {
        this.service = Objects.requireNonNull(service, "service");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @PutMapping(PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QueuedResponse put(ApiKeyPrincipal caller, @PathVariable("collection") String collection,
                              @PathVariable("docId") String docId, @RequestBody DocumentBody body) {
        requireAdmin(caller); // DocumentWriteFilter refused non-admins already; kept so the rule holds without it
        DocumentUpsert upsert = toUpsert(caller, collection, docId, body);
        UpsertResult result = service.upsert(upsert);   // committed when this returns
        metrics.count(result.outcome());
        return new QueuedResponse(upsert.collection(), upsert.docId(), result.outcome().wireValue());
    }

    @DeleteMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(ApiKeyPrincipal caller, @PathVariable("collection") String collection,
                       @PathVariable("docId") String docId) {
        requireAdmin(caller);
        requireId("collection", collection);
        requireId("document id", docId);
        if (service.delete(caller.tenant().id(), collection, docId)) metrics.count(IngestMetrics.Result.DELETED);
    }

    @GetMapping(PATH)
    public DocumentResponse get(ApiKeyPrincipal caller, @PathVariable("collection") String collection,
                                @PathVariable("docId") String docId) {
        requireAdmin(caller);
        requireId("collection", collection);
        requireId("document id", docId);
        return service.find(caller.tenant().id(), collection, docId)
                .map(DocumentResponse::of)
                .orElseThrow(() -> new GatewayException(ErrorCode.NOT_FOUND, "document not found"));
    }

    static void requireAdmin(ApiKeyPrincipal caller) {
        if (!caller.admin()) {
            throw new GatewayException(ErrorCode.FORBIDDEN, "documents can only be written or read with an admin key");
        }
    }

    private static void requireId(String field, String value) {
        if (!DocumentUpsert.ID.matcher(value).matches()) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, field + " must match " + DocumentUpsert.ID.pattern());
        }
    }

    private static DocumentUpsert toUpsert(ApiKeyPrincipal caller, String collection, String docId, DocumentBody body) {
        List<String> groups = body.aclGroups();
        if (groups != null && groups.contains(null)) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "acl_groups must not contain null");
        }
        try {
            return new DocumentUpsert(caller.tenant().id(), collection, docId, body.title(), body.content(),
                    groups == null ? null : new LinkedHashSet<>(groups));
        } catch (IllegalArgumentException e) {
            // DocumentUpsert's messages name the field and the rule, never the rejected value.
            throw new GatewayException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
