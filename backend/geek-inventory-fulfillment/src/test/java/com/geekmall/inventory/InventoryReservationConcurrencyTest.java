package com.geekmall.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReservationConcurrencyTest {
    @Test
    void releaseBeforeDelayedReservationPermanentlyClosesOrder() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:inventory_close;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE inventory_reservation_lock(order_id BIGINT PRIMARY KEY,closed BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE inventory_stock_reservation(id BIGINT,order_no VARCHAR(64),merchant_id BIGINT,warehouse_id BIGINT,sku_id BIGINT,quantity INT,status VARCHAR(32))");
        InventoryService service = new InventoryService(jdbc, new ObjectMapper());
        service.release("SK-CLOSED", 19);
        service.release("SK-CLOSED", 19);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reserve(new InventoryController.ReservationRequest(
                19L, "SK-CLOSED", "310000", Instant.now().plusSeconds(600),
                List.of(new InventoryController.ReservationItem(2001L, 3001L, 11001L, 1)))))
                .isInstanceOf(BusinessException.class).hasMessage("订单库存预占已关闭");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_stock_reservation", Integer.class)).isZero();
    }

    @Test
    void concurrentReservationsNeverExceedAvailableStock() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:inventory_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE inventory_reservation_lock(order_id BIGINT PRIMARY KEY,closed BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.execute("CREATE TABLE inventory_warehouse(id BIGINT PRIMARY KEY,warehouse_code VARCHAR(32),warehouse_name VARCHAR(128),region_code VARCHAR(32),priority INT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE inventory_sku_stock(id BIGINT PRIMARY KEY,merchant_id BIGINT,warehouse_id BIGINT,sku_id BIGINT,physical_qty INT,locked_qty INT,defective_qty INT,version INT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE inventory_stock_reservation(id BIGINT PRIMARY KEY,reservation_no VARCHAR(64),order_id BIGINT,order_no VARCHAR(64),merchant_id BIGINT,warehouse_id BIGINT,sku_id BIGINT,quantity INT,status VARCHAR(32),expires_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(order_id,warehouse_id,sku_id))");
        jdbc.execute("CREATE TABLE inventory_stock_log(id BIGINT PRIMARY KEY,merchant_id BIGINT,warehouse_id BIGINT,sku_id BIGINT,business_type VARCHAR(32),business_no VARCHAR(64),change_physical INT,change_locked INT,before_physical INT,after_physical INT,operator_id BIGINT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE event_outbox(id BIGINT PRIMARY KEY,event_id VARCHAR(64),aggregate_type VARCHAR(64),aggregate_id VARCHAR(64),event_type VARCHAR(128),payload_json VARCHAR(2000),status VARCHAR(32),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO inventory_warehouse VALUES(1,'W1','并发测试仓','310000',100,'ENABLED')");
        jdbc.update("INSERT INTO inventory_sku_stock VALUES(1,2001,1,11001,5,0,0,0,CURRENT_TIMESTAMP)");

        InventoryService service = new InventoryService(jdbc, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficientStock = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            long orderId = 1000 + i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    service.reserve(new InventoryController.ReservationRequest(
                            orderId, "O" + orderId, "310000", Instant.now().plusSeconds(600),
                            List.of(new InventoryController.ReservationItem(2001L, 3001L, 11001L, 1))));
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if ("IV40901".equals(e.code())) {
                        insufficientStock.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(unexpected).as("no worker may fail for an unrelated reason").isEmpty();
        assertThat(success.get()).isEqualTo(5);
        assertThat(insufficientStock.get()).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT locked_qty FROM inventory_sku_stock WHERE id=1", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_stock_reservation", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_stock_log WHERE business_type='RESERVE'", Integer.class)).isEqualTo(5);
    }
}
