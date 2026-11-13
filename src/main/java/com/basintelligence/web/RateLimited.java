package com.basintelligence.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as an expensive AI operation. {@link AiRateLimitInterceptor} limits how often each user
 * may call operations of the same kind; the limits are {@code bas.rate-limit.*}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    Kind value();

    enum Kind {
        /** Diagnostic investigations (telemetry analysis, Isolation Forest, risk model, manual retrieval). */
        INVESTIGATION,
        /** Image inspections (OpenCV and ONNX Runtime inference). */
        INSPECTION,
        /** Model predictions and telemetry analysis. */
        PREDICTION,
        /** Manual library search. */
        SEARCH
    }
}
