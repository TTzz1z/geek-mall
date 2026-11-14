package com.geekmall.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

class PaymentSucceededConsumerTest {
    @Test
    void consumesTheSharedContractSampleOnce() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var inventory = mock(InventoryService.class);
        var consumer = new PaymentSucceededConsumer(jdbc, new ObjectMapper(), inventory);
        String event = Files.readString(Path.of("../../contracts/events/samples/payment.payment.succeeded.v1.json"));
        when(jdbc.update(startsWith("INSERT INTO event_consume_log"), eq("inventory-payment-succeeded"), eq("evt-demo-0001")))
                .thenReturn(1).thenThrow(new DuplicateKeyException("redelivered"));

        consumer.consume(event);
        consumer.consume(event);

        verify(inventory).deduct("GM1");
        verify(jdbc).update(startsWith("UPDATE event_consume_log"), eq("inventory-payment-succeeded"), eq("evt-demo-0001"));
    }

    @Test
    void rejectsMalformedDeliveryBeforeUpdatingInventory() {
        var jdbc = mock(JdbcTemplate.class);
        var inventory = mock(InventoryService.class);
        var consumer = new PaymentSucceededConsumer(jdbc, new ObjectMapper(), inventory);

        assertThatThrownBy(() -> consumer.consume("{bad json")).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> consumer.consume("""
                {"eventId":"evt-demo-0001","eventType":"payment.payment.succeeded.v1","payload":{"orderNo":42}}
                """)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(jdbc, inventory);
    }
}
