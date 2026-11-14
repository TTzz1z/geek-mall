package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.web.InternalRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Component
class SettlementClient {
    private final RestClient client;
    private final String internalKey;

    SettlementClient(@Value("${geek.settlement-service-url:http://localhost:9010}") String url,
                     @Value("${geek.internal-key}") String internalKey) {
        this.client = InternalRestClient.create(url);
        this.internalKey = internalKey;
    }

    void orderCompleted(long merchantId, String orderNo, long orderItemId, BigDecimal income) {
        post("/order-completed", Map.of("merchantId", merchantId, "orderNo", orderNo,
                "orderItemId", orderItemId, "income", income));
    }

    void refund(String refundNo, long orderItemId, BigDecimal refundAmount) {
        JsonNode data = post("/refund", Map.of("refundNo", refundNo, "orderItemId", orderItemId, "refundAmount", refundAmount));
        if ("IGNORED".equals(data.path("status").asText())) {
            throw new BusinessException("TR50310", "结算服务未受理退款");
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        JsonNode response = client.post().uri("/api/v1/internal/settlement" + path)
                .header("X-Internal-Key", internalKey).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        if (response == null || !"0".equals(response.path("code").asText()) || !response.path("data").isObject()) {
            throw new BusinessException("TR50310", "结算服务未确认执行");
        }
        return response.path("data");
    }
}
