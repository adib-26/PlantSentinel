package com.basintelligence.mllab;

import java.nio.file.Path;

/**
 * Entry point for the offline ML lab. Run with {@code ml/run-ml-lab.sh}. Plain Java + Smile, no Spring, and every
 * dataset is processed in memory-light chunks so it runs on an 8 GB laptop.
 * <p>
 * Writes evidence reports to ml/reports and versioned model artifacts (plus model cards) to
 * src/main/resources/models, which the application loads at startup for the prediction API.
 */
public final class MlLab {

    private MlLab() {
    }

    interface Step {
        void run() throws Exception;
    }

    static void deterministic(Step step) throws Exception {
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(1);
        try {
            pool.submit(() -> {
                step.run();
                return null;
            }).get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        } finally {
            pool.shutdown();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path raw = root.resolve("ml/data/raw");
        Path reports = root.resolve("ml/reports");
        Path models = root.resolve("src/main/resources/models");
        long seed = 42;
        long started = System.currentTimeMillis();
        // Smile trains forests with parallel streams that draw from a shared random generator, so results change
        // from run to run. Running each pipeline in a one-thread pool (parallel streams inherit the pool) makes the
        // results reproducible from the seed.
        deterministic(() -> Ai4iPipeline.run(raw, reports, models, seed));
        deterministic(() -> CmapssRulPipeline.run(raw, reports, models, seed));
        deterministic(() -> CwruBearingPipeline.run(raw, reports, models, seed));
        MvtecVisionEvaluation.run(raw, reports, models.resolve("defect-detector.onnx"));
        Summary.write(models, reports.resolve("00-model-evaluation-summary.md"));
        Runtime rt = Runtime.getRuntime();
        System.out.printf("ML lab finished in %d s, peak heap in use about %d MB%n",
                (System.currentTimeMillis() - started) / 1000, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
    }
}
