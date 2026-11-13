package com.basintelligence.mllab;

import static com.basintelligence.mllab.Report.fmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.basintelligence.vision.DefectClassifier;
import com.basintelligence.vision.ImageProcessor;

/**
 * Evaluates the deployed ONNX defect model on MVTec AD test images, if the user has downloaded a category to
 * ml/data/raw/mvtec/<category>/test. Images are decoded and scored one at a time, so memory stays flat no matter
 * how many images there are.
 */
public final class MvtecVisionEvaluation {

    private MvtecVisionEvaluation() {
    }

    public static void run(Path raw, Path reports, Path model) throws Exception {
        Path root = raw.resolve("mvtec");
        if (!Files.isDirectory(root)) {
            System.out.println("MVTec AD: skipped (download a category to ml/data/raw/mvtec/<category>/ to evaluate)");
            return;
        }
        System.out.println("MVTec AD: evaluating the ONNX defect model");
        ImageProcessor images = new ImageProcessor();
        Report r = new Report().h1("MVTec AD: evaluation of the baseline ONNX defect model")
                .p("MVTec AD is licensed CC BY-NC-SA 4.0 (non-commercial). Images are not redistributed. Generated "
                        + Instant.now() + ".");
        List<List<String>> rows = new ArrayList<>();
        try (DefectClassifier classifier = new DefectClassifier(Files.readAllBytes(model), model.getFileName().toString());
                Stream<Path> categories = Files.list(root)) {
            for (Path category : categories.filter(Files::isDirectory).sorted().toList()) {
                Path test = category.resolve("test");
                if (!Files.isDirectory(test)) continue;
                List<Double> scores = new ArrayList<>();
                List<Integer> labels = new ArrayList<>();
                try (Stream<Path> files = Files.walk(test)) {
                    for (Path img : files.filter(p -> p.toString().endsWith(".png")).sorted().toList()) {
                        var gray = images.decodeGray(Files.readAllBytes(img));
                        try {
                            scores.add(classifier.defectProbability(images.toTensor(gray)));
                        } finally {
                            gray.release();
                        }
                        labels.add(img.getParent().getFileName().toString().equals("good") ? 0 : 1);
                    }
                }
                double[] s = scores.stream().mapToDouble(Double::doubleValue).toArray();
                int[] y = labels.stream().mapToInt(Integer::intValue).toArray();
                var m = Metrics.classification(s, y, 0.5);
                rows.add(List.of(category.getFileName().toString(), String.valueOf(y.length), fmt(m.get("roc_auc")),
                        fmt(m.get("pr_auc")), fmt(m.get("precision")), fmt(m.get("recall")), fmt(m.get("false_alarm_rate"))));
            }
        }
        r.table(List.of("Category", "Test images", "ROC-AUC", "PR-AUC", "Precision @0.5", "Recall @0.5", "False alarm rate"), rows)
                .p("The baseline is a fixed-weight edge detector calibrated on synthetic parts, so these numbers show the gap "
                        + "a trained model (for example a small CNN exported to ONNX with the same input contract) must close.");
        r.write(reports.resolve("05-mvtec-vision-evaluation.md"));
    }
}
