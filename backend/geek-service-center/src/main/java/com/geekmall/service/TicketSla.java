package com.geekmall.service;

import java.time.Duration;
import java.time.Instant;

public final class TicketSla {
    private TicketSla() {}

    public static Instant dueAt(String priority, Instant now) {
        return switch (priority == null ? "NORMAL" : priority) {
            case "URGENT" -> now.plus(Duration.ofHours(2));
            case "HIGH" -> now.plus(Duration.ofHours(8));
            case "LOW" -> now.plus(Duration.ofHours(72));
            default -> now.plus(Duration.ofHours(24));
        };
    }

    public static boolean breached(Instant dueAt, Instant now) {
        return dueAt != null && now.isAfter(dueAt);
    }
}
