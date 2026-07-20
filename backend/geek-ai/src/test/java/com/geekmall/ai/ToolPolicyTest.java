package com.geekmall.ai;

import org.junit.jupiter.api.Test;

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
}
