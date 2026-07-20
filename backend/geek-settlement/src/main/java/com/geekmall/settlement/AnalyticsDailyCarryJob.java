package com.geekmall.settlement;

import com.geekmall.common.domain.DistributedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * When calendar day rolls forward and today has no analytics rows yet,
 * copy the latest available day so dashboards/ops screens are not empty.
 * Does not overwrite rows that already exist for today.
 */
@Component
class AnalyticsDailyCarryJob {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsDailyCarryJob.class);
    private final JdbcTemplate jdbc;

    AnalyticsDailyCarryJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 5 0 * * *")
    void carryForwardIfNeeded() {
        runOnce();
    }

    /** Invoked by schedule and by stage verify scripts via admin endpoint if exposed. */
    int runOnce() {
        LocalDate today = LocalDate.now();
        Integer todayCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_metric_daily WHERE metric_date=?",
                Integer.class, Date.valueOf(today));
        if (todayCount != null && todayCount > 0) {
            return 0;
        }
        List<Date> latest = jdbc.query(
                "SELECT MAX(metric_date) FROM analytics_metric_daily",
                (rs, i) -> rs.getDate(1));
        if (latest.isEmpty() || latest.getFirst() == null) {
            return 0;
        }
        Date source = latest.getFirst();
        if (source.toLocalDate().equals(today)) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT metric_code, dimension, metric_value
                FROM analytics_metric_daily WHERE metric_date=?
                """, source);
        int inserted = 0;
        for (Map<String, Object> row : rows) {
            inserted += jdbc.update("""
                    INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
                    SELECT ?,?,?,?,?,CURRENT_TIMESTAMP FROM DUAL
                    WHERE NOT EXISTS(
                      SELECT 1 FROM analytics_metric_daily
                      WHERE metric_date=? AND metric_code=? AND dimension=?
                    )
                    """,
                    DistributedId.next(),
                    Date.valueOf(today),
                    row.get("metric_code"),
                    row.get("dimension"),
                    row.get("metric_value"),
                    Date.valueOf(today),
                    row.get("metric_code"),
                    row.get("dimension"));
        }
        log.info("analytics carry-forward from {} to {} inserted={}", source, today, inserted);
        return inserted;
    }
}
