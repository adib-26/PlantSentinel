package com.basintelligence.mllab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class MetricsTest {

    private final double[] scores = {0.9, 0.8, 0.7, 0.4, 0.3, 0.2};
    private final int[] labels = {1, 0, 1, 1, 0, 0};

    @Test
    void confusionMatrixAndDerivedMetrics() {
        var c = Metrics.Confusion.of(scores, labels, 0.5);

        assertThat(c).isEqualTo(new Metrics.Confusion(2, 1, 2, 1));
        assertThat(c.precision()).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(c.recall()).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(c.f1()).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(c.falseAlarmRate()).isCloseTo(1 / 3.0, within(1e-12));
    }

    @Test
    void rocAucCountsCorrectlyOrderedPairs() {
        // Positives at ranks (desc) 1, 3, 4; 7 of the 9 positive-negative pairs are ordered correctly.
        assertThat(Metrics.rocAuc(scores, labels)).isCloseTo(7 / 9.0, within(1e-12));
        assertThat(Metrics.rocAuc(new double[] {0.5, 0.5}, new int[] {1, 0})).isEqualTo(0.5); // ties
    }

    @Test
    void prAucIsAveragePrecision() {
        // Precision at each positive: 1/1, 2/3, 3/4.
        assertThat(Metrics.prAuc(scores, labels)).isCloseTo((1 + 2 / 3.0 + 3 / 4.0) / 3, within(1e-12));
    }

    @Test
    void regressionMetrics() {
        var m = Metrics.regression(new double[] {2, 4, 6}, new double[] {1, 4, 7});

        assertThat(m.get("mae")).isCloseTo(2 / 3.0, within(1e-12));
        assertThat(m.get("rmse")).isCloseTo(Math.sqrt(2 / 3.0), within(1e-12));
        assertThat(m.get("r2")).isCloseTo(1 - 2 / 18.0, within(1e-12));
    }

    @Test
    void stratifiedSplitKeepsTheClassRatio() {
        int[] y = new int[1000];
        for (int i = 0; i < 50; i++) y[i] = 1;
        var split = Splits.stratified(y, 0.6, 0.2, 7);

        assertThat(split.train().length + split.validation().length + split.test().length).isEqualTo(1000);
        assertThat(java.util.Arrays.stream(split.test()).filter(i -> y[i] == 1).count()).isEqualTo(10);
        assertThat(java.util.Arrays.stream(split.train()).filter(i -> y[i] == 1).count()).isEqualTo(30);
    }
}
