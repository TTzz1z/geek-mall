package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EmptySearchReplannerTest {

    @Test
    void hardMaxDoesNotRaisePriceMax() {
        Map<String, Object> args = Map.of(
                "keyword", "轻薄本",
                "categoryId", 110,
                "budget", 200,
                "budgetMode", "HARD_MAX",
                "priceMin", 70,
                "priceMax", 200
        );
        List<Map<String, Object>> tools = List.of(emptySearch(args));
        Optional<EmptySearchReplanner.Replan> replan = EmptySearchReplanner.maybeReplan(
                List.of(new PlanEnvelope.PlannedTool("search_products", args)),
                tools,
                Map.of("budget", 200, "budget_mode", "HARD_MAX"),
                "200以内笔记本"
        );
        assertTrue(replan.isPresent());
        Map<String, Object> next = replan.get().tools().getFirst().args();
        assertEquals(200L, ((Number) next.get("priceMax")).longValue());
        assertTrue(((Number) next.get("priceMin")).doubleValue() <= 70);
        assertTrue(replan.get().meta().get("changes").toString().contains("HARD_MAX_keep_cap"));
    }

    @Test
    void aroundWidensRange() {
        Map<String, Object> args = Map.of(
                "keyword", "Phone",
                "categoryId", 220,
                "budget", 500,
                "budgetMode", "AROUND",
                "priceMin", 225,
                "priceMax", 625
        );
        Optional<EmptySearchReplanner.Replan> replan = EmptySearchReplanner.maybeReplan(
                List.of(new PlanEnvelope.PlannedTool("search_products", args),
                        new PlanEnvelope.PlannedTool("compare_products", Map.of("resolveFromSearch", true))),
                List.of(emptySearch(args)),
                Map.of("budget", 500, "budget_mode", "AROUND"),
                "预算500左右的手机"
        );
        assertTrue(replan.isPresent());
        assertEquals(2, replan.get().tools().size());
        Map<String, Object> next = replan.get().tools().getFirst().args();
        assertEquals(150L, ((Number) next.get("priceMin")).longValue());
        assertEquals(750L, ((Number) next.get("priceMax")).longValue());
    }

    @Test
    void noReplanWhenSearchHasHits() {
        Map<String, Object> tr = Map.of(
                "toolName", "search_products",
                "status", "SUCCESS",
                "arguments", Map.of("keyword", "Phone"),
                "result", Map.of("data", Map.of("items", List.of(Map.of("id", 10008))))
        );
        assertTrue(EmptySearchReplanner.maybeReplan(List.of(), List.of(tr), Map.of(), "手机").isEmpty());
    }

    private static Map<String, Object> emptySearch(Map<String, Object> args) {
        return Map.of(
                "toolName", "search_products",
                "status", "SUCCESS",
                "arguments", args,
                "result", Map.of("data", Map.of("items", List.of(), "total", 0))
        );
    }
}
