package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.rag.ingest.ControllableOutboxConfig.ControllableOutboxStore;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The document API over real HTTP (D-47): PUT and GET, admin only, tenant-scoped, size and write-cap admission
 * (AC-t09-2, -12, -13, -14). Runs with {@code gateway.rag.max-concurrent-writes=1} so AC-t09-13 can hold one write
 * inside its transaction; the other tests write one document at a time, so the cap of 1 does not affect them.
 * The size limit is the default (1 MB).
 */
@Import(ControllableOutboxConfig.class)
@TestPropertySource(properties = "gateway.rag.max-concurrent-writes=1") // same context as DocumentServiceTxIT
class DocumentControllerIT extends AbstractIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int LIMIT = 1024 * 1024;
    static final String ADMIN_A = "rgw_test_docs_admin_a_" + UUID.randomUUID().toString().replace("-", "");
    static final String READER_A = "rgw_test_docs_reader_a_" + UUID.randomUUID().toString().replace("-", "");
    static final String ADMIN_B = "rgw_test_docs_admin_b_" + UUID.randomUUID().toString().replace("-", "");
    static final String TENANT_A = "docs-tenant-a";
    static final String TENANT_B = "docs-tenant-b";

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    ControllableOutboxStore outbox;

    @BeforeEach
    void seed() {
        outbox.reset();
        TestData.insertTenant(jdbc, TENANT_A, 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, TENANT_A, ADMIN_A, Set.of(), true);
        TestData.insertKey(jdbc, TENANT_A, READER_A, Set.of("eng"), false);
        TestData.insertTenant(jdbc, TENANT_B, 100_000, BigDecimal.TEN, Set.of());
        TestData.insertKey(jdbc, TENANT_B, ADMIN_B, Set.of(), true);
    }

    @AfterEach
    void disarm() {
        outbox.reset();
    }

    // ---- helpers ----

    String url(String collection, String docId) {
        return baseUrl() + "/v1/rag/collections/" + collection + "/documents/" + docId;
    }

    static String body(String title, String content, List<String> groups) throws Exception {
        return JSON.writeValueAsString(Map.of("title", title, "content", content, "acl_groups", groups));
    }

    HttpResponse<String> put(String key, String collection, String docId, String json) throws Exception {
        return send(key, url(collection, docId), HttpRequest.BodyPublishers.ofString(json));
    }

    HttpResponse<String> send(String key, String url, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").PUT(body);
        if (key != null) request.header("Authorization", "Bearer " + key);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String key, String collection, String docId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(collection, docId))).timeout(Duration.ofSeconds(30)).GET();
        if (key != null) request.header("Authorization", "Bearer " + key);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    /** The error body names this response's own request id (D-15). */
    static JsonNode error(HttpResponse<String> response) throws Exception {
        JsonNode error = json(response).get("error");
        assertThat(error.get("request_id").asText()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        return error;
    }

    int documents(String tenant) {
        return jdbc.sql("SELECT count(*) FROM documents WHERE tenant_id = :t").param("t", tenant).query(Integer.class).single();
    }

    int jobs(String tenant) {
        return jdbc.sql("SELECT count(*) FROM outbox o JOIN documents d ON d.id = o.document_id WHERE d.tenant_id = :t")
                .param("t", tenant).query(Integer.class).single();
    }

    // ---- happy path ----

    @Test
    void putWithAdminKeyIs202QueuedAndWritesTheDocumentAndOneJob() throws Exception {
        var response = put(ADMIN_A, "k8s", "concepts.workloads.pods",
                body("Pods", "A Pod is the smallest deployable unit.", List.of("eng", "sre")));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json(response)).isEqualTo(JSON.readTree("""
                {"collection":"k8s","doc_id":"concepts.workloads.pods","status":"queued"}"""));
        assertThat(documents(TENANT_A)).isEqualTo(1);
        assertThat(jobs(TENANT_A)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM outbox").query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void getReturnsMetadataAndStateButNeverTheContent() throws Exception {
        String content = "A Pod is the smallest deployable unit.";
        put(ADMIN_A, "k8s", "concepts.pods", body("Pods", content, List.of("sre", "eng")));

        var response = get(ADMIN_A, "k8s", "concepts.pods");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode doc = json(response);
        assertThat(doc.get("collection").asText()).isEqualTo("k8s");
        assertThat(doc.get("doc_id").asText()).isEqualTo("concepts.pods");
        assertThat(doc.get("title").asText()).isEqualTo("Pods");
        assertThat(doc.get("acl_groups")).isEqualTo(JSON.readTree("[\"eng\",\"sre\"]"));
        assertThat(doc.get("status").asText()).isEqualTo("queued");
        assertThat(doc.get("chunk_count").asInt()).isZero();
        assertThat(doc.get("content_hash").asText())
                .isEqualTo(DocumentHash.of("Pods", content, Set.of("eng", "sre")));
        assertThat(doc.get("updated_at").asText()).matches("\\d{4}-\\d\\d-\\d\\dT.*Z");
        assertThat(doc.has("content")).isFalse();
        assertThat(response.body()).doesNotContain(content);
    }

    @Test
    void putAgainReplacesTheDocumentAndQueuesAnotherJob() throws Exception {
        put(ADMIN_A, "k8s", "d1", body("Old", "old text", List.of("eng")));
        var response = put(ADMIN_A, "k8s", "d1", body("New", "new text", List.of("support")));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(documents(TENANT_A)).isEqualTo(1);
        assertThat(jobs(TENANT_A)).isEqualTo(2);
        assertThat(json(get(ADMIN_A, "k8s", "d1")).get("title").asText()).isEqualTo("New");
    }

    @Test
    void unknownBodyFieldsAreIgnored() throws Exception {
        var response = put(ADMIN_A, "k8s", "d1", """
                {"title":"T","content":"c","acl_groups":["eng"],"tags":["x"],"aclGroups":["ignored"]}""");
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json(get(ADMIN_A, "k8s", "d1")).get("acl_groups")).isEqualTo(JSON.readTree("[\"eng\"]"));
    }

    // ---- AC-t09-2: admin only ----

    @Test
    void ac_t09_2_putWithNonAdminKeyIs403AndWritesNothing() throws Exception {
        var response = put(READER_A, "k8s", "d1", body("T", "c", List.of("eng")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response).get("code").asText()).isEqualTo("forbidden");
        assertThat(documents(TENANT_A)).isZero();
        assertThat(jobs(TENANT_A)).isZero();
    }

    @Test
    void getWithNonAdminKeyIs403() throws Exception {
        put(ADMIN_A, "k8s", "d1", body("T", "c", List.of("eng")));
        var response = get(READER_A, "k8s", "d1");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(error(response).get("code").asText()).isEqualTo("forbidden");
    }

    @Test
    void missingOrUnknownKeyIs401AndWritesNothing() throws Exception {
        assertThat(put(null, "k8s", "d1", body("T", "c", List.of("eng"))).statusCode()).isEqualTo(401);
        assertThat(put("rgw_test_not_a_key", "k8s", "d1", body("T", "c", List.of("eng"))).statusCode()).isEqualTo(401);
        assertThat(get(null, "k8s", "d1").statusCode()).isEqualTo(401);
        assertThat(documents(TENANT_A)).isZero();
    }

    // ---- AC-t09-12: body limit ----

    static String bodyOfSize(int bytes) throws Exception {
        String empty = body("Big", "", List.of("eng"));
        String content = "x".repeat(bytes - empty.length());
        String json = body("Big", content, List.of("eng"));
        assertThat(json.getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
        return json;
    }

    @Test
    void ac_t09_12_bodyOverTheLimitByContentLengthIs413AndWritesNothing() throws Exception {
        var response = put(ADMIN_A, "k8s", "big", bodyOfSize(LIMIT + 1));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(error(response).get("code").asText()).isEqualTo("payload_too_large");
        assertThat(documents(TENANT_A)).isZero();
        assertThat(jobs(TENANT_A)).isZero();
    }

    @Test
    void ac_t09_12_chunkedBodyOverTheLimitIs413AndWritesNothing() throws Exception {
        byte[] bytes = bodyOfSize(LIMIT + 1).getBytes(StandardCharsets.UTF_8);
        // An InputStream publisher has no known length: the client sends Transfer-Encoding: chunked.
        var response = send(ADMIN_A, url("k8s", "big"), HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(error(response).get("code").asText()).isEqualTo("payload_too_large");
        assertThat(documents(TENANT_A)).isZero();
        assertThat(jobs(TENANT_A)).isZero();
    }

    @Test
    void bodiesAtTheLimitAreAcceptedWithAndWithoutContentLength() throws Exception {
        String json = bodyOfSize(LIMIT);
        assertThat(put(ADMIN_A, "k8s", "fixed", json).statusCode()).isEqualTo(202);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        assertThat(send(ADMIN_A, url("k8s", "chunked"),
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).statusCode()).isEqualTo(202);
        assertThat(documents(TENANT_A)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT length(content) FROM documents WHERE tenant_id = :t").param("t", TENANT_A)
                .query(Integer.class).list()).containsExactly(LIMIT - body("Big", "", List.of("eng")).length());
    }

    // ---- AC-t09-13: write cap ----

    @Test
    void ac_t09_13_secondWriteWhileOneIsHeldInItsTransactionIs503OverloadedThenWritesSucceedAgain() throws Exception {
        var held = outbox.holdNextInsert();
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return put(ADMIN_A, "k8s", "first", body("First", "one", List.of("eng")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).as("first PUT is inside its transaction").isTrue();

            var second = put(ADMIN_A, "k8s", "second", body("Second", "two", List.of("eng")));
            assertThat(second.statusCode()).isEqualTo(503);
            assertThat(second.headers().firstValue("Retry-After")).contains("1");
            assertThat(error(second).get("code").asText()).isEqualTo("overloaded");
        } finally {
            outbox.release();
        }

        assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        assertThat(put(ADMIN_A, "k8s", "third", body("Third", "three", List.of("eng"))).statusCode()).isEqualTo(202);
        assertThat(jdbc.sql("SELECT doc_id FROM documents WHERE tenant_id = :t ORDER BY doc_id").param("t", TENANT_A)
                .query(String.class).list()).containsExactly("first", "third");
    }

    // ---- AC-t09-14: tenant isolation and validation ----

    @Test
    void ac_t09_14_anotherTenantsDocumentIs404NeverForbidden() throws Exception {
        put(ADMIN_A, "k8s", "concepts.pods", body("Pods", "tenant A's text", List.of("eng")));

        var other = get(ADMIN_B, "k8s", "concepts.pods");
        var missing = get(ADMIN_B, "k8s", "no.such.doc");

        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(error(other).get("code").asText()).isEqualTo("not_found");
        assertThat(other.body()).doesNotContain("Pods").doesNotContain("tenant A");
        // Indistinguishable from a document that does not exist at all.
        assertThat(error(missing).get("message")).isEqualTo(error(other).get("message"));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(get(ADMIN_A, "k8s", "concepts.pods").statusCode()).isEqualTo(200);
    }

    @Test
    void ac_t09_14_sameAddressInTwoTenantsIsTwoDocuments() throws Exception {
        put(ADMIN_A, "k8s", "d", body("A", "a", List.of("eng")));
        put(ADMIN_B, "k8s", "d", body("B", "b", List.of("eng")));

        assertThat(json(get(ADMIN_A, "k8s", "d")).get("title").asText()).isEqualTo("A");
        assertThat(json(get(ADMIN_B, "k8s", "d")).get("title").asText()).isEqualTo("B");
    }

    @Test
    void ac_t09_14_invalidIdsAndGroupsAre400NamingTheField() throws Exception {
        assertInvalid(put(ADMIN_A, "k8s", "-bad", body("T", "c", List.of("eng"))), "document id");
        assertInvalid(put(ADMIN_A, "k8s", "a%20b", body("T", "c", List.of("eng"))), "document id");
        assertInvalid(put(ADMIN_A, "_k8s", "d", body("T", "c", List.of("eng"))), "collection");
        assertInvalid(put(ADMIN_A, "k8s", "d", body("T", "c", List.of("Eng"))), "ACL group");
        assertInvalid(put(ADMIN_A, "k8s", "d", body("T", "c", List.of())), "acl_groups");
        assertInvalid(put(ADMIN_A, "k8s", "d", "{\"title\":\"T\",\"content\":\"c\",\"acl_groups\":[null]}"), "acl_groups");
        assertInvalid(put(ADMIN_A, "k8s", "d", "{\"content\":\"c\",\"acl_groups\":[\"eng\"]}"), "title");
        assertInvalid(put(ADMIN_A, "k8s", "d", body("T", " ", List.of("eng"))), "content");
        assertInvalid(get(ADMIN_A, "k8s", "-bad"), "document id");
        assertInvalid(get(ADMIN_A, "k~8s", "d"), "collection");
        assertThat(documents(TENANT_A)).isZero();
    }

    @Test
    void malformedJsonIs400() throws Exception {
        var response = put(ADMIN_A, "k8s", "d", "{\"title\":");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(error(response).get("code").asText()).isEqualTo("invalid_request");
        assertThat(documents(TENANT_A)).isZero();
    }

    static void assertInvalid(HttpResponse<String> response, String field) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode error = error(response);
        assertThat(error.get("code").asText()).isEqualTo("invalid_request");
        assertThat(error.get("message").asText()).contains(field);
    }
}
