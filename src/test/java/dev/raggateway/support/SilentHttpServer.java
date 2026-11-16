package dev.raggateway.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raw-socket HTTP/1.1 server that does not answer in time: it accepts a connection, reads the request and then
 * either never answers ({@link #silent()}) or, after a delay, sends a 200 head and one body chunk and keeps the
 * connection open ({@link #answeringAfter}). It records when the client closes the connection, which is how a
 * test sees what a first-token timeout before the response headers does to the upstream call (t03). WireMock's
 * fixed delay cannot show that, and {@link SlowSseServer} always sends the headers first.
 */
public final class SilentHttpServer implements AutoCloseable {

    private final ServerSocket server;
    private final Duration answerAfter;
    private final String contentType;
    private final String firstChunk;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong answeredAtNanos = new AtomicLong(-1);
    private final AtomicLong clientClosedAtNanos = new AtomicLong(-1);
    private final CountDownLatch clientClosed = new CountDownLatch(1);
    private volatile boolean stopped;

    private SilentHttpServer(Duration answerAfter, String contentType, String firstChunk) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.answerAfter = answerAfter;
        this.contentType = contentType;
        this.firstChunk = firstChunk;
        Thread.ofPlatform().daemon().name("silent-http-acceptor").start(this::acceptLoop);
    }

    /** Never sends a status line. */
    public static SilentHttpServer silent() throws IOException {
        return new SilentHttpServer(null, null, null);
    }

    /** Sends a 200 with {@code contentType} and {@code firstChunk} after {@code delay}, then nothing more. */
    public static SilentHttpServer answeringAfter(Duration delay, String contentType, String firstChunk)
            throws IOException {
        return new SilentHttpServer(delay, contentType, firstChunk);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Connections accepted so far. */
    public int requests() {
        return requests.get();
    }

    /** {@link System#nanoTime()} when the late answer was written, or -1. */
    public long answeredAtNanos() {
        return answeredAtNanos.get();
    }

    /** Waits until the client has closed a connection; returns {@link System#nanoTime()} of the close, or -1. */
    public long awaitClientClose(Duration timeout) throws InterruptedException {
        return clientClosed.await(timeout.toMillis(), TimeUnit.MILLISECONDS) ? clientClosedAtNanos.get() : -1;
    }

    @Override
    public void close() throws IOException {
        stopped = true;
        server.close();
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = server.accept();
                requests.incrementAndGet();
                Thread.ofPlatform().daemon().name("silent-http-conn").start(() -> serve(socket));
            } catch (IOException e) {
                if (!stopped) throw new IllegalStateException(e);
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            Thread.ofPlatform().daemon().name("silent-http-watch").start(() -> watchForClose(in));
            if (answerAfter != null && !clientClosed.await(answerAfter.toMillis(), TimeUnit.MILLISECONDS)) {
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nConnection: close\r\n\r\n"
                        + firstChunk).getBytes(StandardCharsets.UTF_8));
                out.flush();
                answeredAtNanos.set(System.nanoTime());
            }
            clientClosed.await(30, TimeUnit.SECONDS);
        } catch (IOException e) {
            markClosed(); // write failed: the client is gone
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void watchForClose(InputStream in) {
        try {
            byte[] buffer = new byte[4096];
            while (in.read(buffer) != -1) {
                // swallow the request and anything after it
            }
        } catch (IOException e) {
            // reset by the client, or closed locally: either way the connection is over
        }
        markClosed();
    }

    private void markClosed() {
        clientClosedAtNanos.compareAndSet(-1, System.nanoTime());
        clientClosed.countDown();
    }
}
