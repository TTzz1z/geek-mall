package com.geekmall.common.messaging;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecovererWithConfirms;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/** Exhausted deliveries leave the source queue only after the DLQ publish is confirmed. */
public final class DurableDeadLetterRecoverer implements MessageRecoverer {
    private final RepublishMessageRecovererWithConfirms delegate;

    public DurableDeadLetterRecoverer(RabbitTemplate template) {
        template.setMandatory(true);
        delegate = new RepublishMessageRecovererWithConfirms(template,
                new LiteralExpression("geek.domain.events.dlx"),
                new SpelExpressionParser().parseExpression("messageProperties.consumerQueue + '.dlq'"),
                CachingConnectionFactory.ConfirmType.CORRELATED);
        delegate.setConfirmTimeout(5000);
    }

    @Override public void recover(Message message, Throwable cause) {
        try {
            delegate.recover(message, cause);
        } catch (RuntimeException unavailable) {
            throw new ImmediateRequeueAmqpException("死信发布未确认，保留原消息重试", unavailable);
        }
    }
}
