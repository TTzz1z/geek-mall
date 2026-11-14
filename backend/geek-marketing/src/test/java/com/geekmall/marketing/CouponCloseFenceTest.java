package com.geekmall.marketing;

import com.geekmall.common.api.BusinessException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class CouponCloseFenceTest {
    @Test void releaseBeforeFreezeClosesOrderAndDoesNotTouchCoupon() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:coupon_close;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE marketing_coupon_order_lock(order_no VARCHAR(64) PRIMARY KEY,closed BOOLEAN DEFAULT FALSE NOT NULL)");
        jdbc.execute("CREATE TABLE marketing_user_coupon(id BIGINT PRIMARY KEY,user_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE marketing_coupon_reservation(order_no VARCHAR(64),user_coupon_id BIGINT,amount DECIMAL(18,2),status VARCHAR(32))");
        jdbc.update("INSERT INTO marketing_user_coupon VALUES(1,4,'UNUSED')");
        var service = new PromotionService(jdbc);
        service.settleCoupon("GM-CLOSED", "release");
        service.settleCoupon("GM-CLOSED", "release");
        assertThatThrownBy(() -> service.freezeCoupon("GM-CLOSED", 4, 1, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("关闭");
        assertThat(jdbc.queryForObject("SELECT status FROM marketing_user_coupon", String.class)).isEqualTo("UNUSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM marketing_coupon_reservation", Integer.class)).isZero();
    }
}
