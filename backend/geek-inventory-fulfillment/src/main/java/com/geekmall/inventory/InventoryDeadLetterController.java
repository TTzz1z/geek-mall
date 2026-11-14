package com.geekmall.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.messaging.DeadLetterReplay;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
class InventoryDeadLetterController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InventoryDeadLetterController.class);
    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    InventoryDeadLetterController(RabbitTemplate rabbit, ObjectMapper mapper) { this.rabbit = rabbit; this.mapper = mapper; }

    @PostMapping("/api/v1/admin/inventory/dead-letters/payment/replay")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<Map<String, Integer>> replay(@AuthenticationPrincipal AuthPrincipal actor, HttpServletRequest request) {
        String requestId = String.valueOf(request.getAttribute("requestId"));
        String queue = InventoryRabbitConfiguration.PAYMENT_QUEUE;
        try {
            int count = DeadLetterReplay.replay(rabbit, mapper, queue, "payment.payment.succeeded.v1");
            log.info("dead_letter_replay user_id={} queue={} replayed={} request_id={} result=SUCCESS", actor.userId(), queue, count, requestId);
            return ApiResponse.success(Map.of("replayed", count), requestId);
        } catch (RuntimeException failure) {
            log.warn("dead_letter_replay user_id={} queue={} request_id={} result=FAILED", actor.userId(), queue, requestId);
            throw failure;
        }
    }
}
