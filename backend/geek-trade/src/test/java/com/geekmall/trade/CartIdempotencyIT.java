package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@Testcontainers
class CartIdempotencyIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void committedCartMutationSurvivesLostHttpResponseConcurrentReplayAndServiceReplacement() throws Exception {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(dataSource).locations("filesystem:src/main/resources/db/migration").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var products = mock(ProductSnapshotClient.class);
        var inventory = mock(InventoryClient.class);
        var sku = new SkuSnapshot(91, 81, "fixture", BigDecimal.TEN, BigDecimal.TEN, "ENABLED",
                "fixture", "", "", 1, 2, "APPROVED", "ON_SALE", "fixture");
        when(products.snapshots(anyList())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(sku);
        });
        when(inventory.availability(anyList())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(new StockAvailability(1,91,1,100,true,List.of()));
        });
        var mapper = new ObjectMapper();
        var dropResponse = new AtomicBoolean(true);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var client = HttpClient.newHttpClient()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/api/v1/trade/cart/items", exchange -> {
                exchange.getRequestBody().readAllBytes();
                // A new service instance per request proves recovery depends on SQL, not JVM memory.
                var service = new CartService(jdbc,products,inventory,mapper);
                var result = service.addIdempotent(4,new CartController.AddItem(91L,2),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                if (dropResponse.compareAndSet(true,false)) { exchange.close(); return; }
                byte[] body = mapper.writeValueAsBytes(result);
                exchange.sendResponseHeaders(200,body.length);
                try (var out=exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            try {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/trade/cart/items"))
                        .header("Idempotency-Key","turn-fixture:0").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                assertThatThrownBy(() -> client.send(request,HttpResponse.BodyHandlers.ofString())).isInstanceOf(java.io.IOException.class);
                var first=workers.submit(() -> client.send(request,HttpResponse.BodyHandlers.ofString()));
                var second=workers.submit(() -> client.send(request,HttpResponse.BodyHandlers.ofString()));
                assertThat(first.get().statusCode()).isEqualTo(200);
                assertThat(second.get().body()).isEqualTo(first.get().body());
                assertThat(jdbc.queryForObject("SELECT quantity FROM trade_cart_item WHERE user_id=4 AND sku_id=91",Integer.class)).isEqualTo(2);
                var restarted=new CartService(jdbc,products,inventory,mapper);
                assertThatThrownBy(() -> restarted.addIdempotent(4,new CartController.AddItem(91L,3),"turn-fixture:0"))
                        .isInstanceOf(BusinessException.class);
                restarted.addIdempotent(5,new CartController.AddItem(91L,2),"turn-fixture:0");
                assertThat(jdbc.queryForObject("SELECT quantity FROM trade_cart_item WHERE user_id=5 AND sku_id=91",Integer.class)).isEqualTo(2);
                var reads = new java.util.concurrent.atomic.AtomicInteger();
                when(products.snapshots(anyList())).thenAnswer(call -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    if (reads.incrementAndGet() == 2) throw new IllegalStateException("cart response lookup unavailable");
                    return List.of(sku);
                });
                assertThatThrownBy(() -> restarted.addIdempotent(6,new CartController.AddItem(91L,2),"response-failed:0"))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(jdbc.queryForObject("SELECT quantity FROM trade_cart_item WHERE user_id=6 AND sku_id=91",Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT result_json FROM trade_cart_mutation WHERE user_id=6",String.class)).isNull();
                new CartService(jdbc,products,inventory,mapper).addIdempotent(6,new CartController.AddItem(91L,2),"response-failed:0");
                assertThat(jdbc.queryForObject("SELECT quantity FROM trade_cart_item WHERE user_id=6 AND sku_id=91",Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT result_json FROM trade_cart_mutation WHERE user_id=6",String.class)).isNotNull();
            } finally { server.stop(0); }
        }
    }
}
