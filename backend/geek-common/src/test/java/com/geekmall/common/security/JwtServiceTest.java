package com.geekmall.common.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private final JwtService jwtService = new JwtService(
            "unit-test-secret-must-have-at-least-thirty-two-bytes", "geek-mall-test");

    @Test
    void preservesAudiencePermissionsAndMerchantIdentity() {
        AuthPrincipal source = new AuthPrincipal(8, "merchant-a", "BACKOFFICE", 2001L, 3001L,
                Set.of("MERCHANT"), Set.of("merchant:order:view"));

        AuthPrincipal parsed = jwtService.parse(jwtService.issue(source, Duration.ofMinutes(5)));

        assertThat(parsed.userId()).isEqualTo(8);
        assertThat(parsed.audience()).isEqualTo("BACKOFFICE");
        assertThat(parsed.merchantId()).isEqualTo(2001L);
        assertThat(parsed.hasPermission("merchant:order:view")).isTrue();
    }
}
