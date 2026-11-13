package com.basintelligence.analytics;

import java.time.Instant;
import java.util.UUID;

import com.basintelligence.machine.Metric;
import com.basintelligence.machine.Severity;

public record AnomalyEvent(
        UUID machineId,
        Instant detectedAt,
        Metric metric,
        Detector detector,
        Severity severity,
        double observedValue,
        Double expectedValue,
        String message) {

    public enum Detector {
        /** Value outside the machine type's operating envelope. */
        ENVELOPE,
        /** Sudden statistical deviation from the machine's own recent baseline. */
        ZSCORE
    }
}
