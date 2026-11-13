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
import java.util.function.Function;

import com.basintelligence.ml.ModelCard;
import com.basintelligence.ml.Models;
import com.basintelligence.ml.ProbabilisticClassifier;
import com.basintelligence.ml.ProcessFailureFeatures;

import smile.base.cart.SplitRule;
import smile.classification.DecisionTree;
import smile.classification.GradientTreeBoost;
import smile.classification.LogisticRegression;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.IntVector;

/**
 * AI4I 2020 machine-failure classification: EDA (step 1), features (2), logistic baseline (3), decision tree and
 * random forest (4), gradient boosting (5), model selection on the validation split (8) and export (9).
 */
public final class Ai4iPipeline {

    static final String MODEL_NAME = "process-failure-classifier";
    private static final String LABEL = "failure";

    private record Row(char type, double air, double process, double rpm, double torque, double wear, int failure,
            int twf, int hdf, int pwf, int osf, int rnf) {
    }

    private record Candidate(String name, String algorithm, ProbabilisticClassifier model, double threshold,
            Map<String, Double> validation, Map<String, Double> test, double[] importance) {
    }

    private Ai4iPipeline() {
    }

    public static void run(Path raw, Path reports, Path modelsDir, long seed) throws IOException {
        System.out.println("AI4I 2020: failure classification");
        List<Row> rows = load(raw.resolve("ai4i/ai4i2020.csv"));
        eda(rows, reports.resolve("01-ai4i-data-quality.md"));

        double[][] x = rows.stream()
                .map(r -> ProcessFailureFeatures.of(r.type(), r.air(), r.process(), r.rpm(), r.torque(), r.wear()))
                .toArray(double[][]::new);
        int[] y = rows.stream().mapToInt(Row::failure).toArray();
        Splits.ThreeWay split = Splits.stratified(y, 0.6, 0.2, seed);
        double[][] xTrain = Splits.rows(x, split.train()), xVal = Splits.rows(x, split.validation()),
                xTest = Splits.rows(x, split.test());
        int[] yTrain = Splits.labels(y, split.train()), yVal = Splits.labels(y, split.validation()),
                yTest = Splits.labels(y, split.test());
        smile.math.MathEx.setSeed(seed);

        List<Candidate> candidates = new ArrayList<>();
        // Step 3: baseline logistic regression on standardised features.
        double[] mean = new double[x[0].length], std = new double[x[0].length];
        for (int j = 0; j < mean.length; j++) {
            final int c = j;
            mean[j] = Arrays.stream(xTrain).mapToDouble(r -> r[c]).average().orElse(0);
            std[j] = Math.sqrt(Arrays.stream(xTrain).mapToDouble(r -> (r[c] - mean[c]) * (r[c] - mean[c])).sum() / xTrain.length);
        }
        double[][] zTrain = Arrays.stream(xTrain).map(r -> Models.standardise(r, mean, std)).toArray(double[][]::new);
        var lr = new Models.StandardisedLogistic(LogisticRegression.binomial(zTrain, yTrain,
                new LogisticRegression.Options(0.01, 1e-6, 2000)), mean, std);
        candidates.add(evaluate("Logistic regression (baseline)", "Smile LogisticRegression, L2 λ=0.01", lr, null,
                xVal, yVal, xTest, yTest));

        // Step 4: decision tree and random forest.
        DataFrame train = frame(xTrain, yTrain);
        Formula formula = Formula.lhs(LABEL);
        var tree = DecisionTree.fit(formula, train, new DecisionTree.Options(SplitRule.GINI, 8, 64, 5));
        candidates.add(evaluate("Decision tree", "Smile CART, Gini, depth ≤ 8, ≤ 64 leaves",
                new Models.TreeClassifier(tree, ProcessFailureFeatures.NAMES), null, xVal, yVal, xTest, yTest));

        int ntrees = 150;
        long[] seeds = new long[ntrees];
        for (int i = 0; i < ntrees; i++) seeds[i] = seed + i;
        int positives = Arrays.stream(yTrain).sum();
        int weight = Math.max(1, (yTrain.length - positives) / Math.max(1, positives) / 3);
        var forest = RandomForest.fit(formula, train, new RandomForest.Options(ntrees, 4, SplitRule.GINI, 20, 200, 2,
                1.0, new int[] {1, weight}, seeds, null));
        candidates.add(evaluate("Random forest", "Smile RandomForest, 150 trees, mtry=4, class weight 1:" + weight,
                new Models.TreeClassifier(forest, ProcessFailureFeatures.NAMES), forest.importance(),
                xVal, yVal, xTest, yTest));

        // Step 5: gradient boosting.
        var gbt = GradientTreeBoost.fit(formula, train, new GradientTreeBoost.Options(300, 6, 16, 5, 0.05, 0.8, null, null));
        candidates.add(evaluate("Gradient boosting", "Smile GradientTreeBoost, 300 trees, depth ≤ 6, shrinkage 0.05",
                new Models.TreeClassifier(gbt, ProcessFailureFeatures.NAMES), gbt.importance(), xVal, yVal, xTest, yTest));

        // Step 8: select on validation PR-AUC (robust to the 3.4% positive rate); test is reported, never used to choose.
        Candidate best = candidates.stream().max((a, b) -> Double.compare(a.validation().get("pr_auc"),
                b.validation().get("pr_auc"))).orElseThrow();
        comparisonReport(candidates, best, split, reports.resolve("02-ai4i-model-comparison.md"));

        // Step 9: export with a model card.
        ModelCard card = Artifacts.export(modelsDir, best.model(), new ModelCard(MODEL_NAME, "1.0.0",
                "Binary classification: will this machining process fail?", best.algorithm(), null, null, Instant.now(),
                new ModelCard.Dataset("AI4I 2020 Predictive Maintenance", "https://doi.org/10.24432/C5HS5C", "CC BY 4.0",
                        Artifacts.datasetVersion(raw, "ai4i/ai4i2020.csv"), split.train().length,
                        split.validation().length, split.test().length,
                        "stratified random 60/20/20, seed " + seed),
                ProcessFailureFeatures.DEFINITIONS, best.threshold(), best.validation(), best.test(),
                List.of("AI4I 2020 is itself a synthetic dataset; real plant data will have different failure physics.",
                        "Predicts process failure for a milling operation (tool wear, heat dissipation, power, overstrain).",
                        "Decision threshold was tuned on the validation split to maximise F1.")),
                ProcessFailureFeatures.NAMES, best.importance());
        System.out.printf("  selected %s: test PR-AUC %s, F1 %s%n", best.name(), fmt(card.testMetrics().get("pr_auc")),
                fmt(card.testMetrics().get("f1")));
    }

