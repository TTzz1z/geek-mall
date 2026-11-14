package com.geekmall.trade;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class TradeRecoveryMetrics {
    private final JdbcTemplate jdbc;

    TradeRecoveryMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String state : new String[]{"PENDING", "RETRY", "RUNNING", "MANUAL"}) {
            Gauge.builder("geek.trade.recovery.backlog", this,
                            metrics -> metrics.value("SELECT COUNT(*) FROM trade_compensation_task WHERE status=?", state))
                    .tag("state", state).description("Durable trade recovery tasks by state").register(registry);
        }
        Gauge.builder("geek.trade.recovery.oldest.age.seconds", this, metrics -> metrics.value("""
                SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,CURRENT_TIMESTAMP)),0)
                FROM trade_compensation_task WHERE status IN ('PENDING','RETRY','RUNNING')
                """)).register(registry);
        Gauge.builder("geek.trade.recovery.expired.leases", this, metrics -> metrics.value("""
                SELECT COUNT(*) FROM trade_compensation_task
                WHERE status='RUNNING' AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                """)).register(registry);
        Gauge.builder("geek.trade.late.return.pending", this, metrics -> metrics.value(
                "SELECT COUNT(*) FROM trade_late_payment_return WHERE status<>'SUCCESS'")).register(registry);
    }

    private double value(String sql, Object... args) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class, args);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException unavailable) {
            // A failed observation must not look like an empty backlog.
            return Double.NaN;
        }
    }
}
