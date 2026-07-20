package com.geekmall.aftersale;

import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
class TradeRefundClient {
    private final RestClient client;
    private final String key;

    TradeRefundClient(@Value("${geek.trade-service-url}") String base, @Value("${geek.internal-key}") String key) {
        this.client = RestClient.builder().baseUrl(base).build();
        this.key = key;
    }

    void refund(String orderNo, long itemId, String aftersaleNo, int quantity, BigDecimal approvedAmount) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("orderNo", orderNo);
            body.put("orderItemId", itemId);
            body.put("aftersaleId", aftersaleNo);
            body.put("quantity", quantity);
            if (approvedAmount != null) {
                body.put("approvedAmount", approvedAmount);
            }
            client.post().uri("/api/v1/internal/trade/refunds")
                    .header("X-Internal-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new BusinessException("AS50301", "退款服务不可用，售后保持待退款状态");
        }
    }
}
