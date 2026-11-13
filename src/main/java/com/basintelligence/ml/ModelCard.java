package com.basintelligence.ml;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Lightweight model governance record, stored as {@code <artifact>.card.json} next to each model artifact.
 * Captures what the model is, what it was trained on, how it was evaluated, and the artifact checksum.
 */
public record ModelCard(
        String name,
        String version,
        String task,
        String algorithm,
        String artifact,
        String artifactSha256,
        Instant trainedAt,
        Dataset dataset,
        List<Feature> features,
        Double decisionThreshold,
        Map<String, Double> validationMetrics,
        Map<String, Double> testMetrics,
        List<String> limitations,
        Map<String, Double> featureImportance) {

    /** Card without feature importance (models that do not provide it). */
    public ModelCard(String name, String version, String task, String algorithm, String artifact, String artifactSha256,
            Instant trainedAt, Dataset dataset, List<Feature> features, Double decisionThreshold,
            Map<String, Double> validationMetrics, Map<String, Double> testMetrics, List<String> limitations) {
        this(name, version, task, algorithm, artifact, artifactSha256, trainedAt, dataset, features, decisionThreshold,
                validationMetrics, testMetrics, limitations, null);
    }

    public record Dataset(String name, String source, String licence, String version, int trainRows, int validationRows,
            int testRows, String splitStrategy) {
    }

    public record Feature(String name, String unit, String definition) {
    }

    public List<String> featureNames() {
        return features.stream().map(Feature::name).toList();
    }
}
