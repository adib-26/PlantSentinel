package com.basintelligence.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetrySource;

class DataQualityTest {

    private static final UUID M = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void regularEdgeDataIsOnlineAndComplete() {
        var q = DataQuality.of(series(60, 10, TelemetrySource.EDGE, false), T0.plusSeconds(600));

        assertThat(q.connectivity()).isEqualTo(DataQuality.Connectivity.ONLINE);
        assertThat(q.typicalInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(q.completeness()).isEqualTo(1.0);
        assertThat(q.issues()).isEmpty();
    }

    @Test
    void detectsGapsStaleDataStuckSensorsAndSimulatedSource() {
        List<TelemetryReading> readings = new ArrayList<>(series(30, 10, TelemetrySource.SIMULATOR, true));
        // a 100 s gap = 9 missing readings at a 10 s interval
        readings.add(reading(T0.plusSeconds(390), TelemetrySource.SIMULATOR, true));
        var q = DataQuality.of(readings, T0.plusSeconds(390 + 900));

        assertThat(q.connectivity()).isEqualTo(DataQuality.Connectivity.STALE);
        assertThat(q.estimatedMissing()).isEqualTo(9);
        assertThat(q.flatlinedChannels()).contains("TEMPERATURE");
        assertThat(q.issues()).anyMatch(i -> i.contains("simulated")).anyMatch(i -> i.contains("stuck"));
    }

    @Test
    void emptyWindowHasNoData() {
        assertThat(DataQuality.of(List.of(), T0).connectivity()).isEqualTo(DataQuality.Connectivity.NO_DATA);
    }

    private static List<TelemetryReading> series(int n, int stepSeconds, TelemetrySource source, boolean flat) {
        List<TelemetryReading> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(reading(T0.plusSeconds((long) i * stepSeconds), source, flat ? false : true, i));
        return out;
    }

    private static TelemetryReading reading(Instant at, TelemetrySource source, boolean flat) {
        return reading(at, source, !flat, 0);
    }

    private static TelemetryReading reading(Instant at, TelemetrySource source, boolean vary, int i) {
        double d = vary ? i * 0.01 : 0;
        return new TelemetryReading(M, at, 55 + d, 1.8 + d, 2400 + d, 35 + d, 6.0 + d, source);
    }
}
