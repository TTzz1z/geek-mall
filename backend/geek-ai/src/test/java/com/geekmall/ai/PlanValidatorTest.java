package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator(5);

    @Test
    void hardMaxForcesPriceMaxEqualBudget() {
        PlanEnvelope raw = new PlanEnvelope("GUIDE", 0.9, "buy laptop",
                Map.of("budget", 8000, "budget_mode", "AROUND"),
                List.of(new PlanEnvelope.PlannedTool("search_products",
                        Map.of("keyword", "笔记本", "priceMax", 12000, "budget", 8000))),
                true, "LLM", null);
        PlanEnvelope out = validator.validate(raw, "8000以内的笔记本");
        assertEquals("GUIDE", out.agent());
        assertEquals(1, out.toolPlan().size());
        Map<String, Object> args = out.toolPlan().getFirst().args();
        assertEquals(8000L, ((Number) args.get("priceMax")).longValue());
        assertEquals("HARD_MAX", String.valueOf(args.get("budgetMode")));
        assertEquals(110L, ((Number) args.get("categoryId")).longValue());
    }

    @Test
    void injectsPayOrderForVisibleDeny() {
        PlanEnvelope raw = new PlanEnvelope("ORDER_LOGISTICS", 0.8, "pay",
                Map.of(), List.of(), true, "LLM", null);
        PlanEnvelope out = validator.validate(raw, "请帮我直接支付订单");
        assertTrue(out.toolPlan().stream().anyMatch(t -> "pay_order".equals(t.name())));
    }

    @Test
    void cartFollowUpRewritesToDetailAndCart() {
        Map<String, Object> prior = Map.of(ConversationSlots.LAST_SEARCH_SPU_IDS, List.of(10011L, 10013L));
        PlanEnvelope raw = new PlanEnvelope("GUIDE", 0.9, "add first",
                Map.of(),
                List.of(new PlanEnvelope.PlannedTool("search_products", Map.of("keyword", "耳机"))),
                true, "LLM", null);
        PlanEnvelope out = validator.validate(raw, "把第一款加购", prior);
        assertTrue(out.toolPlan().stream().noneMatch(t -> "search_products".equals(t.name())));
        assertTrue(out.toolPlan().stream().anyMatch(t -> "get_product_detail".equals(t.name())));
        assertTrue(out.toolPlan().stream().anyMatch(t -> "add_cart_item".equals(t.name())));
        Map<String, Object> detailArgs = out.toolPlan().stream()
                .filter(t -> "get_product_detail".equals(t.name()))
                .findFirst().orElseThrow().args();
        assertEquals(0, ((Number) detailArgs.get("searchIndex")).intValue());
        assertEquals(List.of(10011L, 10013L), out.slots().get(ConversationSlots.LAST_SEARCH_SPU_IDS));
    }
}
