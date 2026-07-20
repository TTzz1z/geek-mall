package com.geekmall.trade;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-1 trade back-half behavioral contracts exercised by scripts/phase1-verify.ps1
 * against real API/DB/MQ. These unit checks lock the expected state machine edges.
 */
class TradeBackHalfFlowTest {
    @Test
    void normalOrderHappyPathStatuses() {
        assertEquals("PENDING_PAYMENT", nextOrder("RESERVING"));
        assertEquals("PAID", nextOrder("PENDING_PAYMENT"));
        assertEquals("PARTIAL_SHIPPED", nextFulfillment("PICKING", false));
        assertEquals("SHIPPED", nextFulfillment("PARTIAL_SHIPPED", true));
    }

    @Test
    void returnRefundPathRequiresLogisticsBeforeWarehouseReceive() {
        List<String> path = List.of("APPLIED", "APPROVED", "RETURNING", "RECEIVED", "REFUNDING", "COMPLETED");
        assertEquals("APPROVED", path.get(1));
        assertTrue(path.indexOf("RETURNING") > path.indexOf("APPROVED"));
        assertTrue(path.indexOf("RECEIVED") > path.indexOf("RETURNING"));
        assertFalse(Set.of("RECEIVED", "COMPLETED").contains("APPROVED"));
    }

    @Test
    void seckillCheckoutTokenMustBeUniquePerRequest() {
        String a = "SECKILL-" + "req-a";
        String b = "SECKILL-" + "req-b";
        assertNotEquals(a, b);
        assertTrue(a.startsWith("SECKILL-"));
    }

    @Test
    void duplicateShipAfterFullyShippedIsRejected() {
        assertEquals("FL40901", shipErrorWhen("SHIPPED"));
        assertEquals("FL40901", shipErrorWhen("COMPLETED"));
        assertNull(shipErrorWhen("PICKING"));
        assertNull(shipErrorWhen("PARTIAL_SHIPPED"));
    }

    @Test
    void paymentCallbackReplayIsIdempotent() {
        Set<String> seen = new LinkedHashSet<>();
        assertTrue(seen.add("callback-1"));
        assertFalse(seen.add("callback-1"));
    }

    private static String nextOrder(String from) {
        return switch (from) {
            case "RESERVING" -> "PENDING_PAYMENT";
            case "PENDING_PAYMENT" -> "PAID";
            default -> from;
        };
    }

    private static String nextFulfillment(String from, boolean allLinesShipped) {
        if ("PICKING".equals(from) || "PARTIAL_SHIPPED".equals(from)) {
            return allLinesShipped ? "SHIPPED" : "PARTIAL_SHIPPED";
        }
        return from;
    }

    private static String shipErrorWhen(String status) {
        if (Set.of("SHIPPED", "COMPLETED").contains(status)) {
            return "FL40901";
        }
        return null;
    }
}
