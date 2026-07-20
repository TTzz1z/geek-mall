package com.geekmall.settlement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class SettlementCalculatorTest {
    @Test
    void commissionAndPayable() {
        BigDecimal income = new BigDecimal("100.00");
        BigDecimal commission = SettlementCalculator.commission(income, new BigDecimal("0.05"));
        assertEquals(new BigDecimal("5.00"), commission);
        assertEquals(new BigDecimal("85.00"),
                SettlementCalculator.payable(income, commission, new BigDecimal("10.00"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("100.00"),
                SettlementCalculator.applyRefund(new BigDecimal("90.00"), new BigDecimal("20.00"), income));
    }
}
