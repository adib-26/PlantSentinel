package com.basintelligence.web;

import java.time.Clock;
import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.RateLimitExceededException;
import com.basintelligence.common.RateLimiter;
import com.basintelligence.config.BasProperties;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies the per-user limits of {@link RateLimited} handlers before the controller runs. Users are keyed by their
 * login name, anything else by client address. Rejections return 429 with Retry-After and are audited (throttled).
 */
public class AiRateLimitInterceptor implements HandlerInterceptor {

    private final BasProperties.RateLimit limits;
    private final RateLimiter limiter;
    private final AuditService audit;
    private final MeterRegistry meters;

    public AiRateLimitInterceptor(BasProperties.RateLimit limits, Clock clock, AuditService audit, MeterRegistry meters) {
        this.limits = limits;
        this.limiter = new RateLimiter(clock, 10_000);
        this.audit = audit;
        this.meters = meters;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!limits.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String caller = auth == null || auth instanceof AnonymousAuthenticationToken ? "ip:" + request.getRemoteAddr()
                : auth.getName();
        int perMinute = perMinute(annotation.value());
        RateLimiter.Decision decision = limiter.tryAcquire(annotation.value() + ":" + caller, perMinute);
        if (decision.allowed()) {
            return true;
        }
        meters.counter("bas.ratelimit.rejected", "operation", annotation.value().name()).increment();
        try {
            audit.recordThrottled("rate:" + annotation.value() + ":" + caller, Duration.ofMinutes(1), caller, "RATE_LIMITED",
                    request.getMethod() + " " + request.getRequestURI(), annotation.value() + " limit " + perMinute + "/min");
        } catch (DataAccessException e) {
            // auditing must not turn a 429 into a 500
        }
        throw new RateLimitExceededException("Too many " + annotation.value().name().toLowerCase() + " requests: the limit is "
                + perMinute + " per minute. Retry in " + decision.retryAfterSeconds() + " s.", decision.retryAfterSeconds());
    }

    private int perMinute(RateLimited.Kind kind) {
        return switch (kind) {
            case INVESTIGATION -> limits.investigationsPerMinute();
            case INSPECTION -> limits.inspectionsPerMinute();
            case PREDICTION -> limits.predictionsPerMinute();
            case SEARCH -> limits.searchesPerMinute();
            case ASSISTANT -> limits.assistantMessagesPerMinute();
        };
    }
}
