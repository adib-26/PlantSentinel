package com.basintelligence.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TelemetryRollupServiceTest {

    private static final UUID DEVICE = UUID.randomUUID();

    @Test
    void floorsToFiveMinuteBuckets() {
        assertThat(TelemetryRollupService.floor(Instant.parse("2026-11-12T08:07:59Z")))
                .isEqualTo(Instant.parse("2026-11-12T08:05:00Z"));
        assertThat(TelemetryRollupService.floor(Instant.parse("2026-11-12T08:10:00Z")))
                .isEqualTo(Instant.parse("2026-11-12T08:10:00Z"));
    }

    @Test
    void aggregatesMeanAndWorstCasePerBucket() {
        var rollups = TelemetryRollupService.aggregate(List.of(
                reading("2026-11-12T08:00:10Z", 50, 1.0, 2400, 30, 6.0),
                reading("2026-11-12T08:04:50Z", 60, 3.0, 2300, 40, 5.0),
                reading("2026-11-12T08:05:00Z", 55, 2.0, 2350, 35, 5.5)));

        assertThat(rollups).hasSize(2);
        var first = rollups.getFirst();
        assertThat(first.bucketStart()).isEqualTo(Instant.parse("2026-11-12T08:00:00Z"));
        assertThat(first.samples()).isEqualTo(2);
        assertThat(first.temperatureAvg()).isCloseTo(55, within(1e-9));
        assertThat(first.temperatureMax()).isEqualTo(60);
        assertThat(first.vibrationMax()).isEqualTo(3.0);
        assertThat(first.rpmMin()).isEqualTo(2300);
        assertThat(first.pressureMin()).isEqualTo(5.0);
        assertThat(rollups.get(1).samples()).isEqualTo(1);
    }

    private static TelemetryReading reading(String at, double t, double v, double rpm, double torque, double p) {
        return new TelemetryReading(DEVICE, Instant.parse(at), t, v, rpm, torque, p, TelemetrySource.EDGE);
    }
}
