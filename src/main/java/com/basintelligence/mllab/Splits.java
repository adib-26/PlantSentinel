package com.basintelligence.mllab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Reproducible data splits. */
public final class Splits {

    public record ThreeWay(int[] train, int[] validation, int[] test) {
    }

    private Splits() {
    }

    /** Stratified split by label so the rare failure class appears in every part in the same proportion. */
    public static ThreeWay stratified(int[] labels, double trainFraction, double validationFraction, long seed) {
        List<Integer> pos = new ArrayList<>(), neg = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) (labels[i] == 1 ? pos : neg).add(i);
        Random random = new Random(seed);
        Collections.shuffle(pos, random);
        Collections.shuffle(neg, random);
        List<Integer> train = new ArrayList<>(), val = new ArrayList<>(), test = new ArrayList<>();
        for (List<Integer> group : List.of(pos, neg)) {
            int a = (int) Math.round(group.size() * trainFraction);
            int b = (int) Math.round(group.size() * (trainFraction + validationFraction));
            train.addAll(group.subList(0, a));
            val.addAll(group.subList(a, b));
            test.addAll(group.subList(b, group.size()));
        }
        return new ThreeWay(toArray(train, random), toArray(val, random), toArray(test, random));
    }

    static int[] toArray(List<Integer> list, Random random) {
        Collections.shuffle(list, random);
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    public static double[][] rows(double[][] x, int[] idx) {
        double[][] out = new double[idx.length][];
        for (int i = 0; i < idx.length; i++) out[i] = x[idx[i]];
        return out;
    }

    public static int[] labels(int[] y, int[] idx) {
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = y[idx[i]];
        return out;
    }

    public static double[] values(double[] y, int[] idx) {
        double[] out = new double[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = y[idx[i]];
        return out;
    }
}
