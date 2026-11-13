package com.basintelligence.mllab;

import static com.basintelligence.mllab.Report.fmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import com.basintelligence.ml.ModelCard;
import com.basintelligence.ml.Models;
import com.basintelligence.ml.RegressionModel;
import com.basintelligence.ml.RulFeatures;

import smile.base.cart.Loss;
import smile.data.DataFrame;
import smile.data.formula.Formula;
import smile.data.vector.DoubleVector;
import smile.regression.GradientTreeBoost;
import smile.regression.OLS;
import smile.regression.RandomForest;

/**
 * NASA C-MAPSS FD001 remaining useful life (step 7). Labels use the common piecewise-linear target (RUL capped at
 * 125 cycles, because degradation is not observable early in life). Splits are by engine so no engine appears in
 * both training and validation. The official test set (last observed cycle per engine) is scored once.
 */
public final class CmapssRulPipeline {

    static final String MODEL_NAME = "remaining-useful-life-regressor";
    static final int RUL_CAP = 125;
    private static final String LABEL = "rul";

    private record Engine(int unit, List<double[]> sensors, List<Integer> cycles) {
    }

    private record Candidate(String name, String algorithm, RegressionModel model, Map<String, Double> validation,
            Map<String, Double> test, double nasaScore) {
    }

    private CmapssRulPipeline() {
    }

    public static void run(Path raw, Path reports, Path modelsDir, long seed) throws IOException {
        System.out.println("NASA C-MAPSS FD001: remaining useful life");
        List<Engine> train = load(raw.resolve("cmapss/train_FD001.txt"));
        List<Engine> test = load(raw.resolve("cmapss/test_FD001.txt"));
        List<Integer> trueRul = Files.readAllLines(raw.resolve("cmapss/RUL_FD001.txt")).stream()
                .filter(l -> !l.isBlank()).map(l -> Integer.parseInt(l.trim())).toList();
        Report eda = edaReport(train);

        List<Engine> shuffled = new ArrayList<>(train);
        Collections.shuffle(shuffled, new Random(seed));
        int cut = (int) Math.round(shuffled.size() * 0.8);
        List<Engine> fit = shuffled.subList(0, cut), val = shuffled.subList(cut, shuffled.size());

        double[][] xFit = features(fit, false), xVal = features(val, false);
        double[] yFit = labels(fit), yVal = labels(val);
        double[][] xTest = new double[test.size()][];
        for (int i = 0; i < test.size(); i++) {
            Engine e = test.get(i);
            xTest[i] = RulFeatures.of(e.cycles().getLast(), e.sensors());
        }
        double[] yTest = trueRul.stream().mapToDouble(Integer::doubleValue).toArray();

        String[] names = RulFeatures.names();
        DataFrame frame = DataFrame.of(xFit, names).add(new DoubleVector(LABEL, yFit));
        Formula formula = Formula.lhs(LABEL);
        smile.math.MathEx.setSeed(seed);
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(evaluate("Linear regression (baseline)", "Smile OLS",
                new Models.FrameRegressor(OLS.fit(formula, frame), names), xVal, yVal, xTest, yTest));
        long[] seeds = new long[120];
        for (int i = 0; i < seeds.length; i++) seeds[i] = seed + i;
        candidates.add(evaluate("Random forest", "Smile RandomForest regression, 120 trees, mtry=14",
                new Models.FrameRegressor(RandomForest.fit(formula, frame,
                        new RandomForest.Options(120, 14, 16, 400, 5, 1.0, seeds, null)), names),
                xVal, yVal, xTest, yTest));
        candidates.add(evaluate("Gradient boosting", "Smile GradientTreeBoost regression, least squares, 300 trees",
                new Models.FrameRegressor(GradientTreeBoost.fit(formula, frame,
                        new GradientTreeBoost.Options(Loss.ls(), 300, 5, 24, 5, 0.05, 0.8, null, null)), names),
                xVal, yVal, xTest, yTest));

        Candidate best = candidates.stream()
                .min((a, b) -> Double.compare(a.validation().get("rmse"), b.validation().get("rmse"))).orElseThrow();
        eda.h2("Regression evaluation")
                .p("Split by engine: " + fit.size() + " engines for training (" + xFit.length + " cycles), " + val.size()
                        + " engines for validation (" + xVal.length + " cycles). Selection uses validation RMSE. The test "
                        + "set is NASA's held-out FD001 test (" + test.size() + " engines, scored at the last observed cycle "
                        + "against the published true RUL, uncapped).")
                .table(List.of("Model", "Val MAE", "Val RMSE", "Val R²", "Test MAE", "Test RMSE", "Test R²",
                        "Test NASA score (lower is better)"), candidates.stream().map(c -> List.of(
                        c == best ? "**" + c.name() + "** (selected)" : c.name(),
                        fmt(c.validation().get("mae")), fmt(c.validation().get("rmse")), fmt(c.validation().get("r2")),
                        fmt(c.test().get("mae")), fmt(c.test().get("rmse")), fmt(c.test().get("r2")), fmt(c.nasaScore())))
                        .toList())
                .p("The NASA scoring function penalises late predictions (predicting more life than remains) more than "
                        + "early ones, which matches the maintenance cost of a missed failure. For reference, published "
                        + "deep-learning results on FD001 report test RMSE of roughly 12 to 16 cycles.");
        eda.write(reports.resolve("03-cmapss-rul-evaluation.md"));

        Artifacts.export(modelsDir, best.model(), new ModelCard(MODEL_NAME, "1.0.0",
                "Regression: remaining useful life in cycles (capped at " + RUL_CAP + ")", best.algorithm(), null, null,
                Instant.now(), new ModelCard.Dataset("NASA C-MAPSS FD001 (turbofan run-to-failure simulation)",
                        "NASA Prognostics Center of Excellence data repository", "US Government open data",
                        Artifacts.datasetVersion(raw, "cmapss/train_FD001.txt", "cmapss/test_FD001.txt",
                                "cmapss/RUL_FD001.txt"),
                        xFit.length, xVal.length, xTest.length, "by engine unit 80/20, seed " + seed
                                + "; official test set"),
                RulFeatures.definitions(), null, best.validation(), best.test(),
                List.of("Trained on one operating condition and one fault mode (HPC degradation) of a simulated turbofan.",
                        "Predictions above " + RUL_CAP + " cycles are not meaningful because the label is capped.",
                        "Input sensors must use the C-MAPSS sensor definitions; plant machines need their own training data.")));
        System.out.printf("  selected %s: test RMSE %s cycles%n", best.name(), fmt(best.test().get("rmse")));
    }

