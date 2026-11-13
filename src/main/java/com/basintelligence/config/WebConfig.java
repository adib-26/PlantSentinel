package com.basintelligence.config;

import java.time.Clock;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.basintelligence.common.AuditService;
import com.basintelligence.web.AiRateLimitInterceptor;

import io.micrometer.core.instrument.MeterRegistry;

/** Registers the rate limiter for expensive AI endpoints (methods annotated with {@code @RateLimited}). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AiRateLimitInterceptor rateLimits;

    public WebConfig(BasProperties properties, Clock clock, AuditService audit, MeterRegistry meters) {
        this.rateLimits = new AiRateLimitInterceptor(properties.rateLimit(), clock, audit, meters);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            /** Adds the authenticated caller to the logging MDC (cleared by RequestCorrelationFilter). */
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    org.slf4j.MDC.put(RequestCorrelationFilter.MDC_USER, auth.getName());
                }
                return true;
            }
        });
        registry.addInterceptor(rateLimits);
    }
}
