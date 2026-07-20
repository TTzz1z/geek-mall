package com.geekmall.member;

import com.geekmall.common.api.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberPointsIdempotencyTest {
    @Test
    void freezeIsIdempotentAndRejectsOverdraft() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:member_points;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE member_profile(user_id BIGINT PRIMARY KEY,level_code VARCHAR(32),growth_value INT,point_balance INT,frozen_points INT,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE member_point_reservation(id BIGINT PRIMARY KEY,user_id BIGINT,order_no VARCHAR(64),points INT,status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(order_no))");
        jdbc.execute("CREATE TABLE member_point_log(id BIGINT PRIMARY KEY,user_id BIGINT,change_type VARCHAR(32),points INT,balance INT,frozen_balance INT,source_type VARCHAR(32),source_id VARCHAR(64),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO member_profile VALUES(4,'SILVER',100,50,0,CURRENT_TIMESTAMP)");

        MemberController controller = new MemberController(jdbc, "test-internal-key");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("requestId", "t1");

        Map<String, Object> first = controller.freeze("test-internal-key", "O-1", 4L, 20, req).data();
        Map<String, Object> second = controller.freeze("test-internal-key", "O-1", 4L, 20, req).data();
        assertThat(first.get("idempotentReplay")).isEqualTo(false);
        assertThat(second.get("idempotentReplay")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT point_balance FROM member_profile WHERE user_id=4", Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT frozen_points FROM member_profile WHERE user_id=4", Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_point_reservation", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> controller.freeze("test-internal-key", "O-2", 4L, 999, req))
                .isInstanceOf(BusinessException.class);
    }
}
