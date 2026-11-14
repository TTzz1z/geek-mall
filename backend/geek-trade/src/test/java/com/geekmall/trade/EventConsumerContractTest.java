package com.geekmall.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventConsumerContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private String sample(String type) throws Exception {
        return Files.readString(Path.of("../../contracts/events/samples/" + type + ".json"));
    }
    @Test void seckillConsumerPassesDocumentedPayloadToService() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var service = mock(SeckillOrderService.class);
        String body = sample("marketing.seckill.order.v1");
        new SeckillOrderConsumer(jdbc, mapper, service).consume(body);
        verify(service).enqueue(eq(mapper.readTree(body).path("payload")));
    }
    @Test void fulfillmentConsumerAcceptsDocumentedStatus() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("GM1"))).thenReturn(List.of(Map.of("id", 1L, "status", "PAID")));
        var controller = new TradeInternalController(jdbc, mock(OrderService.class), mock(TradeTasks.class), mapper, "fixture");
        controller.onFulfillmentStatus(sample("inventory.fulfillment.status-changed.v1"));
        verify(jdbc).update(contains("UPDATE trade_order SET status='SHIPPED'"), eq(1L));
    }
}
