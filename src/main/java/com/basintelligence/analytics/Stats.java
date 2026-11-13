package com.basintelligence.analytics;

/** Descriptive statistics for one signal. */
public record Stats(int count, double mean, double std, double min, double max) {

    public static Stats of(double[] values) {
        if (values.length == 0) {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double mean = sum / values.length;
        double sq = 0;
        for (double v : values) {
            sq += (v - mean) * (v - mean);
        }
        double std = values.length > 1 ? Math.sqrt(sq / (values.length - 1)) : 0;
        return new Stats(values.length, mean, std, min, max);
    }

    /** Least-squares slope of {@code y} against {@code x}. Returns 0 when x has no spread. */
    public static double slope(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) {
            return 0;
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            num += (x[i] - mx) * (y[i] - my);
            den += (x[i] - mx) * (x[i] - mx);
        }
        return den == 0 ? 0 : num / den;
    }
}
