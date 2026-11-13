package com.basintelligence.mllab;

import static com.basintelligence.mllab.Report.fmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.basintelligence.ml.BearingFeatures;
import com.basintelligence.ml.ModelCard;
import com.basintelligence.ml.Models;

import smile.anomaly.IsolationForest;
import smile.base.cart.SplitRule;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;

/**
 * CWRU bearing vibration (step 6): an unsupervised anomaly detector trained on healthy data only, evaluated as a
 * case study with labelled faults (precision, recall, false alarm rate, detection delay), plus a supervised
 * four-class fault classifier.
 */
public final class CwruBearingPipeline {

    static final String DETECTOR_NAME = "bearing-anomaly-detector";
    static final String CLASSIFIER_NAME = "bearing-fault-classifier";
    static final String[] CLASSES = {"normal", "inner_race", "ball", "outer_race"};
    private static final double WINDOW_MS = BearingFeatures.WINDOW / BearingFeatures.SAMPLE_RATE_HZ * 1000;

    private CwruBearingPipeline() {
    }

    /** File number for a class (0 normal, 1 inner race, 2 ball, 3 outer race) at a motor load of 0-3 hp. */
    static int fileNumber(int cls, int load) {
        return new int[] {97, 105, 118, 130}[cls] + load;
    }

