package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.InternalServiceAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/internal/trade")
class TradeInternalController {
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final TradeTasks tasks;
    private final ObjectMapper mapper;
    private final String internalKey;

    TradeInternalController(JdbcTemplate jdbc, OrderService orders, TradeTasks tasks, ObjectMapper mapper,
                            @Value("${geek.internal-key}") String internalKey) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.tasks = tasks;
        this.mapper = mapper;
        this.internalKey = internalKey;
    }

    @GetMapping("/orders/{orderNo}")
    ApiResponse<Map<String, Object>> orderSnapshot(@PathVariable String orderNo,
                                                   @RequestParam long userId,
                                                   @RequestHeader("X-Internal-Key") String key,
                                                   HttpServletRequest r) {
        InternalServiceAccess.require(key, internalKey);
        Map<String, Object> detail = orders.detail(userId, orderNo);
        String status = String.valueOf(detail.get("status"));
        if (Set.of("PENDING_PAYMENT", "CANCELLED", "RESERVING", "RESERVATION_RETRY").contains(status)) {
            throw new BusinessException("TR40920", "订单当前状态不可申请售后: " + status);
        }
        return ApiResponse.success(detail, String.valueOf(r.getAttribute("requestId")));
    }

    @PostMapping("/orders/{orderNo}/fulfillment-status")
    @Transactional
    ApiResponse<Map<String, Object>> fulfillmentStatus(@PathVariable String orderNo,
                                                       @RequestParam String status,
                                                       @RequestHeader("X-Internal-Key") String key,
                                                       HttpServletRequest r) {
        InternalServiceAccess.require(key, internalKey);
        return ApiResponse.success(advanceFulfillment(orderNo, status), String.valueOf(r.getAttribute("requestId")));
    }

    @RabbitListener(queues = TradeRabbitConfiguration.FULFILLMENT_QUEUE)
    @Transactional
    void onFulfillmentStatus(String event) throws com.fasterxml.jackson.core.JsonProcessingException {
        var payload = mapper.readTree(event).path("payload");
        try {
            advanceFulfillment(payload.path("orderNo").asText(), payload.path("status").asText());
        } catch (BusinessException e) {
            throw new AmqpRejectAndDontRequeueException("无效的履约状态事件", e);
        }
    }

    private Map<String, Object> advanceFulfillment(String orderNo, String status) {
        if (!Set.of("SHIPPED", "COMPLETED").contains(status)) {
            throw new BusinessException("TR40040", "不支持的履约回写状态: " + status);
        }
        var rows = jdbc.queryForList("SELECT id,status FROM trade_order WHERE order_no=? FOR UPDATE", orderNo);
        if (rows.isEmpty()) {
            throw new BusinessException("TR40404", "订单不存在");
        }
        Map<String, Object> order = rows.getFirst();
        String current = String.valueOf(order.get("status"));
        if ("COMPLETED".equals(current) && "COMPLETED".equals(status)) {
            return Map.of("orderNo", orderNo, "status", current, "idempotentReplay", true);
        }
        if ("SHIPPED".equals(status)) {
            if ("COMPLETED".equals(current) || "SHIPPED".equals(current)) {
                return Map.of("orderNo", orderNo, "status", current, "idempotentReplay", true);
            }
            if (!Set.of("PAID", "PARTIAL_SHIPPED").contains(current)) {
                throw new BusinessException("TR40921", "订单不可标记为已发货: " + current);
            }
            jdbc.update("UPDATE trade_order SET status='SHIPPED',updated_at=CURRENT_TIMESTAMP WHERE id=?", order.get("id"));
            jdbc.update("UPDATE trade_sub_order SET status='SHIPPED',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", order.get("id"));
            jdbc.update("""
                    INSERT INTO trade_order_log(id,order_id,from_status,to_status,operator_type,operator_id,detail,created_at)
                    VALUES(?,?,?,'SHIPPED','FULFILLMENT',0,'全部发货',CURRENT_TIMESTAMP)
                    """, DistributedId.next(), order.get("id"), current);
            return Map.of("orderNo", orderNo, "status", "SHIPPED");
        }
        if (!Set.of("PAID", "SHIPPED", "PARTIAL_SHIPPED", "COMPLETED").contains(current)) {
            throw new BusinessException("TR40922", "订单不可标记为已完成: " + current);
        }
        if (!"COMPLETED".equals(current)) {
            jdbc.update("UPDATE trade_order SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE id=?", order.get("id"));
            jdbc.update("UPDATE trade_sub_order SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", order.get("id"));
            jdbc.update("""
                    INSERT INTO trade_order_log(id,order_id,from_status,to_status,operator_type,operator_id,detail,created_at)
                    VALUES(?,?,?,'COMPLETED','FULFILLMENT',0,'确认收货',CURRENT_TIMESTAMP)
                    """, DistributedId.next(), order.get("id"), current);
            tasks.enqueue("ORDER_SETTLEMENT", orderNo);
        }
        return Map.of("orderNo", orderNo, "status", "COMPLETED");
    }

}
