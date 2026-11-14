package com.geekmall.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FulfillmentEventProducerContractTest {
    @Test void actualProducerWritesConsumerFields() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var captured = new AtomicReference<String>();
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            captured.set(call.getArgument(5)); return 1;
        });
        var mapper = new ObjectMapper();
        var controller = new FulfillmentController(jdbc, "fixture", "http://localhost:1", mapper);
        ReflectionTestUtils.invokeMethod(controller, "notifyTradeOrder", "GM1", "SHIPPED");
        var actual = mapper.readTree(captured.get());
        var sample = mapper.readTree(Files.readString(Path.of("../../contracts/events/samples/inventory.fulfillment.status-changed.v1.json")));
        assertThat(actual.path("eventType")).isEqualTo(sample.path("eventType"));
        assertThat(actual.path("aggregateId").asText()).isEqualTo("GM1");
        assertThat(actual.path("payload")).isEqualTo(sample.path("payload"));
        assertThat(actual.path("eventId").asText()).isNotBlank();
        assertThat(actual.path("producer").asText()).isEqualTo(sample.path("producer").asText());
        assertThat(actual.path("version").asInt()).isEqualTo(1);
    }
}
