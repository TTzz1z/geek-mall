package com.geekmall.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SettlementCalculator {
    private SettlementCalculator() {}

    public static BigDecimal commission(BigDecimal income, BigDecimal rate) {
        if (income == null || income.signum() <= 0) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
        return income.multiply(r).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal payable(BigDecimal income, BigDecimal commission, BigDecimal refund, BigDecimal adjustment) {
        BigDecimal i = nz(income);
        BigDecimal c = nz(commission);
        BigDecimal rf = nz(refund);
        BigDecimal adj = nz(adjustment);
        return i.subtract(c).subtract(rf).add(adj).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal applyRefund(BigDecimal currentRefund, BigDecimal refundAmount, BigDecimal income) {
        BigDecimal next = nz(currentRefund).add(nz(refundAmount));
        if (next.compareTo(nz(income)) > 0) {
            return nz(income);
        }
        return next.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : v.setScale(2, RoundingMode.HALF_UP);
    }
}
