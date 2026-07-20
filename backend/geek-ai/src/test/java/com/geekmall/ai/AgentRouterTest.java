package com.geekmall.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRouterTest {
    @Test
    void routesByIntent() {
        assertEquals("GUIDE", AgentRouter.route("预算8000办公本", null));
        assertEquals("PRODUCT_KNOWLEDGE", AgentRouter.route("内存是多少", null));
        assertEquals("COMPARE", AgentRouter.route("对比这两款", null));
        assertEquals("ORDER_LOGISTICS", AgentRouter.route("我的订单物流", null));
        assertEquals("AFTERSALE", AgentRouter.route("申请退款", null));
        assertEquals("COMPARE", AgentRouter.route("随便", "COMPARE"));
    }
}
