package com.geekmall.common.security;

import com.geekmall.common.api.BusinessException;

/**
 * Shared check for service-to-service {@code X-Internal-Key}.
 */
public final class InternalServiceAccess {
    public static final String PLACEHOLDER_KEY = "change-internal-service-key";

    private InternalServiceAccess() {
    }

    public static void require(String provided, String configured) {
        if (configured == null || configured.isBlank()) {
            throw new BusinessException("AU50304", "内部服务密钥未配置");
        }
        if (provided == null || provided.isBlank() || !configured.equals(provided)) {
            throw new BusinessException("AU40304", "内部服务凭证无效");
        }
    }

    public static boolean isPlaceholder(String configured) {
        return configured == null || configured.isBlank() || PLACEHOLDER_KEY.equals(configured);
    }
}
