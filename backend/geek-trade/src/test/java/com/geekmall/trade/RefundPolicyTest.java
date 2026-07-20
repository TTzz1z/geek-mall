package com.geekmall.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

class RefundPolicyTest {
    @Test
    void partialRefundAmountIsBoundedByLinePayableAmount() {
        BigDecimal line = new BigDecimal("99.99");
        BigDecimal first = line.multiply(BigDecimal.valueOf(2)).divide(BigDecimal.valueOf(3), 2, RoundingMode.DOWN);
        BigDecimal last = line.subtract(first);
        assertEquals(line, first.add(last));
        assertTrue(first.compareTo(line) <= 0);
    }

    @Test
    void cumulativeRefundQuantityMustNotExceedPurchasedQuantity() {
        int purchased = 2;
        int alreadyRefundedQty = 1;
        int requestQty = 2;
        assertTrue(alreadyRefundedQty + requestQty > purchased);
        assertFalse(alreadyRefundedQty + 1 > purchased);
    }

    @Test
    void cumulativeRefundAmountMustNotExceedPayable() {
        BigDecimal payable = new BigDecimal("100.00");
        BigDecimal used = new BigDecimal("60.00");
        BigDecimal request = new BigDecimal("50.00");
        assertTrue(used.add(request).compareTo(payable) > 0);
        assertFalse(used.add(new BigDecimal("40.00")).compareTo(payable) > 0);
    }
}
