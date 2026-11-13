package com.basintelligence.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SlidingRateTest {

    @Test
    void countsTheLastSixtySecondsOnly() {
        var clock = new RateLimiterTest.MutableClock();
        SlidingRate rate = new SlidingRate(clock);
        rate.record(5);
        clock.now = clock.now.plus(Duration.ofSeconds(30));
        rate.record(3);
        assertThat(rate.perMinute()).isEqualTo(8);

        clock.now = clock.now.plus(Duration.ofSeconds(30)); // the first 5 are now 60 s old
        assertThat(rate.perMinute()).isEqualTo(3);
        rate.record(1);                                       // reuses the slot of the expired second
        assertThat(rate.perMinute()).isEqualTo(4);

        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(rate.perMinute()).isZero();
    }
}
