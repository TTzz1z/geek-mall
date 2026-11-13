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
        return Observation.createNotStarted("ai.agent." + name, observations).observe(action);
    }

    void count(String name) {
        metrics.counter("ai.agent." + name).increment();
    }
}
