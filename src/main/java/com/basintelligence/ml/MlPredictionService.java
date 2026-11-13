package com.basintelligence.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.basintelligence.common.BasMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Inference for the models trained in the ML lab. Feature engineering is the same code the lab used
 * ({@link ProcessFailureFeatures}, {@link RulFeatures}, {@link BearingFeatures}), so training and serving cannot
 * drift apart. All methods take batches to amortise per-call overhead.
 */
@Service
public class MlPredictionService {

    public static final String PROCESS_FAILURE = "process-failure-classifier";
    public static final String RUL = "remaining-useful-life-regressor";
    public static final String BEARING_ANOMALY = "bearing-anomaly-detector";
    public static final String BEARING_FAULT = "bearing-fault-classifier";

    public record ProcessInput(char type, double airTempK, double processTempK, double rpm, double torqueNm,
            double toolWearMin) {
    }

    public record ProcessPrediction(double failureProbability, boolean failurePredicted, double threshold,
            String modelVersion) {
    }

    public record RulPrediction(double remainingCycles, int cyclesObserved, String modelVersion) {
    }

    /** {@code anomalous} is per window; {@code alarm} applies the persistence rule (consecutive abnormal windows). */
    public record BearingWindowResult(int window, double anomalyScore, double threshold, boolean anomalous, boolean alarm,
            String likelyCondition, Map<String, Double> conditionProbabilities) {
    }

    private final ModelRegistry registry;
    private final MeterRegistry meters;

    public MlPredictionService(ModelRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        this.meters = meters;
    }

    public List<ModelCard> models() {
        return registry.cards();
    }

    public List<ProcessPrediction> predictProcessFailure(List<ProcessInput> inputs) {
        ProbabilisticClassifier model = registry.model(PROCESS_FAILURE, ProbabilisticClassifier.class);
        ModelCard card = registry.find(PROCESS_FAILURE).orElseThrow().card(); // present: model() above succeeded
        double threshold = card.decisionThreshold();
        return timed(PROCESS_FAILURE, () -> inputs.stream().map(in -> {
            double p = model.probability(ProcessFailureFeatures.of(in.type(), in.airTempK(), in.processTempK(), in.rpm(),
                    in.torqueNm(), in.toolWearMin()));
            return new ProcessPrediction(p, p >= threshold, threshold, card.version());
        }).toList()).stream().peek(r -> BasMetrics.recordPrediction(meters, PROCESS_FAILURE,
                r.failurePredicted() ? "failure" : "no_failure", r.failureProbability())).toList();
    }

    /**
     * @param sensorHistory one row per cycle, oldest first, each row the 21 C-MAPSS sensor readings s1..s21
     */
    public RulPrediction predictRemainingUsefulLife(List<double[]> sensorHistory) {
        RegressionModel model = registry.model(RUL, RegressionModel.class);
        List<double[]> indexed = new ArrayList<>();
        for (double[] row : sensorHistory) {
            if (row.length != 21) {
                throw new IllegalArgumentException("each cycle must contain 21 sensor values (s1..s21)");
            }
            double[] s = new double[22];
            System.arraycopy(row, 0, s, 1, 21);
            indexed.add(s);
        }
        double rul = timed(RUL, () -> model.predict(RulFeatures.of(indexed.size(), indexed)));
        double cycles = Math.clamp(rul, 0, 125);
        meters.summary("bas.prediction.rul.cycles").record(cycles);
        BasMetrics.recordPrediction(meters, RUL, cycles <= 30 ? "0-30_cycles" : cycles <= 60 ? "31-60_cycles" : "over_60_cycles", null);
        return new RulPrediction(Math.clamp(rul, 0, 125), indexed.size(), registry.find(RUL).orElseThrow().card().version());
    }

    /** Splits the signal into 1024-sample windows and scores each one (anomaly detector + fault classifier). */
    public List<BearingWindowResult> analyzeBearingVibration(double[] samples) {
        if (samples.length < BearingFeatures.WINDOW) {
            throw new IllegalArgumentException("at least " + BearingFeatures.WINDOW + " samples are required");
        }
        var detector = registry.model(BEARING_ANOMALY, Models.BearingAnomalyDetector.class);
        var classifier = registry.model(BEARING_FAULT, Models.FrameMultiClassifier.class);
        return timed(BEARING_ANOMALY, () -> {
            List<BearingWindowResult> out = new ArrayList<>();
            int run = 0;
            for (int w = 0; (w + 1) * BearingFeatures.WINDOW <= samples.length; w++) {
                double[] f = BearingFeatures.of(Arrays.copyOfRange(samples, w * BearingFeatures.WINDOW,
                        (w + 1) * BearingFeatures.WINDOW));
                double score = detector.score(f);
                double[] p = classifier.probabilities(f);
                Map<String, Double> probs = new LinkedHashMap<>();
                int best = 0;
                for (int c = 0; c < p.length; c++) {
                    probs.put(classifier.classNames()[c], p[c]);
                    if (p[c] > p[best]) best = c;
                }
                boolean abnormal = score > detector.threshold();
                run = abnormal ? run + 1 : 0;
                out.add(new BearingWindowResult(w, score, detector.threshold(), abnormal,
                        run >= Models.BearingAnomalyDetector.ALARM_WINDOWS, classifier.classNames()[best], probs));
            }
            out.forEach(r -> BasMetrics.recordPrediction(meters, BEARING_ANOMALY, r.anomalous() ? "anomalous" : "normal", null));
            return out;
        });
    }

    private <T> T timed(String model, java.util.function.Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return work.get();
        } finally {
            sample.stop(meters.timer(BasMetrics.INFERENCE, "model", model));
        }
    }
}
