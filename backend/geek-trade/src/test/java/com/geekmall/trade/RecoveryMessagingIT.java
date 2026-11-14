package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.messaging.DeadLetterReplay;
import com.geekmall.common.messaging.DurableDeadLetterRecoverer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
class RecoveryMessagingIT {
    @Container static final RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:4.0-management-alpine");

    @Test void legacyPopulatedQueueSurvivesDeploymentThenBoundedFailureAndReplayMutatesOnce() throws Exception {
        var factory = connection();
        try {
            var admin = new RabbitAdmin(factory);
            var rabbit = new RabbitTemplate(factory);
            rabbit.setMandatory(true);
            var config = new TradeRabbitConfiguration();
            // This is the pre-upgrade queue: durable, no x-dead-letter arguments, with a pending event.
            admin.declareQueue(new org.springframework.amqp.core.Queue(TradeRabbitConfiguration.SECKILL_QUEUE));
            String event = Files.readString(Path.of("../../contracts/events/samples/marketing.seckill.order.v1.json"));
            rabbit.convertAndSend("", TradeRabbitConfiguration.SECKILL_QUEUE, event);
            admin.declareQueue(config.seckillQueue());
            assertThat(admin.getQueueInfo(TradeRabbitConfiguration.SECKILL_QUEUE).getMessageCount()).isEqualTo(1);
            admin.declareExchange(config.deadExchange());
            admin.declareQueue(config.seckillDeadQueue());
            admin.declareBinding(config.seckillDeadBinding(config.seckillDeadQueue()));
            var policy = broker.execInContainer("rabbitmqctl", "set_policy", "--apply-to", "queues", "--priority", "10",
                    "geek-recovery-it", "^trade\\.seckill\\.order\\.q$",
                    "{\"dead-letter-exchange\":\"geek.domain.events.dlx\",\"dead-letter-routing-key\":\"trade.seckill.order.q.dlq\"}");
            assertThat(policy.getExitCode()).isZero();

            var ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:messaging_replay;MODE=MySQL;DB_CLOSE_DELAY=-1");
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE trade_event_consume_log(id BIGINT PRIMARY KEY,consumer_group VARCHAR(64),event_id VARCHAR(64),processed_at TIMESTAMP,result VARCHAR(32),UNIQUE(consumer_group,event_id))");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            var fail = new AtomicBoolean(true);
            var attempts = new AtomicInteger();
            var mutations = new AtomicInteger();
            var orders = mock(SeckillOrderService.class);
            doAnswer(call -> {
                attempts.incrementAndGet();
                if (fail.get()) throw new IllegalStateException("temporary database failure");
                mutations.incrementAndGet();
                return null;
            }).when(orders).enqueue(any());
            var consumer = new SeckillOrderConsumer(jdbc, new ObjectMapper(), orders);
            var container = new SimpleMessageListenerContainer(factory);
            container.setQueueNames(TradeRabbitConfiguration.SECKILL_QUEUE);
            container.setDefaultRequeueRejected(false);
            container.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3).backOffOptions(10, 1, 10)
                    .recoverer(new DurableDeadLetterRecoverer(rabbit)).build());
            container.setMessageListener((MessageListener) message -> tx.executeWithoutResult(status -> {
                try { consumer.consume(new String(message.getBody(), StandardCharsets.UTF_8)); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }));
            try {
                container.start();
                await(() -> admin.getQueueInfo(TradeRabbitConfiguration.SECKILL_QUEUE + ".dlq").getMessageCount() == 1);
                assertThat(attempts.get()).isEqualTo(3);
                assertThat(mutations.get()).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_event_consume_log", Integer.class)).isZero();
                fail.set(false);
                assertThat(DeadLetterReplay.replay(rabbit, new ObjectMapper(), TradeRabbitConfiguration.SECKILL_QUEUE, TradeRabbitConfiguration.SECKILL_ROUTING)).isEqualTo(1);
                await(() -> mutations.get() == 1);
                rabbit.convertAndSend("", TradeRabbitConfiguration.SECKILL_QUEUE + ".dlq", event);
                assertThat(DeadLetterReplay.replay(rabbit, new ObjectMapper(), TradeRabbitConfiguration.SECKILL_QUEUE, TradeRabbitConfiguration.SECKILL_ROUTING)).isEqualTo(1);
                await(() -> admin.getQueueInfo(TradeRabbitConfiguration.SECKILL_QUEUE).getMessageCount() == 0);
                assertThat(mutations.get()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_event_consume_log WHERE result='SUCCESS'", Integer.class)).isEqualTo(1);
            } finally { container.stop(); }

            rabbit.convertAndSend("", TradeRabbitConfiguration.SECKILL_QUEUE + ".dlq", "broken json");
            assertThatThrownBy(() -> DeadLetterReplay.replay(rabbit, new ObjectMapper(), TradeRabbitConfiguration.SECKILL_QUEUE, TradeRabbitConfiguration.SECKILL_ROUTING)).isInstanceOf(RuntimeException.class);
            await(() -> admin.getQueueInfo(TradeRabbitConfiguration.SECKILL_QUEUE + ".dlq").getMessageCount() == 1);
        } finally { factory.destroy(); }
    }

    @Test void unboundDeadLetterRouteRequeuesOriginalDelivery() {
        var factory = connection();
        try {
            var rabbit = new RabbitTemplate(factory);
            var admin = new RabbitAdmin(factory);
            admin.declareExchange(new org.springframework.amqp.core.DirectExchange("geek.domain.events.dlx"));
            var properties = new org.springframework.amqp.core.MessageProperties();
            properties.setConsumerQueue("queue-without-dead-letter-binding");
            var message = new org.springframework.amqp.core.Message("{}".getBytes(StandardCharsets.UTF_8), properties);
            assertThatThrownBy(() -> new DurableDeadLetterRecoverer(rabbit).recover(message, new IllegalStateException("poison")))
                    .isInstanceOf(ImmediateRequeueAmqpException.class);
        } finally { factory.destroy(); }
    }

    private CachingConnectionFactory connection() {
        var factory = new CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
        factory.setUsername(broker.getAdminUsername());
        factory.setPassword(broker.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    private void await(java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(done.getAsBoolean()).isTrue();
    }
}
