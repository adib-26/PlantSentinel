package com.basintelligence.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.basintelligence.machine.Metric;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetrySource;

/**
 * Connectivity and sensor data quality for one machine over a recent window. Plain computation over readings, so it
 * is easy to test: how often data arrives, how much is missing, whether any channel is stuck, and where it comes from.
 */
public record DataQuality(
        Connectivity connectivity,
        Instant lastReadingAt,
        int samples,
        Duration typicalInterval,
        Duration largestGap,
        int estimatedMissing,
        double completeness,
        List<String> flatlinedChannels,
        long edgeReadings,
        long simulatedReadings,
        List<String> issues) {

    public enum Connectivity {
        /** Data within 3 typical intervals (at least 2 minutes). */
        ONLINE,
        /** Last reading within 30 minutes. */
        STALE,
        /** Last reading older than 30 minutes. */
        OFFLINE,
        NO_DATA
    }

    /** Channels with no variation over this many consecutive readings are reported as possibly stuck. */
    public static final int FLATLINE_RUN = 20;

    public static DataQuality of(List<TelemetryReading> window, Instant now) {
        if (window.isEmpty()) {
            return new DataQuality(Connectivity.NO_DATA, null, 0, null, null, 0, 0, List.of(), 0, 0,
                    List.of("No readings in the window"));
        }
        Instant last = window.getLast().recordedAt();
        long[] gaps = new long[Math.max(0, window.size() - 1)];
        for (int i = 1; i < window.size(); i++) {
            gaps[i - 1] = Duration.between(window.get(i - 1).recordedAt(), window.get(i).recordedAt()).toMillis();
        }
        long typical = gaps.length == 0 ? 60_000 : median(gaps);
        long largest = Arrays.stream(gaps).max().orElse(0);
        int missing = 0;
        for (long g : gaps) {
            if (typical > 0 && g > 1.5 * typical) {
                missing += (int) Math.round((double) g / typical) - 1;
            }
        }
        double completeness = (double) window.size() / (window.size() + missing);

        long sinceLast = Duration.between(last, now).toMillis();
        Connectivity connectivity = sinceLast <= Math.max(120_000, 3 * typical) ? Connectivity.ONLINE
                : sinceLast <= Duration.ofMinutes(30).toMillis() ? Connectivity.STALE : Connectivity.OFFLINE;

        List<String> flat = new ArrayList<>();
        if (window.size() >= FLATLINE_RUN) {
            List<TelemetryReading> tail = window.subList(window.size() - FLATLINE_RUN, window.size());
            for (Metric m : Metric.values()) {
                double first = m.of(tail.getFirst());
                if (tail.stream().allMatch(r -> m.of(r) == first)) {
                    flat.add(m.name());
                }
            }
        }
        long edge = window.stream().filter(r -> r.source() == TelemetrySource.EDGE).count();
        List<String> issues = new ArrayList<>();
        if (connectivity != Connectivity.ONLINE) {
            issues.add("Last reading " + Duration.ofMillis(sinceLast).toMinutes() + " min ago");
        }
        if (completeness < 0.95) {
            issues.add(String.format("About %d readings missing (%.0f%% complete)", missing, completeness * 100));
        }
        if (!flat.isEmpty()) {
            issues.add("No variation in " + String.join(", ", flat) + " over the last " + FLATLINE_RUN + " readings (stuck sensor?)");
        }
        if (edge == 0) {
            issues.add("All readings in this window are simulated");
        }
        return new DataQuality(connectivity, last, window.size(), Duration.ofMillis(typical), Duration.ofMillis(largest),
                missing, completeness, List.copyOf(flat), edge, window.size() - edge, List.copyOf(issues));
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
