package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.messaging.DeadLetterReplay;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/trade/dead-letters")
class TradeDeadLetterController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradeDeadLetterController.class);
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    TradeDeadLetterController(RabbitTemplate rabbit, ObjectMapper mapper) { this.rabbit = rabbit; this.mapper = mapper; }

    @PostMapping("/{kind}/replay")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<Map<String, Integer>> replay(@PathVariable String kind, @AuthenticationPrincipal AuthPrincipal actor, HttpServletRequest request) {
        String queue;
        String type;
        switch (kind) {
            case "seckill" -> { queue = TradeRabbitConfiguration.SECKILL_QUEUE; type = TradeRabbitConfiguration.SECKILL_ROUTING; }
            case "fulfillment" -> { queue = TradeRabbitConfiguration.FULFILLMENT_QUEUE; type = "inventory.fulfillment.status-changed.v1"; }
            default -> throw new BusinessException("MQ40002", "不支持的死信队列");
        }
        String requestId = String.valueOf(request.getAttribute("requestId"));
        try {
            int count = DeadLetterReplay.replay(rabbit, mapper, queue, type);
            log.info("dead_letter_replay user_id={} queue={} replayed={} request_id={} result=SUCCESS", actor.userId(), queue, count, requestId);
            return ApiResponse.success(Map.of("replayed", count), requestId);
        } catch (RuntimeException failure) {
            log.warn("dead_letter_replay user_id={} queue={} request_id={} result=FAILED", actor.userId(), queue, requestId);
            throw failure;
        }
    }
}
