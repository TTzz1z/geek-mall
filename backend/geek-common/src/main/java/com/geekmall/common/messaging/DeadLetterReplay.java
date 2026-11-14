package com.geekmall.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Confirm publication before acknowledging the dead letter; replay preserves the event ID. */
public final class DeadLetterReplay {
    private DeadLetterReplay() {}

    public static int replay(RabbitTemplate rabbit, ObjectMapper mapper, String queue, String eventType) {
        Integer replayed = rabbit.execute(channel -> {
            channel.queueDeclarePassive(queue);
            channel.confirmSelect();
            var returned = new AtomicBoolean();
            var listener = channel.addReturnListener(message -> returned.set(true));
            int count = 0;
            try {
                for (; count < 100; count++) {
                    var message = channel.basicGet(queue + ".dlq", false);
                    if (message == null) break;
                    long tag = message.getEnvelope().getDeliveryTag();
                    try {
                        var envelope = mapper.readTree(message.getBody());
                        if (!envelope.path("eventId").isTextual() || envelope.path("eventId").asText().isBlank()
                                || !eventType.equals(envelope.path("eventType").asText())) {
                            throw new BusinessException("MQ40001", "死信事件类型或标识无效，需修复后重放");
                        }
                        Map<String, Object> headers = message.getProps().getHeaders() == null
                                ? new HashMap<>() : new HashMap<>(message.getProps().getHeaders());
                        headers.remove("x-death");
                        headers.remove("retry_count");
                        returned.set(false);
                        channel.basicPublish("", queue, true,
                                message.getProps().builder().headers(headers).deliveryMode(2).build(), message.getBody());
                        channel.waitForConfirmsOrDie(5000);
                        if (returned.get()) throw new IllegalStateException("死信重放未路由到原队列");
                        channel.basicAck(tag, false);
                    } catch (Exception failure) {
                        channel.basicNack(tag, false, true);
                        throw failure;
                    }
                }
            } finally {
                channel.removeReturnListener(listener);
            }
            return count;
        });
        return replayed == null ? 0 : replayed;
    }
}
