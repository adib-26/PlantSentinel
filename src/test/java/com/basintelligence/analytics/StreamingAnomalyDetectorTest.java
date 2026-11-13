package com.basintelligence.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.basintelligence.TestReadings;
import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.machine.Severity;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.simulation.Scenario;

class StreamingAnomalyDetectorTest {

    private final StreamingAnomalyDetector detector = new StreamingAnomalyDetector(4.0);
    private final List<TelemetryReading> baseline = TestReadings.series(MachineType.CNC_MILL, Scenario.HEALTHY, 60, 1);

    @Test
    void nominalReadingRaisesNothing() {
        var result = detector.evaluate(MachineType.CNC_MILL, TestReadings.reading(55, 1.8, 2400, 35, 6.0), baseline);

        assertThat(result.envelopeSeverity()).isEqualTo(Severity.NORMAL);
        assertThat(result.events()).isEmpty();
    }

    @Test
    void vibrationInZoneCIsWarningAndZoneDIsCritical() {
        var warning = detector.evaluate(MachineType.CNC_MILL, TestReadings.reading(55, 5.0, 2400, 35, 6.0), List.of());
        var critical = detector.evaluate(MachineType.CNC_MILL, TestReadings.reading(55, 7.5, 2400, 35, 6.0), List.of());

        assertThat(warning.envelopeSeverity()).isEqualTo(Severity.WARNING);
        assertThat(critical.envelopeSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(critical.events()).singleElement()
                .satisfies(e -> {
                    assertThat(e.metric()).isEqualTo(Metric.VIBRATION);
                    assertThat(e.detector()).isEqualTo(AnomalyEvent.Detector.ENVELOPE);
                });
    }

    @Test
    void lowPressureBreachesLowerLimit() {
        var result = detector.evaluate(MachineType.CNC_MILL, TestReadings.reading(55, 1.8, 2400, 35, 3.0), List.of());

        assertThat(result.envelopeSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(result.events()).extracting(AnomalyEvent::metric).containsExactly(Metric.PRESSURE);
    }

    @Test
    void suddenSpikeInsideEnvelopeIsCaughtByZScore() {
        // 3.6 mm/s is still inside the envelope (warning at 4.5) but far outside this machine's own baseline.
        var result = detector.evaluate(MachineType.CNC_MILL, TestReadings.reading(55, 3.6, 2400, 35, 6.0), baseline);

        assertThat(result.envelopeSeverity()).isEqualTo(Severity.NORMAL);
        assertThat(result.events()).singleElement().satisfies(e -> {
            assertThat(e.detector()).isEqualTo(AnomalyEvent.Detector.ZSCORE);
            assertThat(e.metric()).isEqualTo(Metric.VIBRATION);
        });
    }

    @Test
    void zScoreNeedsEnoughBaseline() {
        var result = detector.evaluate(MachineType.CNC_MILL, TestReadings.reading(55, 3.6, 2400, 35, 6.0),
                baseline.subList(0, StreamingAnomalyDetector.MIN_BASELINE - 1));

        assertThat(result.events()).isEmpty();
    }
}
