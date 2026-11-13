package com.basintelligence.common;

/** A dependency (model file, vision runtime) is not available; the request may succeed later. Maps to 503. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
