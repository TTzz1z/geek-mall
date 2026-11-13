package com.geekmall.ai;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class TurnCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Closeable> stream = new AtomicReference<>();
    private final AtomicReference<Future<?>> task = new AtomicReference<>();

    void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    void attach(Future<?> future) {
        task.set(future);
        if (cancelled.get()) future.cancel(true);
    }

    void attach(Closeable body) {
        stream.set(body);
        if (cancelled.get()) closeStream();
        check();
    }

    void detach(Closeable body) {
        stream.compareAndSet(body, null);
    }

    void cancel() {
        cancelled.set(true);
        Future<?> future = task.get();
        if (future != null) future.cancel(true);
        closeStream();
    }

    private void closeStream() {
        Closeable body = stream.getAndSet(null);
        if (body == null) return;
        try { body.close(); } catch (IOException ignored) {}
    }
}
