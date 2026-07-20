package com.geekmall.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
class RequestIdGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long started = System.nanoTime();
        String incoming = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        String requestId = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
        ServerWebExchange enriched = exchange.mutate().request(builder -> builder.header("X-Request-Id", requestId)).build();
        enriched.getResponse().getHeaders().set("X-Request-Id", requestId);
        return chain.filter(enriched).doFinally(signal -> log.info(
                "gateway_request_completed request_id={} trace_id={} method={} path={} status={} duration_ms={}",
                requestId, requestId, exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                exchange.getResponse().getStatusCode(), (System.nanoTime() - started) / 1_000_000));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
