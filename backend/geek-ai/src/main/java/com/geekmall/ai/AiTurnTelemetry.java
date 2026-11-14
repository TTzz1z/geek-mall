package com.geekmall.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
class AiTurnTelemetry {
    private final ObservationRegistry observations;
    private final MeterRegistry metrics;

    AiTurnTelemetry(ObservationRegistry observations, MeterRegistry metrics) {
        this.observations = observations;
        this.metrics = metrics;
    }

    <T> T stage(String name, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return Observation.createNotStarted("ai.agent." + name, observations).observe(action);
        } finally {
            duration(name, System.nanoTime() - started);
        }
    }

    void count(String name) {
        metrics.counter("ai.agent." + name).increment();
    }
    void timeout(String phase) {
        metrics.counter("ai.agent.timeout","phase",phase).increment();
    }
    void duration(String name,long nanos) {
        io.micrometer.core.instrument.Timer.builder("ai.agent." + name)
                .publishPercentileHistogram().register(metrics)
                .record(nanos,java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
