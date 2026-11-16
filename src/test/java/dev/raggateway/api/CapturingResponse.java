package dev.raggateway.api;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Servlet response for sink unit tests: records the bytes, how many were flushed, whether the stream
 * was closed, and can be told to fail writes as a client that went away would.
 */
final class CapturingResponse extends MockHttpServletResponse {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int flushedLength = -1;
    private boolean closed;
    private boolean failWrites;

    private final ServletOutputStream stream = new ServletOutputStream() {
        @Override
        public void write(int b) throws IOException {
            if (failWrites) throw new IOException("Broken pipe");
            if (closed) throw new IOException("stream closed");
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (failWrites) throw new IOException("Broken pipe");
            if (closed) throw new IOException("stream closed");
            bytes.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (failWrites) throw new IOException("Broken pipe");
            flushedLength = bytes.size();
            setCommitted(true);
        }

        @Override
        public void close() {
            closed = true;
            setCommitted(true);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }
    };

    @Override
    public ServletOutputStream getOutputStream() {
        return stream;
    }

    void failWrites() {
        failWrites = true;
    }

    String body() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Bytes that reached the client so far: everything up to the last flush. */
    String flushedBody() {
        return flushedLength < 0 ? "" : new String(bytes.toByteArray(), 0, flushedLength, StandardCharsets.UTF_8);
    }

    boolean closed() {
        return closed;
    }
}
