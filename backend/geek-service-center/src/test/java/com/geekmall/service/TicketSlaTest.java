package com.geekmall.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TicketSlaTest {
    @Test
    void urgentDueInTwoHours() {
        Instant now = Instant.parse("2026-07-16T01:00:00Z");
        assertEquals(Instant.parse("2026-07-16T03:00:00Z"), TicketSla.dueAt("URGENT", now));
        assertFalse(TicketSla.breached(TicketSla.dueAt("NORMAL", now), now));
        assertTrue(TicketSla.breached(now.minusSeconds(1), now));
    }
}
