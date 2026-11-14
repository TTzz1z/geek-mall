package com.geekmall.ai;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Closing an HTTP response body unblocks reads when its peer never sends EOF. */
final class DeadlineInputStream extends FilterInputStream {
    // One watchdog per active body ensures a slow close cannot delay another body's deadline.
    private static final ScheduledExecutorService CLOCK = Executors.newScheduledThreadPool(32,
            Thread.ofPlatform().daemon().name("ai-body-deadline-", 0).factory());
    private final long totalDeadline;
    private final long firstDeadline;
    private final long idleNanos;
    private final AtomicLong lastByte = new AtomicLong();
    private final ScheduledFuture<?> watchdog;
    private volatile String expired;

    DeadlineInputStream(InputStream input, long startedNanos, int firstMs, int idleMs, int totalMs) {
        super(input);
        totalDeadline = startedNanos + TimeUnit.MILLISECONDS.toNanos(Math.max(1, totalMs));
        firstDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, firstMs));
        idleNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, idleMs));
        watchdog = CLOCK.scheduleAtFixedRate(this::expire, 10, 10, TimeUnit.MILLISECONDS);
    }

    private void expire() {
        long now = System.nanoTime();
        long last = lastByte.get();
        String reason = null;
        if (now >= totalDeadline) reason = "total";
        else if (last == 0 && now >= firstDeadline) reason = "first-byte";
        else if (last != 0 && now - last >= idleNanos) reason = "idle";
        if (reason != null && expired == null) {
            expired = reason;
            try {
                in.close();
            } catch (IOException ignored) { }
        }
    }

    void checkDeadline() throws IOException {
        if (expired != null) throw new BodyTimeoutException(expired);
    }

    @Override
    public int read() throws IOException {
        checkDeadline();
        try {
            int value = in.read();
            if (value >= 0) lastByte.set(System.nanoTime());
            checkDeadline();
            return value;
        } catch (IOException error) {
            checkDeadline();
            throw error;
        }
    }

    @Override
    public int read(byte[] bytes, int off, int length) throws IOException {
        checkDeadline();
        try {
            int count = in.read(bytes, off, length);
            if (count > 0) lastByte.set(System.nanoTime());
            checkDeadline();
            return count;
        } catch (IOException error) {
            checkDeadline();
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        watchdog.cancel(false);
        super.close();
    }

    static final class BodyTimeoutException extends IOException {
        final String phase;
        BodyTimeoutException(String phase) {
            super("upstream body timeout: " + phase);
            this.phase = phase;
        }
    }
}
