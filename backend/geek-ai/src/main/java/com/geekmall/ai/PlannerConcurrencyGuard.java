package com.geekmall.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase D memory/latency protection: cap concurrent remote planner calls.
 */
@Component
public class PlannerConcurrencyGuard {
    private final int maxConcurrent;
    private final long acquireTimeoutMs;
    private final Semaphore semaphore;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong acquired = new AtomicLong();

    public PlannerConcurrencyGuard(
            @Value("${geek.llm.planner-max-concurrent:4}") int maxConcurrent,
            @Value("${geek.llm.planner-acquire-timeout-ms:50}") long acquireTimeoutMs) {
        this.maxConcurrent = Math.max(1, Math.min(32, maxConcurrent));
        this.acquireTimeoutMs = Math.max(0, Math.min(5_000L, acquireTimeoutMs));
        this.semaphore = new Semaphore(this.maxConcurrent, true);
    }

    public boolean tryAcquire() {
        try {
            boolean ok = acquireTimeoutMs <= 0
                    ? semaphore.tryAcquire()
                    : semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                rejected.incrementAndGet();
                return false;
            }
            inFlight.incrementAndGet();
            acquired.incrementAndGet();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rejected.incrementAndGet();
            return false;
        }
    }

    public void release() {
        inFlight.decrementAndGet();
        semaphore.release();
    }

    public Map<String, Object> stats() {
        return Map.of(
                "maxConcurrent", maxConcurrent,
                "acquireTimeoutMs", acquireTimeoutMs,
                "inFlight", Math.max(0, inFlight.get()),
                "availablePermits", semaphore.availablePermits(),
                "rejected", rejected.get(),
                "acquired", acquired.get()
        );
    }
}
