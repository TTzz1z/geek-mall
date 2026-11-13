package com.geekmall.common.web;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

public final class InternalRestClient {
    private InternalRestClient() {}

    public static RestClient create(String baseUrl) {
        return create(baseUrl, Duration.ofSeconds(10));
    }

    public static RestClient create(String baseUrl, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