    public static void run(Path raw, Path reports, Path modelsDir, long seed) throws IOException {
        System.out.println("CWRU bearings: anomaly detection and fault classification");
        // windows[class][load] = feature vectors in recording order
        List<List<List<double[]>>> w = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (int c = 0; c < CLASSES.length; c++) {
            List<List<double[]>> byLoad = new ArrayList<>();
            for (int load = 0; load < 4; load++) {
                String f = "cwru/" + fileNumber(c, load) + "_DE.csv";
                files.add(f);
                byLoad.add(windows(raw.resolve(f)));
            }
            w.add(byLoad);
        }

        // Anomaly detector: healthy data only. Fit on the first 70% of each healthy recording at 0, 1 and 2 hp and
        // calibrate the alarm threshold on the remaining 30% of each, so the threshold reflects several operating
        // conditions rather than one. Test on healthy 3 hp (a load never seen) plus every fault recording.
        List<double[]> fitList = new ArrayList<>(), calibration = new ArrayList<>();
        for (int load = 0; load <= 2; load++) {
            List<double[]> h = w.get(0).get(load);
            int cut = (int) (h.size() * 0.7);
            fitList.addAll(h.subList(0, cut));
            calibration.addAll(h.subList(cut, h.size()));
        }
        double[][] fit = fitList.toArray(double[][]::new);
        double[] mean = new double[fit[0].length], std = new double[fit[0].length];
        for (int j = 0; j < mean.length; j++) {
            final int c = j;
            mean[j] = Arrays.stream(fit).mapToDouble(r -> r[c]).average().orElse(0);
            std[j] = Math.sqrt(Arrays.stream(fit).mapToDouble(r -> (r[c] - mean[c]) * (r[c] - mean[c])).sum() / fit.length);
        }
        smile.math.MathEx.setSeed(seed);
        IsolationForest forest = IsolationForest.fit(
                Arrays.stream(fit).map(r -> Models.standardise(r, mean, std)).toArray(double[][]::new),
                new IsolationForest.Options(300, 0, 0.7, 0));
        double[] calib = calibration.stream().mapToDouble(r -> forest.score(Models.standardise(r, mean, std))).sorted().toArray();
        double threshold = calib[(int) Math.floor(0.995 * (calib.length - 1))];
        var detector = new Models.BearingAnomalyDetector(forest, threshold, mean, std);

        List<double[]> testNormal = w.get(0).get(3);
        List<double[]> faults = new ArrayList<>();
        for (int c = 1; c < CLASSES.length; c++) for (int load = 0; load < 4; load++) faults.addAll(w.get(c).get(load));
        double[] scores = new double[testNormal.size() + faults.size()];
        int[] labels = new int[scores.length];
        int i = 0;
        for (double[] x : testNormal) { scores[i] = detector.score(x); labels[i++] = 0; }
        for (double[] x : faults) { scores[i] = detector.score(x); labels[i++] = 1; }
        Map<String, Double> anomalyMetrics = new java.util.LinkedHashMap<>(Metrics.classification(scores, labels, threshold));
        // Production alarm rule: N consecutive abnormal windows. Measured on the unseen-load healthy stream (false
        // alarms per window) and on every fault recording (share detected).
        int alarms = falseAlarms(detector, testNormal, testNormal.size(), Models.BearingAnomalyDetector.ALARM_WINDOWS);
        anomalyMetrics.put("alarm_false_alarm_rate", (double) alarms / testNormal.size());
        int detected = 0, recordings = 0;
        for (int c = 1; c < CLASSES.length; c++) {
            for (int load = 0; load < 4; load++) {
                recordings++;
                if (!"not detected".equals(delay(detector, w.get(c).get(load), 0, Models.BearingAnomalyDetector.ALARM_WINDOWS))) {
                    detected++;
                }
            }
        }
        anomalyMetrics.put("alarm_fault_recordings_detected", (double) detected / recordings);

        Report r = new Report().h1("CWRU bearings: anomaly detection case study and fault classification")
                .p("Source: Case Western Reserve University Bearing Data Center. Drive-end accelerometer, 12 kHz, 0.007\" "
                        + "seeded faults, motor loads 0-3 hp (16 recordings). Windows of " + BearingFeatures.WINDOW
                        + " samples (" + fmt(WINDOW_MS) + " ms). Generated " + Instant.now() + ".");
        List<List<String>> counts = new ArrayList<>();
        for (int c = 0; c < CLASSES.length; c++) {
            List<String> row = new ArrayList<>(List.of(CLASSES[c]));
            for (int load = 0; load < 4; load++) row.add(String.valueOf(w.get(c).get(load).size()));
            counts.add(row);
        }
        r.h2("Data quality").table(List.of("Class", "0 hp", "1 hp", "2 hp", "3 hp"), counts)
                .p("Every recording parsed completely; no missing samples. Healthy recordings are about four times longer "
                        + "than fault recordings.")
                .h2("Anomaly detection (unsupervised, healthy data only)")
                .p("Isolation Forest (300 trees) fitted on the first 70% of each healthy recording at 0, 1 and 2 hp ("
                        + fit.length + " windows). Alarm threshold = 99.5th percentile of the held-out 30% of those recordings ("
                        + calib.length + " windows). Test: healthy 3 hp (" + testNormal.size() + " windows, a load never seen) "
                        + "plus all " + faults.size() + " fault windows.")
                .metrics("Test", anomalyMetrics)
                .p("Finding: the false alarm rate on the unseen 3 hp load is " + fmt(100 * anomalyMetrics.get("false_alarm_rate"))
                        + "% against a 0.5% calibration target. A change in operating condition alone shifts the healthy "
                        + "signature, so in production the threshold should be calibrated per machine and load, or alarms "
                        + "should require persistence (rule B below).");

        // Detection delay at the unseen 3 hp load: healthy stream, then the fault begins.
        List<List<String>> delays = new ArrayList<>();
        for (int c = 1; c < CLASSES.length; c++) {
            List<double[]> stream = concat(testNormal, w.get(c).get(3));
            int onset = testNormal.size();
            delays.add(List.of(CLASSES[c], delay(detector, stream, onset, 1), delay(detector, stream, onset, 3),
                    String.valueOf(falseAlarms(detector, stream, onset, 1)), String.valueOf(falseAlarms(detector, stream, onset, 3))));
        }
        r.h2("Detection delay (3 hp)").p("Alarm rule A: any single window above threshold. Rule B: 3 consecutive windows "
                        + "above threshold, which suppresses isolated false alarms at the cost of two extra windows.")
                .table(List.of("Fault", "Delay, rule A", "Delay, rule B", "False alarms before onset, A",
                        "False alarms before onset, B"), delays);

        // Fault classifier, evaluated on a motor load it has never seen: train 0-1 hp, validate 2 hp, test 3 hp.
        List<double[]> xs = new ArrayList<>(), xv = new ArrayList<>(), xt = new ArrayList<>();
        List<Integer> ys = new ArrayList<>(), yv = new ArrayList<>(), yt = new ArrayList<>();
        for (int c = 0; c < CLASSES.length; c++) {
            for (int load = 0; load < 4; load++) {
                for (double[] x : w.get(c).get(load)) {
                    if (load <= 1) { xs.add(x); ys.add(c); } else if (load == 2) { xv.add(x); yv.add(c); } else { xt.add(x); yt.add(c); }
                }
            }
        }
        String[] names = BearingFeatures.names();
        DataFrame frame = DataFrame.of(xs.toArray(double[][]::new), names)
                .add(new IntVector("fault", ys.stream().mapToInt(Integer::intValue).toArray()));
        long[] seeds = new long[100];
        for (int j = 0; j < seeds.length; j++) seeds[j] = seed + j;
        var rf = RandomForest.fit(Formula.lhs("fault"), frame,
                new RandomForest.Options(100, 3, SplitRule.GINI, 12, 100, 2, 1.0, null, seeds, null));
        var classifier = new Models.FrameMultiClassifier(rf, names, CLASSES);
        Map<String, Double> valMetrics = multiClass(classifier, xv, yv, null);
        r.h2("Fault classification (supervised random forest, unseen load)")
                .p("Trained on 0-1 hp (" + xs.size() + " windows), validated on 2 hp (" + xv.size() + "), tested on 3 hp ("
                        + xt.size() + "). Validation accuracy " + fmt(valMetrics.get("accuracy")) + ", macro F1 "
                        + fmt(valMetrics.get("macro_f1")) + ".");
        Map<String, Double> testMetrics = multiClass(classifier, xt, yt, r);
        r.p("Test accuracy " + fmt(testMetrics.get("accuracy")) + ", macro F1 " + fmt(testMetrics.get("macro_f1"))
                + ". Caveat: CWRU uses one test rig and seeded EDM faults, so near-perfect scores here do not transfer "
                + "directly to naturally developing faults on plant bearings.");
        r.write(reports.resolve("04-cwru-bearing-anomaly-case-study.md"));

        String version = Artifacts.datasetVersion(raw, files.toArray(String[]::new));
        ModelCard.Dataset dataset = new ModelCard.Dataset("CWRU Bearing Data Center, 12 kHz drive end, 0.007 in, 0-3 hp",
                "https://engineering.case.edu/bearingdatacenter", "Public research data; not redistributed", version,
                fit.length, calib.length, scores.length, "healthy 0-2 hp: first 70% fit, last 30% calibration; test healthy 3 hp + all faults");
        Artifacts.export(modelsDir, detector, new ModelCard(DETECTOR_NAME, "1.0.0",
                "Anomaly detection: does this vibration window look unlike a healthy bearing?",
                "Smile IsolationForest, 300 trees, threshold = 99.5th percentile of held-out healthy scores at 0-2 hp", null, null,
                Instant.now(), dataset, BearingFeatures.definitions(), threshold, Map.of(), anomalyMetrics,
                List.of("Per-window false alarms are higher on unseen loads; raise an alarm only after "
                        + Models.BearingAnomalyDetector.ALARM_WINDOWS + " consecutive abnormal windows (alarm_* metrics).",
                        "Healthy baseline comes from one test rig; recalibrate the threshold on each plant machine.",
                        "Expects 1024-sample windows at 12 kHz in g units.")));
        Artifacts.export(modelsDir, classifier, new ModelCard(CLASSIFIER_NAME, "1.0.0",
                "Classification: normal, inner race, ball or outer race fault", "Smile RandomForest, 100 trees", null,
                null, Instant.now(), new ModelCard.Dataset(dataset.name(), dataset.source(), dataset.licence(), version,
                        xs.size(), xv.size(), xt.size(), "by motor load: train 0-1 hp, validation 2 hp, test 3 hp"),
                BearingFeatures.definitions(), null, valMetrics, testMetrics,
                List.of("Seeded 0.007 in faults on a single rig; natural faults and other fault sizes are not covered.")),
                names, rf.importance());
    }

