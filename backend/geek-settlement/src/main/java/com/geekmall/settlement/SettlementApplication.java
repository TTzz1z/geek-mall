package com.geekmall.settlement;

import com.geekmall.common.web.ServiceErrorHandler;
import com.geekmall.common.web.ServiceWebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({ServiceWebConfiguration.class, ServiceErrorHandler.class})
public class SettlementApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
