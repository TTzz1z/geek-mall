package com.geekmall.common.security;

import com.geekmall.common.api.BusinessException;

public final class TenantContext {
    private static final ThreadLocal<Long> MERCHANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void setMerchantId(Long merchantId) {
        if (merchantId == null) {
            MERCHANT.remove();
        } else {
            MERCHANT.set(merchantId);
        }
    }

    public static long requireMerchantId() {
        Long merchantId = MERCHANT.get();
        if (merchantId == null) {
            throw new BusinessException("AU40302", "当前身份没有商家数据范围");
        }
        return merchantId;
    }

    public static void assertAccessible(Long requestedMerchantId) {
        if (requestedMerchantId != null && requestedMerchantId != requireMerchantId()) {
            throw new BusinessException("AU40303", "禁止访问其他商家的数据");
        }
    }

    public static void clear() {
        MERCHANT.remove();
    }
}
