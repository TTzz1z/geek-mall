package com.geekmall.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class SeckillRecoveryIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ProductSnapshotClient products;
    private InventoryClient inventory;
    private MarketingClient marketing;
    private MemberClient member;
    private TradeEventStore events;
    private TradeTasks tasks;

    @BeforeEach
    void setup() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        products = mock(ProductSnapshotClient.class);
        inventory = mock(InventoryClient.class);
        marketing = mock(MarketingClient.class);
        member = mock(MemberClient.class);
        events = spy(new TradeEventStore(jdbc, mapper));
        tasks = new TradeTasks(jdbc, 120);
        when(member.address(4, 7)).thenAnswer(call -> {
            assertNoTransaction();
            return Map.of("receiver", "fixture", "mobile", "13800000000", "regionCode", "310000", "detail", "fixture address");
        });
        when(products.snapshots(List.of(11001L))).thenAnswer(call -> {
            assertNoTransaction();
            return List.of(new SkuSnapshot(11001, 10001, "SKU", BigDecimal.TEN, BigDecimal.TEN,
                    "ENABLED", "fixture", "fixture", "cover", 2001, 3001, "APPROVED", "ON_SALE", "shop"));
        });
        when(inventory.reserve(anyLong(), anyString(), anyString(), any(), anyList())).thenAnswer(call -> {
            assertNoTransaction();
            return allocation(call.getArgument(0), call.getArgument(1), call.getArgument(3));
        });
        doAnswer(call -> { assertNoTransaction(); return null; }).when(marketing).markSeckillCreated(anyString(), anyString());
        doAnswer(call -> { assertNoTransaction(); return null; }).when(marketing).releaseSeckill(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void inventoryCommitThenProcessDeathReplaysSameOrderAndPublishesOnce() {
        var reserved = new ConcurrentHashMap<String, List<StockAllocation>>();
        var crash = new AtomicBoolean(true);
        when(inventory.reserve(anyLong(), anyString(), anyString(), any(), anyList())).thenAnswer(call -> {
            assertNoTransaction();
            String orderNo = call.getArgument(1);
            var result = reserved.computeIfAbsent(orderNo, ignored -> allocation(call.getArgument(0), orderNo, call.getArgument(3)));
            if (crash.getAndSet(false)) throw new ProcessDeath();
            return result;
        });
        service().enqueue(payload());
        assertThatThrownBy(() -> worker().compensate()).isInstanceOf(ProcessDeath.class);
        assertThat(phase()).isEqualTo("PREPARED");
        assertThat(orderStatus()).isEqualTo("RESERVING");
        expireLease();
        worker().compensate();
        worker().compensate();
        assertCompleted();
        assertThat(reserved).hasSize(1);
        verify(inventory, times(2)).reserve(anyLong(), eq("SK9001"), anyString(), any(), anyList());
        verify(marketing).markSeckillCreated("request-9001", "SK9001");
    }

    @Test
    void marketingCommitThenProcessDeathReplaysConfirmationWithoutReservingAgain() {
        var remoteCommits = new AtomicInteger();
        var confirmed = new AtomicBoolean();
        var crash = new AtomicBoolean(true);
        doAnswer(call -> {
            assertNoTransaction();
            if (confirmed.compareAndSet(false, true)) remoteCommits.incrementAndGet();
            if (crash.getAndSet(false)) throw new ProcessDeath();
            return null;
        }).when(marketing).markSeckillCreated("request-9001", "SK9001");
        service().enqueue(payload());
        assertThatThrownBy(() -> worker().compensate()).isInstanceOf(ProcessDeath.class);
        assertThat(phase()).isEqualTo("STOCK_RESERVED");
        jdbc.update("UPDATE trade_seckill_progress SET expires_at=TIMESTAMPADD(SECOND,-1,NOW())");
        expireLease();
        worker().compensate();
        assertCompleted();
        assertThat(remoteCommits.get()).isEqualTo(1);
        verify(inventory).reserve(anyLong(), eq("SK9001"), anyString(), any(), anyList());
        verify(marketing, times(2)).markSeckillCreated("request-9001", "SK9001");
    }

    @Test
    void expiredDurableIntentReleasesMarketingWithoutCreatingOrReservingOrder() {
        var expired = (com.fasterxml.jackson.databind.node.ObjectNode) payload();
        expired.put("expiresAt", Instant.now().minusSeconds(30).toString());
        service().enqueue(expired);
        worker().compensate();
        assertThat(phase()).isEqualTo("CANCELLED");
        assertThat(count("trade_order")).isZero();
        worker().compensate();
        verify(marketing).releaseSeckill(10, 11001, 4, 1);
        verifyNoInteractions(products, member, inventory);
        assertThat(jdbc.queryForList("SELECT status FROM trade_compensation_task", String.class)).containsOnly("SUCCESS");
    }

    @Test
    void activationAndOutboxRollbackTogetherThenResumeConfirmedStage() {
        doThrow(new IllegalStateException("outbox unavailable")).doCallRealMethod()
                .when(events).append(eq("TRADE_ORDER"), eq("SK9001"), eq("trade.order.created.v1"), anyMap());
        service().enqueue(payload());
        worker().compensate();
        assertThat(phase()).isEqualTo("MARKETING_CONFIRMED");
        assertThat(orderStatus()).isEqualTo("RESERVING");
        assertThat(count("event_outbox")).isZero();
        jdbc.update("UPDATE trade_compensation_task SET next_retry_at=NOW()");
        worker().compensate();
        assertCompleted();
        verify(marketing).markSeckillCreated("request-9001", "SK9001");
        verify(inventory).reserve(anyLong(), eq("SK9001"), anyString(), any(), anyList());
    }

    @Test
    void duplicateConsumersPersistOneIntentAndRejectChangedRequest() throws Exception {
        String body = mapper.writeValueAsString(Map.of("eventId", "same-event", "payload", payload()));
        var consumer = new SeckillOrderConsumer(jdbc, mapper, service());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(ignored -> pool.submit(() -> {
                start.await();
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    try { consumer.consume(body); } catch (Exception e) { throw new IllegalStateException(e); }
                });
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("trade_seckill_progress")).isEqualTo(1);
        assertThat(count("trade_compensation_task")).isEqualTo(1);
        assertThat(count("trade_event_consume_log")).isEqualTo(1);
        verifyNoInteractions(products, member, inventory, marketing);
        var changed = payload().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("userId", 5);
        assertThatThrownBy(() -> service().enqueue(changed)).isInstanceOf(BusinessException.class);
        worker().compensate();
        assertCompleted();
    }

    @Test
    void failedReservationPersistsBothReleasesAndRetriesMarketingFailure() {
        when(inventory.reserve(anyLong(), anyString(), anyString(), any(), anyList()))
                .thenThrow(new BusinessException("IV40901", "insufficient"));
        doThrow(new IllegalStateException("marketing unavailable")).doNothing()
                .when(marketing).releaseSeckill(10, 11001, 4, 1);
        service().enqueue(payload());
        worker().compensate();
        assertThat(phase()).isEqualTo("CANCELLED");
        assertThat(orderStatus()).isEqualTo("CANCELLED");
        worker().compensate();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_compensation_task WHERE task_type='RELEASE_SECKILL'", String.class)).isEqualTo("RETRY");
        jdbc.update("UPDATE trade_compensation_task SET next_retry_at=NOW()");
        worker().compensate();
        verify(inventory).release(eq("SK9001"), anyLong());
        verify(marketing, times(2)).releaseSeckill(10, 11001, 4, 1);
        assertThat(jdbc.queryForList("SELECT status FROM trade_compensation_task", String.class)).containsOnly("SUCCESS");
    }

    @Test
    void replacedLeaseRejectsOldWorkerProgress() {
        var replace = new AtomicBoolean(true);
        when(inventory.reserve(anyLong(), anyString(), anyString(), any(), anyList())).thenAnswer(call -> {
            assertNoTransaction();
            if (replace.getAndSet(false)) {
                expireLease();
                long id = jdbc.queryForObject("SELECT id FROM trade_compensation_task", Long.class);
                assertThat(tasks.claim(id)).isNotBlank();
            }
            return allocation(call.getArgument(0), call.getArgument(1), call.getArgument(3));
        });
        service().enqueue(payload());
        worker().compensate();
        assertThat(phase()).isEqualTo("PREPARED");
        assertThat(count("trade_fulfillment_plan")).isZero();
        verifyNoInteractions(marketing);
        expireLease();
        worker().compensate();
        assertCompleted();
    }

    @Test
    void migrationRecoversExistingSeckillStagesWithoutReservingPaidOrCancelledOrders() {
        flyway(null).clean();
        flyway("6").migrate();
        seedLegacy(41, "RESERVING");
        seedLegacy(42, "PENDING_PAYMENT");
        seedLegacy(43, "PAID");
        seedLegacy(44, "CANCELLED");
        jdbc.update("INSERT INTO trade_compensation_task(id,task_type,business_no,payload_json,status,next_retry_at,created_at,updated_at) VALUES(-1000000,'EXISTING','other','{}','SUCCESS',NOW(),NOW(),NOW())");
        flyway(null).migrate();
        assertThat(jdbc.queryForObject("SELECT phase FROM trade_seckill_progress WHERE order_no='SK42'", String.class)).isEqualTo("STOCK_RESERVED");
        worker().compensate();
        assertThat(jdbc.queryForList("SELECT status FROM trade_order WHERE order_no IN ('SK41','SK42')", String.class)).containsOnly("PENDING_PAYMENT");
        verify(inventory).reserve(eq(41L), eq("SK41"), anyString(), any(), anyList());
        verify(inventory, never()).reserve(eq(42L), anyString(), anyString(), any(), anyList());
        verify(inventory, never()).reserve(eq(43L), anyString(), anyString(), any(), anyList());
        verify(inventory, never()).reserve(eq(44L), anyString(), anyString(), any(), anyList());
        verify(marketing).markSeckillCreated("legacy-42", "SK42");
        verify(marketing).releaseSeckill(10, 11001, 4, 1);
    }

    private void seedLegacy(long id, String status) {
        jdbc.update("INSERT INTO trade_order(id,order_no,user_id,total_amount,payable_amount,status,checkout_token,inventory_status,created_at,updated_at) VALUES(?,?,4,10,10,?,?,'RESERVING',NOW(),NOW())", id, "SK" + id, status, "SECKILL-legacy-" + id);
        jdbc.update("INSERT INTO trade_sub_order(id,sub_order_no,order_id,merchant_id,shop_id,shop_name,total_amount,discount_amount,payable_amount,status,created_at,updated_at) VALUES(?,?,?,2001,3001,'shop',10,0,10,?,NOW(),NOW())", id, "SUB" + id, id, status);
        jdbc.update("INSERT INTO trade_order_item(id,order_id,sub_order_id,spu_id,sku_id,title,sku_snapshot,quantity,unit_price,discount_amount,payable_amount,created_at) VALUES(?,?,?,10001,11001,'fixture','{}',1,10,0,10,NOW())", id, id, id);
        jdbc.update("INSERT INTO trade_order_discount(id,order_id,order_item_id,discount_type,source_id,amount,snapshot_json) VALUES(?,?,?,'SECKILL','10',0,JSON_OBJECT('sessionId',10,'skuId',11001))", id, id, id);
        jdbc.update("INSERT INTO trade_order_address(order_id,receiver,mobile,region_code,detail) VALUES(?,'fixture','13800000000','310000','fixture')", id);
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().cleanDisabled(false).locations("filesystem:src/main/resources/db/migration")
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        if (target != null) config.target(target);
        return config.load();
    }

    private JsonNode payload() {
        return mapper.valueToTree(Map.of("requestId", "request-9001", "seckillOrderId", 9001,
                "sessionId", 10, "skuId", 11001, "userId", 4, "quantity", 1,
                "price", "10.00", "addressId", 7, "expiresAt", Instant.now().plusSeconds(900).toString()));
    }

    private SeckillOrderService service() {
        return new SeckillOrderService(jdbc, products, inventory, events, marketing, member, manager, tasks, mapper, 900);
    }

    private TradeRecoveryJobs worker() {
        return new TradeRecoveryJobs(jdbc, mock(OrderService.class), inventory, mock(RefundService.class), marketing,
                member, mock(PaymentService.class), mock(SettlementClient.class), service(), mapper, tasks);
    }

    private static List<StockAllocation> allocation(long orderId, String orderNo, Instant expires) {
        return List.of(new StockAllocation("RS" + orderId, orderId, orderNo, 2001, 1, 11001, 1, "RESERVED", expires));
    }

    private void expireLease() {
        jdbc.update("UPDATE trade_compensation_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW()) WHERE task_type='CREATE_SECKILL_ORDER'");
    }

    private String phase() { return jdbc.queryForObject("SELECT phase FROM trade_seckill_progress", String.class); }
    private String orderStatus() { return jdbc.queryForObject("SELECT status FROM trade_order WHERE order_no='SK9001'", String.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static void assertNoTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private void assertCompleted() {
        assertThat(phase()).isEqualTo("COMPLETED");
        assertThat(orderStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(count("trade_order")).isEqualTo(1);
        assertThat(count("trade_fulfillment_plan")).isEqualTo(1);
        assertThat(count("trade_order_timeout")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM trade_compensation_task", String.class)).isEqualTo("SUCCESS");
    }

    private static class ProcessDeath extends Error {}
}
