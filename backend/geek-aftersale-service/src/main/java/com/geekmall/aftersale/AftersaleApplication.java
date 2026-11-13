package com.geekmall.aftersale;import com.geekmall.common.web.*;import org.springframework.boot.*;import org.springframework.boot.autoconfigure.*;import org.springframework.context.annotation.Import;
@SpringBootApplication @org.springframework.scheduling.annotation.EnableScheduling
@Import({ServiceWebConfiguration.class,ServiceErrorHandler.class}) public class AftersaleApplication{public static void main(String[]a){SpringApplication.run(AftersaleApplication.class,a);}}
