package com.geekmall.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.AuthAccessKeys;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.JwtService;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Component
class GatewayAuthenticationFilter implements GlobalFilter, Ordered {
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redis;

    GatewayAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper, ReactiveStringRedisTemplate redis) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isPublic(path)) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少访问凭证");
        }
        final AuthPrincipal principal;
        try {
            principal = jwtService.parse(authorization.substring(7));
        } catch (JwtException | IllegalArgumentException exception) {
            return unauthorized(exchange, "访问凭证无效或已过期");
        }
        return redis.hasKey(AuthAccessKeys.disabledUserKey(principal.userId()))
                .defaultIfEmpty(false)
                .flatMap(disabled -> {
                    if (Boolean.TRUE.equals(disabled)) {
                        return unauthorized(exchange, "账号已停用，访问凭证已失效");
                    }
                    return chain.filter(exchange);
                });
    }

    private boolean isPublic(String path) {
        return path.equals("/api/v1/auth/login/password") || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/refresh")
                || path.startsWith("/api/v1/payments/callback/")
                || (path.startsWith("/api/v1/products/") && !path.startsWith("/api/v1/products/compare"))
                || path.equals("/api/v1/products/categories/tree") || path.equals("/api/v1/products/brands")
                || path.startsWith("/api/v1/search/") || path.startsWith("/api/v1/shops/")
                || path.startsWith("/api/v1/cms/pages/")
                || path.startsWith("/api/v1/marketing/ab/")
                || path.equals("/api/v1/marketing/seckill/sessions")
                || path.startsWith("/actuator/health") || path.startsWith("/actuator/prometheus")
                || path.startsWith("/actuator/info") || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            String requestId = exchange.getResponse().getHeaders().getFirst("X-Request-Id");
            byte[] body = objectMapper.writeValueAsBytes(
                    new ApiResponse<Void>("AU40101", message, null, requestId, Instant.now()));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception exception) {
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
