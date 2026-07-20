package com.geekmall.trade;

import com.geekmall.common.api.ApiResponse;import com.geekmall.common.security.AuthPrincipal;import jakarta.servlet.http.HttpServletRequest;import jakarta.validation.Valid;import jakarta.validation.constraints.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;import java.math.BigDecimal;import java.util.Map;
@RestController @RequestMapping("/api/v1/payments") class PaymentController{private final PaymentService service;PaymentController(PaymentService service){this.service=service;}
 @PostMapping @PreAuthorize("hasRole('USER')") ApiResponse<Map<String,Object>> create(@Valid @RequestBody CreatePayment body,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.create(p.userId(),body),r);}
 @GetMapping("/{paymentNo}") @PreAuthorize("hasRole('USER')") ApiResponse<Map<String,Object>> get(@PathVariable String paymentNo,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.payment(p.userId(),paymentNo),r);}
 @PostMapping("/{paymentNo}/simulate") @PreAuthorize("hasRole('USER')") ApiResponse<Map<String,Object>> simulate(@PathVariable String paymentNo,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.simulate(p.userId(),paymentNo),r);}
 @PostMapping("/callback/{channel}") ApiResponse<Map<String,Object>> callback(@PathVariable String channel,@RequestHeader("X-Payment-Signature")String signature,@Valid @RequestBody Callback body,HttpServletRequest r){return ok(service.callback(channel,signature,body),r);}
 private static<T>ApiResponse<T>ok(T d,HttpServletRequest r){return ApiResponse.success(d,String.valueOf(r.getAttribute("requestId")));}
 record CreatePayment(@NotBlank String orderNo,@NotBlank String channel){}
 record Callback(@NotBlank String callbackId,@NotBlank String paymentNo,@NotBlank String merchantNo,@NotNull @DecimalMin("0.01")BigDecimal amount,@NotBlank String status,@NotBlank String channelTradeNo){}
}
