package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.messaging.ConfirmedOutbox;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Testcontainers
class TradeInfrastructureIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    @Test
    void migrationsAndLeaseRecoveryUseRealMySql() {
        JdbcTemplate jdbc = migrate();
        var tasks = new TradeTasks(jdbc, 120);
        tasks.enqueue("DEDUCT_STOCK", "lease-it");
        long id = jdbc.queryForObject("SELECT id FROM trade_compensation_task WHERE business_no='lease-it'", Long.class);
        String old = tasks.claim(id);
        assertThat(old).isNotBlank();
        assertThat(tasks.claim(id)).isNull();
        jdbc.update("UPDATE trade_compensation_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW()) WHERE id=?", id);
        String recovered = new TradeTasks(jdbc, 120).claim(id);
        assertThat(recovered).isNotBlank().isNotEqualTo(old);
        assertThat(tasks.finish(id, old, "FAILED", "stale")).isZero();
        assertThat(tasks.finish(id, recovered, "SUCCESS", null)).isEqualTo(1);
    }

    @Test
    void paymentEffectsSurviveWorkerReplacementAndTransientInventoryFailure() {
        JdbcTemplate jdbc = migrate();
        jdbc.update("""
                INSERT INTO trade_order(id,order_no,user_id,total_amount,payable_amount,status,checkout_token,
                    inventory_status,created_at,updated_at)
                VALUES(7901,'GM-PAYMENT-IT',4,100,100,'PENDING_PAYMENT','CHECKOUT-PAYMENT-IT','RESERVED',NOW(),NOW())
                """);
        jdbc.update("""
                INSERT INTO trade_payment(id,payment_no,order_id,user_id,channel,merchant_no,amount,status,created_at,updated_at)
                VALUES(7902,'PAY-RECOVERY-IT',7901,4,'SIMULATED','GEEK-MALL',100,'PENDING',NOW(),NOW())
                """);
        jdbc.update("""
                INSERT INTO trade_order_discount(id,order_id,discount_type,amount,snapshot_json)
                VALUES(7903,7901,'COUPON',10,JSON_OBJECT()),(7904,7901,'POINTS',5,JSON_OBJECT())
                """);
        var inventory = mock(InventoryClient.class);
        var marketing = mock(MarketingClient.class);
        var member = mock(MemberClient.class);
        var fulfillment = mock(PaymentService.class);
        var mapper = new ObjectMapper();
        var adapter = new UnavailableRealPaymentAdapters(List.of(new SimulatedPaymentChannelAdapter()));
        var payment = new PaymentService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), adapter,
                inventory, new TradeEventStore(jdbc, mapper), new TradeTasks(jdbc, 120), "GEEK-MALL", "fixture-secret");
        assertThat(payment.simulate(4, "PAY-RECOVERY-IT")).containsEntry("idempotentReplay", false);
        assertThat(payment.simulate(4, "PAY-RECOVERY-IT")).containsEntry("idempotentReplay", true);
        verifyNoInteractions(inventory, marketing, member, fulfillment);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM event_outbox WHERE aggregate_id='PAY-RECOVERY-IT' AND status='PENDING'
                """, Integer.class)).isEqualTo(1);

        var restarted = new TradeRecoveryJobs(jdbc, mock(OrderService.class), inventory, mock(RefundService.class),
                marketing, member, fulfillment, mock(SettlementClient.class), mock(SeckillOrderService.class),
                mapper, new TradeTasks(jdbc, 120));
        doThrow(new IllegalStateException("inventory unavailable")).doNothing().when(inventory).deduct("GM-PAYMENT-IT");
        restarted.compensate();
        verifyNoInteractions(fulfillment);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trade_compensation_task WHERE business_no='GM-PAYMENT-IT' AND status='RETRY'
                """, Integer.class)).isEqualTo(2);

        jdbc.update("""
                UPDATE trade_compensation_task SET next_retry_at=NOW() WHERE business_no='GM-PAYMENT-IT'
                """);
        restarted.compensate();
        restarted.compensate();
        assertThat(jdbc.queryForList("""
                SELECT status FROM trade_compensation_task WHERE business_no='GM-PAYMENT-IT'
                """, String.class)).containsExactlyInAnyOrder("SUCCESS", "SUCCESS", "SUCCESS");
        verify(inventory, times(2)).deduct("GM-PAYMENT-IT");
        verify(marketing).settleCoupon("GM-PAYMENT-IT", "use");
        verify(member).settlePoints("GM-PAYMENT-IT", "use");
        verify(fulfillment).createFulfillments("GM-PAYMENT-IT");
    }

    @Test
    void unroutableAckRetriesAndRoutedEventMatchesContract() throws Exception {
        JdbcTemplate jdbc = migrate();
        var mapper = new ObjectMapper();
        String route = "payment.payment.succeeded.v1";
        new TradeEventStore(jdbc, mapper).append("PAYMENT", "PAY-IT", route,
                Map.of("orderNo", "GM-IT", "paymentNo", "PAY-IT", "amount", 42, "channel", "SIMULATED"));
        var factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        try {
            var template = new RabbitTemplate(factory);
            template.setMandatory(true);
            var admin = new RabbitAdmin(template);
            admin.declareExchange(new DirectExchange("trade-it"));
            var outbox = new ConfirmedOutbox(jdbc, template, "event_outbox", "trade-it", Set.of());
            outbox.publish();
            assertThat(outboxStatus(jdbc)).isEqualTo("RETRY");
            assertThat(jdbc.queryForObject("SELECT COUNT(published_at) FROM event_outbox WHERE aggregate_id='PAY-IT'", Integer.class)).isZero();

            admin.declareQueue(new Queue("trade-payment-it"));
            admin.declareBinding(new Binding("trade-payment-it", Binding.DestinationType.QUEUE, "trade-it", route, Map.of()));
            jdbc.update("UPDATE event_outbox SET next_retry_at=CURRENT_TIMESTAMP WHERE aggregate_id='PAY-IT'");
            outbox.publish();
            assertThat(outboxStatus(jdbc)).isEqualTo("PUBLISHED");
            var message = template.receive("trade-payment-it", 3000);
            assertThat(message).isNotNull();
            var event = mapper.readTree(message.getBody());
            try (var schemaSource = Files.newInputStream(Path.of("../../contracts/events/domain-event.schema.json"))) {
                var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaSource);
                assertThat(schema.validate(event)).isEmpty();
                var incomplete = event.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) incomplete.path("payload")).remove("orderNo");
                assertThat(schema.validate(incomplete)).isNotEmpty();
            }
            assertThat(event.path("payload").path("orderNo").asText()).isEqualTo("GM-IT");
            assertThat(event.path("eventId").asText()).isEqualTo(message.getMessageProperties().getMessageId());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void refundRemainsProcessingWhenSettlementFailsAndRecoversWithTheSameKey() {
        JdbcTemplate jdbc = migrate();
        jdbc.update("""
                INSERT INTO trade_order(id,order_no,user_id,total_amount,payable_amount,status,checkout_token,
                    paid_payment_id,inventory_status,created_at,updated_at)
                VALUES(7801,'GM-REFUND-IT',4,100,100,'SHIPPED','CHECKOUT-REFUND-IT',7802,'DEDUCTED',NOW(),NOW())
                """);
        jdbc.update("""
                INSERT INTO trade_order_item(id,order_id,sub_order_id,spu_id,sku_id,title,sku_snapshot,quantity,
                    unit_price,discount_amount,payable_amount,created_at)
                VALUES(7803,7801,7804,10001,11001,'fixture',JSON_OBJECT(),2,50,0,100,NOW())
                """);
        var settlement = mock(SettlementClient.class);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var tasks = new TradeTasks(jdbc, 120);
        var first = new RefundService(jdbc, settlement, tasks);
        var amount = new BigDecimal("50.00");
        var refund = tx.execute(status -> first.refund("GM-REFUND-IT", 7803, "AS-REFUND-IT", 1, amount));
        String refundNo = String.valueOf(refund.get("refundNo"));
        doThrow(new IllegalStateException("settlement unavailable")).doNothing()
                .when(settlement).refund(refundNo, 7803, amount);
        assertThatThrownBy(() -> first.settle("AS-REFUND-IT")).hasMessage("settlement unavailable");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trade_refund WHERE refund_no=? AND status='PROCESSING' AND succeeded_at IS NULL
                """, Integer.class, refundNo)).isEqualTo(1);

        var restarted = new RefundService(jdbc, settlement, tasks);
        assertThat(restarted.settle("AS-REFUND-IT")).isTrue();
        assertThat(restarted.settle("AS-REFUND-IT")).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM trade_refund WHERE refund_no=?", String.class, refundNo))
                .isEqualTo("SUCCESS");
        verify(settlement, times(2)).refund(refundNo, 7803, amount);
    }

    private static JdbcTemplate migrate() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    }

    private static String outboxStatus(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT status FROM event_outbox WHERE aggregate_id='PAY-IT'", String.class);
    }
}
