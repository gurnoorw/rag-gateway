package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.auth.ApiKeyAuthenticator;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Tenant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link DocumentWriteFilter} without a server: who gets past, and that refusals never touch the body. */
class DocumentWriteFilterTest {

    static final String URI = "/v1/rag/collections/k8s/documents/concepts.pods";
    static final int LIMIT = 100;
    static final ObjectMapper JSON = new ObjectMapper();
    static final Tenant TENANT = new Tenant("acme", "Acme", 1000, BigDecimal.ONE, Set.of());

    final Map<String, ApiKeyPrincipal> keys = Map.of(
            "admin-key", new ApiKeyPrincipal("k1", TENANT, Set.of(), true),
            "reader-key", new ApiKeyPrincipal("k2", TENANT, Set.of("eng"), false));
    final ApiKeyAuthenticator authenticator = key -> Optional.ofNullable(keys.get(key));

    DocumentWriteFilter filter(int maxWrites) {
        return new DocumentWriteFilter(authenticator, JSON, LIMIT, maxWrites);
    }

    /** A request whose body must not be read: any read fails the test. */
    static MockHttpServletRequest untouchable(String method, String uri, String key, long contentLength) {
        var request = new MockHttpServletRequest(method, uri) {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body was read");
            }

            @Override
            public long getContentLengthLong() {
                return contentLength;
            }

            @Override
            public int getContentLength() {
                return (int) contentLength;
            }
        };
        if (key != null) request.addHeader("Authorization", "Bearer " + key);
        return request;
    }

    /** A chunked request: a body but no Content-Length. */
    static MockHttpServletRequest chunked(String key, byte[] body) {
        var request = new MockHttpServletRequest("PUT", URI) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body);
        request.addHeader("Authorization", "Bearer " + key);
        return request;
    }

    static JsonNode error(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray()).get("error");
    }

    @Test
    void ac_t09_2_nonAdminKeyIs403BeforeTheBody() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter(4).doFilter(untouchable("PUT", URI, "reader-key", 10), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(error(response).get("code").asText()).isEqualTo("forbidden");
        assertThat(chain.getRequest()).as("chain not called").isNull();
    }

    @Test
    void ac_t09_12_contentLengthOverTheLimitIs413BeforeTheBody() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter(4).doFilter(untouchable("PUT", URI, "admin-key", LIMIT + 1), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(error(response).get("code").asText()).isEqualTo("payload_too_large");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void ac_t09_12_chunkedBodyOverTheLimitIs413AfterLimitPlusOneBytes() throws Exception {
        byte[] big = new byte[LIMIT * 50];
        var request = chunked("admin-key", big);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter(4).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
        // Stopped counting at the limit: most of the body was never read.
        assertThat(request.getInputStream().available()).isEqualTo(big.length - (LIMIT + 1));
    }

    @Test
    void chunkedBodyWithinTheLimitReachesTheHandlerWhole() throws Exception {
        byte[] body = "x".repeat(LIMIT).getBytes(StandardCharsets.UTF_8);
        var seen = new AtomicReference<byte[]>();
        FilterChain chain = (req, res) -> seen.set(req.getInputStream().readAllBytes());
        var response = new MockHttpServletResponse();
        filter(4).doFilter(chunked("admin-key", body), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get()).isEqualTo(body);
    }

    @Test
    void bodyAtExactlyTheLimitPasses() throws Exception {
        var request = new MockHttpServletRequest("PUT", URI);
        request.setContent(new byte[LIMIT]);
        request.addHeader("Authorization", "Bearer admin-key");
        var chain = new MockFilterChain();
        filter(4).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void ac_t09_13_writesOverTheCapGet503OverloadedWithRetryAfterBeforeTheBody() throws Exception {
        var filter = filter(1);
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        FilterChain holding = (req, res) -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var first = new MockHttpServletRequest("PUT", URI);
        first.addHeader("Authorization", "Bearer admin-key");
        var held = CompletableFuture.runAsync(() -> {
            try {
                filter.doFilter(first, new MockHttpServletResponse(), holding);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(filter.writesInProgress()).isEqualTo(1);

            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(untouchable("PUT", URI, "admin-key", 10), response, chain);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("1");
            assertThat(error(response).get("code").asText()).isEqualTo("overloaded");
            assertThat(chain.getRequest()).isNull();
        } finally {
            release.countDown();
        }
        held.get(10, TimeUnit.SECONDS);
        assertThat(filter.writesInProgress()).isZero();

        var again = new MockFilterChain();
        var ok = new MockHttpServletRequest("PUT", URI);
        ok.addHeader("Authorization", "Bearer admin-key");
        filter.doFilter(ok, new MockHttpServletResponse(), again);
        assertThat(again.getRequest()).isNotNull();
    }

    @Test
    void permitIsReleasedWhenTheHandlerThrows() {
        var filter = filter(1);
        var request = new MockHttpServletRequest("PUT", URI);
        request.addHeader("Authorization", "Bearer admin-key");
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };
        try {
            filter.doFilter(request, new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            // the exception propagates; the permit must not leak
        }
        assertThat(filter.writesInProgress()).isZero();
    }

    @Test
    void missingOrUnknownKeyPassesThroughToTheAuthInterceptorWithoutTakingAPermit() throws Exception {
        var filter = filter(1);
        for (String key : new String[]{null, "unknown-key"}) {
            var request = untouchable("PUT", URI, key, LIMIT * 10);
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
        assertThat(filter.writesInProgress()).isZero();
    }

    @Test
    void onlyDocumentPutsAreFiltered() throws Exception {
        var filter = filter(1);
        for (HttpServletRequest request : new HttpServletRequest[]{
                untouchable("GET", URI, "reader-key", LIMIT * 10),
                untouchable("PUT", "/v1/rag/collections/k8s/documents", "reader-key", LIMIT * 10),
                untouchable("POST", "/v1/chat/completions", "reader-key", LIMIT * 10)}) {
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            ServletRequest passed = chain.getRequest();
            assertThat(passed).isSameAs(request);
        }
    }
}
