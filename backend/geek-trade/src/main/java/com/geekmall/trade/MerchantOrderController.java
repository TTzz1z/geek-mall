package com.geekmall.trade;

import com.geekmall.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/merchant/orders")
@PreAuthorize("hasRole('MERCHANT')")
class MerchantOrderController {
    private final MerchantOrderService service;

    MerchantOrderController(MerchantOrderService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        return ApiResponse.success(service.orders(), requestId(request));
    }

    @GetMapping("/{subOrderNo}")
    ApiResponse<Map<String, Object>> detail(@PathVariable String subOrderNo, HttpServletRequest request) {
        return ApiResponse.success(service.detail(subOrderNo), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("requestId"));
    }
}
