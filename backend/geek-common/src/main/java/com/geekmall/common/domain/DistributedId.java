package com.geekmall.common.domain;

import java.util.concurrent.atomic.AtomicLong;

public final class DistributedId {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private DistributedId() {}
    public static long next() {
        long millis = System.currentTimeMillis() - 1_700_000_000_000L;
        // Keep IDs within JavaScript's safe integer range while retaining
        // 65,536 IDs per millisecond for browser-administered resources.
        return (millis << 16) | (SEQUENCE.getAndIncrement() & 0xFFFF);
    }
}
