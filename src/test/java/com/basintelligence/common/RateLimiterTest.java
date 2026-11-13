package com.basintelligence.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsABurstUpToTheLimitThenRefillsContinuously() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 100);
        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire("u", 6).allowed()).isTrue();
        }
        RateLimiter.Decision denied = limiter.tryAcquire("u", 6);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(10); // 6 per minute = one every 10 s

        clock.now = clock.now.plus(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire("u", 6).allowed()).isTrue();
        assertThat(limiter.tryAcquire("u", 6).allowed()).isFalse();
    }

    @Test
    void keysAreIndependentAndIdleBucketsAreDropped() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 2);
        assertThat(limiter.tryAcquire("a", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1).allowed()).isTrue();

        clock.now = clock.now.plus(Duration.ofMinutes(11));
        limiter.tryAcquire("c", 1);
        assertThat(limiter.size()).isEqualTo(1);
    }
}
