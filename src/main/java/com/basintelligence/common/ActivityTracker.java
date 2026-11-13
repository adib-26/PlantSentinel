package com.basintelligence.common;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.basintelligence.config.BasProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Remembers when a person, an edge gateway or the agent last used the app. Background jobs (simulator,
 * rollups, scheduled predictions, retention) pause after {@code bas.idle-pause} without activity, so Neon's
 * compute can scale to zero and the free plan's compute allowance is not spent on an unattended laptop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActivityTracker extends OncePerRequestFilter {

    private final Clock clock;
    private final Duration idlePause;
    private final AtomicReference<Instant> lastActivity;

    public ActivityTracker(Clock clock, BasProperties properties) {
        this.clock = clock;
        this.idlePause = properties.idlePause();
        this.lastActivity = new AtomicReference<>(clock.instant());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // Static assets and health probes are not user activity.
        if (!path.startsWith("/webjars/") && !path.startsWith("/css/") && !path.startsWith("/js/")
                && !path.startsWith("/actuator/health")) {
            touch();
        }
        chain.doFilter(request, response);
    }

    public void touch() {
        lastActivity.set(clock.instant());
    }

    public boolean isIdle() {
        return idlePause.isPositive() && lastActivity.get().isBefore(clock.instant().minus(idlePause));
    }

    public Instant lastActivity() {
        return lastActivity.get();
    }
}
