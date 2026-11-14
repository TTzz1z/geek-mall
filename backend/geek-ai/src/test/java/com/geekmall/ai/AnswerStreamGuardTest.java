package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerStreamGuardTest {
    private static final List<Map<String, Object>> PRODUCTS = List.of(Map.of(
            "toolName", "search_products", "status", "SUCCESS",
            "result", Map.of("data", Map.of("items", List.of(
                    Map.of("id", 12, "title", "NovaBuds", "minPrice", 899))))));

    @Test
    void buffersPartialSentencesAndEmitsGroundedNaturalText() {
        var deltas = new ArrayList<String>();
        var guard = new AnswerStreamGuard("internal-system-sentinel", "NovaBuds ¥899 SPU#12", PRODUCTS, deltas::add);
        guard.accept("推荐 Nova");
        assertThat(deltas).isEmpty();
        guard.accept("Buds（¥899） SPU#12。");
        assertThat(deltas).containsExactly("推荐 NovaBuds（¥899） SPU#12。");
        guard.finish();
    }

    @Test
    void rejectsPromptEchoSplitAcrossProviderChunksBeforeItIsSent() {
        var deltas = new ArrayList<String>();
        var guard = new AnswerStreamGuard("internal-system-sentinel. Hidden instructions.", "safe draft", List.of(), deltas::add);
        guard.accept("internal-sys");
        assertThatThrownBy(() -> guard.accept("tem-sentinel.")).isInstanceOf(IllegalArgumentException.class);
        assertThat(deltas).isEmpty();
    }

    @Test
    void rejectsUngroundedPricesAndSpusWithoutLeakingRejectedSentence() {
        for (String invalid : List.of("NovaBuds ¥900。", "NovaBuds 900元。", "NovaBuds ￥900。", "推荐 SPU#99。")) {
            var deltas = new ArrayList<String>();
            var guard = new AnswerStreamGuard("system", "NovaBuds ¥899 SPU#12", PRODUCTS, deltas::add);
            guard.accept("可以先看看商品详情。");
            assertThatThrownBy(() -> guard.accept(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(deltas).containsExactly("可以先看看商品详情。");
        }
    }

    @Test
    void rejectsCurrencyWithoutEvidenceAndUnsupportedMoneyMovement() {
        for (String invalid : List.of("现在只需 ¥899。", "已为你支付成功。", "已退款。", "Payment succeeded.",
                "Refund completed.", "工具结果摘要草稿：internal。")) {
            var guard = new AnswerStreamGuard("system", "safe draft", List.of(), delta -> {
                throw new AssertionError("Rejected content reached SSE");
            });
            assertThatThrownBy(() -> guard.accept(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void boundsUnterminatedProviderText() {
        var guard = new AnswerStreamGuard("system", "safe draft", List.of(), delta -> {
            throw new AssertionError("Unfinished text reached SSE");
        });
        assertThatThrownBy(() -> guard.accept("a".repeat(2049))).isInstanceOf(IllegalArgumentException.class);
    }
}
