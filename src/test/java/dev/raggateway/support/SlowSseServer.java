package dev.raggateway.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raw-socket HTTP/1.1 server for cancellation tests. Answers every request with a 200
 * {@code text/event-stream} and writes the given SSE frames one at a time with a pause between them
 * (no {@code Content-Length}, {@code Connection: close}, so nothing is buffered by a framing layer).
 *
 * <p>While streaming, a reader thread watches the socket: when the client closes its side, {@code read()}
 * returns -1 (or fails with a reset) and the time is recorded. That is the moment the upstream call was
 * cancelled, which WireMock cannot report (AC-t01-9, {@code ClaudeProviderCancelTest}).
 */
public final class SlowSseServer implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> frames;
    private final Duration gap;
    private final Thread acceptor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong clientClosedAtNanos = new AtomicLong(-1);
    private final AtomicInteger framesWritten = new AtomicInteger();
    private final CountDownLatch clientClosed = new CountDownLatch(1);
    private volatile boolean stopped;

    /**
     * @param frames complete SSE frames ({@code "event: x\ndata: {...}\n\n"}), written in order
     * @param gap    pause before each frame after the first
     */
    public SlowSseServer(List<String> frames, Duration gap) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.frames = List.copyOf(frames);
        this.gap = gap;
        this.acceptor = Thread.ofPlatform().daemon().name("slow-sse-acceptor").start(this::acceptLoop);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Requests received so far. */
    public int requests() {
        return requests.get();
    }

    /** Frames fully written before the client went away (or all of them). */
    public int framesWritten() {
        return framesWritten.get();
    }

    /** Waits until the client has closed the connection; returns {@link System#nanoTime()} of the close, or -1. */
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
                Thread.ofPlatform().daemon().name("slow-sse-conn").start(() -> serve(socket));
            } catch (IOException e) {
                if (!stopped) throw new IllegalStateException(e);
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            readRequest(in);
            Thread.ofPlatform().daemon().name("slow-sse-watch").start(() -> watchForClose(in));
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < frames.size(); i++) {
                if (i > 0 && clientClosed.await(gap.toMillis(), TimeUnit.MILLISECONDS)) return;
                if (clientClosed.getCount() == 0) return;
                out.write(frames.get(i).getBytes(StandardCharsets.UTF_8));
                out.flush();
                framesWritten.incrementAndGet();
            }
            // All frames sent: keep the socket open until the client closes it, so the watcher sees the close.
            clientClosed.await(30, TimeUnit.SECONDS);
        } catch (IOException e) {
            markClosed(); // write failed: the client is gone
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void watchForClose(InputStream in) {
        try {
            while (in.read() != -1) {
                // ignore anything after the request
            }
        } catch (SocketException reset) {
            // connection reset by the client also means it is gone
        } catch (IOException ignored) {
            // socket closed locally
        }
        markClosed();
    }

    private void markClosed() {
        clientClosedAtNanos.compareAndSet(-1, System.nanoTime());
        clientClosed.countDown();
    }

    /** Reads the request line, headers and a Content-Length body, so the client is not reset mid-send. */
    private static void readRequest(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < 4) {
            int b = in.read();
            if (b == -1) throw new IOException("client closed before sending a request");
            head.write(b);
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        int length = 0;
        for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        in.readNBytes(length);
    }
}
