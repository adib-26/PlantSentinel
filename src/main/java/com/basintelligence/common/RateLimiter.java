package com.basintelligence.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per key. Each bucket holds up to {@code perMinute} tokens and refills continuously at
 * {@code perMinute} tokens per minute, so short bursts are allowed and the sustained rate is capped. Buckets idle
 * for more than ten minutes are dropped when the map grows, which bounds memory. Single-node only: limits are per
 * application instance.
 */
public final class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Bucket(double tokens, Instant updated) {
    }

    private static final Duration IDLE = Duration.ofMinutes(10);

    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    public Decision tryAcquire(String key, int perMinute) {
        if (perMinute <= 0) {
            throw new IllegalArgumentException("perMinute must be positive");
        }
        Instant now = clock.instant();
        double perSecond = perMinute / 60.0;
        boolean[] allowed = {false};
        double[] remaining = {0};
        buckets.compute(key, (k, b) -> {
            double tokens = b == null ? perMinute
                    : Math.min(perMinute, b.tokens() + Duration.between(b.updated(), now).toNanos() / 1e9 * perSecond);
            if (tokens >= 1) {
                allowed[0] = true;
                tokens -= 1;
            }
            remaining[0] = tokens;
            return new Bucket(tokens, now);
        });
        if (buckets.size() > maxKeys) {
            Instant cutoff = now.minus(IDLE);
            buckets.entrySet().removeIf(e -> e.getValue().updated().isBefore(cutoff));
        }
        if (allowed[0]) {
            return new Decision(true, 0);
        }
        long wait = (long) Math.ceil((1 - remaining[0]) / perSecond);
        return new Decision(false, Math.max(1, wait));
    }

    int size() {
        return buckets.size();
    }
}
