package com.basintelligence.mllab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.basintelligence.ml.ModelCard;
import com.basintelligence.ml.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Writes a model artifact plus its card (with SHA-256) to src/main/resources/models for the app to load. */
public final class Artifacts {

    public static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT).build();

    private Artifacts() {
    }

    public static ModelCard export(Path dir, Serializable model, ModelCard draft) throws IOException {
        return export(dir, model, draft, null, null);
    }

    /** Exports with feature importance (shares that sum to 1), ordered from most to least important. */
    public static ModelCard export(Path dir, Serializable model, ModelCard draft, String[] names, double[] importance)
            throws IOException {
        Map<String, Double> shares = null;
        if (importance != null) {
            double total = java.util.Arrays.stream(importance).sum();
            Integer[] order = new Integer[importance.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(importance[b], importance[a]));
            shares = new java.util.LinkedHashMap<>();
            for (int i : order) shares.put(names[i], total == 0 ? 0 : importance[i] / total);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(model);
        }
        String artifact = draft.name() + "-v" + draft.version() + ".ser";
        Files.createDirectories(dir);
        Files.write(dir.resolve(artifact), bytes.toByteArray());
        ModelCard card = new ModelCard(draft.name(), draft.version(), draft.task(), draft.algorithm(), artifact,
                ModelRegistry.sha256(bytes.toByteArray()), draft.trainedAt(), draft.dataset(), draft.features(),
                draft.decisionThreshold(), draft.validationMetrics(), draft.testMetrics(), draft.limitations(), shares);
        Files.writeString(dir.resolve(draft.name() + ".card.json"), JSON.writeValueAsString(card));
        System.out.printf("  exported %s (%d KB, sha256 %s…)%n", artifact, bytes.size() / 1024,
                card.artifactSha256().substring(0, 12));
        return card;
    }

    /** SHA-256 of the raw dataset files, from ml/data/raw/SHA256SUMS (written by fetch_datasets.sh). */
    public static String datasetVersion(Path raw, String... relativeFiles) throws IOException {
        Map<String, String> sums = new TreeMap<>();
        for (String line : Files.readAllLines(raw.resolve("SHA256SUMS"))) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2) sums.put(parts[1].replaceFirst("^\\./", ""), parts[0]);
        }
        StringBuilder sb = new StringBuilder();
        for (String f : relativeFiles) {
            String sha = sums.get(f);
            if (sha == null) throw new IOException("No checksum for " + f + "; re-run ml/datasets/fetch_datasets.sh");
            sb.append(sb.isEmpty() ? "" : ",").append(f).append("@sha256:").append(sha, 0, 16);
        }
        return sb.toString();
    }
}
