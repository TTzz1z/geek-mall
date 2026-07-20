package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Component
class MarketingClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String internalKey;

    MarketingClient(@Value("${geek.marketing-service-url}") String url, @Value("${geek.internal-key}") String internalKey, ObjectMapper mapper) {
        this.client = RestClient.create(url);
        this.internalKey = internalKey;
        this.mapper = mapper;
    }

    Map<String, Object> quote(long userId, BigDecimal merchandiseTotal, Long userCouponId) {
        StringBuilder uri = new StringBuilder("/api/v1/internal/marketing/promotions/quote?userId=").append(userId).append("&merchandiseTotal=").append(merchandiseTotal);
        if (userCouponId != null) uri.append("&userCouponId=").append(userCouponId);
        String path = uri.toString();
        return data(() -> client.post().uri(path).header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    void freezeCoupon(String orderNo, long userId, long userCouponId, BigDecimal amount) {
        data(() -> client.post().uri("/api/v1/internal/marketing/coupons/freeze?orderNo=" + orderNo + "&userId=" + userId + "&userCouponId=" + userCouponId + "&amount=" + amount)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    void settleCoupon(String orderNo, String action) {
        data(() -> client.post().uri("/api/v1/internal/marketing/coupons/" + orderNo + "/" + action)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    void markSeckillCreated(long seckillOrderId, String tradeOrderNo) {
        data(() -> client.post().uri("/api/v1/internal/marketing/seckill/" + seckillOrderId + "/created?tradeOrderNo=" + tradeOrderNo)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    void releaseSeckill(long sessionId, long skuId, long userId, int quantity) {
        data(() -> client.post().uri("/api/v1/internal/marketing/seckill/release")
                .header("X-Internal-Key", internalKey).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sessionId", sessionId, "skuId", skuId, "userId", userId, "quantity", quantity))
                .retrieve().body(JsonNode.class));
    }

    private Map<String, Object> data(java.util.function.Supplier<JsonNode> action) {
        try {
            return mapper.convertValue(action.get().path("data"), Map.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException("TR50303", "营销服务调用失败");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("TR50303", "营销服务暂时不可用");
        }
    }
}

@Component
class MemberClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String internalKey;

    MemberClient(@Value("${geek.member-service-url}") String url, @Value("${geek.internal-key}") String internalKey, ObjectMapper mapper) {
        this.client = RestClient.create(url);
        this.internalKey = internalKey;
        this.mapper = mapper;
    }

    Map<String, Object> profile(long userId) {
        return data(() -> client.get().uri("/api/v1/internal/member/profiles/" + userId)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    Map<String, Object> address(long userId, long addressId) {
        return data(() -> client.get().uri("/api/v1/internal/member/addresses/" + addressId + "?userId=" + userId)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    void freezePoints(String orderNo, long userId, int points) {
        data(() -> client.post().uri("/api/v1/internal/member/points/" + orderNo + "/freeze?userId=" + userId + "&points=" + points)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    void settlePoints(String orderNo, String action) {
        data(() -> client.post().uri("/api/v1/internal/member/points/" + orderNo + "/" + action)
                .header("X-Internal-Key", internalKey).retrieve().body(JsonNode.class));
    }

    private Map<String, Object> data(java.util.function.Supplier<JsonNode> action) {
        try {
            return mapper.convertValue(action.get().path("data"), Map.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException("TR50304", "会员服务调用失败");
        } catch (Exception e) {
            throw new BusinessException("TR50304", "会员服务暂时不可用");
        }
    }
}
