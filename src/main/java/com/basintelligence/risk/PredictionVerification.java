package com.basintelligence.risk;

import java.time.Instant;
import java.util.List;

/**
 * Result of checking one stored failure-risk prediction against its own record, the running model and any raw
 * telemetry still kept.
 *
 * @param status {@code VERIFIED} when every check that could run passed, {@code MISMATCH} when any failed,
 *               {@code UNVERIFIABLE} for predictions stored before the input was recorded (migration V15)
 */
public record PredictionVerification(
        long predictionId,
        String machineCode,
        Instant predictedAt,
        String modelName,
        String modelVersion,
        String inputReference,
        Status status,
        List<Check> checks) {

    public enum Status {
        VERIFIED, MISMATCH, UNVERIFIABLE
    }

    /** {@code SKIPPED} means the check could not run (for example the raw readings were pruned), not that it failed. */
    public enum Outcome {
        PASS, FAIL, SKIPPED
    }

    public record Check(String name, Outcome outcome, String detail) {
    }
}
