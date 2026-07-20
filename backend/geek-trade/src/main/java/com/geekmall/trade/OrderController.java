package com.geekmall.trade;

import com.geekmall.common.api.ApiResponse;import com.geekmall.common.security.AuthPrincipal;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import jakarta.validation.constraints.NotBlank;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;
import java.util.List;import java.util.Map;
@RestController @RequestMapping("/api/v1/trade/orders") @PreAuthorize("hasRole('USER')") class OrderController{private final OrderService service;OrderController(OrderService service){this.service=service;}
 @PostMapping ApiResponse<Map<String,Object>> create(@Valid @RequestBody CreateOrder body,@RequestHeader(value="Idempotency-Key",required=false)String key,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.create(p.userId(),body,key),r);}
 @GetMapping ApiResponse<List<Map<String,Object>>> list(@RequestParam(required=false)String status,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.orders(p.userId(),status),r);}
 @GetMapping("/{orderNo}") ApiResponse<Map<String,Object>> detail(@PathVariable String orderNo,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.detail(p.userId(),orderNo),r);}
 @PostMapping("/{orderNo}/cancel") ApiResponse<Map<String,Object>> cancel(@PathVariable String orderNo,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.cancel(p.userId(),orderNo,"USER");return ok(service.detail(p.userId(),orderNo),r);}
 private static<T>ApiResponse<T>ok(T data,HttpServletRequest r){return ApiResponse.success(data,String.valueOf(r.getAttribute("requestId")));}
 record CreateOrder(@NotBlank String checkoutToken,Long addressId,Map<String,String> remarkByShop){}
}
