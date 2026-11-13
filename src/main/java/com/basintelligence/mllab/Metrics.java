package com.basintelligence.mllab;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Evaluation metrics implemented from first principles so their definitions are explicit and testable. */
public final class Metrics {

    private Metrics() {
    }

    /** Binary confusion matrix at a decision threshold. Positive class = 1 (failure / anomaly). */
    public record Confusion(int tp, int fp, int tn, int fn) {

        public static Confusion of(double[] scores, int[] labels, double threshold) {
            int tp = 0, fp = 0, tn = 0, fn = 0;
            for (int i = 0; i < labels.length; i++) {
                boolean predicted = scores[i] >= threshold;
                if (predicted && labels[i] == 1) tp++;
                else if (predicted) fp++;
                else if (labels[i] == 1) fn++;
                else tn++;
            }
            return new Confusion(tp, fp, tn, fn);
        }

        public double precision() {
            return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        }

        public double recall() {
            return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        }

        public double f1() {
            double p = precision(), r = recall();
            return p + r == 0 ? 0 : 2 * p * r / (p + r);
        }

        /** False alarm rate = false positive rate = FP / (FP + TN). */
        public double falseAlarmRate() {
            return fp + tn == 0 ? 0 : (double) fp / (fp + tn);
        }

        public double accuracy() {
            return (double) (tp + tn) / (tp + fp + tn + fn);
        }
    }

    /** ROC-AUC via the Mann-Whitney rank statistic, with tied scores sharing their average rank. */
    public static double rocAuc(double[] scores, int[] labels) {
        int n = scores.length;
        Integer[] order = order(scores);
        double[] ranks = new double[n];
        for (int i = 0; i < n;) {
            int j = i;
            while (j + 1 < n && scores[order[j + 1]] == scores[order[i]]) j++;
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) ranks[order[k]] = avg;
            i = j + 1;
        }
        double rankSum = 0;
        long pos = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] == 1) {
                rankSum += ranks[i];
                pos++;
            }
        }
        long neg = n - pos;
        return pos == 0 || neg == 0 ? Double.NaN : (rankSum - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    /** PR-AUC as average precision: sum over positives of precision at that positive's rank. */
    public static double prAuc(double[] scores, int[] labels) {
        Integer[] order = order(scores);
        int positives = (int) Arrays.stream(labels).filter(l -> l == 1).count();
        if (positives == 0) return Double.NaN;
        double sum = 0;
        int tp = 0;
        for (int k = 0; k < order.length; k++) {
            int i = order[order.length - 1 - k]; // highest score first
            if (labels[i] == 1) {
                tp++;
                sum += (double) tp / (k + 1);
            }
        }
        return sum / positives;
    }

    /** Threshold that maximises F1 (used on the validation split only). */
    public static double bestF1Threshold(double[] scores, int[] labels) {
        double best = 0.5, bestF1 = -1;
        double[] candidates = Arrays.stream(scores).distinct().sorted().toArray();
        int step = Math.max(1, candidates.length / 400);
        for (int i = 0; i < candidates.length; i += step) {
            double f1 = Confusion.of(scores, labels, candidates[i]).f1();
            if (f1 > bestF1) {
                bestF1 = f1;
                best = candidates[i];
            }
        }
        return best;
    }

    public static Map<String, Double> classification(double[] scores, int[] labels, double threshold) {
        Confusion c = Confusion.of(scores, labels, threshold);
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("precision", c.precision());
        m.put("recall", c.recall());
        m.put("f1", c.f1());
        m.put("roc_auc", rocAuc(scores, labels));
        m.put("pr_auc", prAuc(scores, labels));
        m.put("accuracy", c.accuracy());
        m.put("false_alarm_rate", c.falseAlarmRate());
        m.put("tp", (double) c.tp());
        m.put("fp", (double) c.fp());
        m.put("tn", (double) c.tn());
        m.put("fn", (double) c.fn());
        return m;
    }

    public static Map<String, Double> regression(double[] predicted, double[] actual) {
        double sae = 0, sse = 0, mean = Arrays.stream(actual).average().orElse(0), sst = 0;
        for (int i = 0; i < actual.length; i++) {
            double e = predicted[i] - actual[i];
            sae += Math.abs(e);
            sse += e * e;
            sst += (actual[i] - mean) * (actual[i] - mean);
        }
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("mae", sae / actual.length);
        m.put("rmse", Math.sqrt(sse / actual.length));
        m.put("r2", sst == 0 ? Double.NaN : 1 - sse / sst);
        return m;
    }

    private static Integer[] order(double[] scores) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> scores[i]));
        return order;
    }
}
