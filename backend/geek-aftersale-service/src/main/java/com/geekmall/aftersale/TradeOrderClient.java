package com.geekmall.aftersale;

import com.fasterxml.jackson.databind.JsonNode;
import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
class TradeOrderClient {
    private final RestClient client;
    private final String internalKey;

    TradeOrderClient(@Value("${geek.trade-service-url}") String url,
                     @Value("${geek.internal-key}") String internalKey) {
        this.client = RestClient.builder().baseUrl(url).build();
        this.internalKey = internalKey;
    }

    Map<String, Object> requireOwnedPaidItem(long userId, String orderNo, long orderItemId, long merchantId, int quantity) {
        try {
            JsonNode node = client.get()
                    .uri(uri -> uri.path("/api/v1/internal/trade/orders/{orderNo}").queryParam("userId", userId).build(orderNo))
                    .header("X-Internal-Key", internalKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = node == null ? null : node.path("data");
            if (data == null || data.isMissingNode() || data.isNull()) {
                throw new BusinessException("AS40410", "订单不存在或无权申请售后");
            }
            String status = data.path("status").asText();
            if (!List.of("PAID", "SHIPPED", "PARTIAL_SHIPPED", "COMPLETED").contains(status)) {
                throw new BusinessException("AS40910", "订单状态不可售后: " + status);
            }
            List<JsonNode> items = new ArrayList<>();
            for (JsonNode sub : data.path("subOrders")) {
                for (JsonNode item : sub.path("items")) {
                    items.add(item);
                    long id = item.path("id").asLong();
                    if (id == orderItemId) {
                        long itemMerchant = sub.path("merchantId").asLong();
                        if (itemMerchant != merchantId) {
                            throw new BusinessException("AS40310", "售后商家与订单行不一致");
                        }
                        int orderedQty = item.path("quantity").asInt();
                        if (quantity < 1 || quantity > orderedQty) {
                            throw new BusinessException("AS40010", "售后数量超出可售后范围");
                        }
                        BigDecimal payable = item.path("payableAmount").isMissingNode()
                                ? BigDecimal.ZERO
                                : new BigDecimal(item.path("payableAmount").asText("0"));
                        return Map.of(
                                "orderNo", orderNo,
                                "orderItemId", orderItemId,
                                "merchantId", merchantId,
                                "status", status,
                                "quantity", orderedQty,
                                "payableAmount", payable,
                                "title", item.path("title").asText(""));
                    }
                }
            }
            throw new BusinessException("AS40411", "订单行不存在或不属于该订单");
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException("AS40410", "订单不存在或无权申请售后");
            }
            throw new BusinessException("AS50310", "校验订单失败: HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            throw new BusinessException("AS50310", "校验订单失败");
        }
    }
}
