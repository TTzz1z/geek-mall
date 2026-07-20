package com.geekmall.common.security;

import com.geekmall.common.api.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {
    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void blocksCrossMerchantAccessEvenWhenMerchantIdComesFromRequest() {
        TenantContext.setMerchantId(2001L);

        assertThatThrownBy(() -> TenantContext.assertAccessible(2002L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他商家");
    }
}
