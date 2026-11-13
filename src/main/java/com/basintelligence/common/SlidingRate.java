package com.basintelligence.common;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Events per minute over the last 60 seconds, from 60 one-second buckets. Lock-free and fixed-size, so it can sit on
 * the ingestion hot path; used for gauges such as the telemetry ingestion rate, which Actuator alone (cumulative
 * counters, no rates) cannot show without Prometheus.
 */
public final class SlidingRate {

    private static final int SECONDS = 60;

    private final Clock clock;
    private final AtomicLongArray counts = new AtomicLongArray(SECONDS);
    private final AtomicLongArray stamps = new AtomicLongArray(SECONDS);

    public SlidingRate(Clock clock) {
        this.clock = clock;
    }

    public void record(long events) {
        long second = clock.millis() / 1000;
        int slot = (int) (second % SECONDS);
        long stamp = stamps.get(slot);
        if (stamp != second && stamps.compareAndSet(slot, stamp, second)) {
            counts.set(slot, 0); // the slot held a second that is more than a minute old
        }
        counts.addAndGet(slot, events);
    }

    /** Events in the last 60 seconds, which is the per-minute rate. */
    public long perMinute() {
        long now = clock.millis() / 1000;
        long total = 0;
        for (int i = 0; i < SECONDS; i++) {
            if (now - stamps.get(i) < SECONDS) {
                total += counts.get(i);
            }
        }
        return total;
    }
}
