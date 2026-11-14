package com.geekmall.trade;

import com.geekmall.common.domain.DistributedId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class TradeTasks {
    static final String DUE = """
            ((status IN ('PENDING','RETRY') AND next_retry_at<=CURRENT_TIMESTAMP)
              OR (status='RUNNING' AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)))
            """;
    private final JdbcTemplate jdbc;
    private final int leaseSeconds;

    TradeTasks(JdbcTemplate jdbc, @Value("${geek.compensation.lease-seconds:120}") int leaseSeconds) {
        this.jdbc = jdbc;
        this.leaseSeconds = Math.max(1, leaseSeconds);
    }

    void enqueue(String type, String businessNo) {
        enqueue(type, businessNo, null);
    }

    void enqueue(String type, String businessNo, String error) {
        enqueue(type, businessNo, "{}", error);
    }

    void enqueue(String type, String businessNo, String payload, String error) {
        try {
            jdbc.update("""
                    INSERT INTO trade_compensation_task
                    (id,task_type,business_no,payload_json,status,next_retry_at,last_error,created_at,updated_at)
                    VALUES(?,?,?,?,'PENDING',CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, DistributedId.next(), type, businessNo, payload, error);
        } catch (DuplicateKeyException duplicate) {
            if (jdbc.queryForList("SELECT id FROM trade_compensation_task WHERE task_type=? AND business_no=? FOR UPDATE",
                    type, businessNo).isEmpty()) {
                throw duplicate;
            }
        }
    }

    boolean owns(long id, String token) {
        return !jdbc.queryForList("SELECT id FROM trade_compensation_task WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3) FOR UPDATE",
                id, token).isEmpty();
    }

    String claim(long id) {
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("""
                UPDATE trade_compensation_task SET status='RUNNING',lease_token=?,
                    lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND
                """ + DUE, token, leaseSeconds, id);
        return changed == 1 ? token : null;
    }

    int finish(long id, String token, String status, String error) {
        return jdbc.update("""
                UPDATE trade_compensation_task SET status=?,last_error=?,lease_token=NULL,lease_until=NULL,
                    updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(3)
                """, status, error, id, token);
    }
}
