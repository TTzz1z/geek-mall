package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationSlotsTest {

    @Test
    void ordinalFirstAndSecond() {
        assertEquals(0, ConversationSlots.ordinalFromMessage("把第一款加购"));
        assertEquals(1, ConversationSlots.ordinalFromMessage("把第二件加入购物车"));
        assertEquals(0, ConversationSlots.ordinalFromMessage("把这款加购"));
    }

    @Test
    void cartFollowUpDetectsOrdinalWithoutFreshSearch() {
        assertTrue(ConversationSlots.isCartFollowUp("把第一款加购"));
        assertFalse(ConversationSlots.isCartFollowUp("再搜一批耳机"));
        assertFalse(ConversationSlots.isCartFollowUp("帮我找笔记本"));
    }

    @Test
    void mergeKeepsRuntimeKeys() {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put(ConversationSlots.LAST_SEARCH_SPU_IDS, List.of(10011L, 10013L));
        prior.put("budget", 800);
        Map<String, Object> plan = Map.of(
                "budget", 1000,
                ConversationSlots.LAST_SEARCH_SPU_IDS, List.of(1L)
        );
        Map<String, Object> merged = ConversationSlots.mergePlan(prior, plan);
        assertEquals(1000, ((Number) merged.get("budget")).intValue());
        assertEquals(List.of(10011L, 10013L), merged.get(ConversationSlots.LAST_SEARCH_SPU_IDS));
    }

    @Test
    void applyToolResultsWritesSearchIds() {
        Map<String, Object> slots = new LinkedHashMap<>();
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("toolName", "search_products");
        tr.put("status", "SUCCESS");
        tr.put("result", Map.of("data", Map.of("items", List.of(
                Map.of("id", 10011),
                Map.of("id", 10013)
        ))));
        ConversationSlots.applyToolResults(slots, List.of(tr));
        assertEquals(List.of(10011L, 10013L), slots.get(ConversationSlots.LAST_SEARCH_SPU_IDS));
    }
}
