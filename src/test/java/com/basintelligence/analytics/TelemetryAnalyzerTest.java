package com.basintelligence.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.basintelligence.TestReadings;
import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.telemetry.simulation.Scenario;

class TelemetryAnalyzerTest {

    private final TelemetryAnalyzer analyzer = new TelemetryAnalyzer();

    @Test
    void healthyMachineIsStable() {
        var analysis = analyzer.analyze("CNC-01", MachineType.CNC_MILL,
                TestReadings.series(MachineType.CNC_MILL, Scenario.HEALTHY, 120, 3));

        var vibration = analysis.metric(Metric.VIBRATION).orElseThrow();
        assertThat(vibration.changePct()).isCloseTo(0, within(0.1));
        assertThat(analysis.multivariate()).isNotNull();
        assertThat(analysis.multivariate().anomalous()).isFalse();
    }

    @Test
    void bearingWearShowsRisingVibrationAndIsMultivariateAnomalous() {
        var analysis = analyzer.analyze("CNC-01", MachineType.CNC_MILL,
                TestReadings.series(MachineType.CNC_MILL, Scenario.BEARING_WEAR, 120, 3));

        var vibration = analysis.metric(Metric.VIBRATION).orElseThrow();
        assertThat(vibration.changePct()).isGreaterThan(1.0);
        assertThat(vibration.slopePerHour()).isPositive();
        assertThat(analysis.multivariate().anomalous()).isTrue();
        assertThat(analysis.multivariate().score()).isGreaterThan(analysis.multivariate().threshold());
    }

    @Test
    void shortWindowSkipsIsolationForest() {
        var analysis = analyzer.analyze("CNC-01", MachineType.CNC_MILL,
                TestReadings.series(MachineType.CNC_MILL, Scenario.HEALTHY, TelemetryAnalyzer.MIN_SAMPLES_FOR_FOREST - 1, 3));

        assertThat(analysis.sampleCount()).isEqualTo(TelemetryAnalyzer.MIN_SAMPLES_FOR_FOREST - 1);
        assertThat(analysis.multivariate()).isNull();
    }

    @Test
    void emptyWindowHasNoData() {
        assertThat(analyzer.analyze("X", MachineType.CONVEYOR, List.of()).hasData()).isFalse();
    }

    @Test
    void slopeOfALineIsItsGradient() {
        assertThat(Stats.slope(new double[] {0, 1, 2, 3}, new double[] {1, 3, 5, 7})).isCloseTo(2.0, within(1e-9));
    }
}