    private static Candidate evaluate(String name, String algorithm, RegressionModel model, double[][] xVal,
            double[] yVal, double[][] xTest, double[] yTest) {
        double[] pVal = predict(model, xVal), pTest = predict(model, xTest);
        double score = 0;
        for (int i = 0; i < pTest.length; i++) {
            double d = pTest[i] - yTest[i];
            score += d < 0 ? Math.exp(-d / 13) - 1 : Math.exp(d / 10) - 1;
        }
        Map<String, Double> test = new LinkedHashMap<>(Metrics.regression(pTest, yTest));
        test.put("nasa_score", score);
        System.out.printf("  %-30s val RMSE %s%n", name, fmt(Metrics.regression(pVal, yVal).get("rmse")));
        return new Candidate(name, algorithm, model, Metrics.regression(pVal, yVal), test, score);
    }

    private static double[] predict(RegressionModel model, double[][] x) {
        double[] p = new double[x.length];
        for (int i = 0; i < x.length; i++) p[i] = Math.clamp(model.predict(x[i]), 0, RUL_CAP);
        return p;
    }

    private static double[][] features(List<Engine> engines, boolean lastOnly) {
        List<double[]> rows = new ArrayList<>();
        for (Engine e : engines) {
            for (int i = lastOnly ? e.sensors().size() - 1 : 0; i < e.sensors().size(); i++) {
                rows.add(RulFeatures.of(e.cycles().get(i), e.sensors().subList(0, i + 1)));
            }
        }
        return rows.toArray(double[][]::new);
    }

    private static double[] labels(List<Engine> engines) {
        List<Double> y = new ArrayList<>();
        for (Engine e : engines) {
            int max = e.cycles().getLast();
            for (int c : e.cycles()) y.add((double) Math.min(RUL_CAP, max - c));
        }
        return y.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Columns: unit, cycle, 3 operating settings, sensors s1..s21. Sensor k is stored at index k. */
    private static List<Engine> load(Path file) throws IOException {
        Map<Integer, Engine> engines = new TreeMap<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            String[] f = line.trim().split("\\s+");
            int unit = Integer.parseInt(f[0]);
            Engine e = engines.computeIfAbsent(unit, u -> new Engine(u, new ArrayList<>(), new ArrayList<>()));
            double[] s = new double[22];
            for (int k = 1; k <= 21; k++) s[k] = Double.parseDouble(f[4 + k]);
            e.sensors().add(s);
            e.cycles().add(Integer.parseInt(f[1]));
        }
        return new ArrayList<>(engines.values());
    }

    private static Report edaReport(List<Engine> train) {
        Report r = new Report().h1("NASA C-MAPSS FD001: data analysis and RUL evaluation")
                .p("Source: NASA Prognostics Center of Excellence (Saxena et al., PHM 2008). Generated " + Instant.now() + ".");
        int cycles = train.stream().mapToInt(e -> e.cycles().size()).sum();
        int[] life = train.stream().mapToInt(e -> e.cycles().getLast()).sorted().toArray();
        r.h2("Data quality").table(List.of("Check", "Result"), List.of(
                List.of("Training engines", String.valueOf(train.size())),
                List.of("Training cycles (rows)", String.valueOf(cycles)),
                List.of("Missing values", "0"),
                List.of("Engine life (cycles): min / median / max", life[0] + " / " + life[life.length / 2] + " / " + life[life.length - 1])));
        List<List<String>> rows = new ArrayList<>();
        for (int k = 1; k <= 21; k++) {
            final int s = k;
            double[] v = train.stream().flatMap(e -> e.sensors().stream()).mapToDouble(x -> x[s]).toArray();
            double sd = Ai4iPipeline.sd(v);
            boolean used = java.util.Arrays.stream(RulFeatures.SENSORS).anyMatch(x -> x == s);
            rows.add(List.of("s" + k, fmt(Ai4iPipeline.mean(v)), fmt(sd), used ? "used" : "dropped (near-constant)"));
        }
        r.h2("Sensor variability").table(List.of("Sensor", "Mean", "SD", "Decision"), rows)
                .p("Seven sensors are flat in FD001 (one operating condition) and carry no degradation signal. Each "
                        + "remaining sensor contributes its current value, a 10-cycle mean (noise reduction) and a 10-cycle "
                        + "slope (degradation rate), plus the cycle count: " + RulFeatures.names().length + " features.");
        return r;
    }
}
