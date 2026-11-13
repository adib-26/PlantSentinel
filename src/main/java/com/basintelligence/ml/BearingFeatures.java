package com.basintelligence.ml;

import java.util.ArrayList;
import java.util.List;

/**
 * Vibration features for one window of accelerometer samples (CWRU: 12 kHz, 1024 samples ≈ 85 ms). Shared by the
 * ML lab and the bearing analysis API. Time-domain statistics capture impulsiveness (kurtosis, crest factor);
 * band-energy shares capture where the energy sits in the spectrum, which differs by fault location.
 */
public final class BearingFeatures {

    public static final int WINDOW = 1024;
    public static final double SAMPLE_RATE_HZ = 12_000;
    /** Band edges in Hz for the spectral energy shares. */
    static final double[] BANDS = {0, 1000, 2000, 3000, 4000, 6000};

    private BearingFeatures() {
    }

    public static List<ModelCard.Feature> definitions() {
        List<ModelCard.Feature> f = new ArrayList<>(List.of(
                new ModelCard.Feature("rms", "g", "Root mean square acceleration"),
                new ModelCard.Feature("peak", "g", "Maximum absolute acceleration"),
                new ModelCard.Feature("crest_factor", "ratio", "Peak / RMS"),
                new ModelCard.Feature("kurtosis", "ratio", "Fourth standardised moment (3 for Gaussian noise)"),
                new ModelCard.Feature("skewness", "ratio", "Third standardised moment"),
                new ModelCard.Feature("shape_factor", "ratio", "RMS / mean absolute value"),
                new ModelCard.Feature("impulse_factor", "ratio", "Peak / mean absolute value")));
        for (int b = 0; b + 1 < BANDS.length; b++) {
            f.add(new ModelCard.Feature("band_" + (int) BANDS[b] + "_" + (int) BANDS[b + 1] + "hz", "share",
                    "Share of spectral energy between " + (int) BANDS[b] + " and " + (int) BANDS[b + 1] + " Hz"));
        }
        return f;
    }

    public static String[] names() {
        return definitions().stream().map(ModelCard.Feature::name).toArray(String[]::new);
    }

    public static double[] of(double[] window) {
        if (window.length != WINDOW) {
            throw new IllegalArgumentException("window must contain exactly " + WINDOW + " samples");
        }
        int n = window.length;
        double mean = 0;
        for (double v : window) mean += v;
        mean /= n;
        double m2 = 0, m3 = 0, m4 = 0, absMean = 0, peak = 0, sq = 0;
        for (double v : window) {
            double d = v - mean;
            m2 += d * d;
            m3 += d * d * d;
            m4 += d * d * d * d;
            absMean += Math.abs(v);
            peak = Math.max(peak, Math.abs(v));
            sq += v * v;
        }
        m2 /= n;
        m3 /= n;
        m4 /= n;
        absMean /= n;
        double rms = Math.sqrt(sq / n);
        double[] bands = bandShares(window);
        double[] f = new double[7 + bands.length];
        f[0] = rms;
        f[1] = peak;
        f[2] = rms == 0 ? 0 : peak / rms;
        f[3] = m2 == 0 ? 0 : m4 / (m2 * m2);
        f[4] = m2 == 0 ? 0 : m3 / Math.pow(m2, 1.5);
        f[5] = absMean == 0 ? 0 : rms / absMean;
        f[6] = absMean == 0 ? 0 : peak / absMean;
        System.arraycopy(bands, 0, f, 7, bands.length);
        return f;
    }

    /** Power spectrum by radix-2 FFT (Hann window), summed into bands and normalised to shares. */
    static double[] bandShares(double[] window) {
        int n = window.length;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) re[i] = window[i] * 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
        fft(re, im);
        double[] bands = new double[BANDS.length - 1];
        double total = 0;
        for (int k = 1; k < n / 2; k++) {
            double hz = k * SAMPLE_RATE_HZ / n;
            double p = re[k] * re[k] + im[k] * im[k];
            for (int b = 0; b < bands.length; b++) {
                if (hz >= BANDS[b] && hz < BANDS[b + 1]) {
                    bands[b] += p;
                    break;
                }
            }
            total += p;
        }
        for (int b = 0; b < bands.length; b++) bands[b] = total == 0 ? 0 : bands[b] / total;
        return bands;
    }

    /** In-place iterative Cooley-Tukey FFT; n must be a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
