package com.basintelligence.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.basintelligence.machine.Metric;
import com.basintelligence.machine.Severity;

/** Window-level analysis of a machine's telemetry: per-metric trends plus a multivariate anomaly score. */
public record TelemetryAnalysis(
        String machineCode,
        Instant from,
        Instant to,
        int sampleCount,
        List<MetricSummary> metrics,
        MultivariateResult multivariate) {

    public record MetricSummary(
            Metric metric,
            String unit,
            double nominal,
            double latest,
            double mean,
            double min,
            double max,
            double baselineMean,
            double recentMean,
            double changePct,
            double slopePerHour,
            Severity latestSeverity) {
    }

    /**
     * Isolation Forest result. {@code score} is the mean anomaly score of the most recent readings, and
     * {@code threshold} is the 99th percentile of scores on the earlier part of the window it was trained on.
     */
    public record MultivariateResult(double score, double threshold, boolean anomalous, int trainingSamples) {
    }

    public Optional<MetricSummary> metric(Metric metric) {
        return metrics.stream().filter(m -> m.metric() == metric).findFirst();
    }

    public boolean hasData() {
        return sampleCount > 0;
    }
}
