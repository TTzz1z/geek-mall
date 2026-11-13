package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolApprovalServiceTest {
    @Test
    void confirmsOnlyForOwnerAndResumesExactlyOneDraftAfterRestart() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tool_approval;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE ai_tool_call(id BIGINT PRIMARY KEY,tool_name VARCHAR(64),arguments_json VARCHAR(2000),
                    status VARCHAR(32),result_summary VARCHAR(2000));
                CREATE TABLE ai_confirmation(id BIGINT PRIMARY KEY,tool_call_id BIGINT,user_id BIGINT,
                    confirmation_token VARCHAR(64),expires_at TIMESTAMP,status VARCHAR(32),created_at TIMESTAMP,
                    execution_enabled BOOLEAN,draft_no VARCHAR(64));
                CREATE TABLE ai_business_draft(id BIGINT PRIMARY KEY,draft_no VARCHAR(64) UNIQUE,user_id BIGINT,
                    draft_type VARCHAR(64),status VARCHAR(32),payload_json VARCHAR(2000),created_at TIMESTAMP,updated_at TIMESTAMP);
                """);
        jdbc.update("INSERT INTO ai_tool_call VALUES(1,'modify_address_draft','{\"orderNo\":\"GM1\",\"addressId\":2}','PENDING',NULL)");
        jdbc.update("INSERT INTO ai_tool_call VALUES(2,'cancel_paid_order_draft','{\"orderNo\":\"GM1\"}','PENDING',NULL)");
        var mapper = new ObjectMapper();
        var tx = new DataSourceTransactionManager(ds);
        var tools = new ToolExecutor(null, mapper, jdbc, "http://localhost", "http://localhost", "http://localhost", "http://localhost");
        var service = new ToolApprovalService(jdbc, mapper, tools, tx);
        String token = service.request(1, 4);
        assertThatThrownBy(() -> service.confirm(token, 5)).isInstanceOf(BusinessException.class);
        assertThat(service.confirm(token, 4)).containsEntry("status", "CONFIRMED");
        assertThat(service.cancel(token, 4)).containsEntry("status", "CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_business_draft", Integer.class)).isZero();

        var restarted = new ToolApprovalService(jdbc, mapper, tools, tx);
        restarted.advance();
        restarted.advance();
        assertThat(restarted.confirm(token, 4)).containsEntry("status", "EXECUTED").containsKey("draftNo");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_business_draft", Integer.class)).isEqualTo(1);

        String expired = service.request(2, 4);
        jdbc.update("UPDATE ai_confirmation SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE confirmation_token=?", expired);
        assertThat(service.confirm(expired, 4)).containsEntry("status", "EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tool_call WHERE id=2", String.class)).isEqualTo("EXPIRED");
    }
}
