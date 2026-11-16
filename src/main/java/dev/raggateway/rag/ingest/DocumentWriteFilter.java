package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.api.ErrorResponse;
import dev.raggateway.api.GatewayHeaders;
import dev.raggateway.api.RequestIdFilter;
import dev.raggateway.auth.ApiKeyAuthenticator;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Admission for document writes (D-47), on {@code PUT /v1/rag/collections/{c}/documents/{id}} only, before the
 * body is parsed:
 * <ol>
 *   <li>a valid key that is not an admin key: 403 {@code forbidden} (a missing or unknown key passes through to
 *       the auth interceptor, which answers 401 without reading the body);</li>
 *   <li>{@code Content-Length} above {@code gateway.rag.max-document-bytes} (1 MB): 413
 *       {@code payload_too_large};</li>
 *   <li>already {@code gateway.rag.max-concurrent-writes} (4) PUTs past this filter: 503 {@code overloaded} with
 *       {@code Retry-After: 1}, like the in-flight limit (D-46);</li>
 *   <li>a body without {@code Content-Length} (chunked) is counted as it is read into memory, holding a write
 *       permit, and refused with 413 at the first byte over the limit.</li>
 * </ol>
 * None of the refusals reads the body (the chunked one stops at limit + 1 bytes), and none writes a row.
 *
 * <p>Why: the in-flight limit alone would admit 1,500 one-megabyte bodies, several GB as bytes, strings and JSON
 * trees, into a 512 MB heap. With the cap, at most 4 bodies of at most 1 MB are being parsed or written at once.
 * The cap is per instance, like the in-flight limit; document writes are not rate-limited per tenant (admin only).
 *
 * <p>Authentication happens here too, because the admin check must come before the body and filters run before the
 * auth interceptor. The key lookup is cached (D-23), so the interceptor's second lookup costs no query. The key is
 * never logged.
 *
 * <p>Order: after the request id and the in-flight limit, so a refusal carries the id and an over-limit instance
 * sheds before authenticating.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class DocumentWriteFilter extends OncePerRequestFilter {

    /** The PUT path; ids are checked by the controller (400), here only the shape matters. */
    static final Pattern PATH = Pattern.compile("/v1/rag/collections/[^/]+/documents/[^/]+");
    private static final String BEARER = "Bearer ";

    private final ApiKeyAuthenticator authenticator;
    private final ObjectMapper json;
    private final long maxBytes;
    private final int maxWrites;
    private final Semaphore writes;

    /**
     * @param maxDocumentBytes    {@code gateway.rag.max-document-bytes}: request body limit (default 1 MB)
     * @param maxConcurrentWrites {@code gateway.rag.max-concurrent-writes}: PUTs past this filter at once (default 4)
     */
    @Autowired
    public DocumentWriteFilter(ApiKeyAuthenticator authenticator, ObjectMapper json,
                               @Value("${gateway.rag.max-document-bytes:1MB}") DataSize maxDocumentBytes,
                               @Value("${gateway.rag.max-concurrent-writes:4}") int maxConcurrentWrites) {
        this(authenticator, json, maxDocumentBytes.toBytes(), maxConcurrentWrites);
    }

    DocumentWriteFilter(ApiKeyAuthenticator authenticator, ObjectMapper json, long maxBytes, int maxConcurrentWrites) {
        if (maxBytes <= 0) throw new IllegalArgumentException("gateway.rag.max-document-bytes must be positive");
        if (maxConcurrentWrites <= 0) {
            throw new IllegalArgumentException("gateway.rag.max-concurrent-writes must be positive");
        }
        this.authenticator = authenticator;
        this.json = json;
        this.maxBytes = maxBytes;
        this.maxWrites = maxConcurrentWrites;
        this.writes = new Semaphore(maxConcurrentWrites);
    }

    /** Writes currently past the filter. */
    int writesInProgress() {
        return maxWrites - writes.availablePermits();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"PUT".equals(request.getMethod()) || !PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ApiKeyPrincipal> caller = caller(request);
        if (caller.isEmpty()) {
            chain.doFilter(request, response); // the auth interceptor answers 401 before the body is read
            return;
        }
        if (!caller.get().admin()) {
            reject(request, response, new GatewayException(ErrorCode.FORBIDDEN, "documents can only be written with an admin key"));
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            reject(request, response, tooLarge());
            return;
        }
        if (!writes.tryAcquire()) {
            reject(request, response, new GatewayException(ErrorCode.OVERLOADED,
                    "this gateway instance is at its concurrent document write limit; retry shortly"));
            return;
        }
        try {
            HttpServletRequest admitted = request;
            if (declared < 0) {
                byte[] body = readAtMost(request.getInputStream(), maxBytes);
                if (body == null) {
                    reject(request, response, tooLarge());
                    return;
                }
                admitted = new BufferedBodyRequest(request, body);
            }
            chain.doFilter(admitted, response);
        } finally {
            writes.release();
        }
    }

    private GatewayException tooLarge() {
        return new GatewayException(ErrorCode.PAYLOAD_TOO_LARGE,
                "document request body is larger than the limit of " + maxBytes + " bytes");
    }

    private Optional<ApiKeyPrincipal> caller(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String key = header.substring(BEARER.length()).trim();
        return key.isEmpty() ? Optional.empty() : authenticator.authenticate(key);
    }

    /** The whole body if it has at most {@code max} bytes, else null after reading {@code max + 1}. */
    static byte[] readAtMost(InputStream in, long max) throws IOException {
        var out = new ByteArrayOutputStream((int) Math.min(max, 64 * 1024));
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer, 0, (int) Math.min(buffer.length, max + 1 - total))) > 0) {
            total += n;
            if (total > max) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, GatewayException error)
            throws IOException {
        byte[] body = json.writeValueAsBytes(ErrorResponse.of(error, RequestIdFilter.requestId(request)));
        response.setStatus(error.code().httpStatus());
        if (error.code() == ErrorCode.OVERLOADED) response.setHeader(GatewayHeaders.RETRY_AFTER, "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** A request whose body was already read (chunked, within the limit) and is served from memory. */
    static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final int length;
        private final ServletInputStream stream;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.length = body.length;
            this.stream = stream(new ByteArrayInputStream(body));
        }

        @Override
        public int getContentLength() {
            return length;
        }

        @Override
        public long getContentLengthLong() {
            return length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }

        private static ServletInputStream stream(ByteArrayInputStream in) {
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous reads only");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
