package com.geekmall.product;

import com.geekmall.common.api.BusinessException;

import java.util.Map;
import java.util.Set;

final class ProductStateMachine {
    private static final Map<String, Set<String>> AUDIT = Map.of(
            "DRAFT", Set.of("PENDING_REVIEW"),
            "REJECTED", Set.of("PENDING_REVIEW"),
            "PENDING_REVIEW", Set.of("APPROVED", "REJECTED")
    );
    private static final Map<String, Set<String>> SALE = Map.of(
            "OFF_SALE", Set.of("ON_SALE", "VIOLATION_OFF_SALE"),
            "ON_SALE", Set.of("OFF_SALE", "VIOLATION_OFF_SALE"),
            "VIOLATION_OFF_SALE", Set.of("OFF_SALE")
    );

    private ProductStateMachine() {}

    static void audit(String from, String to) { validate(AUDIT, from, to); }

    static void sale(String from, String to) { validate(SALE, from, to); }

    private static void validate(Map<String, Set<String>> rules, String from, String to) {
        if (!rules.getOrDefault(from, Set.of()).contains(to)) {
            throw new BusinessException("PD40901", "非法商品状态流转：" + from + " → " + to);
        }
    }
}
