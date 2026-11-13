package com.basintelligence.mllab;

import static com.basintelligence.mllab.Report.fmt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.basintelligence.ml.ModelCard;

/** Step 8 evidence: one table of every exported model with its governance fields and headline test metrics. */
final class Summary {

    private Summary() {
    }

    static void write(Path models, Path out) throws IOException {
        List<ModelCard> cards = new ArrayList<>();
        try (Stream<Path> files = Files.list(models)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".card.json")).sorted().toList()) {
                cards.add(Artifacts.JSON.readValue(f.toFile(), ModelCard.class));
            }
        }
        Report r = new Report().h1("Model evaluation summary")
                .p("Every model the application serves, with the governance fields recorded in its model card "
                        + "(src/main/resources/models/*.card.json). Detailed evidence is in the numbered reports in this folder.");
        r.table(List.of("Model", "Version", "Task", "Algorithm", "Trained", "Dataset version", "Features",
                "Headline test metrics", "Artifact SHA-256"), cards.stream().map(c -> List.of(c.name(), c.version(),
                c.task(), c.algorithm(), c.trainedAt().toString().substring(0, 10), shorten(c.dataset().version()),
                String.valueOf(c.features().size()), headline(c.testMetrics()), c.artifactSha256().substring(0, 16) + "…"))
                .toList());
        r.h2("Selection rules").p("- Process failure (AI4I): highest validation PR-AUC, because failures are 3.4% of rows.\n"
                + "- Remaining useful life (C-MAPSS): lowest validation RMSE.\n"
                + "- Bearing anomaly detector (CWRU): threshold fixed at the 99th percentile of healthy calibration scores.\n"
                + "- Test sets are scored once after selection and never used to choose a model or threshold.");
        r.write(out);
    }

    private static String headline(Map<String, Double> m) {
        List<String> keys = List.of("pr_auc", "roc_auc", "f1", "recall", "false_alarm_rate", "rmse", "mae", "r2", "accuracy", "macro_f1");
        List<String> parts = new ArrayList<>();
        for (String k : keys) if (m.containsKey(k) && parts.size() < 4) parts.add(k + " " + fmt(m.get(k)));
        return String.join(", ", parts);
    }

    private static String shorten(String v) {
        return v.length() > 60 ? v.substring(0, 57) + "…" : v;
    }
}
