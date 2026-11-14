package com.geekmall.marketing;

import com.geekmall.common.api.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class SeckillReleaseRecoveryIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RedisLuaClient redis;

    @BeforeEach
    void setup() {
        var flyway = Flyway.configure().cleanDisabled(false).locations("filesystem:src/main/resources/db/migration")
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load();
        flyway.clean();
        flyway.migrate();
        jdbc = spy(new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())));
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        redis = spy(new RedisLuaClient(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        redis.eval("redis.call('FLUSHDB'); return 1", List.of(), List.of());
        redis.set("seckill:stock:10:11001", "9");
        redis.set("seckill:user:10:11001:4", "1");
        jdbc.update("INSERT INTO marketing_seckill_order(id,session_id,sku_id,user_id,quantity,request_id,status,expires_at,created_at,updated_at) VALUES(9001,10,11001,4,1,'request-9001','QUEUED',TIMESTAMPADD(SECOND,900,NOW()),NOW(),NOW())");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return call.callRealMethod();
        }).when(redis).eval(contains("KEYS[3]"), anyList(), anyList());
    }

    @Test
    void redisCommitThenSqlFailureReplaysPendingReleaseOnce() {
        doThrow(new DataAccessResourceFailureException("lost SQL completion"))
                .doCallRealMethod().when(jdbc).update(startsWith("UPDATE marketing_seckill_order SET status='RELEASED'"), any(Object[].class));
        assertThatThrownBy(() -> service().release(10, 11001, 4, 1)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(status()).isEqualTo("RELEASE_PENDING");
        assertCountersReleasedOnce();
        new SeckillWarmupJob(jdbc, service()).releaseExpired();
        service().release(10, 11001, 4, 1);
        assertThat(status()).isEqualTo("RELEASED");
        assertCountersReleasedOnce();
    }

    @Test
    void redisCommitThenLostResponseReplaysReceiptWithoutSecondIncrement() {
        var first = new AtomicBoolean(true);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Object result = call.callRealMethod();
            if (first.getAndSet(false)) throw new BusinessException("MK50301", "lost Redis response");
            return result;
        }).when(redis).eval(contains("KEYS[3]"), anyList(), anyList());
        assertThatThrownBy(() -> service().release(10, 11001, 4, 1)).isInstanceOf(BusinessException.class);
        assertThat(status()).isEqualTo("RELEASE_PENDING");
        new SeckillWarmupJob(jdbc, service()).releaseExpired();
        assertThat(status()).isEqualTo("RELEASED");
        assertCountersReleasedOnce();
    }

    @Test
    void concurrentReleasesRestoreCountersOnce() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 8).mapToObj(ignored -> pool.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                service().release(10, 11001, 4, 1);
                return null;
            })).toList();
            start.countDown();
            for (var call : calls) call.get(20, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("RELEASED");
        assertCountersReleasedOnce();
    }

    @Test
    void staleExpiryCandidateCannotReleaseConcurrentlyConfirmedOrder() throws Exception {
        var started = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = new TransactionTemplate(manager).execute(tx -> {
                jdbc.queryForMap("SELECT id FROM marketing_seckill_order WHERE id=9001 FOR UPDATE");
                var call = pool.submit(() -> {
                    started.countDown();
                    service().releaseExpired(10, 11001, 4, 1);
                    return null;
                });
                await(started);
                controller().markCreatedByRequest("internal", "request-9001", "SK9001");
                // The scanner selected QUEUED earlier; after waiting on this lock it must recheck CREATED.
                jdbc.update("UPDATE marketing_seckill_order SET expires_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE id=9001");
                return call;
            });
            pending.get(20, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("CREATED");
        assertThat(redis.get("seckill:stock:10:11001")).isEqualTo("9");
        assertThat(redis.get("seckill:user:10:11001:4")).isEqualTo("1");
        verify(redis, never()).eval(contains("KEYS[3]"), anyList(), anyList());
    }

    @Test
    void expiredReleaseFencesLateConfirmationBeforeRedisFinishes() throws Exception {
        jdbc.update("UPDATE marketing_seckill_order SET expires_at=TIMESTAMPADD(SECOND,-1,NOW()) WHERE id=9001");
        var pending = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            pending.countDown();
            await(resume);
            return call.callRealMethod();
        }).when(redis).eval(contains("KEYS[3]"), anyList(), anyList());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var release = pool.submit(() -> { service().releaseExpired(10, 11001, 4, 1); return null; });
            await(pending);
            try {
                assertThat(status()).isEqualTo("RELEASE_PENDING");
                assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(tx ->
                        controller().markCreatedByRequest("internal", "request-9001", "SK9001")))
                        .isInstanceOf(BusinessException.class);
            } finally {
                resume.countDown();
            }
            release.get(20, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("RELEASED");
        assertCountersReleasedOnce();
    }

    private SeckillInventoryService service() { return new SeckillInventoryService(jdbc, redis, manager); }
    private InternalMarketingController controller() {
        return new InternalMarketingController(jdbc, mock(PromotionService.class), redis, mock(MarketingEventStore.class), service(), "internal");
    }
    private String status() { return jdbc.queryForObject("SELECT status FROM marketing_seckill_order WHERE id=9001", String.class); }
    private void assertCountersReleasedOnce() {
        assertThat(redis.get("seckill:stock:10:11001")).isEqualTo("10");
        assertThat(redis.get("seckill:user:10:11001:4")).isEqualTo("0");
        assertThat(redis.get("seckill:release:9001")).isEqualTo("1");
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
