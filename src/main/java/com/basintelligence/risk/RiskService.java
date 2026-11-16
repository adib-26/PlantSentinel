package com.basintelligence.risk;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.Machine;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryService;

@Service
public class RiskService {

    /** Roughly the last few minutes of data, averaged to smooth sensor noise. */
    static final int READINGS_FOR_ASSESSMENT = 12;
    /** Metric tag for the failure-risk model. */
    public static final String MODEL_NAME = FailureRiskModel.NAME;

    private final FailureRiskModel model;
    private final TelemetryService telemetry;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public RiskService(BasProperties properties, TelemetryService telemetry, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.model = FailureRiskModel.train(properties.risk().trainingSamples(), properties.risk().randomSeed());
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public Optional<RiskAssessment> assess(Machine machine) {
        List<TelemetryReading> readings = telemetry.latest(machine.id(), READINGS_FOR_ASSESSMENT);
        if (readings.isEmpty()) {
            return Optional.empty();
        }
        RiskAssessment.ChannelMeans means = RiskFeatures.means(readings);
        double[] features = RiskFeatures.fromMeans(machine.type(), means);
        double p = com.basintelligence.common.BasMetrics.timeInference(meters, MODEL_NAME, () -> model.probability(features));
        var input = new RiskAssessment.InputReference(
                readings.stream().map(TelemetryReading::recordedAt).min(Instant::compareTo).orElseThrow(),
                readings.stream().map(TelemetryReading::recordedAt).max(Instant::compareTo).orElseThrow(),
                readings.size(), means, RiskFeatures.sha256(features));
        return Optional.of(new RiskAssessment(machine.code(), clock.instant(), p, RiskLevel.of(p), model.explain(features),
                readings.size(), model.info(), input));
    }

    /**
     * Scores a stored feature vector with the running model, to check an earlier prediction. Not counted as an
     * inference in the metrics.
     */
    public double rescore(double[] features) {
        return model.probability(features);
    }

    /** Global feature importance of the linear risk model: its coefficient per feature (log-odds per unit). */
    public record FeatureWeight(String feature, String label, double weight) {
    }

    public List<FeatureWeight> featureWeights() {
        double[] w = model.weights();
        List<FeatureWeight> out = new java.util.ArrayList<>();
        for (int i = 0; i < RiskFeatures.NAMES.size(); i++) {
            out.add(new FeatureWeight(RiskFeatures.NAMES.get(i), RiskFeatures.LABELS.get(i), w[i]));
        }
        out.sort(java.util.Comparator.comparingDouble(FeatureWeight::weight).reversed());
        return out;
    }

    public double intercept() {
        double[] w = model.weights();
        return w[w.length - 1];
    }

    public RiskAssessment.ModelInfo modelInfo() {
        return model.info();
    }
}
