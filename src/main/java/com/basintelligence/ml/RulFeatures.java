package com.basintelligence.ml;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature definitions for the C-MAPSS remaining-useful-life model, shared by training and inference.
 * For each informative sensor: the latest value and the mean over the last {@link #WINDOW} cycles, plus the
 * least-squares slope over that window, and the current cycle number.
 */
public final class RulFeatures {

    public static final int WINDOW = 10;

    /** FD001 sensors whose variance is non-negligible (EDA drops s1, s5, s6, s10, s16, s18, s19). */
    public static final int[] SENSORS = {2, 3, 4, 7, 8, 9, 11, 12, 13, 14, 15, 17, 20, 21};

    private RulFeatures() {
    }

    public static List<ModelCard.Feature> definitions() {
        List<ModelCard.Feature> out = new ArrayList<>();
        out.add(new ModelCard.Feature("cycle", "cycles", "Operating cycles since the start of the record"));
        for (int s : SENSORS) {
            out.add(new ModelCard.Feature("s" + s, "sensor units", "Sensor " + s + " at the current cycle"));
            out.add(new ModelCard.Feature("s" + s + "_mean" + WINDOW, "sensor units",
                    "Sensor " + s + " mean over the last " + WINDOW + " cycles"));
            out.add(new ModelCard.Feature("s" + s + "_slope" + WINDOW, "units/cycle",
                    "Sensor " + s + " least-squares slope over the last " + WINDOW + " cycles"));
        }
        return out;
    }

    public static String[] names() {
        return definitions().stream().map(ModelCard.Feature::name).toArray(String[]::new);
    }

    /**
     * @param cycle   cycle number of the latest row
     * @param history sensor rows up to and including the current cycle, oldest first; each row is indexed by
     *                sensor number (index 1..21, index 0 unused)
     */
    public static double[] of(int cycle, List<double[]> history) {
        if (history.isEmpty()) {
            throw new IllegalArgumentException("history must contain at least the current cycle");
        }
        int from = Math.max(0, history.size() - WINDOW);
        List<double[]> window = history.subList(from, history.size());
        double[] current = history.getLast();
        double[] f = new double[1 + SENSORS.length * 3];
        f[0] = cycle;
        int k = 1;
        for (int s : SENSORS) {
            double sum = 0;
            double[] x = new double[window.size()];
            double[] y = new double[window.size()];
            for (int i = 0; i < window.size(); i++) {
                x[i] = i;
                y[i] = window.get(i)[s];
                sum += y[i];
            }
            f[k++] = current[s];
            f[k++] = sum / window.size();
            f[k++] = com.basintelligence.analytics.Stats.slope(x, y);
        }
        return f;
    }
}
