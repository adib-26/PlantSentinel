package com.basintelligence.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * ML evaluation gate: the shipped models must meet minimum test-set quality, read from their model cards. If a
 * retrain with ml/run-ml-lab.sh makes a model worse than these floors, the build fails instead of shipping it.
 * Floors are set below the current results to allow for normal variation.
 */
class ModelQualityGateTest {

    static ModelRegistry registry;

    @BeforeAll
    static void load() throws IOException {
        registry = new ModelRegistry(JsonMapper.builder().addModule(new JavaTimeModule()).build());
    }

    private static Map<String, Double> test(String model) {
        return registry.find(model).orElseThrow(() -> new AssertionError(model + " is not installed")).card().testMetrics();
    }

    @Test
    void processFailureClassifierFindsMostFailuresWithFewFalseAlarms() {
        var m = test(MlPredictionService.PROCESS_FAILURE);
        assertThat(m.get("pr_auc")).as("PR-AUC").isGreaterThanOrEqualTo(0.80);
        assertThat(m.get("recall")).as("recall: share of real failures caught").isGreaterThanOrEqualTo(0.70);
        assertThat(m.get("precision")).as("precision").isGreaterThanOrEqualTo(0.80);
        assertThat(m.get("false_alarm_rate")).as("false alarm rate").isLessThanOrEqualTo(0.01);
    }

    @Test
    void remainingUsefulLifeErrorStaysWithinBudget() {
        var m = test(MlPredictionService.RUL);
        assertThat(m.get("rmse")).as("RMSE in cycles").isLessThanOrEqualTo(22.0);
        assertThat(m.get("mae")).as("MAE in cycles").isLessThanOrEqualTo(16.0);
        assertThat(m.get("r2")).as("R²").isGreaterThanOrEqualTo(0.70);
    }

    @Test
    void bearingAnomalyDetectorCatchesFaultsOnAnUnseenLoad() {
        var m = test(MlPredictionService.BEARING_ANOMALY);
        assertThat(m.get("recall")).as("fault windows detected").isGreaterThanOrEqualTo(0.95);
        assertThat(m.get("false_alarm_rate")).as("healthy windows flagged (single window)").isLessThanOrEqualTo(0.06);
        // what operators actually see: alarms after 3 consecutive abnormal windows
        assertThat(m.get("alarm_false_alarm_rate")).as("alarm false alarms per healthy window").isLessThanOrEqualTo(0.005);
        assertThat(m.get("alarm_fault_recordings_detected")).as("fault recordings that raise an alarm").isEqualTo(1.0);
    }

    @Test
    void everyModelHasGovernanceMetadata() {
        assertThat(registry.cards()).hasSizeGreaterThanOrEqualTo(4).allSatisfy(c -> {
            assertThat(c.version()).isNotBlank();
            assertThat(c.trainedAt()).isNotNull();
            assertThat(c.dataset().version()).contains("sha256:");
            assertThat(c.features()).isNotEmpty();
            assertThat(c.artifactSha256()).hasSize(64);
            assertThat(c.limitations()).isNotEmpty();
        });
    }
}
