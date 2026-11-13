package com.basintelligence.machine;

public enum Severity {
    NORMAL, WARNING, CRITICAL;

    public Severity max(Severity other) {
        return other.ordinal() > ordinal() ? other : this;
    }

    public HealthStatus toHealth() {
        return switch (this) {
            case NORMAL -> HealthStatus.NORMAL;
            case WARNING -> HealthStatus.WARNING;
            case CRITICAL -> HealthStatus.CRITICAL;
        };
    }
}
