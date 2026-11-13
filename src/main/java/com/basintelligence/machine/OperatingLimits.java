package com.basintelligence.machine;

/**
 * Operating envelope of one metric. Low limits are {@code NaN} when only a high limit applies.
 */
public record OperatingLimits(double nominal, double warnLow, double critLow, double warnHigh, double critHigh) {

    public static OperatingLimits highOnly(double nominal, double warnHigh, double critHigh) {
        return new OperatingLimits(nominal, Double.NaN, Double.NaN, warnHigh, critHigh);
    }

    public static OperatingLimits band(double nominal, double warnLow, double critLow, double warnHigh, double critHigh) {
        return new OperatingLimits(nominal, warnLow, critLow, warnHigh, critHigh);
    }

    public Severity evaluate(double value) {
        if (value >= critHigh || (!Double.isNaN(critLow) && value <= critLow)) {
            return Severity.CRITICAL;
        }
        if (value >= warnHigh || (!Double.isNaN(warnLow) && value <= warnLow)) {
            return Severity.WARNING;
        }
        return Severity.NORMAL;
    }

    /** Ratio of value to nominal, used as a machine-independent ML feature. */
    public double ratio(double value) {
        return value / nominal;
    }
}
