package com.basintelligence.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensorPlausibilityTest {

    @Test
    void validReadingHasNoFaults() {
        assertThat(SensorPlausibility.check(55.0, 1.8, 2400.0, 35.0, 6.0)).isEmpty();
    }

    @Test
    void missingTemperatureIsASensorFault() {
        var faults = SensorPlausibility.check(null, 1.8, 2400.0, 35.0, 6.0);

        assertThat(faults).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(SensorPlausibility.FaultType.MISSING_VALUE);
            assertThat(f.channel()).isEqualTo("TEMPERATURE");
        });
    }

    @Test
    void negativeOrImpossibleValuesAreSensorFaults() {
        var faults = SensorPlausibility.check(55.0, -0.5, 2400.0, 35.0, 250.0);

        assertThat(faults).extracting(SensorPlausibility.Fault::channel).containsExactly("VIBRATION", "PRESSURE");
        assertThat(faults).allMatch(f -> f.type() == SensorPlausibility.FaultType.IMPOSSIBLE_VALUE);
        assertThat(SensorPlausibility.check(Double.NaN, 1.0, 1.0, 1.0, 1.0)).hasSize(1);
        assertThat(SensorPlausibility.check(-273.0, 1.0, 1.0, 1.0, 1.0).getFirst().message()).contains("physical range");
    }

    @Test
    void negativeTorqueIsPlausible() {
        // Regenerative braking produces negative torque; only impossible magnitudes are rejected.
        assertThat(SensorPlausibility.check(55.0, 1.8, 2400.0, -40.0, 6.0)).isEmpty();
    }
}
