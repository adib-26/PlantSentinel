package com.basintelligence.analytics;

import com.basintelligence.machine.Machine;

/** Published after a new CRITICAL anomaly is stored, so a failure-risk prediction can be recorded for it. */
public record CriticalAnomalyDetected(Machine machine, long anomalyEventId) {
}
