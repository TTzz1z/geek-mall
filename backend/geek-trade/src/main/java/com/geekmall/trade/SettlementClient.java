package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;

@Component
class SettlementClient {
    private static final Logger log = LoggerFactory.getLogger(SettlementClient.class);
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String internalKey;

    SettlementClient(@Value("${geek.settlement-service-url:http://localhost:9010}") String url,
                     @Value("${geek.internal-key}") String internalKey,
                     ObjectMapper mapper) {
        this.client = RestClient.create(url);
        this.internalKey = internalKey;
        this.mapper = mapper;
    }

    void orderCompleted(long merchantId, String orderNo, long orderItemId, BigDecimal income) {
        try {
            client.post().uri("/api/v1/internal/settlement/order-completed")
                    .header("X-Internal-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("merchantId", merchantId, "orderNo", orderNo, "orderItemId", orderItemId, "income", income))
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("settlement order-completed failed orderNo={} item={}", orderNo, orderItemId);
        }
    }

    void refund(long orderItemId, BigDecimal refundAmount) {
        try {
            client.post().uri("/api/v1/internal/settlement/refund")
                    .header("X-Internal-Key", internalKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderItemId", orderItemId, "refundAmount", refundAmount))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw remote(e, "结算服务退款影响失败");
        } catch (Exception e) {
            log.warn("settlement refund failed item={}", orderItemId);
        }
    }

    private BusinessException remote(RestClientResponseException e, String fallback) {
        try {
            JsonNode n = mapper.readTree(e.getResponseBodyAsString());
            return new BusinessException(n.path("code").asText("TR50310"), n.path("message").asText(fallback));
        } catch (Exception ignored) {
            return new BusinessException("TR50310", fallback);
        }
    }
}
