package com.basintelligence.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FailureRiskModelTest {

    private static FailureRiskModel model;

    @BeforeAll
    static void train() {
        model = FailureRiskModel.train(4000, 42);
    }

    @Test
    void generalisesToHoldOutData() {
        assertThat(model.info().holdoutAuc()).isGreaterThan(0.85);
        assertThat(model.info().holdoutAccuracy()).isGreaterThan(0.8);
    }

    @Test
    void healthyMachineIsLowRisk() {
        double p = model.probability(RiskFeatures.fromRatios(1.0, 1.0, 1.0, 1.0, 1.0));

        assertThat(RiskLevel.of(p)).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void severeVibrationWithHeatIsHighRiskAndExplainedByVibration() {
        double[] features = RiskFeatures.fromRatios(1.25, 3.5, 1.05, 1.0, 1.0);

        assertThat(model.probability(features)).isGreaterThan(0.75);
        assertThat(model.explain(features).getFirst().feature()).isEqualTo("vibration_excess");
    }

    @Test
    void riskIncreasesMonotonicallyWithPressureLoss() {
        double mild = model.probability(RiskFeatures.fromRatios(1, 1, 1, 0.9, 1));
        double severe = model.probability(RiskFeatures.fromRatios(1, 1, 1, 0.6, 1));

        assertThat(severe).isGreaterThan(mild);
    }

    @Test
    void contributionsAddUpToTheModelLogit() {
        double[] features = RiskFeatures.fromRatios(1.1, 2.0, 1.2, 0.9, 1.02);
        double[] w = model.weights();
        double logit = w[w.length - 1] + model.explain(features).stream().mapToDouble(RiskAssessment.Contribution::logOdds).sum();

        assertThat(1 / (1 + Math.exp(-logit))).isCloseTo(model.probability(features), within(1e-6));
    }

    @Test
    void aucOfPerfectRankingIsOne() {
        assertThat(FailureRiskModel.auc(new double[] {0.1, 0.2, 0.8, 0.9}, new int[] {0, 0, 1, 1})).isEqualTo(1.0);
    }
}
