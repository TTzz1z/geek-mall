package com.geekmall.gateway;

import com.geekmall.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GatewaySecurityConfiguration {
    @Bean
    JwtService jwtService(@Value("${geek.auth.jwt-secret}") String secret,
                          @Value("${geek.auth.issuer}") String issuer) {
        return new JwtService(secret, issuer);
    }
}
