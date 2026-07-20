package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnswerGroundingTest {

    @Test
    void fallsBackOnHallucinatedProduct() {
        String draft = "推荐 NovaBuds Pro（¥899） SPU#10011";
        String llm = "强烈推荐 Air 14 笔记本电脑，性价比极高。";
        List<Map<String, Object>> tools = List.of(Map.of(
                "toolName", "search_products",
                "status", "SUCCESS",
                "result", Map.of("data", Map.of("items", List.of(
                        Map.of("id", 10011, "title", "NovaBuds Pro", "minPrice", 899)
                )))
        ));
        AnswerGrounding.Result r = AnswerGrounding.ground(llm, draft, tools);
        assertTrue(r.usedDraft());
        assertTrue(r.strippedHallucination());
        assertTrue(r.text().contains("NovaBuds Pro"));
    }

    @Test
    void fallsBackOnUngroundedSpu() {
        String draft = "候选 SPU#10011";
        String llm = "可以看看 SPU#99999，价格很香。";
        List<Map<String, Object>> tools = List.of(Map.of(
                "toolName", "search_products",
                "status", "SUCCESS",
                "result", Map.of("data", Map.of("items", List.of(
                        Map.of("id", 10011L, "title", "NovaBuds Pro", "minPrice", 899.0)
                )))
        ));
        AnswerGrounding.Result r = AnswerGrounding.ground(llm, draft, tools);
        assertTrue(r.usedDraft());
        assertTrue(r.issues().stream().anyMatch(i -> i.startsWith("ungrounded_spu")));
    }

    @Test
    void keepsGroundedAnswer() {
        String draft = "1) NovaBuds Pro（¥899） SPU#10011";
        String llm = "对比下来更推荐 NovaBuds Pro（¥899） SPU#10011，降噪更强。";
        List<Map<String, Object>> tools = List.of(Map.of(
                "toolName", "search_products",
                "status", "SUCCESS",
                "result", Map.of("data", Map.of("items", List.of(
                        Map.of("id", 10011L, "title", "NovaBuds Pro", "minPrice", 899.0)
                )))
        ));
        AnswerGrounding.Result r = AnswerGrounding.ground(llm, draft, tools);
        assertFalse(r.usedDraft());
        assertTrue(r.text().contains("NovaBuds Pro"));
    }
}
