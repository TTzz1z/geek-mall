package com.geekmall.marketing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

final class PromotionAllocator {
    private PromotionAllocator() {}

    static List<BigDecimal> allocate(List<BigDecimal> amounts, BigDecimal discount) {
        if (amounts.isEmpty() || discount.signum() < 0) throw new IllegalArgumentException("invalid allocation input");
        BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) return amounts.stream().map(a -> BigDecimal.ZERO.setScale(2)).toList();
        if (discount.compareTo(total) > 0) throw new IllegalArgumentException("discount exceeds total");
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < amounts.size(); i++) {
            BigDecimal share = i == amounts.size() - 1
                    ? discount.subtract(allocated)
                    : discount.multiply(amounts.get(i)).divide(total, 2, RoundingMode.DOWN);
            out.add(share);
            allocated = allocated.add(share);
        }
        return out;
    }
}
