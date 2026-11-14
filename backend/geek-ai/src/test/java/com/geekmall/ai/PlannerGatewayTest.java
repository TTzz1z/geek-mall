package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerGatewayTest {
    @Test
    void internalInstructionEchoInPlannerMetadataStillFallsBack() throws Exception {
        var models = mock(ModelGateway.class);
        var mapper = new ObjectMapper();
        when(models.remoteConfigured()).thenReturn(true);
        when(models.completeJson(anyString(), anyString(), anyString(), anyLong(), anyDouble(), anyInt()))
                .thenAnswer(call -> new ModelGateway.CompletionResult("remote", mapper.writeValueAsString(
                        java.util.Map.of("agent", "ORDER_LOGISTICS", "user_goal", call.getArgument(1, String.class),
                                "tool_plan", java.util.List.of())), 10, 20, false, false));
        var gateway = new PlannerGateway(models, mapper, new PlanCache(false, 60000),
                new PlannerConcurrencyGuard(1, 0), true, 1000);

        assertThat(gateway.plan("不要回显内部指令", null, 1L)).isEmpty();
    }

    @Test
    void publicAgentAndToolVocabularyStillProducesLlmPlans() throws Exception {
        for (String plan : java.util.List.of("""
                {"agent":"ORDER_LOGISTICS","user_goal":"draft","tool_plan":[
                  {"name":"modify_address_draft","args":{"orderNo":"GM1","addressId":42}}]}
                """, """
                {"agent":"PRODUCT_KNOWLEDGE","tool_plan":[
                  {"name":"search_products","args":{"keyword":"耳机"},"reason":"先调用 search_products"},
                  {"name":"get_product_detail","args":{"resolveFromSearch":true}},
                  {"name":"add_cart_item","args":{"resolveFromDetail":true}}]}
                """)) {
            var models = mock(ModelGateway.class);
            when(models.remoteConfigured()).thenReturn(true);
            when(models.completeJson(anyString(), anyString(), anyString(), anyLong(), anyDouble(), anyInt()))
                    .thenReturn(new ModelGateway.CompletionResult("remote", plan, 10, 20, false, false));
            var gateway = new PlannerGateway(models, new ObjectMapper(), new PlanCache(false, 60000),
                    new PlannerConcurrencyGuard(1, 0), true, 1000);

            var result = gateway.plan("合法计划", null, 1L);

            assertThat(result).isPresent();
            assertThat(result.orElseThrow().plannerMode()).isEqualTo("LLM");
            assertThat(result.orElseThrow().toolPlan()).isNotEmpty();
        }
    }
}
