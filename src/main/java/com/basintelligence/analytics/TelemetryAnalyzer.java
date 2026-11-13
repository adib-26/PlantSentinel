package com.basintelligence.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.machine.OperatingLimits;
import com.basintelligence.telemetry.TelemetryReading;

import smile.anomaly.IsolationForest;

/**
 * Computes trends and a multivariate anomaly score over a window of readings.
 * <p>
 * The Isolation Forest is fitted per request on the earliest 60% of the window (the machine's own
 * recent "normal"), then scores the latest readings. Features are values divided by the machine
 * type's nominal values so all channels share a comparable scale.
 */
public final class TelemetryAnalyzer {

    static final int MIN_SAMPLES_FOR_FOREST = 60;
    private static final int RECENT_COUNT = 5;

    public TelemetryAnalysis analyze(String machineCode, MachineType type, List<TelemetryReading> readings) {
        if (readings.isEmpty()) {
            return new TelemetryAnalysis(machineCode, null, null, 0, List.of(), null);
        }
        int n = readings.size();
        double startMillis = readings.getFirst().recordedAt().toEpochMilli();
        double[] hours = readings.stream()
                .mapToDouble(r -> (r.recordedAt().toEpochMilli() - startMillis) / 3_600_000.0).toArray();
        int baselineEnd = Math.max(1, (int) Math.round(n * 0.3));
        int recentStart = Math.max(0, n - Math.max(RECENT_COUNT, n / 10));

        List<TelemetryAnalysis.MetricSummary> summaries = new ArrayList<>();
        for (Metric metric : Metric.values()) {
            double[] values = readings.stream().mapToDouble(metric::of).toArray();
            Stats all = Stats.of(values);
            double baselineMean = Stats.of(Arrays.copyOfRange(values, 0, baselineEnd)).mean();
            double recentMean = Stats.of(Arrays.copyOfRange(values, recentStart, n)).mean();
            double latest = values[n - 1];
            OperatingLimits limits = type.limits(metric);
            summaries.add(new TelemetryAnalysis.MetricSummary(metric, metric.unit(), limits.nominal(), latest, all.mean(),
                    all.min(), all.max(), baselineMean, recentMean,
                    baselineMean == 0 ? 0 : (recentMean - baselineMean) / baselineMean,
                    Stats.slope(hours, values), limits.evaluate(latest)));
        }
        return new TelemetryAnalysis(machineCode, readings.getFirst().recordedAt(), readings.getLast().recordedAt(), n,
                List.copyOf(summaries), multivariate(type, readings));
    }

    private TelemetryAnalysis.MultivariateResult multivariate(MachineType type, List<TelemetryReading> readings) {
        int n = readings.size();
        if (n < MIN_SAMPLES_FOR_FOREST) {
            return null;
        }
        double[][] features = readings.stream().map(r -> normalise(type, r)).toArray(double[][]::new);
        int trainEnd = (int) (n * 0.6);
        double[][] train = Arrays.copyOfRange(features, 0, trainEnd);
        IsolationForest forest = IsolationForest.fit(train);
        double[] trainScores = forest.score(train);
        Arrays.sort(trainScores);
        double threshold = trainScores[(int) Math.floor(0.99 * (trainScores.length - 1))];
        double recent = Arrays.stream(forest.score(Arrays.copyOfRange(features, n - RECENT_COUNT, n))).average().orElse(0);
        return new TelemetryAnalysis.MultivariateResult(recent, threshold, recent > threshold, trainEnd);
    }

    static double[] normalise(MachineType type, TelemetryReading r) {
        Metric[] metrics = Metric.values();
        double[] f = new double[metrics.length];
        for (int i = 0; i < metrics.length; i++) {
            f[i] = type.limits(metrics[i]).ratio(metrics[i].of(r));
        }
        return f;
    }
}
