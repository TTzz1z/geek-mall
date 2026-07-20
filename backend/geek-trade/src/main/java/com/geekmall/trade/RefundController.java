package com.geekmall.trade;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/trade/refunds")
class RefundController {
    private final RefundService service;
    private final String key;

    RefundController(RefundService service, @Value("${geek.internal-key}") String key) {
        this.service = service;
        this.key = key;
    }

    @PostMapping
    ApiResponse<Map<String, Object>> create(@RequestHeader("X-Internal-Key") String value,
                                            @Valid @RequestBody Request b,
                                            HttpServletRequest r) {
        if (!key.equals(value)) {
            throw new BusinessException("AU40304", "内部服务凭证无效");
        }
        return ApiResponse.success(
                service.refund(b.orderNo(), b.orderItemId(), b.aftersaleId(), b.quantity(), b.approvedAmount()),
                String.valueOf(r.getAttribute("requestId")));
    }

    record Request(@NotBlank String orderNo, @NotNull Long orderItemId, @NotBlank String aftersaleId,
                   @Min(1) int quantity, BigDecimal approvedAmount) {}
}
