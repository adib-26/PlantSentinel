package com.basintelligence.ml;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.basintelligence.common.ActivityTracker;
import com.basintelligence.common.BasMetrics;
import com.basintelligence.config.BasProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Inference for the models trained in the ML lab. Feature engineering is the same code the lab used
 * ({@link ProcessFailureFeatures}, {@link RulFeatures}, {@link BearingFeatures}), so training and serving cannot
 * drift apart. All methods take batches to amortise per-call overhead. Every prediction is stored through
 * {@link MlPredictionLog}, and each result carries the id of its stored row.
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
            String modelVersion, long predictionId) {
    }

    public record RulPrediction(double remainingCycles, int cyclesObserved, String modelVersion, long predictionId) {
    }

    /**
     * {@code anomalous} is per window; {@code alarm} applies the persistence rule (consecutive abnormal windows).
     * The two ids are the stored rows of the anomaly detector and the fault classifier for this window.
     */
    public record BearingWindowResult(int window, double anomalyScore, double threshold, boolean anomalous, boolean alarm,
            String likelyCondition, Map<String, Double> conditionProbabilities, long anomalyPredictionId,
            long conditionPredictionId) {
    }

    private final ModelRegistry registry;
    private final MlPredictionLog log;
    private final BasProperties properties;
    private final ActivityTracker activity;
    private final Clock clock;
    private final MeterRegistry meters;

    public MlPredictionService(ModelRegistry registry, MlPredictionLog log, BasProperties properties,
            ActivityTracker activity, Clock clock, MeterRegistry meters) {
        this.registry = registry;
        this.log = log;
        this.properties = properties;
        this.activity = activity;
        this.clock = clock;
        this.meters = meters;
    }

    public List<ModelCard> models() {
        return registry.cards();
    }

    /**
     * Keeps {@code ml_predictions} within {@code bas.predictions.ml-retention} and, in case a write could not, the
     * row limit. Runs hourly while the app is in use.
     */
    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1H")
    public void applyRetention() {
        if (activity.isIdle()) {
            return;
        }
        log.deleteOlderThan(clock.instant().minus(properties.predictions().mlRetention()));
        log.enforceRowLimit();
    }

    public List<ProcessPrediction> predictProcessFailure(List<ProcessInput> inputs, MlPredictionLog.Caller caller) {
        ProbabilisticClassifier model = registry.model(PROCESS_FAILURE, ProbabilisticClassifier.class);
        ModelCard card = registry.find(PROCESS_FAILURE).orElseThrow().card(); // present: model() above succeeded
        double threshold = card.decisionThreshold();
        String version = card.artifactVersion();
        double[] p = timed(PROCESS_FAILURE, () -> inputs.stream().mapToDouble(in -> model.probability(
                ProcessFailureFeatures.of(in.type(), in.airTempK(), in.processTempK(), in.rpm(), in.torqueNm(),
                        in.toolWearMin()))).toArray());

        List<MlPredictionLog.Entry> entries = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            entries.add(new MlPredictionLog.Entry(i, PROCESS_FAILURE, version, processOutcome(p[i], threshold), p[i],
                    null, null, processReference(inputs.get(i))));
        }
        List<Long> ids = log.record(caller, clock.instant(), entries);

        List<ProcessPrediction> out = new ArrayList<>(p.length);
        for (int i = 0; i < p.length; i++) {
            BasMetrics.recordPrediction(meters, PROCESS_FAILURE, processOutcome(p[i], threshold), p[i]);
            out.add(new ProcessPrediction(p[i], p[i] >= threshold, threshold, version, ids.get(i)));
        }
        return out;
    }

    /**
     * @param sensorHistory one row per cycle, oldest first, each row the 21 C-MAPSS sensor readings s1..s21
     */
    public RulPrediction predictRemainingUsefulLife(List<double[]> sensorHistory, MlPredictionLog.Caller caller) {
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
        String bucket = cycles <= 30 ? "0-30_cycles" : cycles <= 60 ? "31-60_cycles" : "over_60_cycles";
        String version = registry.find(RUL).orElseThrow().card().artifactVersion();
        // a regression has no probability, so no risk score is stored; the cycles are the prediction
        long id = log.record(caller, clock.instant(), List.of(new MlPredictionLog.Entry(0, RUL, version, bucket, null,
                cycles, null, rulReference(sensorHistory)))).getFirst();

        meters.summary("bas.prediction.rul.cycles").record(cycles);
        BasMetrics.recordPrediction(meters, RUL, bucket, null);
        return new RulPrediction(cycles, indexed.size(), version, id);
    }

    /** Splits the signal into 1024-sample windows and scores each one (anomaly detector + fault classifier). */
    public List<BearingWindowResult> analyzeBearingVibration(double[] samples, MlPredictionLog.Caller caller) {
        if (samples.length < BearingFeatures.WINDOW) {
            throw new IllegalArgumentException("at least " + BearingFeatures.WINDOW + " samples are required");
        }
        var detector = registry.model(BEARING_ANOMALY, Models.BearingAnomalyDetector.class);
        var classifier = registry.model(BEARING_FAULT, Models.FrameMultiClassifier.class);
        String detectorVersion = registry.find(BEARING_ANOMALY).orElseThrow().card().artifactVersion();
        String classifierVersion = registry.find(BEARING_FAULT).orElseThrow().card().artifactVersion();

        record Window(double score, boolean anomalous, boolean alarm, int best, double[] p) {

            /** Class probabilities in class order, as returned and as stored. */
            Map<String, Double> probabilities(String[] classNames) {
                Map<String, Double> out = new LinkedHashMap<>();
                for (int c = 0; c < p.length; c++) {
                    out.put(classNames[c], p[c]);
                }
                return out;
            }
        }
        List<Window> windows = timed(BEARING_ANOMALY, () -> {
            List<Window> out = new ArrayList<>();
            int run = 0;
            for (int w = 0; (w + 1) * BearingFeatures.WINDOW <= samples.length; w++) {
                double[] f = BearingFeatures.of(Arrays.copyOfRange(samples, w * BearingFeatures.WINDOW,
                        (w + 1) * BearingFeatures.WINDOW));
                double score = detector.score(f);
                double[] p = classifier.probabilities(f);
                int best = 0;
                for (int c = 1; c < p.length; c++) {
                    if (p[c] > p[best]) best = c;
                }
                boolean abnormal = score > detector.threshold();
                run = abnormal ? run + 1 : 0;
                out.add(new Window(score, abnormal, run >= Models.BearingAnomalyDetector.ALARM_WINDOWS, best, p));
            }
            return out;
        });

        // The fault classifier gets no risk score: the lab evaluated only its most-probable-class decision, with no
        // validated fault score or threshold, and a derived score such as 1 - P(normal) can contradict that class.
        // Its row keeps all class probabilities instead; the detector row says whether the window is abnormal.
        String[] classes = classifier.classNames();
        List<MlPredictionLog.Entry> entries = new ArrayList<>();
        for (int w = 0; w < windows.size(); w++) {
            Window win = windows.get(w);
            String input = vibrationReference(samples, w * BearingFeatures.WINDOW, (w + 1) * BearingFeatures.WINDOW);
            entries.add(new MlPredictionLog.Entry(w, BEARING_ANOMALY, detectorVersion, bearingOutcome(win.anomalous()),
                    win.score(), null, null, input));
            entries.add(new MlPredictionLog.Entry(w, BEARING_FAULT, classifierVersion, classes[win.best()], null, null,
                    win.probabilities(classes), input));
        }
        List<Long> ids = log.record(caller, clock.instant(), entries);

        List<BearingWindowResult> out = new ArrayList<>(windows.size());
        for (int w = 0; w < windows.size(); w++) {
            Window win = windows.get(w);
            BasMetrics.recordPrediction(meters, BEARING_ANOMALY, bearingOutcome(win.anomalous()), null);
            BasMetrics.recordPrediction(meters, BEARING_FAULT, classes[win.best()], null);
            out.add(new BearingWindowResult(w, win.score(), detector.threshold(), win.anomalous(), win.alarm(),
                    classes[win.best()], win.probabilities(classes), ids.get(2 * w), ids.get(2 * w + 1)));
        }
        return out;
    }

    private static String processOutcome(double probability, double threshold) {
        return probability >= threshold ? "failure" : "no_failure";
    }

    private static String bearingOutcome(boolean anomalous) {
        return anomalous ? "anomalous" : "normal";
    }

    /** The input itself: six numbers are short enough to store, and they are enough to re-score the prediction. */
    static String processReference(ProcessInput in) {
        return "process[type=" + in.type() + " airTempK=" + in.airTempK() + " processTempK=" + in.processTempK()
                + " rpm=" + in.rpm() + " torqueNm=" + in.torqueNm() + " toolWearMin=" + in.toolWearMin() + "]";
    }

    /** SHA-256 of the readings as 8-byte big-endian IEEE 754 doubles, cycle by cycle, s1..s21 within a cycle. */
    static String rulReference(List<double[]> sensorHistory) {
        ByteBuffer bytes = ByteBuffer.allocate(sensorHistory.size() * 21 * Double.BYTES);
        sensorHistory.forEach(row -> Arrays.stream(row).forEach(bytes::putDouble));
        return "cycles n=" + sensorHistory.size() + " x 21 sensors sha256:" + ModelRegistry.sha256(bytes.array());
    }

    /** SHA-256 of samples {@code [from, to)} as 8-byte big-endian IEEE 754 doubles. */
    static String vibrationReference(double[] samples, int from, int to) {
        ByteBuffer bytes = ByteBuffer.allocate((to - from) * Double.BYTES);
        for (int i = from; i < to; i++) {
            bytes.putDouble(samples[i]);
        }
        return "vibration[" + from + "," + to + ") of " + samples.length + " samples sha256:"
                + ModelRegistry.sha256(bytes.array());
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
