package com.geekmall.trade;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/trade/cart")
@PreAuthorize("hasRole('USER')")
class CartController {
    private final CartService service;
    CartController(CartService service){this.service=service;}
    @GetMapping ApiResponse<Map<String,Object>> cart(@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){return ok(service.cart(p.userId()),r);}
    @PostMapping("/items") ApiResponse<Map<String,Object>> add(@Valid @RequestBody AddItem body,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.add(p.userId(),body);return ok(service.cart(p.userId()),r);}
    @PatchMapping("/items/{id}") ApiResponse<Map<String,Object>> update(@PathVariable long id,@Valid @RequestBody UpdateItem body,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.update(p.userId(),id,body);return ok(service.cart(p.userId()),r);}
    @DeleteMapping("/items/{id}") ApiResponse<Map<String,Object>> delete(@PathVariable long id,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){service.delete(p.userId(),id);return ok(service.cart(p.userId()),r);}
    @PostMapping("/merge") ApiResponse<Map<String,Object>> merge(@Valid @RequestBody MergeCart body,@AuthenticationPrincipal AuthPrincipal p,HttpServletRequest r){for(AddItem item:body.items())service.add(p.userId(),item);return ok(service.cart(p.userId()),r);}
    private static<T>ApiResponse<T>ok(T data,HttpServletRequest r){return ApiResponse.success(data,String.valueOf(r.getAttribute("requestId")));}
    record AddItem(@NotNull Long skuId,@Min(1)int quantity){}
    record UpdateItem(@Min(1)Integer quantity,Boolean selected){}
    record MergeCart(@NotEmpty List<@Valid AddItem> items){}
}
