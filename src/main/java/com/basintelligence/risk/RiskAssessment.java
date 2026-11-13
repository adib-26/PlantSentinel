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
        ModelInfo model) {

    public record Contribution(String feature, String label, double value, double logOdds) {
    }

    public record ModelInfo(String algorithm, int trainingSamples, double holdoutAccuracy, double holdoutAuc,
            String trainingData) {
    }

    /** Contributions that push risk up, largest first. */
    public List<Contribution> drivers() {
        return contributions.stream().filter(c -> c.logOdds() > 0.05).toList();
    }
}
