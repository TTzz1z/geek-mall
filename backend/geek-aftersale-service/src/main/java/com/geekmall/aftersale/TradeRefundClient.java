package com.geekmall.aftersale;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.web.InternalRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
class TradeRefundClient {
    private final RestClient client;
    private final String key;

    TradeRefundClient(@Value("${geek.trade-service-url}") String base, @Value("${geek.internal-key}") String key) {
        this.client = InternalRestClient.create(base);
        this.key = key;
    }

    String refund(String orderNo, long itemId, String aftersaleNo, int quantity, BigDecimal approvedAmount) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", orderNo);
        body.put("orderItemId", itemId);
        body.put("aftersaleId", aftersaleNo);
        body.put("quantity", quantity);
        if (approvedAmount != null) body.put("approvedAmount", approvedAmount);
        try {
            JsonNode response = client.post().uri("/api/v1/internal/trade/refunds")
                    .header("X-Internal-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !"0".equals(response.path("code").asText())) {
                throw new BusinessException("AS50301", "退款服务未返回有效状态");
            }
            return response.path("data").path("status").asText();
        } catch (RestClientResponseException e) {
            var error = e.getResponseBodyAs(JsonNode.class);
            String code = error == null ? "" : error.path("code").asText();
            if (java.util.Set.of("TR40020", "TR40921", "TR40922", "TR40923").contains(code)) {
                return "REJECTED";
            }
            throw e;
        }
    }
}
