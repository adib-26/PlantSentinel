package com.basintelligence.risk;

import java.time.Instant;
import java.util.List;

/**
 * Failure-risk estimate with per-feature contributions to the log-odds, so an engineer can see why the
 * score is what it is.
 */
public record RiskAssessment(
        String machineCode,
        Instant assessedAt,
        double probability,
        RiskLevel level,
        List<Contribution> contributions,
        int readingsUsed,
        ModelInfo model,
        InputReference input) {

    public record Contribution(String feature, String label, double value, double logOdds) {
    }

    /**
     * @param name    stable model identifier, also the metric tag ({@code failure-risk})
     * @param version training-recipe version plus a digest of the fitted coefficients, so two predictions with the
     *                same version came from exactly the same weights
     */
    public record ModelInfo(String name, String version, String algorithm, int trainingSamples, double holdoutAccuracy,
            double holdoutAuc, String trainingData) {
    }

    /**
     * What the prediction was computed from: the machine's telemetry readings recorded between {@code from} and
     * {@code to} (the {@code readings} most recent ones), their mean per channel, and a SHA-256 of the resulting
     * feature vector. Stored with the prediction as {@code input_json}, so the input stays auditable after raw
     * telemetry is pruned. The feature values themselves are in the prediction's contributions.
     */
    public record InputReference(Instant from, Instant to, int readings, ChannelMeans means, String featuresSha256) {

        /** Compact form stored in {@code model_predictions.input_reference}. */
        public String reference() {
            return "telemetry[" + from + "/" + to + "] n=" + readings + " features=sha256:" + featuresSha256;
        }
    }

    /** Mean of each channel over the input readings, in the telemetry units (°C, mm/s RMS, rpm, N·m, bar). */
    public record ChannelMeans(double temperature, double vibration, double rpm, double torque, double pressure) {
    }

    /** Contributions that push risk up, largest first. */
    public List<Contribution> drivers() {
        return contributions.stream().filter(c -> c.logOdds() > 0.05).toList();
    }
}
