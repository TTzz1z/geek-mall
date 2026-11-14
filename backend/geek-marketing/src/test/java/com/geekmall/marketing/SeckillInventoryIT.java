package com.geekmall.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.security.AuthPrincipal;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class SeckillInventoryIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final long SESSION = 10;
    private static final long SKU = 11001;
    private static final long SECOND_SKU = 11002;
    private static final long USER = 4;
    private static final long ADDRESS = 2004;
    private static final String KEY = "test-internal";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private MarketingEventStore events;
    private SeckillInventoryService service;

    @BeforeEach
    void setup() {
        var flyway = flyway(null);
        flyway.clean();
        flyway.migrate();
        jdbc = spy(new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())));
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        events = spy(new MarketingEventStore(jdbc, MAPPER));
        service = new SeckillInventoryService(jdbc, events, manager);
        seedItems();
    }

    @Test
    void failureAfterStockHoldRollsBackAndSameRequestCanRetry() {
        doAnswer(call -> {
            assertThat(reserved(SKU)).isEqualTo(2);
            throw new DataAccessResourceFailureException("lost write before order insert");
        }).doCallRealMethod().when(jdbc).update(startsWith("INSERT INTO marketing_seckill_order(id,"), any(Object[].class));

        assertThatThrownBy(() -> order(USER, 2, "insert-failure")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(reserved(SKU)).isZero();
        assertThat(orderCount()).isZero();
        assertThat(outboxCount()).isZero();

        assertThat(order(USER, 2, "insert-failure").status()).isEqualTo("QUEUED");
        assertThat(reserved(SKU)).isEqualTo(2);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void failureAfterOutboxInsertRollsBackAllWritesAndNothingIsVisibleBeforeCommit() {
        doAnswer(call -> {
            call.callRealMethod();
            assertThat(reserved(SKU)).isEqualTo(2);
            assertThat(orderCount()).isEqualTo(1);
            assertThat(outboxCount()).isEqualTo(1);
            var reader = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
            assertThat(reader.queryForObject("SELECT reserved_qty FROM marketing_seckill_item WHERE id=11", Long.class)).isZero();
            assertThat(reader.queryForObject("SELECT COUNT(*) FROM marketing_seckill_order", Integer.class)).isZero();
            assertThat(reader.queryForObject("SELECT COUNT(*) FROM marketing_event_outbox", Integer.class)).isZero();
            throw new DataAccessResourceFailureException("failed after Outbox insert");
        }).doCallRealMethod().when(events).append(eq("SECKILL_ORDER"), anyString(), eq(MarketingRabbitConfiguration.SECKILL_ROUTING), anyMap());

        assertThatThrownBy(() -> order(USER, 2, "outbox-failure")).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(reserved(SKU)).isZero();
        assertThat(orderCount()).isZero();
        assertThat(outboxCount()).isZero();

        assertThat(order(USER, 2, "outbox-failure").status()).isEqualTo("QUEUED");
        assertThat(reserved(SKU)).isEqualTo(2);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void publicAndInternalReplayReturnCurrentStatusAndCheckOwnershipAndRequest() throws Exception {
        var publicController = new MarketingController(jdbc, service);
        var request = new MockHttpServletRequest();
        var principal = new AuthPrincipal(USER, "test", "mall", null, null, Set.of("USER"), Set.of());
        var body = new MarketingController.SeckillOrder(SESSION, ADDRESS, 2);
        var first = publicController.order(SKU, body, "shared", principal, request).data();
        assertThat(first).containsEntry("requestId", "shared").containsEntry("status", "QUEUED");
        long id = ((Number) first.get("seckillOrderId")).longValue();
        var internal = internalOrder(SKU, USER, 2, ADDRESS, "shared");
        assertThat(internal).containsEntry("seckillOrderId", id).containsEntry("status", "QUEUED")
                .containsEntry("idempotentReplay", true).containsEntry("tradeOrderNo", null);

        assertCode(() -> internalOrder(SKU, USER + 1, 2, ADDRESS, "shared"), "MK40904");
        assertCode(() -> internalOrder(SECOND_SKU, USER, 2, ADDRESS, "shared"), "MK40904");
        assertCode(() -> internalOrder(SKU, USER, 1, ADDRESS, "shared"), "MK40904");
        assertCode(() -> internalOrder(SKU, USER, 2, ADDRESS + 1, "shared"), "MK40904");
        assertCode(() -> order(USER, 1, "other-key"), "MK40904");
        var otherUser = new AuthPrincipal(USER + 1, "other", "mall", null, null, Set.of("USER"), Set.of());
        assertCode(() -> publicController.order(SKU, body, "shared", otherUser, request), "MK40904");

        confirmByRequest("shared", "SK" + id);
        new TransactionTemplate(manager).executeWithoutResult(tx -> controller().markCreated(KEY, id, "SK" + id));
        var replay = publicController.order(SKU, body, "shared", principal, request).data();
        assertThat(replay).containsEntry("seckillOrderId", id).containsEntry("status", "CREATED")
                .containsEntry("tradeOrderNo", "SK" + id).containsEntry("idempotentReplay", true);
        assertThat(reserved(SKU)).isEqualTo(2);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        var payload = MAPPER.readTree(jdbc.queryForObject("SELECT payload_json FROM marketing_event_outbox", String.class)).path("payload");
        assertThat(payload.path("seckillOrderId").longValue()).isEqualTo(id);
        assertThat(payload.path("addressId").longValue()).isEqualTo(ADDRESS);
        var storedExpiry = jdbc.queryForObject("SELECT UNIX_TIMESTAMP(expires_at) FROM marketing_seckill_order", BigDecimal.class);
        assertThat(Instant.parse(payload.path("expiresAt").textValue()).toEpochMilli())
                .isEqualTo(storedExpiry.movePointRight(3).longValueExact());
    }

    @Test
    void rejectsInvalidInternalQuantityAndAddressBeforeStockHold() {
        assertCode(() -> internalOrder(SKU, USER, 1, null, "missing-address"), "MK40030");
        assertCode(() -> internalOrder(SKU, USER, 1, 0L, "zero-address"), "MK40030");
        assertCode(() -> internalOrder(SKU, USER, -1, ADDRESS, "negative-quantity"), "MK40031");
        assertCode(() -> internalOrder(SKU, USER, 1.5, ADDRESS, "fractional-quantity"), "MK40031");
        assertCode(() -> internalOrder(SKU, USER, 1, ADDRESS, ""), "MK40031");
        assertCode(() -> internalOrder(SKU, USER, 4, ADDRESS, "over-limit"), "MK40903");
        assertThat(reserved(SKU)).isZero();
        assertThat(orderCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void concurrentAdmissionsEnforceQuantityCapacityAndSharedRetry() throws Exception {
        var same = concurrent(8, ignored -> order(USER, 2, "concurrent-replay"));
        assertThat(same).extracting(SeckillInventoryService.Admission::id).containsOnly(same.getFirst().id());
        assertThat(same).filteredOn(result -> !result.replay()).hasSize(1);
        var stockRace = concurrent(8, n -> {
            try {
                order(100 + n, 3, "stock-race-" + n);
                return "QUEUED";
            } catch (BusinessException e) {
                return e.code();
            }
        });
        assertThat(stockRace).filteredOn("QUEUED"::equals).hasSize(2);
        assertThat(stockRace).filteredOn("MK40903"::equals).hasSize(6);
        var remaining = order(200, 2, "last-stock");
        assertThat(remaining.status()).isEqualTo("QUEUED");
        assertThat(reserved(SKU)).isEqualTo(10);
        assertThat(orderCount()).isEqualTo(4);
        assertThat(outboxCount()).isEqualTo(4);
        var publicController = new MarketingController(jdbc, service);
        var request = new MockHttpServletRequest();
        assertThat(publicController.qualify(SKU, SESSION,
                new AuthPrincipal(USER, "test", "mall", null, null, Set.of("USER"), Set.of()), request).data())
                .containsEntry("qualified", false).containsEntry("reason", "OUT_OF_STOCK");
    }

    @Test
    void concurrentUseOfGlobalKeyAcrossItemsCommitsOnlyOneHold() throws Exception {
        var wroteHold = new CountDownLatch(2);
        doAnswer(call -> {
            int result = (Integer) call.callRealMethod();
            wroteHold.countDown();
            await(wroteHold);
            return result;
        }).when(jdbc).update(startsWith("UPDATE marketing_seckill_item SET reserved_qty=reserved_qty+"), any(Object[].class));
        var results = concurrent(2, n -> {
            try {
                service.order(SESSION, n == 0 ? SKU : SECOND_SKU, USER, 2, ADDRESS, "global-key");
                return "QUEUED";
            } catch (BusinessException e) {
                return e.code();
            }
        });
        assertThat(results).containsExactlyInAnyOrder("QUEUED", "MK40904");
        assertThat(reserved(SKU) + reserved(SECOND_SKU)).isEqualTo(2);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void failureAfterStockRestoreRollsBackAndConcurrentReleaseRestoresOnce() throws Exception {
        var order = order(USER, 2, "release-retry");
        assertCode(() -> service.release(SESSION, SKU, USER, 1), "MK40906");
        doAnswer(call -> {
            assertThat(reserved(SKU)).isZero();
            throw new DataAccessResourceFailureException("lost order completion");
        }).doCallRealMethod().when(jdbc).update(startsWith("UPDATE marketing_seckill_order SET status='RELEASED'"), any(Object[].class));

        assertThatThrownBy(() -> service.release(SESSION, SKU, USER, 2)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(status(order.id())).isEqualTo("QUEUED");
        assertThat(reserved(SKU)).isEqualTo(2);

        concurrent(8, ignored -> {
            service.release(SESSION, SKU, USER, 2);
            return null;
        });
        assertThat(status(order.id())).isEqualTo("RELEASED");
        assertThat(reserved(SKU)).isZero();
        assertThat(order(USER, 2, "release-retry").status()).isEqualTo("RELEASED");
        assertCode(() -> order(USER, 2, "still-one-per-user"), "MK40904");
    }

    @Test
    void staleExpiryCannotReleaseConcurrentlyConfirmedOrder() throws Exception {
        var order = order(USER, 2, "confirmed");
        var expiryStarted = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new TransactionTemplate(manager).execute(tx -> {
                jdbc.queryForMap("SELECT id FROM marketing_seckill_order WHERE id=? FOR UPDATE", order.id());
                var release = pool.submit(() -> {
                    expiryStarted.countDown();
                    service.releaseExpired(SESSION, SKU, order.id());
                });
                await(expiryStarted);
                controller().markCreatedByRequest(KEY, "confirmed", "SK" + order.id());
                expire(order.id());
                return release;
            });
            pending.get(20, TimeUnit.SECONDS);
        }
        assertThat(status(order.id())).isEqualTo("CREATED");
        assertThat(reserved(SKU)).isEqualTo(2);
    }

    @Test
    void expiredQueuedOrderIsReleasedAndCannotBeConfirmedLate() {
        var order = order(USER, 2, "expired");
        expire(order.id());
        assertCode(() -> confirmByRequest("expired", "SK" + order.id()), "MK40905");
        var job = new SeckillWarmupJob(jdbc, service);
        job.releaseExpired();
        job.releaseExpired();
        assertCode(() -> confirmByRequest("expired", "SK" + order.id()), "MK40905");
        assertThat(status(order.id())).isEqualTo("RELEASED");
        assertThat(reserved(SKU)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"utf8mb4_0900_ai_ci", "utf8mb4_unicode_ci"})
    void migratesHistoricalReservationsAndReplaysLegacyRows(String collation) {
        jdbc.execute("ALTER DATABASE CHARACTER SET utf8mb4 COLLATE " + collation);
        var prior = flyway("3");
        prior.clean();
        prior.migrate();
        seedItems();
        jdbc.update("""
                INSERT INTO marketing_seckill_order(id,session_id,sku_id,user_id,quantity,request_id,status,created_at)
                VALUES (41,10,11001,4,2,'legacy-queued','QUEUED',CURRENT_TIMESTAMP),
                       (42,10,11001,5,3,'legacy-created','CREATED',CURRENT_TIMESTAMP),
                       (43,10,11001,6,2,'legacy-pending','RELEASE_PENDING',CURRENT_TIMESTAMP),
                       (44,10,11001,7,4,'legacy-released','RELEASED',CURRENT_TIMESTAMP),
                       (45,10,11001,8,1,'legacy-timeout','TIMEOUT',CURRENT_TIMESTAMP)
                """);
        events.append("SECKILL_ORDER", "41", MarketingRabbitConfiguration.SECKILL_ROUTING, Map.of("addressId", ADDRESS));
        events.append("SECKILL_ORDER", "042", MarketingRabbitConfiguration.SECKILL_ROUTING, Map.of("addressId", ADDRESS + 1));
        events.append("SECKILL_ORDER", "42 ", MarketingRabbitConfiguration.SECKILL_ROUTING, Map.of("addressId", ADDRESS + 1));

        var updated = flyway(null);
        updated.migrate();
        updated.migrate();
        assertThat(reserved(SKU)).isEqualTo(6);
        assertThat(status(43)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,created_at,expires_at) FROM marketing_seckill_order WHERE id=41", Integer.class)).isEqualTo(900);
        assertThat(order(USER, 2, "legacy-queued").id()).isEqualTo(41);
        assertCode(() -> internalOrder(SKU, USER, 2, ADDRESS + 1, "legacy-queued"), "MK40904");
        assertThat(jdbc.queryForObject("SELECT address_id FROM marketing_seckill_order WHERE id=42", Long.class)).isNull();
        assertThat(internalOrder(SKU, 5, 3, ADDRESS, "legacy-created")).containsEntry("idempotentReplay", true);
        service.release(SESSION, SKU, 6, 2);
        assertThat(reserved(SKU)).isEqualTo(6);
        service.release(SESSION, SKU, 8, 1);
        assertThat(reserved(SKU)).isEqualTo(5);
    }

    private void seedItems() {
        jdbc.update("""
                INSERT INTO marketing_seckill_session(id,session_code,start_at,end_at,status)
                VALUES(10,'test-session',TIMESTAMPADD(MINUTE,-5,NOW()),TIMESTAMPADD(MINUTE,30,NOW()),'ENABLED')
                """);
        jdbc.update("""
                INSERT INTO marketing_seckill_item(id,session_id,sku_id,price,stock_qty,limit_per_user,status)
                VALUES(11,10,11001,20.00,10,3,'ENABLED'),(12,10,11002,21.00,10,3,'ENABLED')
                """);
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure().cleanDisabled(false).locations("filesystem:src/main/resources/db/migration")
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private InternalMarketingController controller() {
        return new InternalMarketingController(jdbc, mock(PromotionService.class), service, KEY);
    }

    private SeckillInventoryService.Admission order(long user, int quantity, String request) {
        return service.order(SESSION, SKU, user, quantity, ADDRESS, request);
    }

    private Map<String, Object> internalOrder(long sku, long user, Number quantity, Long address, String request) {
        var body = MAPPER.createObjectNode().put("sessionId", SESSION).put("userId", user).put("requestId", request);
        body.set("quantity", MAPPER.valueToTree(quantity));
        if (address != null) body.put("addressId", address);
        return controller().internalSeckillOrder(KEY, sku, body).data();
    }

    private void confirmByRequest(String request, String tradeOrder) {
        new TransactionTemplate(manager).executeWithoutResult(tx -> controller().markCreatedByRequest(KEY, request, tradeOrder));
    }

    private void expire(long id) {
        jdbc.update("UPDATE marketing_seckill_order SET expires_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE id=?", id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM marketing_seckill_order WHERE id=?", String.class, id);
    }

    private long reserved(long sku) {
        return jdbc.queryForObject("SELECT reserved_qty FROM marketing_seckill_item WHERE session_id=? AND sku_id=?", Long.class, SESSION, sku);
    }

    private int orderCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM marketing_seckill_order", Integer.class); }
    private int outboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM marketing_event_outbox", Integer.class); }

    private static <T> List<T> concurrent(int count, IntFunction<T> call) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var pending = IntStream.range(0, count).mapToObj(index -> pool.submit(() -> {
                await(start);
                return call.apply(index);
            })).toList();
            start.countDown();
            var result = new ArrayList<T>();
            for (var next : pending) result.add(next.get(20, TimeUnit.SECONDS));
            return result;
        }
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