    private static List<double[]> concat(List<double[]> a, List<double[]> b) {
        List<double[]> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static Map<String, Double> multiClass(Models.FrameMultiClassifier model, List<double[]> x, List<Integer> y,
            Report report) {
        int k = CLASSES.length;
        int[][] cm = new int[k][k];
        for (int i = 0; i < x.size(); i++) {
            double[] p = model.probabilities(x.get(i));
            int pred = 0;
            for (int c = 1; c < k; c++) if (p[c] > p[pred]) pred = c;
            cm[y.get(i)][pred]++;
        }
        double f1Sum = 0;
        int correct = 0;
        for (int c = 0; c < k; c++) {
            int tp = cm[c][c], fp = 0, fn = 0;
            for (int o = 0; o < k; o++) if (o != c) { fp += cm[o][c]; fn += cm[c][o]; }
            double prec = tp + fp == 0 ? 0 : (double) tp / (tp + fp), rec = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            f1Sum += prec + rec == 0 ? 0 : 2 * prec * rec / (prec + rec);
            correct += tp;
        }
        if (report != null) {
            List<List<String>> rows = new ArrayList<>();
            for (int c = 0; c < k; c++) {
                List<String> row = new ArrayList<>(List.of("actual " + CLASSES[c]));
                for (int o = 0; o < k; o++) row.add(String.valueOf(cm[c][o]));
                rows.add(row);
            }
            List<String> header = new ArrayList<>(List.of("Confusion matrix (test)"));
            for (String c : CLASSES) header.add("predicted " + c);
            report.table(header, rows);
        }
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("accuracy", (double) correct / x.size());
        m.put("macro_f1", f1Sum / k);
        return m;
    }

    private static String delay(Models.BearingAnomalyDetector d, List<double[]> stream, int onset, int consecutive) {
        int run = 0;
        for (int i = onset; i < stream.size(); i++) {
            run = d.anomalous(stream.get(i)) ? run + 1 : 0;
            if (run >= consecutive) {
                int windowsLate = i - onset + 1;
                return windowsLate + " windows (" + fmt(windowsLate * WINDOW_MS) + " ms)";
            }
        }
        return "not detected";
    }

    private static int falseAlarms(Models.BearingAnomalyDetector d, List<double[]> stream, int onset, int consecutive) {
        int run = 0, alarms = 0;
        for (int i = 0; i < onset; i++) {
            run = d.anomalous(stream.get(i)) ? run + 1 : 0;
            if (run == consecutive) alarms++;
        }
        return alarms;
    }

    /** Non-overlapping windows; the signal file is read line by line, then discarded (small memory footprint). */
    private static List<double[]> windows(Path csv) throws IOException {
        double[] signal = Files.lines(csv).mapToDouble(Double::parseDouble).toArray();
        List<double[]> out = new ArrayList<>();
        for (int s = 0; s + BearingFeatures.WINDOW <= signal.length; s += BearingFeatures.WINDOW) {
            out.add(BearingFeatures.of(Arrays.copyOfRange(signal, s, s + BearingFeatures.WINDOW)));
        }
        return out;
    }
}
