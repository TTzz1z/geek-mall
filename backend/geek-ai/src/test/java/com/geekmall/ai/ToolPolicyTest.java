package com.geekmall.ai;

import com.geekmall.common.api.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolPolicyTest {
    @Test
    void forbidsPaymentOrderAndFormalRefund() {
        assertEquals(RiskLevel.L4, ToolPolicy.riskOf("pay_order"));
        assertEquals(RiskLevel.L4, ToolPolicy.riskOf("submit_order"));
        assertEquals(RiskLevel.L4, ToolPolicy.riskOf("formal_refund"));
        assertTrue(ToolPolicy.isForbidden("pay_order"));
        assertEquals(RiskLevel.L0, ToolPolicy.riskOf("search_products"));
        assertEquals(RiskLevel.L2, ToolPolicy.riskOf("add_cart_item"));
        assertEquals(RiskLevel.L2, ToolPolicy.riskOf("create_aftersale_draft"));
    }

    @Test
    void strictSchemasRejectInjectionAndApprovalCannotAuthorizeForbiddenTools() {
        assertEquals("AI40401", assertThrows(BusinessException.class,
                () -> ToolPolicy.authorize("unknown_tool", Map.of(), 4, false)).code());
        assertEquals("AI40301", assertThrows(BusinessException.class,
                () -> ToolPolicy.authorize("formal_refund", Map.of("approved", true), 4, true)).code());
        assertEquals("AI40305", assertThrows(BusinessException.class,
                () -> ToolPolicy.authorize("modify_address_draft", Map.of("orderNo", "GM1", "addressId", 1), 4, false)).code());
        assertThrows(BusinessException.class,
                () -> ToolPolicy.validatePlan("add_cart_item", Map.of("skuId", "42", "quantity", 1)));
        assertThrows(BusinessException.class,
                () -> ToolPolicy.validatePlan("add_cart_item", Map.of("skuId", 42, "quantity", 1, "userId", 100)));
        assertThrows(BusinessException.class,
                () -> ToolPolicy.validatePlan("navigate_to_page", Map.of("path", "//example.com")));
        assertDoesNotThrow(() -> ToolPolicy.validatePlan("add_cart_item", Map.of("resolveFromDetail", true, "quantity", 1)));
        assertThrows(BusinessException.class,
                () -> ToolPolicy.authorize("add_cart_item", Map.of("resolveFromDetail", true, "quantity", 1), 4, false));
    }
}
