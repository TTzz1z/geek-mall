package com.geekmall.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayApplicationTest {
    @Test
    void securityBeansLoadWithValidSecret() {
        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(GatewaySecurityConfiguration.class)
                .withPropertyValues(
                        "geek.auth.jwt-secret=unit-test-secret-must-have-at-least-thirty-two-bytes",
                        "geek.auth.issuer=geek-test")
                .run(context -> assertThat(context).hasSingleBean(com.geekmall.common.security.JwtService.class));
    }
}
