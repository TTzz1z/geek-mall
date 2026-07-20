package com.geekmall.ai;

import com.geekmall.common.web.ServiceErrorHandler;
import com.geekmall.common.web.ServiceWebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ServiceWebConfiguration.class, ServiceErrorHandler.class})
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
