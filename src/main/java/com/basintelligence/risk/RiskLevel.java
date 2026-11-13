package com.basintelligence.risk;

public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static RiskLevel of(double probability) {
        if (probability >= 0.75) {
            return CRITICAL;
        }
        if (probability >= 0.5) {
            return HIGH;
        }
        if (probability >= 0.25) {
            return MEDIUM;
        }
        return LOW;
    }
}