    private static Candidate evaluate(String name, String algorithm, ProbabilisticClassifier model, double[] importance,
            double[][] xVal, int[] yVal, double[][] xTest, int[] yTest) {
        double[] sVal = Arrays.stream(xVal).mapToDouble(model::probability).toArray();
        double threshold = Metrics.bestF1Threshold(sVal, yVal);
        double[] sTest = Arrays.stream(xTest).mapToDouble(model::probability).toArray();
        System.out.printf("  %-32s val PR-AUC %s%n", name, fmt(Metrics.prAuc(sVal, yVal)));
        return new Candidate(name, algorithm, model, threshold, Metrics.classification(sVal, yVal, threshold),
                Metrics.classification(sTest, yTest, threshold), importance);
    }

    private static DataFrame frame(double[][] x, int[] y) {
        return DataFrame.of(x, ProcessFailureFeatures.NAMES).add(new IntVector(LABEL, y));
    }

    private static List<Row> load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            rows.add(new Row(f[2].charAt(0), d(f[3]), d(f[4]), d(f[5]), d(f[6]), d(f[7]), i(f[8]), i(f[9]), i(f[10]),
                    i(f[11]), i(f[12]), i(f[13])));
        }
        return rows;
    }

    private static void eda(List<Row> rows, Path out) throws IOException {
        int n = rows.size();
        long failures = rows.stream().filter(r -> r.failure() == 1).count();
        long noModeFailures = rows.stream().filter(r -> r.failure() == 1 && r.twf() + r.hdf() + r.pwf() + r.osf() + r.rnf() == 0).count();
        long modeNoFailure = rows.stream().filter(r -> r.failure() == 0 && r.twf() + r.hdf() + r.pwf() + r.osf() + r.rnf() > 0).count();
        Report r = new Report().h1("AI4I 2020: exploratory data analysis and data quality")
                .p("Source: UCI ML Repository #601 (CC BY 4.0), https://doi.org/10.24432/C5HS5C. Generated by the BAS "
                        + "Intelligence ML lab on " + Instant.now() + ".")
                .h2("Shape and completeness")
                .table(List.of("Check", "Result"), List.of(
                        List.of("Rows", String.valueOf(n)),
                        List.of("Columns", "14 (UDI, Product ID, Type, 5 process variables, Machine failure, 5 failure modes)"),
                        List.of("Missing values", "0 (every row parsed into numeric fields)"),
                        List.of("Positive class (Machine failure = 1)", failures + " (" + fmt(100.0 * failures / n) + "%)"),
                        List.of("Failures with no failure-mode flag set", String.valueOf(noModeFailures)),
                        List.of("Failure-mode flag set but Machine failure = 0", String.valueOf(modeNoFailure))));
        r.p("Findings: the label is heavily imbalanced (about 1 in 30), so accuracy is misleading and PR-AUC, recall and F1 "
                + "are the primary metrics. The label and failure-mode flags disagree on a small number of rows "
                + "(mostly random failures, RNF), which caps achievable recall. The failure-mode flags are excluded from "
                + "the features because they are the label broken down by cause (target leakage).");
        r.h2("Failure modes").table(List.of("Mode", "Rows"), List.of(
                List.of("TWF tool wear", cnt(rows, Row::twf)), List.of("HDF heat dissipation", cnt(rows, Row::hdf)),
                List.of("PWF power", cnt(rows, Row::pwf)), List.of("OSF overstrain", cnt(rows, Row::osf)),
                List.of("RNF random", cnt(rows, Row::rnf))));
        r.h2("Product type").table(List.of("Type", "Rows", "Failure rate"), "LMH".chars().mapToObj(c -> {
            List<Row> t = rows.stream().filter(x -> x.type() == c).toList();
            return List.of(String.valueOf((char) c), String.valueOf(t.size()),
                    fmt(100.0 * t.stream().filter(x -> x.failure() == 1).count() / t.size()) + "%");
        }).toList());
        Map<String, Function<Row, Double>> vars = new LinkedHashMap<>();
        vars.put("Air temperature [K]", Row::air);
        vars.put("Process temperature [K]", Row::process);
        vars.put("Rotational speed [rpm]", Row::rpm);
        vars.put("Torque [Nm]", Row::torque);
        vars.put("Tool wear [min]", Row::wear);
        List<List<String>> stats = new ArrayList<>();
        vars.forEach((name, f) -> {
            double[] all = rows.stream().mapToDouble(f::apply).toArray();
            double[] fail = rows.stream().filter(x -> x.failure() == 1).mapToDouble(f::apply).toArray();
            double[] ok = rows.stream().filter(x -> x.failure() == 0).mapToDouble(f::apply).toArray();
            stats.add(List.of(name, fmt(Arrays.stream(all).min().orElse(0)), fmt(mean(all)), fmt(sd(all)),
                    fmt(Arrays.stream(all).max().orElse(0)), fmt(mean(ok)), fmt(mean(fail)),
                    fmt(pointBiserial(all, rows.stream().mapToInt(Row::failure).toArray()))));
        });
        r.h2("Process variables").table(List.of("Variable", "Min", "Mean", "SD", "Max", "Mean (no failure)",
                "Mean (failure)", "Correlation with failure"), stats);
        r.p("Torque and tool wear are higher, and rotational speed lower, in failing rows. Single-variable correlations are "
                + "weak because the failure modes are threshold interactions (e.g. power = torque × speed, strain = wear × "
                + "torque), which motivates the engineered features in the model card.");
        r.write(out);
    }

    private static void comparisonReport(List<Candidate> candidates, Candidate best, Splits.ThreeWay split, Path out)
            throws IOException {
        Report r = new Report().h1("AI4I 2020: model comparison and selection")
                .p("Split: stratified 60/20/20 (" + split.train().length + " train, " + split.validation().length
                        + " validation, " + split.test().length + " test). Each model's decision threshold maximises F1 on "
                        + "validation. Selection uses validation PR-AUC only; test metrics are reported once for the record.");
        List<String> header = List.of("Model", "Val PR-AUC", "Val ROC-AUC", "Val F1", "Test precision", "Test recall",
                "Test F1", "Test ROC-AUC", "Test PR-AUC", "Threshold");
        r.table(header, candidates.stream().map(c -> List.of(c == best ? "**" + c.name() + "** (selected)" : c.name(),
                fmt(c.validation().get("pr_auc")), fmt(c.validation().get("roc_auc")), fmt(c.validation().get("f1")),
                fmt(c.test().get("precision")), fmt(c.test().get("recall")), fmt(c.test().get("f1")),
                fmt(c.test().get("roc_auc")), fmt(c.test().get("pr_auc")), fmt(c.threshold()))).toList());
        r.h2("Confusion matrices (test split)");
        r.table(List.of("Model", "TP", "FP", "TN", "FN", "False alarm rate"), candidates.stream().map(c -> List.of(c.name(),
                fmt(c.test().get("tp")), fmt(c.test().get("fp")), fmt(c.test().get("tn")), fmt(c.test().get("fn")),
                fmt(c.test().get("false_alarm_rate")))).toList());
        for (Candidate c : candidates) {
            if (c.importance() == null) continue;
            double total = Arrays.stream(c.importance()).sum();
            List<List<String>> rows = new ArrayList<>();
            Integer[] order = new Integer[c.importance().length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Double.compare(c.importance()[b], c.importance()[a]));
            for (int i : order) rows.add(List.of(ProcessFailureFeatures.NAMES[i], fmt(c.importance()[i] / total)));
            r.h2("Feature importance: " + c.name()).table(List.of("Feature", "Share of impurity reduction"), rows);
        }
        r.write(out);
    }

    private static String cnt(List<Row> rows, java.util.function.ToIntFunction<Row> f) {
        return String.valueOf(rows.stream().mapToInt(f).sum());
    }

    static double mean(double[] v) {
        return Arrays.stream(v).average().orElse(Double.NaN);
    }

    static double sd(double[] v) {
        double m = mean(v);
        return Math.sqrt(Arrays.stream(v).map(x -> (x - m) * (x - m)).sum() / (v.length - 1));
    }

    static double pointBiserial(double[] x, int[] y) {
        double mx = mean(x), my = Arrays.stream(y).average().orElse(0), sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < x.length; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }

    private static int i(String s) {
        return Integer.parseInt(s.trim());
    }
}
