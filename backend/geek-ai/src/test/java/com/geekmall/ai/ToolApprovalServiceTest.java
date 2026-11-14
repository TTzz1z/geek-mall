package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolApprovalServiceTest {
    @Test
    void confirmsOnlyForOwnerAndResumesExactlyOneDraftAfterRestart() {
        var fixture = fixture();
        var jdbc = fixture.jdbc();
        var service = fixture.service();
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(fixture.tools()).validateDraftResources(anyString(), anyMap(), anyLong(), anyString());
        assertThatThrownBy(() -> service.request(1, 5)).isInstanceOf(BusinessException.class);
        String token = service.request(1, 4);
        assertThat(service.request(1, 4)).isEqualTo(token);
        assertThatThrownBy(() -> service.confirm(token, 5, "Bearer other")).isInstanceOf(BusinessException.class);
        assertThat(service.confirm(token, 4, "Bearer owner")).containsEntry("status", "CONFIRMED");
        assertThat(service.cancel(token, 4)).containsEntry("status", "CONFIRMED");
        assertThat(fixture.draftCount()).isZero();

        var restarted = fixture.service();
        restarted.advance();
        restarted.advance();
        assertThat(restarted.confirm(token, 4, "Bearer owner")).containsEntry("status", "EXECUTED").containsKey("draftNo");
        assertThat(fixture.draftCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM ai_business_draft", String.class)).doesNotContain("Bearer");

        String expired = service.request(2, 4);
        jdbc.update("UPDATE ai_confirmation SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE confirmation_token=?", expired);
        assertThat(service.status(expired, 4)).containsEntry("status", "EXPIRED");
        assertThat(service.confirm(expired, 4, "Bearer owner")).containsEntry("status", "EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tool_call WHERE id=2", String.class)).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentConfirmationAndWorkersExecuteOneBusinessDraft() throws Exception {
        var fixture = fixture();
        var service = fixture.service();
        String token = service.request(1, 4);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var jobs = java.util.stream.IntStream.range(0, 4).mapToObj(i -> executor.submit(() -> {
                start.await();
                service.confirm(token, 4, "Bearer owner");
                fixture.service().advance();
                return true;
            })).toList();
            start.countDown();
            for (var job : jobs) assertThat(job.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(service.status(token, 4)).containsEntry("status", "EXECUTED");
        assertThat(fixture.draftCount()).isEqualTo(1);
    }

    @Test
    void failedLocalWriteRollsBackDraftAndWorkerRetriesConfirmedApproval() {
        var fixture = fixture();
        var service = fixture.service();
        String token = service.request(1, 4);
        service.confirm(token, 4, "Bearer owner");
        fixture.jdbc().execute("ALTER TABLE ai_tool_call ADD CONSTRAINT fail_execution CHECK (status<>'EXECUTED')");
        service.advance();
        assertThat(fixture.draftCount()).isZero();
        assertThat(service.status(token, 4)).containsEntry("status", "CONFIRMED");
        fixture.jdbc().execute("ALTER TABLE ai_tool_call DROP CONSTRAINT fail_execution");
        fixture.service().advance();
        assertThat(fixture.draftCount()).isEqualTo(1);
        assertThat(service.status(token, 4)).containsEntry("status", "EXECUTED");
    }

    @Test
    void transientResourceFailureLeavesApprovalRetryable() {
        var fixture = fixture();
        var service = fixture.service();
        String token = service.request(1, 4);
        doThrow(new BusinessException("AI50301", "temporarily unavailable"))
                .doNothing().when(fixture.tools()).validateDraftResources(anyString(), anyMap(), eq(4L), anyString());
        assertThatThrownBy(() -> service.confirm(token, 4, "Bearer owner")).isInstanceOf(BusinessException.class);
        assertThat(service.status(token, 4)).containsEntry("status", "PENDING");
        assertThat(service.confirm(token, 4, "Bearer owner")).containsEntry("status", "CONFIRMED");
        service.advance();
        assertThat(fixture.draftCount()).isEqualTo(1);
    }

    @Test
    void directExecutionRequiresMatchingApprovalAndArguments() {
        var fixture = fixture();
        var service = fixture.service();
        var arguments = java.util.Map.<String, Object>of("orderNo", "GM1", "addressId", 2);
        assertThatThrownBy(() -> fixture.tools().executeApprovedDraft("modify_address_draft", arguments, 4, 999))
                .isInstanceOf(BusinessException.class);
        String token = service.request(1, 4);
        service.confirm(token, 4, "Bearer owner");
        long id = fixture.jdbc().queryForObject("SELECT id FROM ai_confirmation WHERE confirmation_token=?", Long.class, token);
        assertThatThrownBy(() -> fixture.tools().executeApprovedDraft("modify_address_draft",
                java.util.Map.of("orderNo", "GM2", "addressId", 2), 4, id)).isInstanceOf(BusinessException.class);
        fixture.jdbc().update("UPDATE ai_conversation SET user_id=5 WHERE id=7");
        service.advance();
        assertThat(fixture.jdbc().queryForObject("SELECT status FROM ai_confirmation WHERE id=?", String.class, id)).isEqualTo("DENIED");
        assertThat(fixture.draftCount()).isZero();
    }

    private static Fixture fixture() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:tool_approval_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE ai_conversation(id BIGINT PRIMARY KEY,user_id BIGINT);
                CREATE TABLE ai_tool_call(id BIGINT PRIMARY KEY,conversation_id BIGINT,tool_name VARCHAR(64),arguments_json VARCHAR(2000),
                    status VARCHAR(32),result_summary VARCHAR(2000));
                CREATE TABLE ai_confirmation(id BIGINT PRIMARY KEY,tool_call_id BIGINT,user_id BIGINT,
                    confirmation_token VARCHAR(64),expires_at TIMESTAMP,status VARCHAR(32),created_at TIMESTAMP,
                    execution_enabled BOOLEAN,draft_no VARCHAR(64));
                CREATE TABLE ai_business_draft(id BIGINT PRIMARY KEY,draft_no VARCHAR(64) UNIQUE,user_id BIGINT,
                    draft_type VARCHAR(64),status VARCHAR(32),payload_json VARCHAR(2000),created_at TIMESTAMP,updated_at TIMESTAMP);
                """);
        jdbc.update("INSERT INTO ai_conversation VALUES(7,4)");
        jdbc.update("INSERT INTO ai_tool_call VALUES(1,7,'modify_address_draft','{\"orderNo\":\"GM1\",\"addressId\":2}','PENDING',NULL)");
        jdbc.update("INSERT INTO ai_tool_call VALUES(2,7,'cancel_paid_order_draft','{\"orderNo\":\"GM1\"}','PENDING',NULL)");
        var mapper = new ObjectMapper();
        var tx = new DataSourceTransactionManager(ds);
        var tools = spy(new ToolExecutor(null, mapper, jdbc, null, "http://localhost", "http://localhost",
                "http://localhost", "http://localhost", "http://localhost"));
        doNothing().when(tools).validateDraftResources(anyString(), anyMap(), anyLong(), anyString());
        return new Fixture(jdbc, mapper, tools, tx);
    }

    private record Fixture(JdbcTemplate jdbc, ObjectMapper mapper, ToolExecutor tools, DataSourceTransactionManager tx) {
        ToolApprovalService service() { return new ToolApprovalService(jdbc, mapper, tools, tx); }
        int draftCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM ai_business_draft", Integer.class); }
    }
}
