package com.geekmall.trade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
class ProductSnapshotClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String internalKey;
    ProductSnapshotClient(@Value("${geek.product-service-url}") String url,@Value("${geek.internal-key}") String internalKey,ObjectMapper mapper) {
        this.client=RestClient.create(url); this.internalKey=internalKey; this.mapper=mapper;
    }
    List<SkuSnapshot> snapshots(List<Long> skuIds) {
        JsonNode data = call(() -> client.post().uri("/api/v1/internal/products/sku-snapshots").header("X-Internal-Key",internalKey).contentType(MediaType.APPLICATION_JSON).body(skuIds).retrieve().body(JsonNode.class));
        return mapper.convertValue(data,new TypeReference<>(){});
    }
    private JsonNode call(java.util.function.Supplier<JsonNode> action){try{return action.get().path("data");}catch(RestClientResponseException e){throw remote(e,"商品服务调用失败");}catch(Exception e){throw new BusinessException("TR50301","商品服务暂时不可用");}}
    private BusinessException remote(RestClientResponseException e,String fallback){try{JsonNode n=mapper.readTree(e.getResponseBodyAsString());return new BusinessException(n.path("code").asText("TR50301"),n.path("message").asText(fallback));}catch(Exception ignored){return new BusinessException("TR50301",fallback);}}
}

@Component
class InventoryClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String internalKey;
    InventoryClient(@Value("${geek.inventory-service-url}")String url,@Value("${geek.internal-key}")String internalKey,ObjectMapper mapper){this.client=RestClient.create(url);this.mapper=mapper;this.internalKey=internalKey;}
    List<StockAvailability> availability(List<Map<String,Object>> items){JsonNode data=call(()->client.post().uri("/api/v1/internal/inventory/availability").header("X-Internal-Key",internalKey).contentType(MediaType.APPLICATION_JSON).body(items).retrieve().body(JsonNode.class));return mapper.convertValue(data,new TypeReference<>(){});}
    List<StockAllocation> reserve(long orderId,String orderNo,String regionCode,Instant expiresAt,List<Map<String,Object>> items){Map<String,Object> body=Map.of("orderId",orderId,"orderNo",orderNo,"regionCode",regionCode,"expiresAt",expiresAt,"items",items);JsonNode data=call(()->client.post().uri("/api/v1/internal/inventory/reservations").header("X-Internal-Key",internalKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));return mapper.convertValue(data.path("allocations"),new TypeReference<>(){});}
    void release(String orderNo){call(()->client.post().uri("/api/v1/internal/inventory/reservations/{orderNo}/release",orderNo).header("X-Internal-Key",internalKey).retrieve().body(JsonNode.class));}
    void deduct(String orderNo){call(()->client.post().uri("/api/v1/internal/inventory/reservations/{orderNo}/deduct",orderNo).header("X-Internal-Key",internalKey).retrieve().body(JsonNode.class));}
    void createFulfillment(Map<String,Object> body){call(()->client.post().uri("/api/v1/internal/fulfillments").header("X-Internal-Key",internalKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));}
    private JsonNode call(java.util.function.Supplier<JsonNode> action){try{return action.get().path("data");}catch(RestClientResponseException e){throw remote(e,"库存服务调用失败");}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException("TR50302","库存服务暂时不可用");}}
    private BusinessException remote(RestClientResponseException e,String fallback){try{JsonNode n=mapper.readTree(e.getResponseBodyAsString());return new BusinessException(n.path("code").asText("TR50302"),n.path("message").asText(fallback));}catch(Exception ignored){return new BusinessException("TR50302",fallback);}}
}
