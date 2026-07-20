package com.geekmall.trade;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trade/checkout")
@PreAuthorize("hasRole('USER')")
class CheckoutController {
    private final CheckoutService service;CheckoutController(CheckoutService service){this.service=service;}
    @PostMapping("/preview") ApiResponse<CheckoutPayload> preview(@Valid @RequestBody PreviewRequest body,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.preview(p.userId(),body),r);}
    @PostMapping("/confirm") ApiResponse<CheckoutPayload> confirm(@Valid @RequestBody ConfirmRequest body,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.confirm(p.userId(),body.checkoutToken()),r);}
    private static<T>ApiResponse<T>ok(T d,HttpServletRequest r){return ApiResponse.success(d,String.valueOf(r.getAttribute("requestId")));}
    record PreviewRequest(List<Long> cartItemIds, @NotNull Long addressId, String receiver, String mobile, String regionCode, String addressDetail, String deliveryMethod, Long userCouponId, Integer pointsToUse){}
    record ConfirmRequest(@NotNull String checkoutToken){}
}
