package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanCacheTest {

    @Test
    void hitsSameUtteranceWithinTtl() {
        PlanCache cache = new PlanCache(true, 60_000);
        PlanEnvelope plan = new PlanEnvelope("GUIDE", 0.9, "x", Map.of("budget", 8000),
                List.of(new PlanEnvelope.PlannedTool("search_products", Map.of("keyword", "轻薄本"))),
                true, "LLM", null);
        assertTrue(cache.get("8000以内笔记本", "GUIDE", Map.of()).isEmpty());
        cache.put("8000以内笔记本", "GUIDE", Map.of(), plan);
        assertTrue(cache.get("8000以内笔记本", "GUIDE", Map.of()).isPresent());
        assertEquals(1L, ((Number) cache.stats().get("hits")).longValue());
    }

    @Test
    void differentMemoryDoesNotCollide() {
        PlanCache cache = new PlanCache(true, 60_000);
        PlanEnvelope plan = new PlanEnvelope("GUIDE", 0.9, "x", Map.of(),
                List.of(new PlanEnvelope.PlannedTool("add_cart_item", Map.of("resolveFromDetail", true))),
                true, "LLM", null);
        cache.put("把第一款加购", "COMPARE", Map.of(ConversationSlots.LAST_SEARCH_SPU_IDS, List.of(10011L)), plan);
        assertTrue(cache.get("把第一款加购", "COMPARE",
                Map.of(ConversationSlots.LAST_SEARCH_SPU_IDS, List.of(10008L))).isEmpty());
        assertTrue(cache.get("把第一款加购", "COMPARE",
                Map.of(ConversationSlots.LAST_SEARCH_SPU_IDS, List.of(10011L))).isPresent());
    }
}
